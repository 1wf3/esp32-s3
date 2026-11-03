package top.maler.community;

import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.*;
import java.util.*;
import android.os.Handler;
import java.security.SecureRandom;

/** BLE v1 transport. GATT operations and message fragments are serialized. */
public final class BleLink {
    public interface Listener {
        void status(String message);
        void device(BluetoothDevice device);
        void ready();
        boolean page(int page);
        void vehicle(byte[] value);
        void vehicleDiagnostics(byte[] value);
    }
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rx,vehicle,settings,wifi,gpsDiagnostics;
    private static final String GPS_DIAGNOSTICS="7e57a006-b50c-4b6a-9c57-40a54e8e1000";
    private long lastGpsDiagnostics=0;
    private boolean cacheRefreshAttempted=false;
    private byte[] pendingWifi;private long wifiReadAt=0;private String wifiState="";private long wifiRequestedAt=0;private int wifiSent=0,wifiConfirmed=0;public String wifiMessage="屏幕尚未接入投屏热点";
    public boolean mirrorReady(){return ready&&wifiRequestedAt==0&&wifiState.startsWith("IP:")&&wifiState.substring(3).equals(context.getSharedPreferences("nav",0).getString("mirror_host",""));}
    public String wifiDiagnostic(){return "hotspot_gatt="+(wifi!=null)+" queued="+(pendingWifi!=null)+" sent="+wifiSent+" confirmed="+wifiConfirmed+" reading="+reading+" writing="+writing+" queue="+queue.size()+" screen_wifi="+wifiState;}
    private boolean reading=false,settingsWrite=false;
    private long lastVehicle=0,verifyReadyAt=0;
    private byte[] pendingSettings,verifySettings;
    private static final String SETTINGS="7e57a003-b50c-4b6a-9c57-40a54e8e1000",VEHICLE="7e57a004-b50c-4b6a-9c57-40a54e8e1000";
    public void wifi(String ssid,String password){if(!ready||wifi==null){listener.status("请连接支持热点配置的新固件");return;}byte[] a=ssid.getBytes(java.nio.charset.StandardCharsets.UTF_8),b=password.getBytes(java.nio.charset.StandardCharsets.UTF_8);if(a.length<1||a.length>32||b.length>63||(b.length>0&&b.length<8))throw new IllegalArgumentException("热点名最多32字节；密码8至63字节，开放热点可留空");wifiRequestedAt=SystemClock.elapsedRealtime();wifiMessage="屏幕正在连接投屏热点";pendingWifi=new byte[3+a.length+b.length];pendingWifi[0]=1;pendingWifi[1]=(byte)a.length;pendingWifi[2]=(byte)b.length;System.arraycopy(a,0,pendingWifi,3,a.length);System.arraycopy(b,0,pendingWifi,3+a.length,b.length);context.getSharedPreferences("nav",0).edit().remove("mirror_host").apply();listener.status("屏幕正在连接手机热点，请稍候");}
    public void settings(byte[] value){if(!ready||settings==null){listener.status("请连接支持配色同步的新版固件");return;}pendingSettings=value.clone();}
    private BluetoothDevice chosen;
    private BluetoothLeScanner scanner;
    private final ArrayDeque<byte[]> queue=new ArrayDeque<>();
    private final Protocol.Reassembler assembler=new Protocol.Reassembler();
    private int budget=20,seq=1,stage=0,negotiatedCaps=0,heartbeatInterval=1000;
    private boolean writing=false,discovering=false,subscribing=false,wanted=false,scanning=false;
    public boolean ready=false;
    private long session=0,started=0,lastValid=0,lastHeartbeat=0,writeAt=0,handshakeAt=0;
    private long generation=0,displayEpoch=0;
    private int reliableSeq=0,retries=0,lastCommandSeq=0,lastCommandId=0;
    private List<byte[]> reliableFrames;
    private byte[] lastCommandPayload;
    private long reliableAt=0;
    private Runnable committed;
    private byte[] pendingMap;
    public long appliedMapRevision=0;
    public ScreenFrame appliedFrame;
    public BleLink(Context context,Listener listener){
        this.context=context;this.listener=listener;
        BluetoothManager manager=(BluetoothManager)context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter=manager==null?null:manager.getAdapter();
        main.postDelayed(ticker,250);
    }
    public void scan(){
        stopScan();
        if(adapter==null||!adapter.isEnabled()){listener.status("请先开启手机蓝牙");return;}
        scanner=adapter.getBluetoothLeScanner();
        if(scanner==null){listener.status("手机蓝牙扫描不可用");return;}
        scanning=true;
        scanner.startScan(Collections.singletonList(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(UUID.fromString(Protocol.SERVICE))).build()),
            new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scanCallback);
        listener.status("扫描导航屏，请选择设备");
        main.postDelayed(()->{if(scanning){stopScan();listener.status("扫描结束；未发现时检查屏幕电源和固件");}},10000);
    }
    private final ScanCallback scanCallback=new ScanCallback(){
        @Override public void onScanResult(int type,ScanResult result){main.post(()->listener.device(result.getDevice()));}
        @Override public void onScanFailed(int code){main.post(()->{scanning=false;listener.status("蓝牙扫描失败："+code);});}
    };
    public void stopScan(){if(scanning&&scanner!=null)scanner.stopScan(scanCallback);scanning=false;}
    public void connect(BluetoothDevice device){stopScan();if(wanted&&gatt!=null&&device.equals(chosen)){listener.status(ready?"屏幕已连接":"正在连接屏幕，请等待当前握手完成");return;}cacheRefreshAttempted=false;chosen=device;wanted=true;open();}
    private void open(){
        closeGatt();
        if(!wanted||chosen==null||adapter==null||!adapter.isEnabled())return;
        listener.status("正在连接导航屏");
        handshakeAt=SystemClock.elapsedRealtime();
        gatt=chosen.connectGatt(context,false,callbacks,BluetoothDevice.TRANSPORT_LE);
    }
    private void closeGatt(){
        gpsDiagnostics=null;lastGpsDiagnostics=0;
        ready=false;appliedFrame=null;reading=false;settingsWrite=false;settings=null;vehicle=null;wifi=null;pendingWifi=null;pendingSettings=null;verifySettings=null;appliedMapRevision=0;rx=null;queue.clear();writing=false;discovering=false;subscribing=false;stage=0;
        reliableFrames=null;committed=null;assembler.reset();lastCommandPayload=null;
        if(gatt!=null){BluetoothGatt old=gatt;gatt=null;old.disconnect();old.close();}
    }
    public void shutdown(){wanted=false;stopScan();main.removeCallbacksAndMessages(null);closeGatt();}
    private void failed(String reason){
        long now=SystemClock.elapsedRealtime();
        android.util.Log.w("NavBle","disconnect reason="+reason+" ready="+ready+" stage="+stage+
            " mtuBudget="+budget+" queue="+queue.size()+" writing="+writing+" reading="+reading+
            " lastReceiveAgeMs="+(now-lastValid)+" operationAgeMs="+(now-writeAt));
        listener.status(reason+"，正在重连");closeGatt();if(wanted)main.postDelayed(this::open,4000);
    }
    private void recoverWritePermission(){
        if(!cacheRefreshAttempted&&gatt!=null){
            cacheRefreshAttempted=true;
            // Android has no public cache-invalidation API. Try the platform method
            // once, and keep a manual fallback on systems that block this method.
            boolean refreshed=false;
            try{refreshed=Boolean.TRUE.equals(gatt.getClass().getMethod("refresh").invoke(gatt));}
            catch(Exception e){android.util.Log.w("NavBle","GATT cache refresh unavailable",e);}
            android.util.Log.i("NavBle","GATT cache refresh accepted="+refreshed);
            if(refreshed){
                closeGatt();listener.status("检测到蓝牙服务地址不匹配，已请求刷新缓存，正在重新连接（仅尝试一次）");
                if(wanted)main.postDelayed(this::open,1500);
                return;
            }
        }
        wanted=false;closeGatt();
        listener.status("蓝牙服务地址仍不匹配（写入被拒绝3）。请结束会话、取消导航屏配对并重启手机蓝牙后重试");
    }
    private void pairThenDiscover(BluetoothGatt target,long deadline){
        if(target!=gatt)return;
        int state=chosen.getBondState();
        if(state==BluetoothDevice.BOND_BONDED){
            if(!target.requestMtu(185))discover(target);
            main.postDelayed(()->{if(target==gatt&&!discovering)discover(target);},1500);return;
        }
        if(SystemClock.elapsedRealtime()>deadline){failed("蓝牙配对未完成");return;}
        if(state==BluetoothDevice.BOND_NONE&&!chosen.createBond()){failed("无法请求配对");return;}
        listener.status("请在手机系统提示中确认配对");
        main.postDelayed(()->pairThenDiscover(target,deadline),1000);
    }
    private void discover(BluetoothGatt target){if(target!=gatt||discovering)return;discovering=true;if(!target.discoverServices())failed("服务发现未启动");}
    private final BluetoothGattCallback callbacks=new BluetoothGattCallback(){
        @Override public void onConnectionStateChange(BluetoothGatt target,int status,int state){main.post(()->{
            if(target!=gatt)return;
            if(status!=BluetoothGatt.GATT_SUCCESS||state==BluetoothProfile.STATE_DISCONNECTED){failed("导航屏已断开（GATT="+status+"，state="+state+"）");return;}
            if(state==BluetoothProfile.STATE_CONNECTED){budget=20;handshakeAt=SystemClock.elapsedRealtime();pairThenDiscover(target,handshakeAt+30000);}
        });}
        @Override public void onServiceChanged(BluetoothGatt target){main.post(()->{
            if(target==gatt)gpsDiagnostics=null;
            if(target!=gatt)return;ready=false;stage=0;rx=null;settings=null;vehicle=null;queue.clear();writing=false;reading=false;settingsWrite=false;reliableFrames=null;committed=null;pendingMap=null;assembler.reset();discovering=false;handshakeAt=SystemClock.elapsedRealtime();
            listener.status("检测到新版固件，正在更新蓝牙服务");discover(target);
        });}
        @Override public void onMtuChanged(BluetoothGatt target,int mtu,int status){main.post(()->{
            if(target!=gatt)return;if(status==BluetoothGatt.GATT_SUCCESS)budget=Math.min(512,mtu-3);discover(target);
        });}
        @Override public void onServicesDiscovered(BluetoothGatt target,int status){main.post(()->{
            if(target!=gatt||subscribing||stage!=0)return;
            BluetoothGattService service=target.getService(UUID.fromString(Protocol.SERVICE));
            if(status!=BluetoothGatt.GATT_SUCCESS||service==null){failed("设备缺少导航服务");return;}
            rx=service.getCharacteristic(UUID.fromString(Protocol.RX));
            wifi=service.getCharacteristic(UUID.fromString("7e57a005-b50c-4b6a-9c57-40a54e8e1000"));settings=service.getCharacteristic(UUID.fromString(SETTINGS));vehicle=service.getCharacteristic(UUID.fromString(VEHICLE));
            android.util.Log.i("NavBle","vehicle="+(vehicle!=null)+" appearance="+(settings!=null));
            gpsDiagnostics=service.getCharacteristic(UUID.fromString(GPS_DIAGNOSTICS));
            for(BluetoothGattCharacteristic c:service.getCharacteristics())android.util.Log.i("NavBle","GATT uuid="+c.getUuid()+" handle="+c.getInstanceId()+" properties="+c.getProperties());
            if(gpsDiagnostics==null)listener.vehicleDiagnostics(null);
            BluetoothGattCharacteristic tx=service.getCharacteristic(UUID.fromString(Protocol.TX));
            if(rx==null||tx==null||!target.setCharacteristicNotification(tx,true)){failed("导航特征不完整");return;}
            BluetoothGattDescriptor descriptor=tx.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
            if(descriptor==null){failed("设备缺少通知订阅项");return;}
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            subscribing=true;if(!target.writeDescriptor(descriptor))failed("订阅未启动");
        });}
        @Override public void onDescriptorWrite(BluetoothGatt target,BluetoothGattDescriptor descriptor,int status){main.post(()->{
            if(target!=gatt||!subscribing||stage!=0)return;subscribing=false;
            if(status!=BluetoothGatt.GATT_SUCCESS){failed("加密通知订阅失败："+status);return;}
            session=new SecureRandom().nextInt()&0xffffffffL;if(session==0)session=1;
            seq=1;stage=1;assembler.reset();started=lastValid=handshakeAt=SystemClock.elapsedRealtime();
            send(1,Protocol.connection(0,session,budget,Protocol.CAPS,1000),0);
            listener.status("已订阅，正在完成协议握手");
        });}
        @Override public void onCharacteristicWrite(BluetoothGatt target,BluetoothGattCharacteristic characteristic,int status){main.post(()->{
            if(target!=gatt||!writing)return;
            if(status!=BluetoothGatt.GATT_SUCCESS){
                android.util.Log.w("NavBle","write failed uuid="+characteristic.getUuid()+" handle="+characteristic.getInstanceId()+" properties="+characteristic.getProperties()+" status="+status);
                if(status==BluetoothGatt.GATT_WRITE_NOT_PERMITTED){
                    // A stale bonded GATT table can point a writable UUID at a read-only value.
                    // Reconnecting to the same cached table indefinitely cannot repair it.
                    recoverWritePermission();
                }else if(status==BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION||status==BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION){
                    failed("蓝牙认证或加密未完成："+status);
                }else failed("蓝牙写入失败："+status);
                return;
            }
            if(characteristic==wifi)wifiConfirmed++;writing=false;if(settingsWrite){settingsWrite=false;}else queue.pollFirst();main.postDelayed(()->pump(),15);
        });}
        private void readResult(BluetoothGatt target,BluetoothGattCharacteristic c,byte[] value,int status){main.post(()->{
            if(target!=gatt)return;reading=false;
            if(status==BluetoothGatt.GATT_SUCCESS){
                if(c==wifi){String address=new String(value,java.nio.charset.StandardCharsets.UTF_8);if(!address.equals(wifiState)){wifiState=address;android.util.Log.i("NavHotspot","screen="+address);}if(address.startsWith("STATE:")){context.getSharedPreferences("nav",0).edit().remove("mirror_host").apply();wifiMessage="屏幕尚未接入投屏网络";}if(address.startsWith("STATE:")&&address.contains(":CONFIG:1")){if(address.contains(":REASON:201:"))listener.status("屏幕找不到2.4GHz热点：请点自动建立投屏热点，或检查手动热点的频段和名称");else if(address.contains(":REASON:202:")||address.contains(":REASON:15:"))listener.status("热点认证失败，请检查热点密码与WPA2设置");}if(address.startsWith("IP:")&&address.substring(3).matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")){String ip=address.substring(3);wifiRequestedAt=0;wifiMessage="屏幕已接入热点，可以开始投屏";if(!ip.equals(context.getSharedPreferences("nav",0).getString("mirror_host",""))){context.getSharedPreferences("nav",0).edit().putString("mirror_host",ip).apply();listener.status("屏幕已连接手机热点，可以开始投屏");}}}
                if(c.getUuid().toString().equals(VEHICLE))listener.vehicle(value);
                if(c.getUuid().toString().equals(GPS_DIAGNOSTICS))listener.vehicleDiagnostics(value);
                if(c.getUuid().toString().equals(SETTINGS)&&verifySettings!=null){boolean matched=Arrays.equals(value,verifySettings);android.util.Log.i("NavAppearance",matched?"settings read-back PASS":"settings read-back FAILED");listener.status(matched?"导航配色已同步到设备":"设备设置校验失败，请重试");verifySettings=null;}
            } else {
                if (c.getUuid().toString().equals(SETTINGS)) {
                    verifySettings = null;
                    listener.status("读取设备设置失败：" + status);
                }
                if (c.getUuid().toString().equals(VEHICLE)) {
                    android.util.Log.w("NavBle", "vehicle read failed: " + status);
                    listener.status("车辆 GPS 蓝牙读取失败，错误码：" + status);
                }
                if(c.getUuid().toString().equals(GPS_DIAGNOSTICS))listener.status("开发板 GPS 诊断读取失败："+status);
            }
            pump();
        });}
        @Override public void onCharacteristicRead(BluetoothGatt target,BluetoothGattCharacteristic c,int status){readResult(target,c,c.getValue().clone(),status);}
        @Override public void onCharacteristicRead(BluetoothGatt target,BluetoothGattCharacteristic c,byte[] value,int status){readResult(target,c,value.clone(),status);}
        @Override public void onCharacteristicChanged(BluetoothGatt target,BluetoothGattCharacteristic characteristic){byte[] b=characteristic.getValue().clone();main.post(()->received(target,b));}
        @Override public void onCharacteristicChanged(BluetoothGatt target,BluetoothGattCharacteristic characteristic,byte[] value){byte[] b=value.clone();main.post(()->received(target,b));}
    };
    private void received(BluetoothGatt target,byte[] bytes){
        if(target!=gatt)return;
        long now=SystemClock.elapsedRealtime();
        try{
            // Repeat a byte-identical committed device command ACK even after
            // newer heartbeats, without applying its page change a second time.
            if(lastCommandPayload!=null&&bytes.length>=13&&bytes[2]==0x20&&
                Protocol.crc(bytes,bytes.length-2)==((bytes[bytes.length-2]&255)|(bytes[bytes.length-1]&255)<<8)){
                Protocol.Reader f=new Protocol.Reader(bytes);f.u8();f.u8();f.u8();int flags=f.u8(),s=f.u16(),off=f.u16(),len=f.u16();
                if(s==lastCommandSeq&&len==lastCommandPayload.length&&off+bytes.length-12<=len&&
                    Arrays.equals(Arrays.copyOfRange(bytes,10,bytes.length-2),Arrays.copyOfRange(lastCommandPayload,off,off+bytes.length-12))){
                    if((flags&2)!=0)send(3,Protocol.ack(s,0,lastCommandId),0);lastValid=now;return;
                }
            }
            Protocol.Packet p=assembler.push(bytes,now);if(p==null)return;
            Protocol.Reader r=new Protocol.Reader(p.payload);r.u8();
            if(p.type==1){
                int role=r.u8(),state=r.u8(),min=r.u8(),max=r.u8();int caps=(int)r.u32();long id=r.u32();int frame=r.u16(),interval=r.u16();r.end();
                if(role!=2||state!=1||min>1||max<1||id!=session||(caps&Protocol.CAPS)!=Protocol.CAPS||
                    frame<13||frame>budget||interval<250)throw new IllegalArgumentException("设备握手不兼容");
                if(stage==1){budget=frame;negotiatedCaps=caps;heartbeatInterval=interval;stage=2;
                    send(1,Protocol.connection(1,session,budget,negotiatedCaps,heartbeatInterval),0);
                }else if(stage==2){
                    if(caps!=negotiatedCaps||frame!=budget||interval!=heartbeatInterval)throw new IllegalArgumentException("两次握手参数不同");
                    stage=3;ready=true;lastHeartbeat=now;listener.status("蓝牙协议已就绪");listener.ready();
                }else return;
            }else if(p.type==2){long id=r.u32();r.u32();int flags=r.u16();r.end();if(!ready||id!=session||flags!=0)return;
            }else if(p.type==3){int ackSeq=r.u16(),status=r.u8(),cmd=r.u16();r.end();
                if(ready&&reliableFrames!=null&&ackSeq==reliableSeq&&cmd==0){
                    Runnable done=committed;reliableFrames=null;committed=null;
                    if(status==0||status==4){if(done!=null)done.run();}else listener.status("屏幕拒绝地图或路线数据："+status);
                }
            }else if(p.type==0x20){
                int kind=r.u8(),command=r.u16(),page=r.u8();r.u16();r.u16();r.u32();r.end();
                if(!ready)return;boolean accepted=kind==0&&command!=0&&page>=0&&page<=2&&listener.page(page);
                send(3,Protocol.ack(p.seq,accepted?0:1,command),0);
                if(accepted){lastCommandSeq=p.seq;lastCommandId=command;lastCommandPayload=p.payload;}
            }else return;
            lastValid=now;
        }catch(IllegalArgumentException e){listener.status("协议数据错误："+e.getMessage());}
    }
    private int next(){int value=seq;seq=seq==65535?1:seq+1;return value;}
    private void send(int type,byte[] body,int flags){
        if(gatt==null||rx==null)return;
        List<byte[]> frames=Protocol.frames(type,next(),body,budget,flags);
        if(queue.size()+frames.size()>256){failed("蓝牙发送积压");return;}
        queue.addAll(frames);pump();
    }
    private void pump(){
        if(writing||reading||queue.isEmpty()||gatt==null||rx==null)return;
        rx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);rx.setValue(queue.peekFirst());
        writing=true;writeAt=SystemClock.elapsedRealtime();
        if(!gatt.writeCharacteristic(rx)){writing=false;main.postDelayed(this::pump,50);}
    }
    public void snapshot(Protocol.Snapshot snapshot){if(ready&&queue.size()<32)send(0x10,snapshot.encode(),0);}
    public void invalidateNavigation(){displayEpoch++;appliedFrame=null;reliableFrames=null;committed=null;pendingMap=null;}
    public void map(byte[] scene){pendingMap=scene;}
    public boolean geometry(long token,double[] origin,List<double[]> points,Protocol.Snapshot snapshot){
        if(!ready||reliableFrames!=null||pendingMap!=null||queue.size()>16)return false;
        generation++;snapshot.generation=generation;snapshot.flags|=128;
        final long currentEpoch=displayEpoch;
        final ScreenFrame frame=new ScreenFrame(origin,points,snapshot);
        reliableSeq=next();retries=0;committed=()->{if(ready&&currentEpoch==displayEpoch){appliedFrame=frame;send(0x10,snapshot.encode(),0);}};
        reliableFrames=Protocol.frames(0x11,reliableSeq,Protocol.geometry(token,generation,origin,points),budget,4);
        reliableAt=SystemClock.elapsedRealtime();queue.addAll(reliableFrames);pump();return true;
    }
    private final Runnable ticker=new Runnable(){public void run(){
        long now=SystemClock.elapsedRealtime();
        if(gatt!=null&&!ready&&now-handshakeAt>35000){failed("蓝牙握手超时");}
        if((writing||reading)&&now-writeAt>8000){failed("蓝牙写入超时");}
        if(ready){
            if(wifiRequestedAt>0&&now-wifiRequestedAt>20000){wifiRequestedAt=0;wifiMessage="屏幕连接超时：请确认热点为2.4GHz，或重建投屏热点";listener.status(wifiMessage);}
            if(!writing&&!reading&&queue.isEmpty()&&reliableFrames==null){
                if(pendingWifi!=null&&wifi!=null){wifi.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);wifi.setValue(pendingWifi);pendingWifi=null;wifiSent++;settingsWrite=true;writing=true;writeAt=now;if(!gatt.writeCharacteristic(wifi)){writing=false;settingsWrite=false;listener.status("热点配置未发送，请重试");}}
                else if(pendingSettings!=null&&settings!=null){byte[] value=pendingSettings;pendingSettings=null;verifySettings=value;verifyReadyAt=now+1200;settings.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);settings.setValue(value);settingsWrite=true;writing=true;writeAt=now;if(!gatt.writeCharacteristic(settings)){writing=false;settingsWrite=false;verifySettings=null;listener.status("设置写入未启动，请重试");}}
                else if(verifySettings!=null&&settings!=null&&now>=verifyReadyAt){reading=gatt.readCharacteristic(settings);writeAt=now;}
                else if(gpsDiagnostics!=null&&now-lastGpsDiagnostics>=3000){
                    lastGpsDiagnostics=now;reading=gatt.readCharacteristic(gpsDiagnostics);writeAt=now;
                    if(!reading)listener.status("GPS 诊断读取未启动，将自动重试");
                }
                else if(wifi!=null&&now-wifiReadAt>=2500){wifiReadAt=now;reading=gatt.readCharacteristic(wifi);writeAt=now;}
                else if (vehicle != null && now - lastVehicle >= 2000) {
                    reading = gatt.readCharacteristic(vehicle);
                    lastVehicle = now;
                    writeAt = now;
                    if (!reading) {
                        android.util.Log.w("NavBle", "vehicle read not started");
                        listener.status("车辆 GPS 读取请求未启动，将自动重试");
                    }
                }
            }
            if(now-lastValid>5000){failed("屏幕心跳超时");}
            else{
                if(now-lastHeartbeat>=heartbeatInterval&&queue.size()<16){send(2,Protocol.heartbeat(session,now-started),0);lastHeartbeat=now;}
                if(pendingMap!=null&&reliableFrames==null&&queue.isEmpty()&&!writing&&!reading){
                    List<byte[]> frames=Protocol.frames(0x14,next(),pendingMap,budget,4);
                    if(frames.size()>200){pendingMap=null;listener.status("地图传输需要更大的蓝牙 MTU");}
                    else{byte[] scene=pendingMap;long revision=(scene[2]&255L)|(scene[3]&255L)<<8|(scene[4]&255L)<<16|(scene[5]&255L)<<24;
                        reliableSeq=seq==1?65535:seq-1;reliableFrames=frames;pendingMap=null;retries=0;reliableAt=now;committed=()->{appliedMapRevision=revision;};queue.addAll(frames);pump();}
                }
                if(reliableFrames!=null&&queue.isEmpty()&&!writing&&!reading&&now-reliableAt>1500){
                    if(retries++<2){queue.addAll(reliableFrames);reliableAt=now;pump();}
                    else{reliableFrames=null;committed=null;listener.status("地图或路线提交确认超时");}
                }
            }
        }
        main.postDelayed(this,250);
    }};
}
