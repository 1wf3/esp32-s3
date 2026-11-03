"""Local authenticated HTML-to-BLE debug bridge. Simulation is always labelled DEMO."""
import asyncio,binascii,copy,hashlib,json,pathlib,re,secrets,struct,subprocess,sys,threading,time
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
ROOT=pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'build/ble_tools'));sys.path.insert(0,str(ROOT/'software/phone_ble_nav/tests'))
from hardware_ble_test import Peer as BasePeer,SERVICE,RX,TX
from bleak import BleakClient,BleakScanner
SETTINGS='7e57a003-b50c-4b6a-9c57-40a54e8e1000';VEHICLE='7e57a004-b50c-4b6a-9c57-40a54e8e1000'
class Peer(BasePeer):
 def notify(self,*args):
  before=len(self.errors);super().notify(*args)
  if len(self.errors)==before:self.received_at=time.monotonic()
 async def send(self,kind,payload,flags=0,sequence=None):
  async with self.lock:
   if sequence is None:self.sequence=self.sequence%65535+1;sequence=self.sequence
   for offset in range(0,len(payload),self.budget-12):
    chunk=payload[offset:offset+self.budget-12];last=offset+len(chunk)==len(payload)
    f=flags|(1 if offset==0 else 0)|(2 if last else 0)
    data=struct.pack('<BBBBHHH',183,1,kind,f,sequence,offset,len(payload))+chunk
    data+=struct.pack('<H',binascii.crc_hqx(data,65535))
    try:await asyncio.wait_for(self.client.write_gatt_char(RX,data,response=last),5)
    except asyncio.TimeoutError:raise RuntimeError('蓝牙写入超时，请重新连接导航屏')
    # Keep notifications and board rendering responsive while sending a map.
    await asyncio.sleep(.02)
   return sequence
 async def handshake(self):
  offered=self.budget
  await self.send(1,struct.pack('<BBBBBIIHH',1,1,0,1,1,215,self.session,offered,1000))
  reply=await self.receive(1)
  rev,role,phase,lo,hi,caps,sid,size,interval=struct.unpack('<BBBBBIIHH',reply)
  if (rev,role,phase,sid)!=(1,2,1,self.session) or not 13<=size<=offered or caps&215!=215 or interval<250:raise RuntimeError('设备握手参数不兼容')
  self.budget=size
  await self.send(1,struct.pack('<BBBBBIIHH',1,1,1,1,1,caps,sid,size,interval))
  final=await self.receive(1)
  if final!=reply:raise RuntimeError('两次握手参数不一致')
  state['frameBudget']=size
  print('PASS PC encrypted two-stage Ready; negotiated frame budget='+str(size),flush=True)
PORT=8790;TOKEN=secrets.token_urlsafe(32);WORK=ROOT/'build/pc_debug';WORK.mkdir(exist_ok=True)
state={'connected':False,'message':'请连接导航屏，电脑发送的数据均为 DEMO','active':False,'paused':False,'stale':False,'speed':24,'progress':0,'txSnapshots':0,'acks':0,'errors':[],'frameRevision':0,'serial':{},'settingsVerified':False}
frame=b'';client=None;peer=None;heart=None;monitor=None;worker=None;worker_lock=None;control_lock=None;generation=0;applied_map=0;appearance=bytes.fromhex('010101003fb66d63ffe7518c');vehicle={};last_vehicle={};last_vehicle_at=0;latest=None
SERIAL=WORK/('pc-serial-'+str(int(time.time()))+'.log')
def status(text):state['message']=text

def serial_state():
 if not SERIAL.exists():return {}
 with SERIAL.open('rb') as f:f.seek(max(0,f.seek(0,2)-8192));text=f.read().decode('utf-8',errors='replace')
 rows=re.findall(r'BLE=(\d+) ENC=(\d+) READY=(\d+) NAV=(\d+) ROUTE=(\d+) MAP=(\d+) STATE=(\d+) FLAGS=(\d+) SPEED=(\d+) ERROR=([^\r\n]*)',text)
 if not rows:return {}
 r=rows[-1];result={**dict(zip(['ble','encrypted','ready','snapshots','route','map','state','flags','speed'],map(int,r[:9]))),'error':r[9]}
 perf=re.findall(r'RENDER FPS_X10=(\d+) AVG_MS=(\d+) MAX_MS=(\d+) SPI_MHZ=(\d+)',text)
 if perf:result['render']={'fps':int(perf[-1][0])/10,'averageMs':int(perf[-1][1]),'maxMs':int(perf[-1][2]),'spiMHz':int(perf[-1][3])}
 return result
