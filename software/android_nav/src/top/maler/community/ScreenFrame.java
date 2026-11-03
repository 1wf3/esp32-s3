package top.maler.community;
import java.util.*;
import org.json.*;
/** Quantized geometry that was actually committed to the display, not the full source tiles. */
public final class ScreenFrame {
 public final JSONObject json;
 public ScreenFrame(double[] origin,List<double[]> points,Protocol.Snapshot s){
  json=new JSONObject();try{json.put("origin",point(origin)).put("route",route(points)).put("heading",s.heading/100.0).put("generation",s.generation).put("token",s.token).put("fresh",(s.flags&2)!=0&&(s.flags&4)==0).put("offRoute",(s.flags&8)!=0).put("speed",s.speed/10.0).put("turnDistance",s.turnDistance).put("maneuver",s.maneuver).put("progress",s.progress).put("total",s.total);}catch(Exception e){throw new IllegalArgumentException("屏幕帧无效");}
 }
 private static JSONArray point(double[] p)throws JSONException {return new JSONArray().put(Math.round(p[0]*1e6)/1e6).put(Math.round(p[1]*1e6)/1e6);}
 private static JSONArray route(List<double[]> points)throws JSONException {JSONArray out=new JSONArray();for(double[] p:points)out.put(point(p));return out;}
 public static JSONObject scene(RoadMap.Scene scene,double[] origin,long revision)throws Exception {
  return new JSONObject().put("origin",point(origin)).put("radius",scene.radius).put("revision",revision).put("roads",features(scene.roads)).put("buildings",features(scene.buildings));
 }
 private static JSONArray features(List<RoadMap.Road> input)throws Exception {JSONArray out=new JSONArray();for(RoadMap.Road road:input){JSONArray points=new JSONArray();for(double[] p:road.points)points.put(new JSONArray().put(Math.round(p[1]*1e6)).put(Math.round(p[0]*1e6)));out.put(new JSONObject().put("class",road.kind).put("points_e6",points));}return out;}
}
