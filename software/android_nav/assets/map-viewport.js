(function(root){
'use strict';
// Invert the same rotation and metres-to-pixels transform used by drawMap.
function measure(origin,width,height,cy,heading,pixelsPerMetre){
 const a=heading*Math.PI/180,c=Math.cos(a),s=Math.sin(a);
 let halfWidth=0,halfHeight=0;
 for(const x of [-width/2,width/2])for(const y of [cy,cy-height]){
  const right=x/pixelsPerMetre,forward=y/pixelsPerMetre;
  halfWidth=Math.max(halfWidth,Math.abs(right*c+forward*s));
  halfHeight=Math.max(halfHeight,Math.abs(-right*s+forward*c));
 }
 return {lon:origin[0],lat:origin[1],halfWidth:Math.min(20_000_000,Math.max(50,halfWidth)),halfHeight:Math.min(20_000_000,Math.max(50,halfHeight))};
}
function changed(a,b){
 if(!a)return true;
 const movement=Math.hypot((a.lon-b.lon)*Math.cos(b.lat*Math.PI/180),a.lat-b.lat)*111320;
 return movement>Math.min(50,Math.min(b.halfWidth,b.halfHeight)*.1)||Math.abs(a.halfWidth-b.halfWidth)>Math.max(5,b.halfWidth*.03)||Math.abs(a.halfHeight-b.halfHeight)>Math.max(5,b.halfHeight*.03);
}
const api={measure,changed};root.MapViewport=api;if(typeof module!=='undefined')module.exports=api;
})(typeof globalThis!=='undefined'?globalThis:this);
