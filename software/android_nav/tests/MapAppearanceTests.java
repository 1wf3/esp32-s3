import top.maler.community.*;
import org.json.*;
import java.util.*;
public final class MapAppearanceTests {
 static int checks;static void check(boolean b){checks++;if(!b)throw new AssertionError("Map / appearance check "+checks);}
 interface Action{void run()throws Exception;}static void rejects(Action a)throws Exception{try{a.run();throw new AssertionError("Expected rejection");}catch(AssertionError e){throw e;}catch(Exception expected){checks++;}}
 public static void main(String[] args)throws Exception {
  java.nio.file.Path fixture=java.nio.file.Paths.get(args[0]);JSONObject meta=new JSONObject(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[1])),java.nio.charset.StandardCharsets.UTF_8));
  JSONObject expected=new JSONObject(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[2])),java.nio.charset.StandardCharsets.UTF_8));
  byte[] raw=java.nio.file.Files.readAllBytes(fixture);JSONObject map=VectorTile.decode(raw,meta.getInt("x"),meta.getInt("y"));
  String[] layers={"streets","buildings","street_labels"},types={"roads","buildings","labels"};
  for(int i=0;i<types.length;i++){JSONArray features=map.getJSONArray(types[i]);JSONObject e=expected.getJSONObject(layers[i]);check(features.length()==e.getInt("parts"));if(features.length()>0)check(features.getJSONObject(0).getJSONArray("points_e6").getJSONArray(0).toString().equals(e.getJSONArray("first").toString()));}
  check(map.getString("coordinate_system").equals("GCJ-02"));double[] gcj=NavMath.gcj(116.397,39.908);
  List<RoadMap.Road> roads=MapTiles.roads(map,gcj);check(!roads.isEmpty());check(RoadMap.encode(1,gcj,roads).length>18);
  rejects(()->VectorTile.decode(Arrays.copyOf(raw,raw.length-1),meta.getInt("x"),meta.getInt("y")));
  int[] requests={0};DirectMap client=new DirectMap(endpoint->{requests[0]++;check(endpoint.startsWith("https://tiles.openfreemap.org/"));check(!endpoint.contains("key="));return endpoint.endsWith("latest")?"{\"tiles\":[\"https://tiles.openfreemap.org/planet/test/{z}/{x}/{y}.pbf\"]}".getBytes(java.nio.charset.StandardCharsets.UTF_8):raw;});
  client.load(new double[]{116.397,39.908});int calls=requests[0];client.load(new double[]{116.397,39.908});check(requests[0]==calls);
  rejects(()->client.load(new double[]{Double.NaN,39}));
  java.io.File dir=java.nio.file.Files.createTempDirectory("nav-map-cache").toFile();DirectMap persisted=new DirectMap(endpoint->endpoint.endsWith("latest")?"{\"tiles\":[\"https://tiles.openfreemap.org/planet/test/{z}/{x}/{y}.pbf\"]}".getBytes(java.nio.charset.StandardCharsets.UTF_8):raw);persisted.setCacheDirectory(dir);persisted.load(new double[]{116.397,39.908});DirectMap offline=new DirectMap(endpoint->{throw new java.io.IOException("Offline");});offline.setCacheDirectory(dir);check(offline.load(new double[]{116.397,39.908}).getJSONArray("roads").length()>0);
  JSONObject colors=new JSONObject("{\"theme\":1,\"marker\":2,\"colors\":[\"#FF0000\",\"#00FF00\",\"#0000FF\",\"#FFFFFF\"]}");
  byte[] b=Appearance.encode(colors);check(Arrays.equals(b,new byte[]{1,1,2,0,0,(byte)248,(byte)224,7,31,0,(byte)255,(byte)255}));
  colors.getJSONArray("colors").put(0,"red");rejects(()->Appearance.encode(colors));
  colors.getJSONArray("colors").put(0,"#123456");colors.put("marker",4);rejects(()->Appearance.encode(colors));
  if(args.length>3&&args[3].equals("live")){JSONObject live=new DirectMap().load(new double[]{116.397,39.908});List<RoadMap.Road> lines=MapTiles.roads(live,NavMath.gcj(116.397,39.908));System.out.println("Live map PASS: roads="+live.getJSONArray("roads").length()+", buildings="+live.getJSONArray("buildings").length()+", screen roads="+lines.size()+", BLE bytes="+RoadMap.encode(1,NavMath.gcj(116.397,39.908),lines).length);}
  System.out.println("Map and appearance PASS: "+checks+" checks.");
 }
}
