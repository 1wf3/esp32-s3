import top.maler.community.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

public final class MapUpdateTests {
 static int checks;
 static void check(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
 interface Action{void run()throws Exception;}
 static void rejects(Action a,String message)throws Exception{try{a.run();throw new AssertionError(message);}catch(AssertionError e){throw e;}catch(Exception expected){checks++;}}
 static final class Worker implements java.util.concurrent.Executor {
  boolean inline=true;final Queue<Runnable> queue=new ArrayDeque<>();
  public void execute(Runnable r){if(inline)r.run();else queue.add(r);}
  void drain(){while(!queue.isEmpty())queue.remove().run();}
 }
 static byte[] manifest(String version){return ("{\"tiles\":[\"https://tiles.openfreemap.org/planet/"+version+"/{z}/{x}/{y}.pbf\"]}").getBytes(StandardCharsets.UTF_8);}
 static RoadMap.Road line(MapCoverage.View v,double r1,double f1,double r2,double f2){return new RoadMap.Road(3,new ArrayList<>(Arrays.asList(v.geo(r1,f1),v.geo(r2,f2))));}
 static JSONObject geometry(List<RoadMap.Road> roads)throws Exception{JSONArray a=new JSONArray();for(RoadMap.Road r:roads){JSONArray p=new JSONArray();for(double[] g:r.points)p.put(new JSONArray().put(Math.round(g[1]*1e6)).put(Math.round(g[0]*1e6)));a.put(new JSONObject().put("class","residential").put("points_e6",p));}return new JSONObject().put("roads",a);}
 public static void main(String[] args)throws Exception{
  byte[] raw=Files.readAllBytes(Paths.get(args[0]));double[] origin={116.397,39.908};
  check(Math.abs(MapCoverage.HALF_WIDTH*2-400)<.001,"navigation map shows 400 metres across");
  check(Math.abs(MapCoverage.FORWARD-161.6666667)<.001,"forward coverage follows enlarged screen scale");
  MapCoverage near=new MapCoverage(origin,750,750),wide=new MapCoverage(origin,100_000,100_000);
  check(near.zoom==14,"street view uses source detail");check(wide.zoom<near.zoom,"zoom out changes tile detail");
  for(double size:new double[]{50,750,3000,10_000,100_000,1_000_000,20_000_000}){
   MapCoverage w=new MapCoverage(origin,size,size);check((w.east-w.west+1)*(w.south-w.north+1)<=16,"visible load bounded to sixteen tiles");
   check(w.east<(1<<w.zoom)&&w.south<(1<<w.zoom),"valid XYZ bounds");
  }
  rejects(()->new MapCoverage(new double[]{Double.NaN,0},50,50),"invalid centre");rejects(()->new MapCoverage(origin,Double.POSITIVE_INFINITY,50),"invalid extent");
  check(near.contains(new MapCoverage(origin,500,500)),"padding covers visible rectangle");
  check(!near.contains(wide),"zooming out exceeds coverage");
  check(!MapCoverage.phoneDue(10_000,9_000,9_000,near,14,wide),"phone request cooldown");
  check(MapCoverage.phoneDue(12_000,9_000,9_000,near,14,wide),"zoom out retries after cooldown without another viewport event");
  check(!MapCoverage.phoneDue(20_000,9_000,9_000,near,14,near),"no redundant request");
  check(MapCoverage.phoneDue(69_000,9_000,9_000,near,14,near),"stationary phone revalidates");
  for(double heading:new double[]{0,45,90,180,270,359}){
   MapCoverage.View v=new MapCoverage.View(origin,heading,100),core=new MapCoverage.View(origin,heading,0);
   check(v.covers(origin,heading,0),"stationary coverage at heading "+heading);
   check(core.covers(origin,heading,0),"zero-buffer coverage tolerates floating point");
   check(v.covers(v.geo(0,50),heading,0),"movement inside padding");
   check(!v.covers(v.geo(0,150),heading,0),"edge triggers map load");
   check(!v.covers(v.geo(0,40),heading,20),"speed anticipates edge");
   List<RoadMap.Road> lines=RoadMap.clipView(Arrays.asList(line(v,-600,0,600,0),line(v,MapCoverage.HALF_WIDTH-5,-500,MapCoverage.HALF_WIDTH-5,600),line(v,450,0,450,50)),core);
   check(lines.size()==2,"screen clipping keeps edge roads and removes offscreen roads");
   for(RoadMap.Road r:lines)for(double[] p:r.points){double[] local=core.local(p);check(local[0]>=-MapCoverage.HALF_WIDTH-.01&&local[0]<=MapCoverage.HALF_WIDTH+.01&&local[1]>=-MapCoverage.BACKWARD-.01&&local[1]<=MapCoverage.FORWARD+.01,"clipped to complete screen at heading "+heading);}
  }
  MapCoverage.View v=new MapCoverage.View(origin,0,100);
  check(!MapCoverage.screenDue(11_000,10_000,10_000,v,true,v.geo(0,150),0,0),"screen rate limit");
  check(MapCoverage.screenDue(13_000,10_000,10_000,v,true,v.geo(0,150),0,0),"edge update does not wait twenty seconds");
  check(!MapCoverage.screenDue(14_000,10_000,10_000,v,false,origin,0,0),"wait for BLE commit before replacement");
  check(MapCoverage.screenDue(25_000,10_000,10_000,v,false,origin,0,0),"retry missing commit");
  check(MapCoverage.screenDue(70_000,10_000,10_000,v,true,origin,0,0),"stationary screen revalidates");
  check(MapCoverage.screenDue(20_000,10_000,10_000,new MapCoverage.View(origin,0,0),true,origin,90,0),"turn triggers oriented coverage update");
  List<RoadMap.Road> busy=new ArrayList<>();busy.add(line(v,-300,0,300,0));
  for(int i=0;i<193;i++)busy.add(line(v,-100+i,MapCoverage.FORWARD+40,-99.5+i,MapCoverage.FORWARD+40));
  RoadMap.Scene scene=MapTiles.scene(geometry(busy),origin,0);
  check(scene.view.buffer==30,"reduce offscreen buffer instead of visible area");check(scene.roads.size()==1,"visible road retained");check(scene.encode(1,origin).length<16384,"existing BLE budget");
  busy.clear();for(int i=0;i<193;i++)busy.add(line(v,-100,-80+i,-50,-80+i));
  rejects(()->MapTiles.scene(geometry(busy),origin,0),"never silently crop an over-capacity visible map");

  AtomicLong now=new AtomicLong(1_800_000_000_000L);AtomicInteger metadata=new AtomicInteger(),tiles=new AtomicInteger();String[] version={"a"};boolean[] online={true};Worker worker=new Worker();
  DirectMap.Transport transport=url->{if(url.endsWith("latest")){metadata.incrementAndGet();if(!online[0])throw new java.io.IOException("offline");return manifest(version[0]);}tiles.incrementAndGet();if(!online[0])throw new java.io.IOException("offline");return raw;};
  DirectMap client=new DirectMap(transport,now::get,worker);Path dir=Files.createTempDirectory("map-update-");client.setCacheDirectory(dir.toFile());
  JSONObject first=client.load(origin);int initialTiles=tiles.get();check(initialTiles>0&&initialTiles<=16,"first visible tiles loaded");String revision=first.getString("source_revision");
  client.load(origin);check(metadata.get()==1&&tiles.get()==initialTiles,"fresh memory cache avoids network");
  worker.inline=false;now.addAndGet(6L*60*60*1000+1);version[0]="b";
  JSONObject old=client.load(origin);check(old.optBoolean("stale"),"expired source remains visible during background update");check(metadata.get()==1,"metadata refresh queued, not blocking display");worker.drain();
  JSONObject changing=client.load(origin);check(changing.optBoolean("stale"),"source revision invalidates memory tiles");check(!changing.getString("source_revision").equals(revision),"new source version selected");check(tiles.get()==initialTiles,"old tile returned before background network");worker.drain();
  JSONObject updated=client.load(origin);check(!updated.optBoolean("stale"),"new revision becomes fresh");check(tiles.get()==initialTiles*2,"each tile fetched once for new revision");
  now.addAndGet(24L*60*60*1000+1);online[0]=false;
  JSONObject cached=client.load(origin);check(cached.getJSONArray("roads").length()>0&&cached.optBoolean("stale"),"expired cache remains visible offline");worker.drain();int failedTiles=tiles.get(),failedMeta=metadata.get();
  client.load(origin);worker.drain();check(tiles.get()==failedTiles&&metadata.get()==failedMeta,"offline failures back off for one minute");
  now.addAndGet(60_001);client.load(origin);worker.drain();check(tiles.get()>failedTiles&&metadata.get()>failedMeta,"offline retry resumes after cooldown");
  DirectMap restarted=new DirectMap(transport,now::get,Runnable::run);restarted.setCacheDirectory(dir.toFile());check(restarted.load(origin).getJSONArray("roads").length()>0,"disk cache survives restart and expiry offline");
  online[0]=true;now.addAndGet(60_001);client.load(origin);worker.drain();check(!client.load(origin).optBoolean("stale"),"reconnect replaces expired memory cache");
  rejects(()->VectorTile.decode(raw,15,0,0),"reject unsupported source zoom");check(VectorTile.decode(raw,8,1,1).getJSONArray("roads").length()>0,"variable zoom decoding");
  rejects(()->client.load(origin,Double.NaN,50),"invalid viewport rejected before networking");
  // Two map consumers requesting the same uncached tile share its one network transfer.
  double[] oneTile={116.3983154296875,39.901308585747355};AtomicInteger concurrentTiles=new AtomicInteger();
  CountDownLatch downloading=new CountDownLatch(1),release=new CountDownLatch(1),secondStarted=new CountDownLatch(1);
  DirectMap concurrent=new DirectMap(url->{if(url.endsWith("latest"))return manifest("concurrent");concurrentTiles.incrementAndGet();downloading.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new java.io.IOException("timeout");return raw;});
  ExecutorService callers=Executors.newFixedThreadPool(2);
  try{
   Future<JSONObject> a=callers.submit(()->concurrent.load(oneTile,50,50));check(downloading.await(5,TimeUnit.SECONDS),"first download starts");
   Future<JSONObject> b=callers.submit(()->{secondStarted.countDown();return concurrent.load(oneTile,50,50);});check(secondStarted.await(5,TimeUnit.SECONDS),"second consumer starts");release.countDown();
   check(a.get(10,TimeUnit.SECONDS).getJSONArray("roads").length()>0&&b.get(10,TimeUnit.SECONDS).getJSONArray("roads").length()>0,"both consumers receive geometry");check(concurrentTiles.get()==1,"concurrent request is coalesced");
  }finally{release.countDown();callers.shutdownNow();}
  System.out.println("Map update PASS: "+checks+" checks (coverage, heading, BLE budget, source revisions, expiry, offline restart/recovery).");
 }
}
