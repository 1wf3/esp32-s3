'use strict';
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const screen=require('../assets/screen-map.js'),folder=process.argv[2];let count=0;
const scaleOrigin=[116.397,39.908],east100=[scaleOrigin[0]+100/(111320*Math.cos(scaleOrigin[1]*Math.PI/180)),scaleOrigin[1]];
assert.ok(Math.abs(screen.project(east100,scaleOrigin,0)[0]-240-120)<=1,'100 metres projects to 120 pixels at the enlarged scale');
for(const angle of [0,45,90,180,270,359]){
 const {scene,frame,appearance}=JSON.parse(fs.readFileSync(path.join(folder,`scene-${angle}.json`),'utf8'));
 const svg=fs.readFileSync(path.join(folder,`scene-${angle}.svg`),'utf8');
 const cpp=[...svg.matchAll(/<path d='M(-?[\d.]+),(-?[\d.]+) L(-?[\d.]+),(-?[\d.]+)' fill='none' stroke='(#[\da-f]+)' stroke-width='([\d.]+)'/g)].map(m=>({a:[+m[1],+m[2]],b:[+m[3],+m[4]],color:m[5],width:+m[6]}));
 const phone=screen.strokes(scene,frame,appearance.colors);assert.ok(cpp.length>=phone.length,'native renderer contains all map strokes');
 for(let i=0;i<phone.length;i++){const a=phone[i],b=cpp[i];assert.equal(a.color,b.color,`angle ${angle}, stroke ${i} color`);assert.equal(a.width,b.width,`angle ${angle}, stroke ${i} width`);for(const key of ['a','b'])for(let j=0;j<2;j++)assert.ok(Math.abs(a[key][j]-b[key][j])<=1,`angle ${angle}, stroke ${i}, ${key}[${j}]`);count++;}
 assert.deepEqual(screen.project(frame.origin,frame.origin,angle),[240,194]);
}
console.log(`Phone / production TFT renderer parity PASS: ${count} strokes, six headings, coordinates within 1 pixel; colors and widths identical.`);
