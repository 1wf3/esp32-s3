const fs=require('fs'),path=require('path'),vm=require('vm');
const root=path.resolve(__dirname,'../../..');
const html=fs.readFileSync(path.join(root,'output/previews/手机导航实机调试.html'),'utf8');
const ctx=vm.createContext({TextEncoder,Uint8Array,DataView,Math,Date});
vm.runInContext(html.match(/<script id="nav-core">([\s\S]*?)<\/script>/)[1],ctx);
const {Bytes,Core,RouteModel}=ctx.NavCore;
const fixture=JSON.parse(fs.readFileSync(path.join(root,'software/phone_gateway/shared/demo_fixture/jinan_big_data_to_inspur.json')));
const p=fixture.route_wgs84.map(p=>Core.gcj(p[1],p[0])),c=[0];
for(let i=1;i<p.length;i++)c.push(c.at(-1)+Core.distance(p[i-1],p[i]));
const route=new RouteModel({route_id:fixture.route_id,provider:'osm-demo',coordinate_system:'GCJ-02',total_distance_m:c.at(-1),total_duration_s:fixture.fallback_duration_s,polyline:p.map(p=>({longitude_deg:p[0],latitude_deg:p[1]})),maneuvers:fixture.maneuvers.map(m=>({...m,route_offset_m:c[m.route_point_index]}))});
const output=[];
// Public OSM road context; no invented building footprints.
let roadPoints=0;
const roads=(fixture.road_context||[]).slice(0,24).map(r=>({kind:({motorway:0,primary:1,secondary:2,residential:3,service:4})[r.highway]??5,points:r.points_wgs84.map(p=>Core.gcj(p[1],p[0]))})).filter(r=>{if(roadPoints+r.points.length>192)return false;roadPoints+=r.points.length;return r.points.length>=2});
const origin=p[0],lat=Math.round(origin[1]*1e6),lon=Math.round(origin[0]*1e6);
const map=new Bytes().u8(1).u8(2).u32(1).u32(lat).u32(lon).u16(1000).u8(roads.length).u8(0);
for(const r of roads){map.u8(r.kind).u8(r.points.length);for(const q of r.points)map.varint(Math.round(q[1]*1e6)-lat).varint(Math.round(q[0]*1e6)-lon)}
output.push({type:0x14,flags:4,label:'DEMO public OSM road context',payload:Buffer.from(map.bytes()).toString('hex')});
for(const progress of [0,120,350]){
  route.progress=progress;
  const pose=route.pose(progress),fix={valid:true,gcj:pose.pos,at:Date.now(),accuracy:5,speed:24/3.6,heading:pose.heading};
  const snap=route.snapshot(fix,{demo:true,generation:1}); snap.flags|=128;
  output.push({type:0x11,flags:4,label:'DEMO route '+progress,payload:Buffer.from(Core.geometry(snap.token,1,pose.pos,route.window())).toString('hex')});
  output.push({type:0x10,flags:0,label:'DEMO navigation '+progress,payload:Buffer.from(Core.snapshot(snap)).toString('hex')});
}
const stale=route.snapshot(null,{demo:true,generation:1}); stale.flags|=128;stale.instruction='DEMO · 定位丢失，暂停指引';
output.push({type:0x10,flags:0,label:'DEMO stale location',payload:Buffer.from(Core.snapshot(stale)).toString('hex')});
const file=path.join(root,'build/board_ble_fixture.json');fs.writeFileSync(file,JSON.stringify(output,null,2));console.log(file);
