const fs=require('fs'),path=require('path'),vm=require('vm'),{spawnSync}=require('child_process'),readline=require('readline');
const root=path.resolve(__dirname,'../..'),out=path.join(root,'build/pc_debug');fs.mkdirSync(out,{recursive:true});
const html=fs.readFileSync(path.join(root,'software/web_debug/phone_debug.template.html'),'utf8');
const ctx=vm.createContext({TextEncoder,Uint8Array,DataView,Math,Date});vm.runInContext(html.match(/<script id="nav-core">([\s\S]*?)<\/script>/)[1],ctx);
const {Core,RouteModel}=ctx.NavCore,fixture=JSON.parse(fs.readFileSync(path.join(root,'software/phone_gateway/shared/demo_fixture/jinan_big_data_to_inspur.json'),'utf8'));
const points=fixture.route_wgs84.map(p=>Core.gcj(p[1],p[0])),cum=[0];for(let i=1;i<points.length;i++)cum.push(cum.at(-1)+Core.distance(points[i-1],points[i]));
const source={schema_version:1,route_id:fixture.route_id,provider:'osm-demo',coordinate_system:'GCJ-02',total_distance_m:cum.at(-1),total_duration_s:fixture.fallback_duration_s,polyline:points.map(p=>({longitude_deg:p[0],latitude_deg:p[1]})),maneuvers:fixture.maneuvers.map(m=>({...m,route_offset_m:cum[m.route_point_index]}))};
let route=new RouteModel(source),mapOrigin=null,mapHex='',revision=0;
const tile=JSON.parse(fs.readFileSync(path.join(root,'software/android_nav/assets/preview-map.json'),'utf8'));
const jdk=fs.readdirSync(path.join(root,'build/android_tools/jdk')).find(x=>x.startsWith('jdk-')),java=path.join(root,'build/android_tools/jdk',jdk,'bin/java.exe');
const hex=b=>Buffer.from(b).toString('hex');
function sample(input){
 const progress=Number(input.progress||0),speed=Number(input.speed??24);if(!Number.isFinite(progress)||progress<0||!Number.isFinite(speed)||speed<0||speed>120)throw Error('演示进度或速度无效');route.progress=Math.min(route.c.at(-1),progress);
 const pose=route.pose(route.progress),fix=input.stale?null:{valid:true,gcj:pose.pos,at:Date.now(),accuracy:5,speed:speed/3.6,heading:pose.heading};
 let snap=input.active?route.snapshot(fix,{demo:true,generation:input.generation||1}):{state:0,network:0,page:0,maneuver:0,flags:0,token:0,generation:0,maneuverId:0,turnDistance:0,remaining:0,duration:0,progress:0,total:0,speed:0,heading:0,accuracy:0,cross:0};if(input.active)snap.flags|=128;
 if(input.active&&(!mapOrigin||Core.distance(pose.pos,mapOrigin)>80)){
  const lines=[pose.pos.join(' '),...tile.roads.map(r=>`${Math.max(0,['motorway','primary','secondary','residential','service','other'].indexOf(r.class))} ${r.points_e6.map(p=>`${p[1]/1e6} ${p[0]/1e6}`).join(' ')}`)];fs.writeFileSync(path.join(out,'roads.txt'),lines.join('\n'));
  const result=spawnSync(java,['-cp',path.join(root,'build/android_java_tests'),'MakeMap',path.join(out,'roads.txt'),path.join(out,'map.txt')],{encoding:'utf8',windowsHide:true});if(result.status!==0)throw Error('真实路网裁剪失败：'+result.stderr.slice(0,200));
  const data=Buffer.from(fs.readFileSync(path.join(out,'map.txt'),'utf8').trim().split(' ')[1],'hex');data.writeUInt32LE(++revision,2);mapHex=data.toString('hex');mapOrigin=pose.pos;
 }
 const geometry=input.active?hex(Core.geometry(snap.token,snap.generation,pose.pos,route.window())):'';
 const snapshot=hex(Core.snapshot(snap));
 const ui={connected:false,navigating:!!input.active,fresh:!!fix,phone:pose.pos,mapStatus:'真实公开路网 · 电脑模拟定位',gatewayReady:false,vehicleLive:false,remaining:snap.remaining,duration:snap.duration,speed:snap.speed/10,turnDistance:snap.turnDistance,instruction:snap.instruction||'',road:snap.road||'',maneuver:snap.maneuver,polyline:route.p,offRoute:false};
 return {snapshot,geometry,map:mapHex,mapRevision:revision,mapRoads:mapHex?Buffer.from(mapHex,'hex')[16]:0,ui,routes:[source],mapData:tile,length:route.c.at(-1),progress:route.progress};
}
readline.createInterface({input:process.stdin}).on('line',line=>{try{const input=JSON.parse(line);process.stdout.write(JSON.stringify(sample(input))+'\n')}catch(e){process.stdout.write(JSON.stringify({error:e.message})+'\n')}});
