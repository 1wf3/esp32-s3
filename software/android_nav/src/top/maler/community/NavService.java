package top.maler.community;

import android.app.*;
import android.bluetooth.BluetoothDevice;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.location.*;
import android.os.*;
import org.json.*;
import java.util.concurrent.*;

/** User-started foreground session keeps native GPS and BLE available on lock screen. */
public final class NavService extends Service implements LocationListener,BleLink.Listener {
    public interface Listener {
        void status(String value);
        void diagnostics(String value);
        void device(BluetoothDevice device);
        void places(JSONArray places);
        void routes(JSONArray routes);
        void state(JSONObject value);
        void mapData(JSONObject value);
    }
    public final class LocalBinder extends Binder {public NavService service(){return NavService.this;}}
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService network=Executors.newFixedThreadPool(2);
    private final ExecutorService mapNetwork=Executors.newSingleThreadExecutor();
    private double[] viewport;private double viewportHalfWidth=750,viewportHalfHeight=750;
    private final LocalBinder binder=new LocalBinder();
    public Listener listener;
    public String message="请配置高德 Web 服务 Key，并连接显示屏";
    private LocationManager locationManager;
    private Location fix;
    private JSONObject vehicleState=new JSONObject();private long vehicleSeen=0;
    private JSONObject gpsDiagnosticsState=new JSONObject();private long gpsDiagnosticsSeen=-1;
    private Protocol.Snapshot uiSnapshot;private final MapTiles phoneTiles=new MapTiles();
    private double[] phoneMapOrigin;private boolean phoneMapLoading=false;private long phoneMapAt=0;private String phoneMapStatus="等待定位和地图服务";
    private JSONObject phoneMap;private MapCoverage phoneCoverage;private long phoneMapLoadedAt;private int phoneRequestedZoom;
    private JSONObject pendingScreenScene,appliedScreenScene;
    public JSONObject state(){JSONObject result=new JSONObject();try{
        result.put("gpsDiagnostics",gpsDiagnosticsState).put("gpsDiagnosticsLive",ble.ready&&gpsDiagnosticsSeen>=0&&SystemClock.elapsedRealtime()-gpsDiagnosticsSeen<10000);
        result.put("castReady",directMirror!=null&&hotspotText.startsWith("Wi-Fi直连已建立")&&ble.mirrorReady()).put("casting",MirrorService.isRunning).put("hotspotStatus",hotspotText+"；"+ble.wifiMessage).put("connected",ble.ready).put("navigating",navigating).put("gatewayReady",liveGateway).put("vehicle",vehicleState).put("vehicleLive",ble.ready&&SystemClock.elapsedRealtime()-vehicleSeen<6000).put("mapStatus",phoneMapStatus).put("fresh",fresh());
        if(ble.ready&&pendingScreenScene!=null&&ble.appliedMapRevision==pendingScreenScene.optLong("revision"))appliedScreenScene=pendingScreenScene;
        if(ble.ready&&navigating&&appliedScreenScene!=null&&ble.appliedMapRevision==appliedScreenScene.optLong("revision"))result.put("screenScene",appliedScreenScene);
        if(ble.ready&&navigating&&ble.appliedFrame!=null&&fresh())result.put("screenFrame",ble.appliedFrame.json);
        if(uiSnapshot!=null)result.put("heading",uiSnapshot.heading/100.0);
        if(fix!=null)result.put("phone",new JSONArray(NavMath.gcj(fix.getLongitude(),fix.getLatitude()))).put("accuracy",fix.getAccuracy()).put("phoneAge",Math.max(0,(SystemClock.elapsedRealtime()-fix.getElapsedRealtimeNanos()/1000000)/1000));
        if(route!=null){result.put("polyline",new JSONArray(route.points));if(uiSnapshot!=null)result.put("progressRatio",uiSnapshot.total>0?uiSnapshot.progress/(double)uiSnapshot.total:0).put("remaining",uiSnapshot.remaining).put("duration",uiSnapshot.duration).put("speed",uiSnapshot.speed/10.0).put("turnDistance",uiSnapshot.turnDistance).put("instruction",uiSnapshot.instruction).put("road",uiSnapshot.road).put("maneuver",uiSnapshot.maneuver).put("offRoute",(uiSnapshot.flags&8)!=0);}
    }catch(Exception ignored){}return result;}
    public void publish(){if(listener!=null){listener.state(state());if(phoneMap!=null)listener.mapData(phoneMap);}}
    public void syncSettings(byte[] value){ble.settings(value);}
    private String hotspotText="投屏连接尚未建立";
    private DirectMirrorNetwork directMirror;
    public void stopDirectMirror(){if(directMirror!=null){directMirror.close();directMirror=null;}hotspotText="投屏连接已关闭";getSharedPreferences("nav",0).edit().remove("mirror_host").apply();ble.wifiMessage="屏幕尚未接入投屏网络";publish();}
    public void startDirectMirror(){
        if(!ble.ready){status("请先连接显示屏");return;}
        if(directMirror==null)directMirror=new DirectMirrorNetwork(this,new DirectMirrorNetwork.Listener(){
            public void status(String value){hotspotText=value;NavService.this.status(value);}
            public void credentials(String ssid,String password){ble.wifi(ssid,password);}
        });
        directMirror.start();
    }
    @Override public void vehicle(byte[] value){try{
        VehicleTelemetry sample=new VehicleTelemetry(value);int sats=sample.satellites,hdop=sample.hdop,chars=sample.chars;long age=sample.age;double lat=sample.latitude,lon=sample.longitude;int flags=(sample.valid?1:0)|(sample.uart?2:0);
        vehicleSeen=SystemClock.elapsedRealtime();vehicleState=new JSONObject().put("valid",(flags&1)!=0&&age<15000).put("uart",(flags&2)!=0).put("satellites",sats).put("hdop",hdop==65535?JSONObject.NULL:hdop/100.0).put("age",age/1000).put("chars",chars);
        if((flags&1)!=0&&age<15000){double[] gcj=NavMath.gcj(lon,lat);vehicleState.put("point",new JSONArray(gcj));getSharedPreferences("nav",0).edit().putString("vehicleLast",new JSONObject().put("point",new JSONArray(gcj)).put("wgs",new JSONArray(new double[]{lon,lat})).put("time",System.currentTimeMillis()).toString()).apply();}
        android.util.Log.i("NavVehicle","uart="+((flags&2)!=0)+" valid="+((flags&1)!=0&&age<15000)+" sats="+sats+" age="+age);
        if(listener!=null)listener.state(state());
    }catch(Exception e){status("车辆 GPS 数据格式异常");}}
    @Override public void vehicleDiagnostics(byte[] value){try{
        gpsDiagnosticsSeen=-1;
        if(value==null){gpsDiagnosticsState=new JSONObject().put("supported",false);}
        else{
            VehicleTelemetry.Diagnostics d=new VehicleTelemetry.Diagnostics(value);
            gpsDiagnosticsSeen=SystemClock.elapsedRealtime();
            gpsDiagnosticsState=new JSONObject().put("supported",true).put("chars",d.chars).put("passed",d.passed).put("failed",d.failed).put("fixSentences",d.fixSentences).put("time",System.currentTimeMillis());
        }
        publish();
    }catch(Exception e){status("开发板 GPS 诊断数据格式异常");}}
    public void locateVehicle(){try{JSONObject last=new JSONObject(getSharedPreferences("nav",0).getString("vehicleLast","{}"));if(!last.has("wgs")){status("车辆 GPS 尚无有效定位，请将天线放到开阔处");return;}JSONArray p=last.getJSONArray("wgs");JSONObject place=new JSONObject().put("location",new JSONObject().put("coordinate_system","WGS84").put("longitude_deg",p.getDouble(0)).put("latitude_deg",p.getDouble(1)));plan(place);}catch(Exception e){status("车辆位置不可用");}}
    private void phoneMap(){
        if(gateway==null||!gateway.supportsMaps()||!liveGateway||phoneMapLoading||listener==null)return;
        double[] source=viewport!=null?AmapDirect.wgs(viewport):fix!=null?wgs():null;
        if(source==null)try{JSONObject last=new JSONObject(getSharedPreferences("nav",0).getString("vehicleLast","{}"));JSONArray p=last.optJSONArray("wgs");if(p!=null)source=new double[]{p.getDouble(0),p.getDouble(1)};}catch(Exception ignored){}
        if(source==null)return;
        long now=SystemClock.elapsedRealtime();
        MapCoverage visible=new MapCoverage(source,viewportHalfWidth,viewportHalfHeight);
        if(!MapCoverage.phoneDue(now,phoneMapAt,phoneMapLoadedAt,phoneCoverage,phoneRequestedZoom,visible))return;
        final MapCoverage requested=new MapCoverage(source,viewportHalfWidth+Math.max(100,viewportHalfWidth*.2),viewportHalfHeight+Math.max(100,viewportHalfHeight*.2));final int desiredZoom=visible.zoom;
        final Gateway current=gateway;phoneMapLoading=true;phoneMapAt=now;phoneMapStatus=phoneMap==null?"正在加载真实路网":"正在补充可见区域路网";
        mapNetwork.execute(()->{try{JSONObject data=phoneTiles.raw(current,requested.origin,requested.halfWidth,requested.halfHeight);main.post(()->{
            phoneMapLoading=false;if(closed||gateway!=current)return;phoneCoverage=requested;phoneRequestedZoom=desiredZoom;phoneMapLoadedAt=SystemClock.elapsedRealtime();phoneMapOrigin=requested.origin;phoneMap=data;
            phoneMapStatus="真实路网 · OpenStreetMap · "+data.optJSONArray("roads").length()+" 段道路"+(data.optBoolean("stale")?" · 缓存显示，后台更新中":"");if(listener!=null)listener.mapData(data);
        });}catch(Exception e){main.post(()->{phoneMapLoading=false;if(!closed&&gateway==current)phoneMapStatus="地图加载失败："+e.getMessage();});}});
    }

