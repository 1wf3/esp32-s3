const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const view=require('../assets/map-viewport.js');
const origin=[116.397,39.908];
let checks=0;function check(ok,message){checks++;assert.ok(ok,message)}
for(const angle of [0,45,90,180,270,359])for(const ppm of [.008,.08,.55,3]){
 const width=480,height=900,cy=360,v=view.measure(origin,width,height,cy,angle,ppm),a=angle*Math.PI/180;
 for(const x of [-width/2,width/2])for(const y of [cy,cy-height]){
  const e=x/ppm*Math.cos(a)+y/ppm*Math.sin(a),n=-x/ppm*Math.sin(a)+y/ppm*Math.cos(a);
  check(Math.abs(e)<=v.halfWidth+1e-8&&Math.abs(n)<=v.halfHeight+1e-8,'all visible corners are covered');
 }
}
const small=view.measure(origin,480,900,360,0,.55),large=view.measure(origin,480,900,360,0,.08);
check(view.changed(small,large),'zoom change sends viewport even without centre movement');
check(view.changed(small,view.measure(origin,900,480,192,0,.55)),'rotation/resizing sends new viewport');
check(!view.changed(small,{...small}),'identical viewport is suppressed');
// Execute the production bridge scheduling functions with fake time and no DOM.
const app=fs.readFileSync(require.resolve('../assets/app.js'),'utf8');
const code=app.slice(app.indexOf('let viewportTimer=null'),app.indexOf('\nfunction splitProgress'));
let timer,now=100_000,sends=[];
const context={window:{AndroidNav:{}},MapViewport:view,Date:{now:()=>now},setTimeout(fn){check(!timer,'only one pending bridge timer');timer=fn;return 1},send(kind,data){sends.push({kind,data})}};
vm.createContext(context);vm.runInContext(code,context);
function request(lon,ppm){vm.runInContext(`requestViewport([${lon},39.908],480,900,360,0,${ppm})`,context)}
function flush(){const fn=timer;timer=null;fn()}
request(116.397,.55);request(116.398,.55);request(116.399,.55);flush();
check(sends.length===1&&sends[0].data.lon===116.399,'continuous updates deliver latest viewport without debounce starvation');
request(116.399,.08);flush();check(sends.length===2&&sends[1].data.halfWidth>small.halfWidth,'zoom forwards dimensions to native');
request(116.399,.08);flush();check(sends.length===2,'unchanged view avoids redundant bridge traffic');
now+=30_001;request(116.399,.08);flush();check(sends.length===3,'periodic resend recovers service attachment and retries');
check(/map-viewport\.js[\s\S]*app\.js/.test(fs.readFileSync(require.resolve('../assets/index.html'),'utf8')),'helper loads before app');
console.log(`Map viewport PASS: ${checks} checks (rotated corners, zoom/resize, moving updates, native bridge retry).`);
