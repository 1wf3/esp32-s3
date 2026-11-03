package top.maler.community;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.bluetooth.BluetoothDevice;
import android.os.*;
import android.webkit.*;
import android.speech.tts.TextToSpeech;
import org.json.*;
import java.util.*;

/** Bundled UI only: no remote scripts or remote navigation can access this bridge. */
public final class MainActivity extends Activity implements NavService.Listener {
    private WebView web;private NavService service;private boolean bound=false,loaded=false,ttsReady=false;
    private boolean pendingAmap=false,pendingScan=false;private boolean mirrorFit=false;private TextToSpeech tts;private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<String,BluetoothDevice> found=new HashMap<>();private JSONArray placeResults=new JSONArray();
    private final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName name,IBinder binder){service=((NavService.LocalBinder)binder).service();service.listener=MainActivity.this;status(service.message);diagnostics(service.diagnostics());service.publish();String key=AmapKeyStore.read(MainActivity.this);if(!key.isEmpty()&&!service.state().optBoolean("gatewayReady"))service.configureAmap(key);if(pendingScan)scanDevices();if(pendingAmap){pendingAmap=false;configureAmap();}}
        public void onServiceDisconnected(ComponentName name){service=null;bound=false;}
    };
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);getWindow().setStatusBarColor(0xfffaf9f6);getWindow().setNavigationBarColor(0xfffaf9f6);getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        web=new WebView(this);setContentView(web);web.setBackgroundColor(0xfff7f6f1);
        WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(false);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setAllowFileAccessFromFileURLs(false);settings.setAllowUniversalAccessFromFileURLs(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.addJavascriptInterface(new Bridge(),"AndroidNav");
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return true;}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){String url=request.getUrl().toString();if(!url.startsWith("file:///android_asset/"))return new WebResourceResponse("text/plain","UTF-8",new java.io.ByteArrayInputStream(new byte[0]));return null;}
            @Override public void onPageFinished(WebView view,String url){if(!url.equals("file:///android_asset/index.html"))return;loaded=true;android.util.Log.i("NavUi","bundled UI ready");initial();if(service!=null){status(service.message);diagnostics(service.diagnostics());service.publish();}}
        });
        tts=new TextToSpeech(this,result->{ttsReady=result==TextToSpeech.SUCCESS;if(ttsReady)tts.setLanguage(Locale.CHINA);});
        web.loadUrl("file:///android_asset/index.html");
    }
    private void initial(){try{JSONObject o=new JSONObject().put("amapConfigured",!AmapKeyStore.read(this).isEmpty()).put("destination",getSharedPreferences("nav",0).getString("destination","")).put("settings",new JSONObject(getSharedPreferences("nav",0).getString("appearance","{}"))).put("vehicleLast",new JSONObject(getSharedPreferences("nav",0).getString("vehicleLast","{}")));event("init",o);}catch(Exception ignored){}}
    private void configureAmap(){
        if(!needService())return;
        if(service.state().optBoolean("navigating")){status("请先结束导航再配置 Key");return;}
        final android.widget.EditText input=new android.widget.EditText(this);
        input.setSingleLine(true);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint(AmapKeyStore.read(this).isEmpty()?"输入高德 Web 服务 Key":"已保存 Key；留空使用原 Key");
        final AlertDialog dialog=new AlertDialog.Builder(this).setTitle("高德直连配置").setMessage("仅用于你自己的设备。Key 加密保存在本机，无需电脑、域名或网关。请输入 Web服务类型 Key。").setView(input).setNegativeButton("取消",null).setPositiveButton("保存并测试",null).create();
        dialog.setOnShowListener(v->{dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{String key=input.getText().toString().trim();try{Gateway.direct(key.isEmpty()?AmapKeyStore.read(this):key);}catch(Exception e){input.setError(e.getMessage());return;}if(needService()){service.configureAmap(key);dialog.dismiss();initial();}});});dialog.show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==72&&result==RESULT_OK&&data!=null){if(service!=null)service.endNavigation();Intent start=new Intent(this,MirrorService.class).putExtra("code",result).putExtra("consent",data).putExtra("fit",mirrorFit);startForegroundService(start);status("手机 Wi-Fi 保持不变，请共享高德 App 画面");}}
    private void event(String type,Object value){if(!loaded)return;String data=(value instanceof String?JSONObject.quote((String)value):String.valueOf(value));web.evaluateJavascript("window.receive("+JSONObject.quote(type)+","+data+")",null);}
    private void pickColor(JSONObject data)throws Exception {
        final int index=data.getInt("index");if(index<0||index>3)return;
        final android.widget.EditText input=new android.widget.EditText(this);input.setSingleLine(true);input.setText(data.optString("color","#FF9300"));input.setHint("#RRGGBB，例如 #36A3FF");
        android.widget.LinearLayout panel=new android.widget.LinearLayout(this);panel.setOrientation(1);panel.setPadding(36,12,36,12);panel.addView(input);
        android.widget.LinearLayout presets=new android.widget.LinearLayout(this);
        for(String color:new String[]{"#FF9300","#2789FF","#26A269","#D544E5","#F3F4EF","#62696D"}){android.widget.Button swatch=new android.widget.Button(this);swatch.setContentDescription(color);swatch.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(color)));swatch.setOnClickListener(v->input.setText(color));presets.addView(swatch,new android.widget.LinearLayout.LayoutParams(0,100,1));}panel.addView(presets);
        final AlertDialog dialog=new AlertDialog.Builder(this).setTitle("选择导航颜色").setView(panel).setNegativeButton("取消",null).setPositiveButton("应用",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{String color=input.getText().toString().trim();if(!color.matches("#[0-9a-fA-F]{6}")){input.setError("请输入 # 加六位十六进制颜色");return;}try{event("color",new JSONObject().put("index",index).put("color",color.toUpperCase(java.util.Locale.ROOT)));dialog.dismiss();}catch(Exception ignored){}}));dialog.show();
    }
    private void scanDevices(){if(service==null)return;pendingScan=false;found.clear();event("devices",new JSONArray());service.scan();}
    private boolean needService(){if(service==null){status("请先开启会话 / 授予权限");return false;}return true;}
    private final class Bridge {
        @JavascriptInterface public void action(String kind,String json){if(json==null||json.length()>8000)return;main.post(()->dispatch(kind,json));}
    }
    private void dispatch(String kind,String json){try{JSONObject data=new JSONObject(json);
        android.util.Log.i("NavUi","action="+kind);switch(kind){
            case "start":permissions();break;
            case "scan":pendingScan=true;if(service==null)permissions();else scanDevices();break;
            case "connect":BluetoothDevice chosen=found.get(data.optString("id"));if(chosen!=null&&needService())service.connect(chosen);break;
            case "configureAmap":if(service==null){pendingAmap=true;permissions();}else configureAmap();break;
            case "prepareBluetoothMode":stopService(new Intent(this,MirrorService.class));if(service!=null)service.stopDirectMirror();status("已切换至高德蓝牙导航");break;
            case "directMirror":if(needService()){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.NEARBY_WIFI_DEVICES")!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{"android.permission.NEARBY_WIFI_DEVICES"},73);else service.startDirectMirror();}break;
            case "startMirrorFit":case "startMirror":if(service==null||!service.state().optBoolean("castReady")){status("请先在高德投屏导航中连接显示屏并建立 Wi-Fi Direct 连接");break;}mirrorFit=kind.equals("startMirrorFit");startActivityForResult(getSystemService(android.media.projection.MediaProjectionManager.class).createScreenCaptureIntent(),72);break;
            case "stopMirror":stopService(new Intent(this,MirrorService.class));status("Wi-Fi 投屏已停止");break;
            case "search":String query=data.optString("value");if(query.length()>500)return;getSharedPreferences("nav",0).edit().putString("destination",query).apply();if(needService())service.search(query);break;
            case "plan":int at=data.optInt("index",-1);if(at>=0&&at<placeResults.length()&&needService())service.plan(placeResults.getJSONObject(at));break;
            case "begin":if(needService())service.begin(data.optInt("index",0));break;
            case "end":if(needService())service.endNavigation();break;
            case "vehicle":if(needService())service.locateVehicle();break;
            case "copyGpsLog":String log=data.optString("text","");if(log.length()>6000)log=log.substring(log.length()-6000);((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("开发板 GPS 诊断",log));status("GPS 诊断记录已复制");break;
            case "pickColor":pickColor(data);break;
            case "mapViewport":if(service!=null)service.mapViewport(data.getDouble("lon"),data.getDouble("lat"),data.optDouble("halfWidth",750),data.optDouble("halfHeight",750));break;
            case "saveAppearance":case "settings":byte[] encoded=Appearance.encode(data);getSharedPreferences("nav",0).edit().putString("appearance",data.toString()).apply();if(kind.equals("settings")&&needService())service.syncSettings(encoded);break;
            case "speak":if(needService()){JSONObject state=service.state();String instruction=state.optBoolean("fresh")&&!state.optBoolean("offRoute")?state.optString("instruction",""):"当前定位不可用或已经偏离路线，请查看屏幕状态";if(instruction.isEmpty())instruction="请先开始导航";if(ttsReady)tts.speak(instruction,TextToSpeech.QUEUE_FLUSH,null,"guidance");else status("语音引擎未就绪，请检查手机文字转语音设置");}break;
            case "close":if(service!=null){service.listener=null;service.endNavigation();}detach();stopService(new Intent(this,NavService.class));state(new JSONObject());status("会话已关闭");break;
        }
    }catch(Exception e){status("操作失败："+e.getMessage());}}
    private void permissions(){
        ArrayList<String> missing=new ArrayList<>();
        String[] needed=Build.VERSION.SDK_INT>=31?new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT}:
            new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION};
        for(String p:needed)if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED)missing.add(p);
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)missing.add(Manifest.permission.POST_NOTIFICATIONS);
        if(!missing.isEmpty())requestPermissions(missing.toArray(new String[0]),1);else startSession();
    }
    @Override public void onRequestPermissionsResult(int request,String[] names,int[] grants){
        super.onRequestPermissionsResult(request,names,grants);if(request==73){if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED&&needService())service.startDirectMirror();else status("需要附近Wi-Fi设备权限才能自动建立投屏热点");return;}
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED||
            (Build.VERSION.SDK_INT>=31&&(checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED))){
            status("需要精确定位与附近设备权限；请在应用设置中允许后重试");return;
        }startSession();
    }

    private void startSession(){try{Intent intent=new Intent(this,NavService.class);startForegroundService(intent);if(!bound)bound=bindService(intent,connection,Context.BIND_AUTO_CREATE);}catch(Exception e){status("会话启动失败："+e.getMessage());}}
    private void detach(){if(bound){if(service!=null)service.listener=null;unbindService(connection);bound=false;service=null;}}
    @Override protected void onStart(){super.onStart();if(!bound)bound=bindService(new Intent(this,NavService.class),connection,0);}
    @Override protected void onStop(){detach();super.onStop();}
    @Override protected void onDestroy(){loaded=false;web.removeJavascriptInterface("AndroidNav");web.destroy();if(tts!=null){tts.stop();tts.shutdown();}super.onDestroy();}
    @Override public void status(String value){event("status",value);}
    @Override public void diagnostics(String value){event("diagnostics",value);}
    @Override public void state(JSONObject value){event("state",value);}
    @Override public void mapData(JSONObject value){event("map",value);}
    @Override public void device(BluetoothDevice device){String id=device.getAddress();if(found.containsKey(id))return;found.put(id,device);try{event("device",new JSONObject().put("id",id).put("name",device.getName()==null?"导航屏":device.getName()));}catch(Exception ignored){}}
    @Override public void places(JSONArray places){placeResults=places;event("places",places);}
    @Override public void routes(JSONArray routes){event("routes",routes);}
}