    public void mapViewport(double lon,double lat,double halfWidth,double halfHeight){
        if(!Double.isFinite(lon)||!Double.isFinite(lat)||Math.abs(lon)>180||Math.abs(lat)>85||!Double.isFinite(halfWidth)||!Double.isFinite(halfHeight)||halfWidth<=0||halfHeight<=0||halfWidth>20_000_000||halfHeight>20_000_000)return;
        viewport=new double[]{lon,lat};viewportHalfWidth=halfWidth;viewportHalfHeight=halfHeight;phoneMap();
    }

    private boolean gnssRegistered=false,gnssStarted=false;
    private int satellites=0,satellitesUsed=0;
    private long satellitesAt=0,gpsFixAt=0,gnssLogAt=0;
    private final GnssStatus.Callback gnssCallback=new GnssStatus.Callback(){
        @Override public void onStarted(){gnssStarted=true;}
        @Override public void onStopped(){gnssStarted=false;satellitesAt=0;}
        @Override public void onSatelliteStatusChanged(GnssStatus value){
            satellites=value.getSatelliteCount();satellitesUsed=0;
            for(int i=0;i<satellites;i++)if(value.usedInFix(i))satellitesUsed++;
            satellitesAt=SystemClock.elapsedRealtime();
            if(satellitesAt-gnssLogAt>=5000){gnssLogAt=satellitesAt;android.util.Log.i("NavGnss","satellites="+satellites+" used="+satellitesUsed);}
            if(listener!=null)listener.diagnostics(diagnostics());
        }
    };
    private boolean reportedFresh=false;
    private BleLink ble;
    private Gateway gateway;
    private long keyTestId=0;
    private boolean testingKey=false;
    private boolean liveGateway=false,navigating=false,rerouting=false,closed=false,displayedFresh=false;
    private Route route;
    private double[] destination;
    private long requestId=0,searchId=0,lastGeometry=0,offSince=0,lastPlan=0;
    private int page=0;
    private JSONArray candidates;
    private final MapTiles tiles=new MapTiles();
    private boolean loadingMap=false;
    private double[] mapOrigin;private MapCoverage.View mapView;private long mapLoadedAt;private boolean mapCacheStale;private byte[] lastMapContent;
    private long mapAt=0,mapRevision=0,mapEpoch=0;
    private int mapRoads=0,mapBuildings=0,mapRadius=500;
    private String mapStatus="周边底图 · 等待定位与网络";
    private double[] planningOrigin;
    private long candidateAt=0;
    @Override public void onCreate(){
        super.onCreate();ble=new BleLink(this,this);MapTiles.setCacheDirectory(new java.io.File(getCacheDir(),"vector-tiles"));
        try{vehicleState.put("last",new JSONObject(getSharedPreferences("nav",0).getString("vehicleLast","{}")));}catch(Exception ignored){}
        String saved=AmapKeyStore.read(this);
        if(!saved.isEmpty())try{gateway=Gateway.direct(saved);}catch(Exception ignored){}
        phoneMapStatus="周边底图 · 等待定位与网络";
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("navigation","导航屏连接",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent!=null&&"STOP".equals(intent.getAction())){endNavigation();stopSelf();return START_NOT_STICKY;}
        Intent stop=new Intent(this,NavService.class).setAction("STOP");
        PendingIntent action=PendingIntent.getService(this,2,stop,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open=PendingIntent.getActivity(this,1,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,"navigation").setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("导航屏助手正在运行").setContentText("手机定位与蓝牙连接；点击返回，或结束会话")
            .setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"结束会话",action).build()).build();
        if(Build.VERSION.SDK_INT>=29)startForeground(1,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION|ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(1,notification);
        if(locationManager==null){
            locationManager=(LocationManager)getSystemService(LOCATION_SERVICE);
            try{
                requestProviders();
                gnssRegistered=Build.VERSION.SDK_INT>=30?locationManager.registerGnssStatusCallback(getMainExecutor(),gnssCallback):locationManager.registerGnssStatusCallback(gnssCallback,main);
            }catch(SecurityException e){status("请允许精确定位和附近设备权限");}
            main.post(tick);
        }
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent){return binder;}
    @Override protected void dump(java.io.FileDescriptor fd,java.io.PrintWriter out,String[] args){
        out.println("navigation="+navigating+" ble_ready="+ble.ready+" location_fresh="+fresh());
        out.println("phone_map="+phoneMapStatus);
        out.println("screen_map_revision="+mapRevision+" applied_revision="+ble.appliedMapRevision+" roads="+mapRoads+" radius_m="+mapRadius);
        out.println("screen_buildings="+mapBuildings+" shared_preview="+(ble.ready&&navigating&&ble.appliedFrame!=null&&appliedScreenScene!=null));
        out.println("screen_map="+mapStatus);
        out.println("status="+message);out.println("network="+hotspotText);out.println(ble.wifiDiagnostic());
    }
    @Override public void onDestroy(){
        closed=true;if(directMirror!=null)directMirror.close();requestId++;searchId++;listener=null;
        main.removeCallbacksAndMessages(null);network.shutdownNow();mapNetwork.shutdownNow();ble.shutdown();
        if(locationManager!=null){locationManager.removeUpdates(this);if(gnssRegistered)locationManager.unregisterGnssStatusCallback(gnssCallback);}
        stopForeground(true);super.onDestroy();
    }
    @Override public void status(String value){message=value;if(listener!=null)listener.status(value);}
    @Override public void device(BluetoothDevice device){if(listener!=null)listener.device(device);}
    public void scan(){ble.scan();}
    public void connect(BluetoothDevice device){ble.connect(device);}
    @Override public void ready(){gpsDiagnosticsSeen=-1;lastGeometry=0;mapEpoch++;mapOrigin=null;mapView=null;lastMapContent=null;mapAt=0;try{String saved=getSharedPreferences("nav",0).getString("appearance","");if(!saved.isEmpty())ble.settings(Appearance.encode(new JSONObject(saved)));}catch(Exception ignored){}emit();}
    @Override public boolean page(int selected){page=selected;emit();return true;}
    public void configureAmap(String value){
        if(navigating){status("请先结束导航，再更换高德 Key");return;}
        try{
            String selected=value.trim().isEmpty()?AmapKeyStore.read(this):value.trim();
            Gateway proposed=Gateway.direct(selected);AmapKeyStore.save(this,selected);phoneMapOrigin=null;phoneCoverage=null;phoneMapAt=0;phoneMapLoadedAt=0;phoneMap=null;gateway=proposed;liveGateway=false;requestId++;searchId++;candidates=null;
            final long testId=++keyTestId;testingKey=true;
            status("正在直连高德验证 Key");
            Future<?> task=network.submit(()->{try{proposed.test();
                main.post(()->{if(!closed&&gateway==proposed&&testId==keyTestId&&testingKey){testingKey=false;liveGateway=true;status("高德直连成功，可搜索目的地；路线权限将在规划时验证");emit();}});
            }catch(Exception e){main.post(()->{if(!closed&&gateway==proposed&&testId==keyTestId&&testingKey){testingKey=false;status("高德测试失败："+e.getMessage());}});}});
            main.postDelayed(()->{if(!closed&&testId==keyTestId&&testingKey){testingKey=false;task.cancel(true);status("高德连接超时，请检查手机网络后重试");}},25000);
        }catch(Exception e){status(e.getMessage());}
    }
    private void requestProviders(){
        int active=0;
        String[] providers=Build.VERSION.SDK_INT>=31?new String[]{LocationManager.GPS_PROVIDER,LocationManager.FUSED_PROVIDER,LocationManager.NETWORK_PROVIDER}:new String[]{LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER};
        for(String provider:providers)try{
            if(!locationManager.isProviderEnabled(provider))continue;
            if(Build.VERSION.SDK_INT>=31)locationManager.requestLocationUpdates(provider,new LocationRequest.Builder(1000).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).setMinUpdateIntervalMillis(500).build(),getMainExecutor(),this);
            else locationManager.requestLocationUpdates(provider,1000,0,this);
            active++;
        }catch(IllegalArgumentException ignored){}
        if(active==0)status("请开启手机位置信息，再重新打开会话");
    }
    private String gnssDiagnostics(){
        if(locationManager==null)return "GPS卫星：等待开启会话";
        if(!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))return "GPS卫星：系统定位开关未开启";
        if(!gnssRegistered)return "GPS卫星：状态监听未注册";
        if(satellitesAt==0)return gnssStarted?"GPS卫星：正在搜星，等待卫星状态":"GPS卫星：已请求，等待系统状态";
        long age=Math.max(0,(SystemClock.elapsedRealtime()-satellitesAt)/1000);
        String sample=gpsFixAt==0?"尚无GPS位置":(SystemClock.elapsedRealtime()-gpsFixAt<=15000?"已有新GPS位置":"GPS位置已过期");
        return "GPS卫星：可见 "+satellites+" / 参与定位 "+satellitesUsed+" · 状态 "+age+" 秒前 · "+sample;
    }
    public String diagnostics(){
        if(navigating&&!loadingMap&&mapOrigin!=null&&ble.appliedMapRevision==mapRevision&&!mapStatus.startsWith("底图加载失败")&&!mapStatus.startsWith("底图：当前可视"))mapStatus="底图：已同步周边 "+mapRoads+" 段道路、"+mapBuildings+" 栋建筑 · 按屏幕视野补图"+(mapCacheStale?" · 缓存显示，后台更新中":"");
        if(fix==null)return "定位：等待手机提供位置（GPS / 融合 / 网络）\n手机："+phoneMapStatus+"\n屏幕："+mapStatus;
        long age=Math.max(0,(SystemClock.elapsedRealtime()-fix.getElapsedRealtimeNanos()/1000000)/1000);
        return String.format(java.util.Locale.CHINA,"定位：%s · 精度 %.0f 米 · %d 秒前更新 · %s",fix.getProvider(),fix.getAccuracy(),age,fresh()?"可规划路线":"等待精度 ≤50 米且 15 秒内的新位置")+"\n"+gnssDiagnostics()+"\n手机："+phoneMapStatus+"\n屏幕："+mapStatus;
    }
    private boolean fresh(){return fix!=null&&NavMath.fresh(SystemClock.elapsedRealtime(),fix.getElapsedRealtimeNanos()/1000000,fix.getAccuracy());}
    private double[] wgs(){return new double[]{fix.getLongitude(),fix.getLatitude()};}
    public void search(String keywords){
        if(keywords.trim().length()<2){status("请输入至少两个字的目的地");return;}
        if(gateway==null||!liveGateway){status("请先配置并测试高德 Key");return;}
        final Gateway current=gateway;final long query=++searchId;
        final double[] origin=fresh()?wgs():null;
        status("正在搜索目的地");
        network.execute(()->{try{JSONArray places=current.search(keywords.trim(),origin);main.post(()->{
            if(closed||query!=searchId||current!=gateway)return;
            if(listener!=null)listener.places(places);status(places.length()==0?"没有搜索结果，可加城市名重试":"请选择目的地");
        });}catch(Exception e){main.post(()->{if(!closed&&query==searchId)status("搜索失败："+e.getMessage());});}});
    }
    public void plan(JSONObject place){
        if(navigating){status("请先结束当前导航");return;}
        try{
            JSONObject p=place.getJSONObject("location");
            if(!"WGS84".equals(p.getString("coordinate_system")))throw new JSONException("目的地坐标系无效");
            destination=Route.point(p);plan(false);
        }catch(Exception e){status("目的地无效："+e.getMessage());}
    }
    private void plan(boolean reroute){
        if(gateway==null||!liveGateway){status("请先配置并测试高德 Key");return;}
        if(!fresh()){status("请到开阔处等待手机有效定位；不使用旧坐标规划");return;}
        if(destination==null)return;
        final long id=++requestId;final Gateway current=gateway;final double[] origin=wgs(),target=destination.clone();
        rerouting=reroute;lastPlan=SystemClock.elapsedRealtime();status(reroute?"偏航，正在重新规划":"正在获取候选路线");emit();
        network.execute(()->{try{JSONArray options=current.routes(id,origin,target,reroute);main.post(()->{
            if(closed||id!=requestId||current!=gateway)return;
            try{
                if(reroute){route=new Route(options.getJSONObject(0));rerouting=false;ble.invalidateNavigation();lastGeometry=0;offSince=0;emit();status("已更新路线");}
                else{candidates=options;planningOrigin=origin;candidateAt=SystemClock.elapsedRealtime();if(listener!=null)listener.routes(options);status("请选择路线，然后开始导航");}
            }catch(Exception e){rerouting=false;status("路线数据无效："+e.getMessage());}
        });}catch(Exception e){main.post(()->{if(!closed&&id==requestId){rerouting=false;status("路线规划失败："+e.getMessage());emit();}});}});
    }
    public void begin(int index){
        if(!fresh()){status("手机定位已过期，请等待有效定位");return;}
        if(candidates==null||index<0||index>=candidates.length()){status("请先选择目的地和路线");return;}
        if(SystemClock.elapsedRealtime()-candidateAt>120000||NavMath.distance(wgs(),planningOrigin)>50){status("起点已经变化，请重新规划");plan(false);return;}
        try{route=new Route(candidates.getJSONObject(index));navigating=true;rerouting=false;ble.invalidateNavigation();lastGeometry=0;offSince=0;emit();status("开始导航；手机持续定位，屏幕通过蓝牙同步");}
        catch(Exception e){status("路线不可用："+e.getMessage());}
    }
    public void endNavigation(){mapEpoch++;mapOrigin=null;mapView=null;lastMapContent=null;mapAt=0;mapStatus="周边底图 · 等待定位与网络";navigating=false;rerouting=false;route=null;destination=null;candidates=null;requestId++;ble.invalidateNavigation();emit();status("导航已结束");}
    @Override public void onLocationChanged(Location location){
        // Android Location coordinates are WGS84; reject mock, stale and regressive samples.
        long now=SystemClock.elapsedRealtime(),sample=location.getElapsedRealtimeNanos()/1000000;
        if(location.isFromMockProvider()||!location.hasAccuracy())return;
        if(LocationManager.GPS_PROVIDER.equals(location.getProvider())&&NavMath.fresh(now,sample,location.getAccuracy()))gpsFixAt=sample;
        if(!NavMath.preferFix(now,fix==null?0:fix.getElapsedRealtimeNanos()/1000000,fix==null?0:fix.getAccuracy(),sample,location.getAccuracy()))return;
        fix=new Location(location);
        if(!navigating&&fresh()!=reportedFresh)status(fresh()?"手机定位已就绪，可搜索目的地或连接屏幕":"定位精度不足，请到开阔处");
        reportedFresh=fresh();
        if(listener!=null)listener.diagnostics(diagnostics());
        emit();
    }
    @Override public void onProviderDisabled(String provider){status("手机定位已关闭");}
    @Override public void onProviderEnabled(String provider){
        try{requestProviders();}catch(SecurityException e){status("定位权限已关闭");}
    }
    @Override public void onStatusChanged(String provider,int status,Bundle extras){}
    private void emit(){
        if(!navigating||!fresh())phoneMap();
        Protocol.Snapshot s=new Protocol.Snapshot();s.network=liveGateway?2:0;s.page=page;
        if(!navigating||route==null){s.state=fresh()?0:1;s.flags=fresh()?2:0;ble.snapshot(s);return;}
        s.state=rerouting?4:3;s.flags=1|4;s.token=route.token;
        if(!fresh()){
            if(displayedFresh){ble.invalidateNavigation();lastGeometry=0;displayedFresh=false;}
            ble.snapshot(s);return;
        }
        displayedFresh=true;
        double[] gcj=NavMath.gcj(fix.getLongitude(),fix.getLatitude());
        try{
            s=route.update(gcj,fix.hasSpeed()?fix.getSpeed():0,fix.hasBearing()?fix.getBearing():Float.NaN,
                fix.getAccuracy(),fix.getElapsedRealtimeNanos()/1000000,liveGateway?2:0,page);
            uiSnapshot=s;if(rerouting)s.state=4;
            updateMap(gcj);phoneMap();
            long now=SystemClock.elapsedRealtime();
            if((s.flags&8)!=0){if(offSince==0)offSince=now;if(!rerouting&&now-offSince>=15000&&now-lastPlan>=60000)plan(true);}
            else offSince=0;
            if(now-lastGeometry>=1000&&ble.geometry(route.token,gcj,route.window(gcj),s))lastGeometry=now;
            else if(now-lastGeometry>=2000)ble.snapshot(s); // numbers only until matching geometry commits
        }catch(Exception e){status("导航数据错误："+e.getMessage());}
    }
    private double mapHeading(){return uiSnapshot!=null?uiSnapshot.heading/100.0:0;}
    private void updateMap(double[] gcj){
        long now=SystemClock.elapsedRealtime();
        if(loadingMap||gateway==null||!gateway.supportsMaps()||!liveGateway||!ble.ready)return;
        final double heading=mapHeading(),speed=fix.hasSpeed()?Math.max(0,fix.getSpeed()):0;
        if(!MapCoverage.screenDue(now,mapAt,mapLoadedAt,mapView,ble.appliedMapRevision==mapRevision,gcj,heading,speed))return;
        final Gateway current=gateway;final long epoch=mapEpoch;final double[] centre=gcj.clone(),wgs=wgs();
        loadingMap=true;mapAt=now;mapStatus="底图：按行驶方向补充周边路网";
        mapNetwork.execute(()->{try{
            JSONObject data=tiles.raw(current,wgs);RoadMap.Scene scene=MapTiles.scene(data,centre,heading);
            main.post(()->{loadingMap=false;if(closed||!navigating||epoch!=mapEpoch||gateway!=current||!ble.ready||!fresh())return;
                // Slow downloads must not commit geometry for a viewport the rider has already left.
                if(!scene.view.covers(NavMath.gcj(fix.getLongitude(),fix.getLatitude()),mapHeading(),0)){mapAt=0;return;}
                try{
                    byte[] content=scene.encode(0,centre);mapCacheStale=data.optBoolean("stale");
                    if(ble.appliedMapRevision==mapRevision&&java.util.Arrays.equals(content,lastMapContent)){mapView=scene.view;mapOrigin=centre;mapLoadedAt=SystemClock.elapsedRealtime();mapStatus="底图：已重新检查周边路网";return;}
                    long revision=mapRevision+1;byte[] payload=scene.encode(revision,centre);pendingScreenScene=ScreenFrame.scene(scene,centre,revision);lastMapContent=content;mapRevision=revision;ble.map(payload);mapOrigin=centre;mapView=scene.view;mapLoadedAt=SystemClock.elapsedRealtime();mapAt=mapLoadedAt;mapRoads=scene.roads.size();mapBuildings=scene.buildings.size();mapRadius=scene.radius;mapCacheStale=data.optBoolean("stale");mapStatus="底图："+mapRoads+" 段道路、"+mapBuildings+" 栋建筑，正在同步";}
                catch(Exception e){mapStatus="底图："+e.getMessage();}
            });
        }catch(Exception e){main.post(()->{loadingMap=false;if(!closed&&epoch==mapEpoch)mapStatus="底图加载失败："+e.getMessage();});}});
    }
    private final Runnable tick=new Runnable(){public void run(){if(!closed){emit();if(listener!=null){listener.diagnostics(diagnostics());try{vehicleState.put("last",new JSONObject(getSharedPreferences("nav",0).getString("vehicleLast","{}")));}catch(Exception ignored){}listener.state(state());}main.postDelayed(this,1000);}}};
}