async def model():
 global latest
 async with worker_lock:
  data={k:state[k] for k in ['active','stale','speed','progress']};data['generation']=generation or 1
  worker.stdin.write((json.dumps(data)+'\n').encode());await worker.stdin.drain();line=await asyncio.wait_for(worker.stdout.readline(),15)
  if not line:raise RuntimeError('导航计算进程已退出')
  result=json.loads(line)
  if 'error' in result:raise RuntimeError(result['error'])
  latest=result;state['progress']=result['progress'];result['ui'].update(connected=state['connected'],vehicleLive=state['connected'] and time.monotonic()-last_vehicle_at<6,vehicle=vehicle)
  return result
async def reliable(kind,payload):
 seq=await peer.send(kind,payload,4)
 for attempt in range(3):
  try:
   ack=await peer.receive(3,timeout=3,ackseq=seq)
   if len(ack)!=6 or ack[3] not in (0,4):raise RuntimeError('设备拒绝数据：'+str(ack[3]))
   state['acks']+=1;return
  except asyncio.TimeoutError:
   if attempt==2:raise RuntimeError('设备提交确认超时')
   await peer.send(kind,payload,4,sequence=seq)
async def render_sample(sample):
 global frame
 scene=[]
 if sample['map']:scene.append('20 '+sample['map'])
 if sample['geometry']:scene.append('17 '+sample['geometry'])
 scene.append('16 '+sample['snapshot']);(WORK/'scene.txt').write_text('\n'.join(scene)+'\n',encoding='utf-8')
 proc=await asyncio.create_subprocess_exec(str(WORK/'render.exe'),str(WORK/'scene.txt'),'fresh',appearance.hex(),stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.PIPE,creationflags=subprocess.CREATE_NO_WINDOW)
 svg,err=await proc.communicate()
 if proc.returncode:raise RuntimeError('同源屏幕预览生成失败：'+str(proc.returncode))
 frame=svg;state['frameRevision']+=1;state['frameHash']=hashlib.sha256(svg).hexdigest()[:16]
async def transmit(sample):
 global applied_map
 if not state['connected'] or not client or not client.is_connected:return
 if state['active']:
  if sample['mapRevision']!=applied_map:await reliable(0x14,bytes.fromhex(sample['map']));applied_map=sample['mapRevision']
  await reliable(0x11,bytes.fromhex(sample['geometry']))
 await peer.send(0x10,bytes.fromhex(sample['snapshot']));state['txSnapshots']+=1
 await render_sample(sample)
 state['serial']=serial_state();state['mapRoads']=sample['mapRoads'];state['snapshotFlags']=struct.unpack_from('<H',bytes.fromhex(sample['snapshot']),6)[0]
