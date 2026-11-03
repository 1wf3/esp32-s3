"""Run after PC bridge is connected. Contains public DEMO only; prints no coordinates or token."""
import json,pathlib,re,time,urllib.request,urllib.error
ROOT=pathlib.Path(__file__).resolve().parents[2]
TOKEN=json.loads(re.search(r'PC_TOKEN=("[^"]+")',(ROOT/'output/previews/手机应用新版界面预览.html').read_text('utf-8')).group(1));URL='http://127.0.0.1:8790'
def request(path='/api/state',data=None,auth=True,origin=None,host=None):
 headers={'Content-Type':'application/json'}
 if auth:headers['Authorization']='Bearer '+TOKEN
 if origin:headers['Origin']=origin
 if host:headers['Host']=host
 r=urllib.request.Request(URL+path,data=json.dumps(data).encode() if data else None,headers=headers)
 with urllib.request.urlopen(r,timeout=50) as response:return json.load(response)
def action(kind,**data):return request('/api/action',{'kind':kind,'data':data})
def wait(predicate,seconds=12):
 deadline=time.monotonic()+seconds
 while time.monotonic()<deadline:
  s=request()
  if predicate(s):return s
  time.sleep(.4)
 raise AssertionError('Expected serial/bridge state absent')
def rejects(fn,code):
 try:fn()
 except urllib.error.HTTPError as e:assert e.code==code;return
 raise AssertionError('Invalid request accepted')
checks=[]
def passed(name):checks.append(name);print('PASS '+name,flush=True)
rejects(lambda:request(auth=False),403);rejects(lambda:request(origin='https://example.com'),403);rejects(lambda:request('/',host='example.com:8790'),403);passed('local API authentication / origin / Host isolation')
wait(lambda s:s['connected'],30);passed('encrypted BLE Ready')
rejects(lambda:action('settings',theme=1,marker=1,colors=['#nope']*4),400);passed('malformed colors rejected')
action('begin');action('pause');s=wait(lambda s:s['serial'].get('ready')==1 and s['serial'].get('route')==1 and s['serial'].get('map',0)>0 and s['serial'].get('speed')==240);assert s['mapRoads']>24;passed('real road map + matching geometry / snapshot physically received')
action('speed',value=38);wait(lambda s:s['serial'].get('speed')==380);passed('HTML speed -> TFT serial speed=38 km/h')
action('settings',theme=0,marker=2,colors=['#F3F4EF','#62696D','#FFF8E7','#888888']);s=request();assert s['settingsVerified'];passed('theme / marker / 4 colors read back from device')
action('stale');wait(lambda s:s['serial'].get('flags',0)&4 and not s['serial'].get('flags',0)&2 and s['serial'].get('speed')==0);passed('stale navigation suppresses fresh fix and speed')
action('fresh');wait(lambda s:s['serial'].get('flags',0)&2 and s['serial'].get('speed')==380);passed('fresh location restores physical navigation')
action('step');wait(lambda s:s['serial'].get('route')==1 and s['serial'].get('map',0)>=1);passed('progress / matching route update')
action('end');wait(lambda s:s['serial'].get('route')==0 and s['serial'].get('state')==0);passed('end navigation clears physical route')
# Leave a calm synchronized demonstration for further manual debugging.
action('settings',theme=1,marker=1,colors=['#93C5FD','#62696D','#E0F2FE','#888888']);action('speed',value=24);action('reset');action('begin');action('pause');s=wait(lambda s:s['serial'].get('route')==1 and s['serial'].get('speed')==240);assert not s['errors'];passed('restored paused DEMO / no protocol errors')
report={'passed':checks,'frame_budget':s.get('frameBudget'),'map_roads':s['mapRoads'],'device_serial':s['serial'],'snapshot_count':s['txSnapshots'],'map_route_acks':s['acks'],'settings_verified':s['settingsVerified'],'vehicle_gps_valid':s['ui'].get('vehicle',{}).get('valid',False)}
(ROOT/'build/pc_debug/test-results.json').write_text(json.dumps(report,indent=2),encoding='utf-8');print('ALL PC / TFT integration checks passed: '+str(len(checks)),flush=True)
