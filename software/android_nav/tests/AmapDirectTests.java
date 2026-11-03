import top.maler.community.*;
import org.json.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class AmapDirectTests {
    private static int checks=0;
    private static void check(boolean b){checks++;if(!b)throw new AssertionError("Direct check "+checks);}
    interface Action {void run()throws Exception;}
    private static void rejects(Action action,String forbidden)throws Exception {try{action.run();throw new AssertionError("Expected rejection");}catch(AssertionError e){throw e;}catch(Exception e){check(!e.toString().contains(forbidden));}}
    private static JSONObject read(String path)throws Exception{return new JSONObject(new String(Files.readAllBytes(Paths.get(path)),StandardCharsets.UTF_8));}
    public static void main(String[] args)throws Exception {
        final String key="0123456789abcdef0123456789abcdef";
        rejects(()->new AmapDirect("https://wkyy.cc.cd/"),"wkyy.cc.cd");
        rejects(()->AmapDirect.assertSuccess(new JSONObject().put("status","0").put("infocode",key).put("info",key)),key);
        JSONObject fixture=read(args[0]);JSONArray expected=new JSONArray(new String(Files.readAllBytes(Paths.get(args[1])),StandardCharsets.UTF_8));
        JSONArray actual=AmapDirect.routes(fixture);check(actual.length()==expected.length());
        for(int i=0;i<actual.length();i++){
            JSONObject a=actual.getJSONObject(i),e=expected.getJSONObject(i);Route route=new Route(a);check(route.total==e.getDouble("total_distance_m"));check(route.duration==e.getLong("total_duration_s"));
            JSONArray ap=a.getJSONArray("polyline"),ep=e.getJSONArray("polyline");check(ap.length()==ep.length());
            for(int j=0;j<ap.length();j++){check(Math.abs(ap.getJSONObject(j).getDouble("longitude_deg")-ep.getJSONObject(j).getDouble("longitude_deg"))<1e-7);check(Math.abs(ap.getJSONObject(j).getDouble("latitude_deg")-ep.getJSONObject(j).getDouble("latitude_deg"))<1e-7);}
            JSONArray am=a.getJSONArray("maneuvers"),em=e.getJSONArray("maneuvers");check(am.length()==em.length());
            for(int j=0;j<am.length();j++){check(am.getJSONObject(j).getString("type").equals(em.getJSONObject(j).getString("type")));check(Math.abs(am.getJSONObject(j).getDouble("route_offset_m")-em.getJSONObject(j).getDouble("route_offset_m"))<.011);}
            check(a.getString("route_id").equals(AmapDirect.routes(fixture).getJSONObject(i).getString("route_id")));
        }
        double[] wgs={117.12,36.67},gcj=NavMath.gcj(wgs[0],wgs[1]),restored=AmapDirect.wgs(gcj);
        check(NavMath.distance(wgs,restored)<.05);
        JSONObject poi=new JSONObject().put("id","station").put("name","站点").put("cityname",new JSONArray().put("济南市")).put("location","0,0").put("navi",new JSONObject().put("entr_location",gcj[0]+","+gcj[1]));
        JSONObject places=new JSONObject().put("status","1").put("pois",new JSONArray().put(poi).put(new JSONObject().put("location","invalid")));
        JSONArray result=AmapDirect.places(places,wgs);check(result.length()==1);check(NavMath.distance(Route.point(result.getJSONObject(0).getJSONObject("location")),wgs)<.05);
        check(result.getJSONObject(0).getString("display_area").equals("济南市"));
        List<String> seen=new ArrayList<>();AmapDirect direct=new AmapDirect(key,url->{seen.add(url);if(url.contains("direction/electrobike"))return fixture;return places;});
        direct.test();direct.search("测试地点",wgs);direct.routes(wgs,new double[]{117.13,36.68});
        check(seen.size()==3);for(String url:seen)check(url.startsWith("https://restapi.amap.com/v5/"));check(seen.get(1).contains("place/around"));check(seen.get(2).contains("show_fields=cost,polyline,navi"));
        check(seen.get(2).contains("alternative_route=3"));check(!seen.get(2).contains("strategy="));
        check(seen.get(2).contains(String.format(Locale.US,"origin=%.6f,%.6f",gcj[0],gcj[1])));
        rejects(()->new AmapDirect(key,url->{throw new java.io.IOException(url);}).test(),key);
        rejects(()->direct.search("x",wgs),key);
        JSONObject broken=read(args[0]);JSONObject path=broken.getJSONObject("route").getJSONArray("paths").getJSONObject(0);path.remove("polyline");JSONObject step=path.getJSONArray("steps").getJSONObject(0);step.remove("polyline");step.remove("tmcs");
        rejects(()->AmapDirect.routes(broken),key);
        step.put("tmcs",new JSONArray().put(new JSONObject().put("distance",step.optDouble("step_distance",step.optDouble("distance"))).put("polyline","117.1,36.6;117.11,36.61")));
        check(AmapDirect.routes(broken).length()>0);
        check(AmapDirect.maneuver("直行","到达目的地").equals("arrive"));check(AmapDirect.maneuver("进入环岛","第三个出口").equals("roundabout"));
        JSONArray ebike=AmapDirect.routes(read(args[2]));check(ebike.length()==3);
        for(int i=0;i<ebike.length();i++){Route r=new Route(ebike.getJSONObject(i));check(r.total>0&&r.duration>0&&r.points.length>2);}
        System.out.println("Amap direct tests PASS: "+checks+" assertions (real fixture, coordinates, geometry, HTTPS, errors and key redaction).");
    }
}