async def read_vehicle():
 global vehicle,last_vehicle,last_vehicle_at
 async with peer.lock:value=bytes(await asyncio.wait_for(client.read_gatt_char(VEHICLE),5))
 if len(value)!=20 or value[0]!=1 or value[1]&~3 or value[3]!=0:raise RuntimeError('车辆数据格式错误')
 version,flags,sats,reserved,hdop,age,lat,lon,chars=struct.unpack('<BBBBHIiiH',value)
 if abs(lat)>900000000 or abs(lon)>1800000000:raise RuntimeError('车辆坐标越界')
 vehicle={'valid':bool(flags&1) and age<15000,'uart':bool(flags&2),'satellites':sats,'hdop':None if hdop==65535 else hdop/100,'age':age//1000,'chars':chars,'last':last_vehicle};last_vehicle_at=time.monotonic()
 # Real vehicle fixes remain separate from the public simulated navigation position.
 if vehicle['valid']:
  lon,lat=lon/1e7,lat/1e7
  p=gcj(lon,lat);last_vehicle={'point':p,'time':int(time.time()*1000)-age};vehicle.update(point=p,last=last_vehicle)
def gcj(lon,lat):
 import math
 if lon<72.004 or lon>137.8347 or lat<.8293 or lat>55.8271:return [lon,lat]
 x,y=lon-105,lat-35;a=-100+2*x+3*y+.2*y*y+.1*x*y+.2*math.sqrt(abs(x));b=300+x+2*y+.1*x*x+.1*x*y+.1*math.sqrt(abs(x))
 a+=(20*math.sin(6*x*math.pi)+20*math.sin(2*x*math.pi))*2/3+(20*math.sin(y*math.pi)+40*math.sin(y/3*math.pi))*2/3+(160*math.sin(y/12*math.pi)+320*math.sin(y*math.pi/30))*2/3
 b+=(20*math.sin(6*x*math.pi)+20*math.sin(2*x*math.pi))*2/3+(20*math.sin(x*math.pi)+40*math.sin(x/3*math.pi))*2/3+(150*math.sin(x/12*math.pi)+300*math.sin(x/30*math.pi))*2/3
 r=lat*math.pi/180;m=1-.00669342162296594323*math.sin(r)**2;q=math.sqrt(m)
 return [lon+b*180/(6378245/q*math.cos(r)*math.pi),lat+a*180/(6378245*(1-.00669342162296594323)/(m*q)*math.pi)]
async def disconnect():
 global client,peer,heart,monitor,applied_map
 state.update(connected=False,active=False,paused=False,serial={});applied_map=0
 if heart:
  heart.cancel()
  try:await heart
  except (asyncio.CancelledError,Exception):pass
  heart=None
 if client:
  try:await asyncio.wait_for(client.disconnect(),5)
  except Exception:pass
 client=None;peer=None
 if monitor:monitor.terminate();monitor=None
 status('电脑已断开，设备会清除本次 DEMO 导航')
async def connect():
 global client,peer,heart,monitor,applied_map,appearance
 if state['connected']:return
 try:
  # Opening Windows USB CDC can reboot this board even with DTR/RTS false.
  # Start the monitor first and wait for fresh board output before BLE discovery.
  status('正在打开串口并等待开发板就绪')
  monitor=subprocess.Popen(['powershell.exe','-NoProfile','-File',str(ROOT/'software/phone_ble_nav/read_serial.ps1'),'-Port','COM7','-Seconds','43200','-LogPath',str(SERIAL)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,creationflags=subprocess.CREATE_NO_WINDOW)
  deadline=time.monotonic()+12
  while not serial_state():
   if monitor.poll() is not None:raise RuntimeError('无法打开 COM7，请检查开发板连接及串口占用')
   if time.monotonic()>deadline:raise RuntimeError('串口未收到开发板状态，请检查 USB 连接')
   await asyncio.sleep(.2)
  status('正在扫描 MOTO GPS TFT，请保持手机会话关闭')
  devices=await BleakScanner.discover(timeout=6,return_adv=True)
  targets=[d for d,a in devices.values() if (a.local_name=='MOTO GPS TFT' or d.name=='MOTO GPS TFT') and SERVICE in a.service_uuids]
  if len(targets)!=1:raise RuntimeError('未发现唯一导航屏；检查设备电源及手机是否关闭会话')
  client=BleakClient(targets[0],pair=True,timeout=30,winrt={'use_cached_services':False})
  await client.connect();peer=Peer(client);peer.received_at=time.monotonic();await client.start_notify(TX,peer.notify);await peer.handshake()
  if not client.services.get_characteristic(SETTINGS) or not client.services.get_characteristic(VEHICLE):raise RuntimeError('设备缺少车辆 GPS / 配色特征，请更新固件')
  appearance=bytes(await asyncio.wait_for(client.read_gatt_char(SETTINGS),5));heart=asyncio.create_task(peer.heartbeat());state['connected']=True;applied_map=0
  await read_vehicle();await transmit(await model());status('电脑已连接导航屏 · 加密 BLE / 协议 Ready')
 except Exception:
  await disconnect();raise
async def command(kind,data):
 global generation,appearance,applied_map
 async with control_lock:
  if kind in ('start','scan','connect'):await connect()
  elif kind in ('close','disconnect'):await disconnect()
  elif kind=='begin':
   if not state['connected']:raise RuntimeError('请先连接导航屏')
   generation+=1;state.update(active=True,paused=False,stale=False);await transmit(await model());status('DEMO 导航已同步，模拟位置沿公开路线行驶')
  elif kind=='end':state.update(active=False,paused=False);await transmit(await model());status('已同步结束导航')
  elif kind=='pause':state['paused']=not state['paused'];status('演示已暂停，定位仍持续同步' if state['paused'] else '演示继续')
  elif kind=='step':state['progress']+=100;await transmit(await model());status('已同步前进 100 米')
  elif kind in ('stale','fresh'):state['stale']=kind=='stale';await transmit(await model());status('已同步定位失效，屏幕隐藏位置与转向' if state['stale'] else '已同步恢复定位')
  elif kind=='reset':state.update(progress=0,stale=False,paused=True);generation+=1;await transmit(await model());status('已同步回到演示起点')
  elif kind=='speed':
   speed=float(data['value'])
   if not 0<=speed<=120:raise RuntimeError('速度范围 0..120 km/h')
   state['speed']=speed;await transmit(await model())
  elif kind=='settings':
   if not state['connected']:raise RuntimeError('请先连接导航屏')
   theme,marker=data.get('theme'),data.get('marker');colors=data.get('colors',[])
   if type(theme)!=int or type(marker)!=int or not 0<=theme<=2 or not 0<=marker<=2 or len(colors)!=4:raise RuntimeError('配色参数无效')
   value=bytearray([1,theme,marker,0])
   for color in colors:
    if not isinstance(color,str) or not re.fullmatch('#[0-9a-fA-F]{6}',color):raise RuntimeError('颜色无效')
    rgb=int(color[1:],16);value+=struct.pack('<H',((rgb>>19)&31)<<11|((rgb>>10)&63)<<5|((rgb>>3)&31))
   state['settingsVerified']=False
   async with peer.lock:await asyncio.wait_for(client.write_gatt_char(SETTINGS,value,response=True),5)
   for _ in range(12):
    await asyncio.sleep(.2)
    async with peer.lock:applied=bytes(await asyncio.wait_for(client.read_gatt_char(SETTINGS),5))
    if applied==value:appearance=applied;state['settingsVerified']=True;break
   if not state['settingsVerified']:raise RuntimeError('设备配色回读不一致')
   await render_sample(await model());status('配色已同步到真实屏幕，设备回读一致')
  elif kind in ('search','plan','gateway','vehicle','speak'):raise RuntimeError('电脑联调使用固定公开 DEMO 路线；真实目的地及语音请在安卓应用调试')
  else:raise RuntimeError('不支持的调试操作')
  return {'ok':True,'message':state['message']}
async def ticker():
 global last_vehicle_at
 while True:
  await asyncio.sleep(1)
  if not state['connected']:continue
  try:
   async with control_lock:
    if not client.is_connected or heart.done() or time.monotonic()-peer.received_at>6:raise RuntimeError('蓝牙连接或心跳已断开')
    if state['active'] and not state['paused'] and not state['stale']:state['progress']+=state['speed']/3.6
    if time.monotonic()-last_vehicle_at>=2:await read_vehicle()
    await transmit(await model())
    if peer.errors:raise RuntimeError('协议接收错误：'+peer.errors[-1])
  except Exception as e:
   state['errors'].append(str(e));state['errors']=state['errors'][-8:];await disconnect();status('同步失败：'+str(e))
async def snapshot():
 result=copy.deepcopy(state)
 if latest:result['ui']=copy.deepcopy(latest['ui']);result['ui'].update(connected=state['connected'],navigating=state['active'],vehicleLive=state['connected'] and time.monotonic()-last_vehicle_at<6,vehicle=copy.deepcopy(vehicle));result['routes']=latest['routes'];result['mapData']=latest['mapData'];result['length']=latest['length']
 result['appearance']=appearance.hex();result['serial']=serial_state() if state['connected'] and SERIAL.exists() and time.time()-SERIAL.stat().st_mtime<7 else {};return result
class Handler(BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def origin(self):return self.headers.get('Host')==f'127.0.0.1:{PORT}' and self.headers.get('Origin') in (None,'null',f'http://127.0.0.1:{PORT}')
 def headers_out(self,code,ctype):
  self.send_response(code);self.send_header('Content-Type',ctype);self.send_header('Cache-Control','no-store');self.send_header('X-Content-Type-Options','nosniff')
  if self.headers.get('Origin') in ('null',f'http://127.0.0.1:{PORT}'):
   self.send_header('Access-Control-Allow-Origin',self.headers['Origin']);self.send_header('Vary','Origin')
  self.end_headers()
 def auth(self):return self.origin() and secrets.compare_digest(self.headers.get('Authorization',''),'Bearer '+TOKEN)
 def do_OPTIONS(self):
  if not self.origin():self.headers_out(403,'text/plain');return
  self.send_response(204);self.send_header('Access-Control-Allow-Origin',self.headers.get('Origin',f'http://127.0.0.1:{PORT}'));self.send_header('Access-Control-Allow-Headers','Authorization,Content-Type');self.send_header('Access-Control-Allow-Methods','GET,POST');self.end_headers()
 def do_GET(self):
  if self.headers.get('Host')!=f'127.0.0.1:{PORT}':self.headers_out(403,'text/plain');return
  if self.path in ('/','/index.html'):
   # No CORS header on HTML: foreign pages cannot obtain this session's token.
   self.send_response(200);self.send_header('Content-Type','text/html; charset=utf-8');self.send_header('Cache-Control','no-store');self.send_header('X-Frame-Options','DENY');self.end_headers();self.wfile.write((ROOT/'output/previews/手机应用新版界面预览.html').read_bytes());return
  if not self.auth():self.headers_out(403,'application/json');self.wfile.write(b'{"error":"unauthorized"}');return
  if self.path=='/api/state':data=json.dumps(asyncio.run_coroutine_threadsafe(snapshot(),LOOP).result(5),ensure_ascii=False).encode();self.headers_out(200,'application/json; charset=utf-8');self.wfile.write(data)
  elif self.path=='/api/frame':self.headers_out(200,'image/svg+xml');self.wfile.write(frame)
  else:self.headers_out(404,'text/plain')
 def do_POST(self):
  if self.path!='/api/action' or not self.auth():self.headers_out(403,'application/json');self.wfile.write(b'{"error":"unauthorized"}');return
  try:
   n=int(self.headers.get('Content-Length','0'))
   if not 0<n<=8000:raise ValueError('请求长度无效')
   data=json.loads(self.rfile.read(n));future=asyncio.run_coroutine_threadsafe(command(data['kind'],data.get('data',{})),LOOP)
   try:result=future.result(45)
   except TimeoutError:future.cancel();raise RuntimeError('操作超时')
   self.headers_out(200,'application/json; charset=utf-8');self.wfile.write(json.dumps(result,ensure_ascii=False).encode())
  except Exception as e:status('调试操作失败：'+str(e));self.headers_out(400,'application/json; charset=utf-8');self.wfile.write(json.dumps({'error':str(e)},ensure_ascii=False).encode())
async def main():
 global worker,worker_lock,control_lock,LOOP
 LOOP=asyncio.get_running_loop();worker_lock=asyncio.Lock();control_lock=asyncio.Lock()
 worker=await asyncio.create_subprocess_exec('node',str(ROOT/'software/pc_debug/model.cjs'),cwd=str(ROOT),limit=2000000,stdin=asyncio.subprocess.PIPE,stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.DEVNULL,creationflags=subprocess.CREATE_NO_WINDOW)
 await model()
 from build_html import build
 build(TOKEN)
 server=ThreadingHTTPServer(('127.0.0.1',PORT),Handler);threading.Thread(target=server.serve_forever,daemon=True).start();print(f'PC debug ready: http://127.0.0.1:{PORT}/',flush=True)
 try:await ticker()
 finally:await disconnect();worker.terminate();server.shutdown()
if __name__=='__main__':
 try:asyncio.run(main())
 except KeyboardInterrupt:pass
