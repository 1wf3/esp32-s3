// Serve only the HTML and the explicitly selected test APK; never expose
// workspace keys, Wi-Fi secrets, source listings or arbitrary file paths.
const http=require('http'),fs=require('fs'),path=require('path');
const root=path.resolve(__dirname,'../..');
const routes={
  '/': [path.join(root,'output/previews/手机导航实机调试.html'),'text/html; charset=utf-8'],
  '/phone': [path.join(root,'output/previews/手机导航实机调试.html'),'text/html; charset=utf-8'],
  '/app.apk': [path.join(root,'software/android_nav/output/nav-screen-android-test.apk'),'application/vnd.android.package-archive']
};
http.createServer((req,res)=>{
  const item=routes[new URL(req.url,'http://localhost').pathname];
  if(!item||!['GET','HEAD'].includes(req.method)){res.writeHead(404);res.end();return;}
  if(!fs.existsSync(item[0])){res.writeHead(404);res.end();return;}
  res.setHeader('Content-Type',item[1]);res.setHeader('Cache-Control','no-store');
  res.setHeader('X-Content-Type-Options','nosniff');
  if(req.method==='HEAD'){res.end();return;}
  fs.createReadStream(item[0]).pipe(res);
}).listen(8765,'0.0.0.0',()=>console.log('Phone debug: http://127.0.0.1:8765/phone (LAN interface enabled, whitelisted files only)'));
