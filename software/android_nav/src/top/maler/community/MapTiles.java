package top.maler.community;
import java.util.*;
import org.json.*;
/** z15 source tiles are WGS84 indexed, their decoded geometry is already GCJ-02. */
public final class MapTiles {
 private static final DirectMap directMap=new DirectMap();
 public static void setCacheDirectory(java.io.File dir){directMap.setCacheDirectory(dir);}
 private final LinkedHashMap<String,JSONObject> cache=new LinkedHashMap<String,JSONObject>(32,.75f,true){protected boolean removeEldestEntry(Map.Entry<String,JSONObject> e){return size()>32;}};
 public JSONObject raw(Gateway gateway,double[] wgs)throws Exception {return raw(gateway,wgs,750,750);}
 public JSONObject raw(Gateway gateway,double[] wgs,double halfWidth,double halfHeight)throws Exception {
  if(gateway.isDirect())return directMap.load(wgs,halfWidth,halfHeight);
  int x=(int)Math.floor((wgs[0]+180)/360*32768),y=(int)Math.floor((1-Math.log(Math.tan(Math.PI/4+Math.toRadians(wgs[1])/2))/Math.PI)/2*32768);
  JSONArray roads=new JSONArray(),buildings=new JSONArray();
  for(int row=y-1;row<=y+1;row++)for(int col=x-1;col<=x+1;col++){
   if(row<0||col<0||row>=32768||col>=32768)continue;String path="v1/map/tiles/15/"+col+"/"+row,key=gateway.base+path;JSONObject tile=cache.get(key);
   if(tile==null){tile=gateway.request(path,null);if(tile.getInt("schema_version")!=1||!"GCJ-02".equals(tile.getString("coordinate_system")))throw new JSONException("底图坐标系无效");cache.put(key,tile);}
   JSONArray r=tile.getJSONArray("roads"),b=tile.optJSONArray("buildings");for(int i=0;i<r.length();i++)roads.put(r.get(i));if(b!=null)for(int i=0;i<b.length();i++)buildings.put(b.get(i));
  }return new JSONObject().put("roads",roads).put("buildings",buildings).put("coordinate_system","GCJ-02");
 }
 public List<RoadMap.Road> load(Gateway gateway,double[] wgs,double[] gcj)throws Exception {
  if(gateway.isDirect())return roads(raw(gateway,wgs),gcj);
  int x=(int)Math.floor((wgs[0]+180)/360*32768),y=(int)Math.floor((1-Math.log(Math.tan(Math.PI/4+Math.toRadians(wgs[1])/2))/Math.PI)/2*32768);
  List<RoadMap.Road> all=new ArrayList<>();
  for(int row=y-1;row<=y+1;row++)for(int col=x-1;col<=x+1;col++){
   if(row<0||col<0||row>=32768||col>=32768)continue;String path="v1/map/tiles/15/"+col+"/"+row,key=gateway.base+path;JSONObject tile=cache.get(key);
   if(tile==null){tile=gateway.request(path,null);if(tile.getInt("schema_version")!=1||!"GCJ-02".equals(tile.getString("coordinate_system")))throw new JSONException("底图坐标系无效");cache.put(key,tile);}
   JSONArray roads=tile.getJSONArray("roads");
   for(int i=0;i<roads.length();i++){JSONObject r=roads.getJSONObject(i);JSONArray points=r.getJSONArray("points_e6");List<double[]> line=new ArrayList<>();
    for(int j=0;j<points.length();j++){JSONArray p=points.getJSONArray(j);double lat=p.getDouble(0)/1e6,lon=p.getDouble(1)/1e6;if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180)throw new JSONException("底图坐标异常");line.add(new double[]{lon,lat});}
    String c=r.optString("class");int kind=Arrays.asList("motorway","primary","secondary","residential","service").indexOf(c);all.add(new RoadMap.Road(kind<0?5:kind,line));
   }
  }return RoadMap.clip(all,gcj);
 }
 public static List<RoadMap.Road> roads(JSONObject data,double[] gcj)throws Exception {
  return scene(data,gcj).roads;
 }
 public static RoadMap.Scene scene(JSONObject data,double[] gcj)throws Exception {return scene(data,gcj,Double.NaN);}
 public static RoadMap.Scene scene(JSONObject data,double[] gcj,double heading)throws Exception {
  List<RoadMap.Road> all=new ArrayList<>();JSONArray roads=data.getJSONArray("roads");
  for(int i=0;i<roads.length();i++){JSONObject r=roads.getJSONObject(i);JSONArray points=r.getJSONArray("points_e6");List<double[]> line=new ArrayList<>();
   for(int j=0;j<points.length();j++){JSONArray p=points.getJSONArray(j);line.add(new double[]{p.getDouble(1)/1e6,p.getDouble(0)/1e6});}
   int kind=Arrays.asList("motorway","primary","secondary","residential","service").indexOf(r.optString("class"));all.add(new RoadMap.Road(kind<0?5:kind,line));
  }List<RoadMap.Road> buildings=new ArrayList<>();JSONArray blocks=data.optJSONArray("buildings");if(blocks!=null)for(int i=0;i<blocks.length();i++){JSONArray pts=blocks.getJSONObject(i).getJSONArray("points_e6");List<double[]> polygon=new ArrayList<>();for(int j=0;j<pts.length();j++){JSONArray p=pts.getJSONArray(j);polygon.add(new double[]{p.getDouble(1)/1e6,p.getDouble(0)/1e6});}buildings.add(new RoadMap.Road(0,polygon));}
  if(Double.isFinite(heading)){
   for(int buffer:new int[]{100,60,30,0})try{
    MapCoverage.View view=new MapCoverage.View(gcj,heading,buffer);int radius=(int)Math.ceil(Math.hypot(MapCoverage.HALF_WIDTH+buffer,MapCoverage.FORWARD+buffer));
    RoadMap.Scene scene=new RoadMap.Scene(RoadMap.clipView(all,view),RoadMap.buildings(buildings,gcj,radius),radius,view);scene.encode(1,gcj);return scene;
   }catch(IllegalArgumentException e){if(buffer==0)throw new IllegalArgumentException("当前可视路网超出屏幕容量，保留上一幅地图");}
  }
  for(int radius:new int[]{500,400,300,200})try{return new RoadMap.Scene(RoadMap.clip(all,gcj,radius),RoadMap.buildings(buildings,gcj,radius),radius);}catch(IllegalArgumentException e){if(radius==200)throw e;}
  throw new IllegalArgumentException("周边路网超出屏幕容量");
 }
}
