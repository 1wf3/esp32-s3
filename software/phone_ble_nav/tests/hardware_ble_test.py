"""Physical ESP32 test using public DEMO data; no phone or real location."""
import asyncio, binascii, json, pathlib, re, secrets, struct, sys, time
ROOT=pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'build/ble_tools'))
from bleak import BleakClient, BleakScanner
SERVICE='7e57a000-b50c-4b6a-9c57-40a54e8e1000'
RX='7e57a001-b50c-4b6a-9c57-40a54e8e1000'
TX='7e57a002-b50c-4b6a-9c57-40a54e8e1000'

async def serial_state(predicate,timeout=8):
    logfile=ROOT/'build/board_ble_serial.log'
    deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        if logfile.exists():
            rows=re.findall(r'BLE=(\d+) ENC=(\d+) READY=(\d+) NAV=(\d+) ROUTE=(\d+) MAP=(\d+) STATE=(\d+) FLAGS=(\d+) SPEED=(\d+) ERROR=([^\r\n]*)',logfile.read_text(encoding='utf-8'))
            if rows:
                state=dict(zip(['ble','encrypted','ready','snapshots','route','map','state','flags','speed'],map(int,rows[-1][:9])))
                if predicate(state):
                    assert not rows[-1][9],rows[-1][9]
                    return state
        await asyncio.sleep(.2)
    raise AssertionError('Expected state absent from serial log')

class Peer:
    def __init__(self,client):
        self.client=client;self.sequence=0;self.lock=asyncio.Lock();self.messages=asyncio.Queue()
        self.budget=20 if '--small-frames' in sys.argv else min(185,client.mtu_size-3)
        self.part=None;self.last=0;self.errors=[];self.frames=0;self.duplicates=0
        self.session=secrets.randbits(32) or 1;self.started=time.monotonic()
    def notify(self,_,data):
        self.frames+=1
        try:
            if len(data)<13 or data[:2]!=b'\xb7\x01' or binascii.crc_hqx(data[:-2],65535)!=struct.unpack_from('<H',data,len(data)-2)[0]:raise ValueError('CRC/header')
            kind,flags,seq,offset,total=struct.unpack_from('<BBHHH',data,2);payload=bytes(data[10:-2])
            if seq==self.last:self.duplicates+=1;return
            if offset==0:
                if self.part and self.part[0]==seq and self.part[2].startswith(payload):self.duplicates+=1;return
                self.part=[seq,kind,b'',total]
            if not self.part or self.part[0]!=seq:raise ValueError('missing first fragment')
            if offset<len(self.part[2]) and self.part[2][offset:offset+len(payload)]==payload:self.duplicates+=1;return
            if offset!=len(self.part[2]):raise ValueError('fragment gap')
            self.part[2]+=payload
            if flags&2:
                if len(self.part[2])!=total:raise ValueError('wrong payload length')
                self.last=seq;self.messages.put_nowait((kind,seq,self.part[2]));self.part=None
        except Exception as e:self.errors.append(str(e))
    async def send(self,kind,payload,flags=0,sequence=None,corrupt=False):
        async with self.lock:
            if sequence is None:self.sequence=self.sequence%65535+1;sequence=self.sequence
            for offset in range(0,len(payload),self.budget-12):
                chunk=payload[offset:offset+self.budget-12]
                f=flags|(1 if offset==0 else 0)|(2 if offset+len(chunk)==len(payload) else 0)
                frame=struct.pack('<BBBBHHH',183,1,kind,f,sequence,offset,len(payload))+chunk
                frame+=struct.pack('<H',binascii.crc_hqx(frame,65535))
                if corrupt:frame=frame[:-1]+bytes([frame[-1]^1])
                await self.client.write_gatt_char(RX,frame,response=True)
                await asyncio.sleep(.02)
            return sequence
    async def receive(self,kind,timeout=5,ackseq=None):
        deadline=time.monotonic()+timeout
        while True:
            k,s,p=await asyncio.wait_for(self.messages.get(),max(.01,deadline-time.monotonic()))
            if k==kind and (ackseq is None or struct.unpack_from('<H',p,1)[0]==ackseq):return p
    async def handshake(self):
        hello=struct.pack('<BBBBBIIHH',1,1,0,1,1,215,self.session,self.budget,1000)
        await self.send(1,hello)
        reply=await self.receive(1)
        rev,role,state,lo,hi,caps,sid,size,interval=struct.unpack('<BBBBBIIHH',reply)
        assert (rev,role,state,sid,size)==(1,2,1,self.session,self.budget),reply.hex()
        assert caps&215==215
        await self.send(1,struct.pack('<BBBBBIIHH',1,1,1,1,1,caps,sid,size,interval))
        final=await self.receive(1);assert final==reply
        print('PASS encrypted GATT + two-stage Ready handshake; frame budget='+str(size),flush=True)
    async def heartbeat(self):
        try:
            while self.client.is_connected:
                await self.send(2,struct.pack('<BIIH',1,self.session,int((time.monotonic()-self.started)*1000),0))
                await asyncio.sleep(.7)
        except Exception as e:
            print('HEARTBEAT FAILED: '+str(e),flush=True)
            raise

