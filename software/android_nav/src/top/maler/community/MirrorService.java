package top.maler.community;
import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.*;
import android.hardware.display.*;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.zip.CRC32;

/** App-window capture; latest frame only, with a persistent local Wi-Fi connection. */
public final class MirrorService extends Service {
 public static volatile boolean isRunning=false;
 public static volatile String status="尚未启动 Wi-Fi 投屏";
 private boolean fit;private volatile String mirrorHost="";
 private MediaProjection projection;private VirtualDisplay display;private ImageReader reader;
 private HandlerThread worker;private Handler capture;private final MirrorCrop crop=new MirrorCrop();
 private Image pendingImage;private Bitmap raw,output;private int[] pixels=new int[0];private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
 private final ByteArrayOutputStream jpegOutput=new ByteArrayOutputStream(32768);
 private volatile boolean busy,closed;private long lastFrame,lastNotice;private int sequence;
 private volatile int accepted,lastBytes,drawMs,receiveMs,roundTripMs,captureMs;private volatile boolean tracking;
 private volatile double fps;private long rateAt;private int rateCount;
 private final ExecutorService sender=Executors.newSingleThreadExecutor();private volatile Socket socket;
 private String peer="";private DataOutputStream stream;private DataInputStream reply;private volatile String lastError="";
 private void report(String value){status=value;long now=SystemClock.elapsedRealtime();if(closed||now-lastNotice<1000)return;lastNotice=now;getSystemService(NotificationManager.class).notify(2,notification());}
 private Notification notification(){Intent stop=new Intent(this,MirrorService.class).setAction("STOP");return new Notification.Builder(this,"mirror").setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("导航屏 Wi-Fi 投屏").setContentText(status).setOngoing(true).addAction(new Notification.Action.Builder(null,"停止投屏",PendingIntent.getService(this,3,stop,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE)).build()).build();}
 public int onStartCommand(Intent intent,int flags,int id){
  if(intent==null||"STOP".equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
  if(projection!=null)return START_NOT_STICKY;fit=intent.getBooleanExtra("fit",false);
  getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("mirror","导航画面投屏",NotificationManager.IMPORTANCE_LOW));
  mirrorHost=getSharedPreferences("nav",0).getString("mirror_host","");status="正在连接屏幕 · 保留上网 Wi-Fi";
  if(Build.VERSION.SDK_INT>=29)startForeground(2,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);else startForeground(2,notification());
  try{
   worker=new HandlerThread("mirror-capture");worker.start();capture=new Handler(worker.getLooper());
   projection=getSystemService(MediaProjectionManager.class).getMediaProjection(intent.getIntExtra("code",0),intent.getParcelableExtra("consent"));
   if(projection==null||mirrorHost.isEmpty())throw new IllegalStateException();isRunning=true;
   projection.registerCallback(new MediaProjection.Callback(){public void onStop(){stopSelf();}public void onCapturedContentResize(int w,int h){if(w>0&&h>0)capture.post(()->resize(w,h));}},capture);
   android.util.DisplayMetrics m=getResources().getDisplayMetrics();capture.post(()->resize(m.widthPixels,m.heightPixels));
  }catch(Exception e){status="投屏启动失败，请重新授权并检查热点连接";stopSelf();}
  return START_NOT_STICKY;
 }
 private void resize(int width,int height){if(closed)return;int w=480,h=Math.max(1,Math.min(1400,Math.round(480f*height/width)));if(reader!=null&&reader.getWidth()==w&&reader.getHeight()==h)return;
  if(pendingImage!=null){pendingImage.close();pendingImage=null;}ImageReader old=reader;reader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,3);reader.setOnImageAvailableListener(this::frame,capture);
  if(display==null)display=projection.createVirtualDisplay("Navigation app sharing",w,h,getResources().getDisplayMetrics().densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,capture);
  else{display.resize(w,h,getResources().getDisplayMetrics().densityDpi);display.setSurface(reader.getSurface());}if(old!=null)old.close();
 }
 private void frame(ImageReader source){Image newest=null;try{newest=source.acquireLatestImage();if(newest==null)return;if(closed){newest.close();return;}if(busy){if(pendingImage!=null)pendingImage.close();pendingImage=newest;return;}if(pendingImage!=null){pendingImage.close();pendingImage=null;}encode(newest);}catch(Exception e){if(newest!=null)newest.close();}}
 private void encode(Image image){try{
  long now=SystemClock.elapsedRealtime();mirrorHost=getSharedPreferences("nav",0).getString("mirror_host","");
  if(closed||mirrorHost.isEmpty())return;
  lastFrame=now;Image.Plane p=image.getPlanes()[0];int width=image.getWidth(),height=image.getHeight(),stride=p.getRowStride()/p.getPixelStride();
  if(raw==null||raw.getWidth()!=stride||raw.getHeight()!=height){if(raw!=null)raw.recycle();raw=Bitmap.createBitmap(stride,height,Bitmap.Config.ARGB_8888);pixels=new int[stride*height];}
  java.nio.ByteBuffer buffer=p.getBuffer();if(buffer.remaining()<stride*height*4){buffer.rewind();for(int y=0;y<height;y++){int n=Math.min(stride*4,buffer.remaining());for(int x=0;x<n/4;x++){int r=buffer.get()&255,g=buffer.get()&255,b=buffer.get()&255,a=buffer.get()&255;pixels[y*stride+x]=(a<<24)|(r<<16)|(g<<8)|b;}}raw.setPixels(pixels,0,stride,0,0,stride,height);}else raw.copyPixelsFromBuffer(buffer);
  if(output==null)output=Bitmap.createBitmap(480,320,Bitmap.Config.RGB_565);Canvas canvas=new Canvas(output);
  if(fit){canvas.drawColor(Color.BLACK);float scale=Math.min(480f/width,320f/height),x=(480-width*scale)/2,y=(320-height*scale)/2;canvas.drawBitmap(raw,new Rect(0,0,width,height),new RectF(x,y,x+width*scale,y+height*scale),paint);tracking=false;}
  else{raw.getPixels(pixels,0,stride,0,0,stride,height);MirrorCrop.Window window=crop.locate(pixels,stride,width,height,now);tracking=window.tracking;canvas.drawBitmap(raw,new Rect(window.left,window.top,window.left+window.width,window.top+window.height),new Rect(0,0,480,320),paint);}
  jpegOutput.reset();output.compress(Bitmap.CompressFormat.JPEG,45,jpegOutput);byte[] jpeg=jpegOutput.toByteArray();if(jpeg.length>65536){report("画面超过接收容量");return;}
  captureMs=(int)(SystemClock.elapsedRealtime()-now);busy=true;final String host=mirrorHost;sender.execute(()->send(jpeg,host));
 }catch(Exception e){if(!closed){lastError=e.getClass().getSimpleName();report("画面采集失败，请重新启动投屏");}}finally{if(image!=null)image.close();}}
 private void closeSocket(){Socket s=socket;socket=null;stream=null;reply=null;peer="";if(s!=null)try{s.close();}catch(IOException ignored){}}
 private void send(byte[] jpeg,String host){try{
  long began=SystemClock.elapsedRealtime();if(socket==null||socket.isClosed()||!host.equals(peer)){closeSocket();Socket s=new Socket();socket=s;s.setTcpNoDelay(true);s.connect(new InetSocketAddress(host,8091),2000);s.setSoTimeout(2500);peer=host;stream=new DataOutputStream(new BufferedOutputStream(s.getOutputStream(),65536));reply=new DataInputStream(new BufferedInputStream(s.getInputStream()));}
  CRC32 crc=new CRC32();crc.update(jpeg);stream.writeInt(0x4e564a32);stream.writeInt(jpeg.length);stream.writeInt(++sequence);stream.writeInt((int)crc.getValue());stream.write(jpeg);stream.flush();
  if(reply.readInt()!=0x4f4b4159)throw new IOException();drawMs=reply.readInt();receiveMs=reply.readInt();roundTripMs=(int)(SystemClock.elapsedRealtime()-began);accepted++;lastBytes=jpeg.length;lastError="";
  long now=SystemClock.elapsedRealtime();if(rateAt==0){rateAt=now;rateCount=accepted;}else if(now-rateAt>=2000){fps=(accepted-rateCount)*1000.0/(now-rateAt);rateAt=now;rateCount=accepted;}
  report((fit?"完整画面":tracking?"定位点跟随":"地图中部 · 等待蓝色定位标记")+" · "+String.format(Locale.CHINA,"%.1f",fps)+"帧/秒");
 }catch(Exception e){lastError=e.getClass().getSimpleName();closeSocket();if(!closed)report("屏幕连接重试中，请检查热点和新版固件");}finally{busy=false;if(capture!=null&&!closed)capture.post(()->{if(!closed&&!busy&&pendingImage!=null){Image newest=pendingImage;pendingImage=null;encode(newest);}});}}
 @Override protected void dump(java.io.FileDescriptor fd,java.io.PrintWriter out,String[] args){out.println("status="+status);out.println("hotspot_address_known="+!mirrorHost.isEmpty()+" projection_active="+(projection!=null)+" accepted_frames="+accepted+" last_jpeg_bytes="+lastBytes);out.println("tracking="+tracking+" fps="+fps+" capture_ms="+captureMs+" roundtrip_ms="+roundTripMs+" screen_draw_ms="+drawMs+" receive_ms="+receiveMs+" error="+lastError);}
 public IBinder onBind(Intent intent){return null;}
 public void onDestroy(){isRunning=false;closed=true;closeSocket();if(display!=null)display.release();if(reader!=null)reader.close();if(projection!=null)projection.stop();if(capture!=null)capture.post(()->{if(pendingImage!=null){pendingImage.close();pendingImage=null;}if(raw!=null)raw.recycle();if(output!=null)output.recycle();});if(worker!=null)worker.quitSafely();sender.shutdownNow();status="投屏已停止";stopForeground(true);super.onDestroy();}
}
