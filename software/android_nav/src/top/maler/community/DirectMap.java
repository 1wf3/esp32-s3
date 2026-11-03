package top.maler.community;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import java.util.zip.GZIPInputStream;

/** Visible OpenFreeMap tiles. Cached geometry stays usable during background revalidation. */
public final class DirectMap {
 public interface Transport {byte[] get(String endpoint)throws Exception;}
 private static final long SOURCE_TTL=6L*60*60*1000,TILE_TTL=24L*60*60*1000,RETRY_MS=60_000;
 private static final ExecutorService DOWNLOADS=Executors.newFixedThreadPool(4,r->{Thread t=new Thread(r,"map-tile");t.setDaemon(true);return t;});
 private static final class Entry {final JSONObject data;final String revision;final long at;Entry(JSONObject d,String r,long a){data=d;revision=r;at=a;}}
 private final Transport transport;private final LongSupplier clock;private final Executor downloads;
 private File disk;private volatile String template;private boolean initialized,sourceRefreshing;private long sourceAt,sourceAttempt;
 private final LinkedHashMap<String,Entry> cache=new LinkedHashMap<String,Entry>(64,.75f,true){protected boolean removeEldestEntry(Map.Entry<String,Entry> e){return size()>64;}};
 private final Map<String,FutureTask<Entry>> inflight=new HashMap<>();
 private final LinkedHashMap<String,Long> failed=new LinkedHashMap<String,Long>(128,.75f,true){protected boolean removeEldestEntry(Map.Entry<String,Long> e){return size()>128;}};
 public DirectMap(){this(DirectMap::get);}
 public DirectMap(Transport t){this(t,System::currentTimeMillis,DOWNLOADS);}
 public DirectMap(Transport t,LongSupplier time,Executor worker){transport=t;clock=time;downloads=worker;}
 public synchronized void setCacheDirectory(File dir){if(dir.isDirectory()||dir.mkdirs())disk=dir;}
 public JSONObject load(double[] wgs)throws Exception {return load(wgs,750,750);}
 public JSONObject load(double[] wgs,double halfWidth,double halfHeight)throws Exception {
  MapCoverage window=new MapCoverage(wgs,halfWidth,halfHeight);ensureSource();String selected=template,revision=revision(selected);
  JSONArray roads=new JSONArray(),buildings=new JSONArray(),labels=new JSONArray();List<Entry> entries=new ArrayList<>();List<FutureTask<Entry>> pending=new ArrayList<>();
  boolean stale=false;
  for(int row=window.north;row<=window.south;row++)for(int col=window.west;col<=window.east;col++){
   String key="ofm-"+window.zoom+"-"+col+"-"+row;Entry saved=stored(key,revision,window.zoom,col,row);long now=clock.getAsLong();
   if(saved!=null){entries.add(saved);if(!revision.equals(saved.revision)||now<saved.at||now-saved.at>=TILE_TTL){stale=true;fetch(key,window.zoom,col,row,selected,revision);}}
   else pending.add(fetch(key,window.zoom,col,row,selected,revision));
  }
  for(FutureTask<Entry> future:pending){if(future==null)throw new IOException("底图暂不可用，一分钟后重试");try{entries.add(future.get());}catch(ExecutionException e){throw new IOException("周边底图失败，请检查网络；路线可独立使用",e.getCause());}}
  for(Entry entry:entries){append(roads,entry.data.getJSONArray("roads"));append(buildings,entry.data.getJSONArray("buildings"));append(labels,entry.data.getJSONArray("labels"));}
  synchronized(this){stale|=clock.getAsLong()<sourceAt||clock.getAsLong()-sourceAt>=SOURCE_TTL;}
  return new JSONObject().put("coordinate_system","GCJ-02").put("schema_version",1).put("roads",roads).put("buildings",buildings).put("labels",labels).put("source","OpenFreeMap / OpenStreetMap").put("source_revision",revision).put("stale",stale).put("zoom",window.zoom).put("coverage_key",window.key());
 }
 private static void append(JSONArray to,JSONArray from)throws Exception {for(int i=0;i<from.length();i++)to.put(from.get(i));}
 private static boolean validTemplate(String t){return t.matches("https://tiles\\.openfreemap\\.org/planet/[A-Za-z0-9_-]+/\\{z\\}/\\{x\\}/\\{y\\}\\.pbf");}
 private static String revision(String t)throws Exception {if(t==null)return "offline";byte[] hash=MessageDigest.getInstance("SHA-256").digest(t.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(int i=0;i<8;i++)s.append(String.format(java.util.Locale.ROOT,"%02x",hash[i]&255));return s.toString();}
 private synchronized void ensureSource()throws Exception {
  long now=clock.getAsLong();
  if(!initialized){initialized=true;if(disk!=null)try{JSONObject info=new JSONObject(new String(Files.readAllBytes(new File(disk,"source-v2.json").toPath()),StandardCharsets.UTF_8));String t=info.getString("template");long at=info.getLong("at");if(validTemplate(t)&&at<=now){template=t;sourceAt=at;}}catch(Exception ignored){}
   if(template==null)try{refreshSource();}catch(Exception e){sourceAttempt=now;/* An old disk tile is still useful offline. */}
  }
  if((template==null||now<sourceAt||now-sourceAt>=SOURCE_TTL)&&!sourceRefreshing&&(sourceAttempt==0||now<sourceAttempt||now-sourceAttempt>=RETRY_MS)){
   sourceRefreshing=true;sourceAttempt=now;downloads.execute(()->{try{refreshSource();}catch(Exception ignored){}finally{synchronized(DirectMap.this){sourceRefreshing=false;}}});
  }
 }
 private void refreshSource()throws Exception {
  JSONObject info=new JSONObject(new String(transport.get("https://tiles.openfreemap.org/planet/latest"),StandardCharsets.UTF_8));String t=info.getJSONArray("tiles").getString(0);if(!validTemplate(t))throw new IOException("底图源地址无效");
  long at=clock.getAsLong();File dir;synchronized(this){template=t;sourceAt=at;sourceAttempt=at;dir=disk;}
  if(dir!=null)try{writeAtomic(new File(dir,"source-v2.json"),new JSONObject().put("template",t).put("at",at).toString().getBytes(StandardCharsets.UTF_8));}catch(Exception ignored){}
 }
 private synchronized Entry stored(String key,String rev,int z,int x,int y){
  Entry saved=cache.get(key);if(saved!=null&&saved.revision.equals(rev))return saved;
  if(disk!=null){File exact=new File(disk,key+"-"+rev+".mvt");List<File> files=new ArrayList<>();if(exact.isFile())files.add(exact);
   if(saved==null){File[] others=disk.listFiles((dir,name)->name.startsWith(key+"-")&&name.endsWith(".mvt"));if(others!=null){Arrays.sort(others,Comparator.comparingLong(File::lastModified).reversed());for(File f:others)if(!f.equals(exact))files.add(f);}if(z==14)files.add(new File(disk,"ofm14-"+x+"-"+y+".mvt"));}
   for(File file:files)if(file.isFile()&&file.length()<=2_000_000)try{String r=file.equals(exact)?rev:"old";saved=new Entry(VectorTile.decode(Files.readAllBytes(file.toPath()),z,x,y),r,file.lastModified());cache.put(key,saved);break;}catch(Exception ignored){}
  }return saved;
 }
 private synchronized FutureTask<Entry> fetch(String key,int z,int x,int y,String source,String rev){
  String id=key+"-"+rev;FutureTask<Entry> existing=inflight.get(id);if(existing!=null)return existing;
  long now=clock.getAsLong();if(failed.containsKey(id)&&now>=failed.get(id)&&now-failed.get(id)<RETRY_MS)return null;
  if(source==null)return null;
  FutureTask<Entry> task=new FutureTask<Entry>(()->{
   try{byte[] bytes=transport.get(source.replace("{z}",Integer.toString(z)).replace("{x}",Integer.toString(x)).replace("{y}",Integer.toString(y)));Entry entry=new Entry(VectorTile.decode(bytes,z,x,y),rev,clock.getAsLong());File dir;
    synchronized(DirectMap.this){cache.put(key,entry);failed.remove(id);dir=disk;}
    if(dir!=null)try{File f=new File(dir,id+".mvt");writeAtomic(f,bytes);f.setLastModified(entry.at);trim(dir);}catch(Exception ignored){}
    return entry;
   }catch(Exception e){synchronized(DirectMap.this){failed.put(id,clock.getAsLong());}throw e;}
  }){protected void done(){synchronized(DirectMap.this){inflight.remove(id,this);}}};
  inflight.put(id,task);downloads.execute(task);return task;
 }
 private static void writeAtomic(File target,byte[] bytes)throws Exception {Path tmp=Files.createTempFile(target.getParentFile().toPath(),"tile-",".tmp");try{Files.write(tmp,bytes);try{Files.move(tmp,target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,target.toPath(),StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(tmp);}}
 private synchronized void trim(File dir){File[] all=dir.listFiles((d,n)->n.endsWith(".mvt"));if(all!=null&&all.length>128){Arrays.sort(all,Comparator.comparingLong(File::lastModified));for(int i=0;i<all.length-128;i++)all[i].delete();}}
 private static byte[] get(String endpoint)throws Exception {
  HttpURLConnection c=(HttpURLConnection)new URL(endpoint).openConnection();c.setConnectTimeout(8000);c.setReadTimeout(10000);c.setInstanceFollowRedirects(false);
  c.setRequestProperty("User-Agent","MotoNavPersonal/0.4 (Android personal navigation)");c.setRequestProperty("Accept-Encoding","gzip");
  Timer deadline=new Timer(true);deadline.schedule(new TimerTask(){public void run(){c.disconnect();}},18000);
  try{if(c.getResponseCode()!=200)throw new IOException("底图服务暂不可用");InputStream stream=c.getInputStream();if("gzip".equalsIgnoreCase(c.getContentEncoding()))stream=new GZIPInputStream(stream);
   try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>2000000)throw new IOException("底图数据过大");out.write(b,0,n);}return out.toByteArray();}
  }finally{deadline.cancel();c.disconnect();}
 }
}