async def main():
    devices=await BleakScanner.discover(timeout=8,return_adv=True)
    targets=[d for d,a in devices.values() if (a.local_name=='MOTO GPS TFT' or d.name=='MOTO GPS TFT') and SERVICE in a.service_uuids]
    if len(targets)!=1:raise RuntimeError('Expected exactly one MOTO GPS TFT board')
    async with BleakClient(targets[0],pair=True,timeout=30,winrt={'use_cached_services':False},disconnected_callback=lambda _:print('HOST observed disconnection',flush=True)) as client:
        peer=Peer(client)
        await client.start_notify(TX,peer.notify)
        await peer.handshake()
        heart=asyncio.create_task(peer.heartbeat())
        try:
            fixtures=json.loads((ROOT/'build/board_ble_fixture.json').read_text())
            if '--map-stress' in sys.argv:fixtures=[fixtures[0],fixtures[1],fixtures[2],fixtures[-1]]
            for item in fixtures:
                seq=await peer.send(item['type'],bytes.fromhex(item['payload']),item['flags'])
                if item['flags']&4:
                    ack=await peer.receive(3,ackseq=seq);assert ack[3] in (0,4),ack.hex()
                    # Identical route retransmit after a heartbeat must be acknowledged.
                    if item['type']==0x11 or (item['type']==0x14 and (peer.budget>20 or '--repeat-map' in sys.argv)):
                        await asyncio.sleep(.9)
                        await peer.send(item['type'],bytes.fromhex(item['payload']),item['flags'],sequence=seq)
                        ack=await peer.receive(3,ackseq=seq);assert ack[3] in (0,4)
                print('PASS '+item['label'],flush=True)
                await asyncio.sleep(4)
                if item['type']==0x14:
                    await serial_state(lambda s:s['ready']==1 and s['map']==1)
                if item['type']==0x10:
                    is_stale=item['label']=='DEMO stale location'
                    await serial_state(lambda s:s['ready']==1 and s['route']==1 and (s['flags']&2==0 if is_stale else s['flags']&2!=0) and s['speed']==(0 if is_stale else 240))
            # Keep transport alive while deliberately withholding navigation.
            valid=fixtures[-2]
            await peer.send(0x10,bytes.fromhex(valid['payload']))
            print('OBSERVE DEMO: 12 seconds of heartbeats without location updates',flush=True)
            await asyncio.sleep(12)
            await serial_state(lambda s:s['ready']==1 and s['flags']&4!=0 and s['flags']&2==0 and s['speed']==0)
            print('PASS serial confirms navigation expired despite continuing heartbeats',flush=True)
            await peer.send(0x10,bytes.fromhex(valid['payload']))
            print('OBSERVE DEMO: fresh snapshot restored',flush=True)
            await asyncio.sleep(4)
            await serial_state(lambda s:s['ready']==1 and s['flags']&2!=0 and s['speed']==240)
            print('PASS serial confirms fresh location and speed restored',flush=True)
            # The device must disconnect and clear stale facts when peer heartbeats stop.
            heart.cancel()
            try:await heart
            except asyncio.CancelledError:pass
            for _ in range(65):
                if not client.is_connected:break
                await asyncio.sleep(.1)
            assert not client.is_connected,'Device failed to disconnect silent peer'
            print('PASS silent peer timeout / device disconnect',flush=True)
            # Windows may automatically reopen a paired GATT link; the board
            # must still clear old session facts before any fresh handshake.
            await serial_state(lambda s:s['ready']==0 and s['route']==0 and s['flags']==0 and s['speed']==0)
            await client.disconnect()
            assert not peer.errors,peer.errors
            print('RX frames='+str(peer.frames)+' duplicates='+str(peer.duplicates),flush=True)
        finally:
            heart.cancel()
    # Confirm the board advertises again after the test connection ends.
    again=await BleakScanner.find_device_by_name('MOTO GPS TFT',timeout=10)
    assert again is not None,'Advertising did not resume'
    print('PASS advertising resumed; all physical BLE transport checks passed',flush=True)

if __name__=='__main__':asyncio.run(main())
