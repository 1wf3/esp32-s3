package top.maler.community;
import android.content.Context;
import android.net.*;
import android.net.wifi.p2p.*;
import android.os.*;

/** Creates a 2.4GHz Wi-Fi Direct group that also accepts ordinary WPA2 clients. */
public final class DirectMirrorNetwork {
 public interface Listener{void status(String value);void credentials(String ssid,String password);}
 private final Context context;private final Listener listener;private final Handler handler=new Handler(Looper.getMainLooper());
 private WifiP2pManager manager;private WifiP2pManager.Channel channel;private int generation;private Network upstream;
 public DirectMirrorNetwork(Context context,Listener listener){this.context=context;this.listener=listener;}
 public void start(){
  if(Build.VERSION.SDK_INT<29){listener.status("Wi-Fi直连建网需要Android10及以上");return;}
  final int epoch=++generation;ConnectivityManager cm=context.getSystemService(ConnectivityManager.class);upstream=cm.getActiveNetwork();
  manager=context.getSystemService(WifiP2pManager.class);if(manager==null){listener.status("本机没有Wi-Fi Direct服务");return;}
  if(channel==null)channel=manager.initialize(context,Looper.getMainLooper(),()->listener.status("Wi-Fi直连服务已断开"));
  listener.status("正在请求2.4GHz Wi-Fi直连网络");
  manager.removeGroup(channel,new WifiP2pManager.ActionListener(){public void onSuccess(){create(epoch);}public void onFailure(int reason){create(epoch);}});
 }
 private void create(int epoch){if(epoch!=generation)return;try{
  byte[] bytes=new byte[15];new java.security.SecureRandom().nextBytes(bytes);String password=android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP|android.util.Base64.URL_SAFE);
  WifiP2pConfig cfg=new WifiP2pConfig.Builder().setNetworkName("DIRECT-NS-MOTO").setPassphrase(password).setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ).enablePersistentMode(false).build();
  manager.createGroup(channel,cfg,new WifiP2pManager.ActionListener(){public void onSuccess(){inspect(epoch,0);}public void onFailure(int reason){if(epoch==generation)listener.status("Wi-Fi直连建网失败（"+reason+"），本机当前无线并发受限");}});
  handler.postDelayed(()->{if(epoch==generation){listener.status("Wi-Fi直连建网超时，请检查系统连接提示");close();}},15000);
 }catch(Exception e){listener.status("Wi-Fi直连启动失败："+e.getClass().getSimpleName());}}
 private void inspect(int epoch,int attempts){if(epoch!=generation)return;manager.requestGroupInfo(channel,group->{
  if(epoch!=generation)return;if(group==null){if(attempts<12)handler.postDelayed(()->inspect(epoch,attempts+1),400);else{listener.status("系统未建立Wi-Fi直连组");close();}return;}
  ConnectivityManager cm=context.getSystemService(ConnectivityManager.class);NetworkCapabilities cap=cm.getNetworkCapabilities(cm.getActiveNetwork());
  if(group.getFrequency()<2400||group.getFrequency()>2500){listener.status("直连实际频段不是2.4GHz，已停止");close();return;}
  if(upstream!=null&&(cap==null||!cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))){listener.status("直连导致上网Wi-Fi中断，已停止直连");close();return;}
  if(!group.isGroupOwner()||group.getPassphrase()==null){listener.status("未取得可供屏幕加入的直连配置");close();return;}
  generation++;handler.removeCallbacksAndMessages(null);listener.status("Wi-Fi直连已建立 · "+group.getFrequency()+"MHz · 上网Wi-Fi保留");listener.credentials(group.getNetworkName(),group.getPassphrase());
 });}
 public void close(){generation++;handler.removeCallbacksAndMessages(null);if(manager!=null&&channel!=null)manager.removeGroup(channel,null);}
}
