package top.maler.community;

import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Personal-device Web Service client. Never logs URLs, coordinates or the key. */
public final class AmapDirect {
    public interface Transport { JSONObject get(String url)throws Exception; }
    private final String key;
    private final Transport transport;
    public AmapDirect(String key){this(key,AmapDirect::getJson);}
    public AmapDirect(String key,Transport transport){
        if(key==null||!key.trim().matches("[a-fA-F0-9]{32}"))throw new IllegalArgumentException("请输入32位高德 Web 服务 Key，不是网址");
        this.key=key.trim();this.transport=transport;
    }
    private JSONObject api(String path,String query)throws Exception {
        JSONObject reply;
        try{reply=transport.get("https://restapi.amap.com/"+path+"?key="+key+"&"+query);}
        catch(Exception e){throw new IOException("高德连接失败，请检查手机网络后重试");}
        assertSuccess(reply);return reply;
    }
    public static void assertSuccess(JSONObject reply)throws Exception {
        if(!"1".equals(reply.optString("status"))){String code=reply.optString("infocode","UNKNOWN");
            if(!code.matches("[0-9]{5}"))code="UNKNOWN";
            throw new IOException("高德接口错误 "+code+"；请检查 Web服务 Key、接口权限、配额及IP限制");}
    }
    public void test()throws Exception {api("v5/place/text","keywords="+enc("北京站")+"&page_size=1");}
    private static String enc(String s)throws Exception{return URLEncoder.encode(s,"UTF-8");}
    private static String position(double[] p){return String.format(Locale.US,"%.6f,%.6f",p[0],p[1]);}
    public JSONArray search(String keywords,double[] wgs)throws Exception {
        if(keywords==null||keywords.trim().length()<2||keywords.trim().length()>80)throw new IllegalArgumentException("目的地请输入2至80个字符");
        String q="keywords="+enc(keywords.trim())+"&page_size=12&page_num=1&show_fields=navi";
        JSONObject reply=api(wgs==null?"v5/place/text":"v5/place/around",q+(wgs==null?"":"&location="+position(NavMath.gcj(wgs[0],wgs[1]))+"&radius=50000&sortrule=weight"));
        JSONArray result=places(reply,wgs);
        if(wgs!=null&&result.length()==0)result=places(api("v5/place/text",q),wgs);
        return result;
    }
    private static String text(JSONObject o,String key,int max)throws JSONException {Object v=o.opt(key);String s=v==null||v==JSONObject.NULL?"":v instanceof JSONArray?((JSONArray)v).join(" / ").replace("\"",""):String.valueOf(v);return s.substring(0,Math.min(max,s.length()));}
    private static double[] parsePoint(String value)throws Exception {
        String[] pair=value.split(",",-1);if(pair.length!=2)throw new JSONException("高德坐标格式无效");
        double lon=Double.parseDouble(pair[0]),lat=Double.parseDouble(pair[1]);
        if(!Double.isFinite(lon)||!Double.isFinite(lat)||Math.abs(lon)>180||Math.abs(lat)>90)throw new JSONException("高德坐标范围无效");
        return new double[]{lon,lat};
    }
    public static double[] wgs(double[] gcj){
        double lon=gcj[0],lat=gcj[1];if(lon<72.004||lon>137.8347||lat<.8293||lat>55.8271)return gcj.clone();
        double lo=lon-.02,hi=lon+.02,bottom=lat-.02,top=lat+.02,x=lon,y=lat;
        for(int i=0;i<32;i++){x=(lo+hi)/2;y=(bottom+top)/2;double[] p=NavMath.gcj(x,y);if(p[0]<lon)lo=x;else hi=x;if(p[1]<lat)bottom=y;else top=y;}
        return new double[]{x,y};
    }
    public static JSONArray places(JSONObject reply,double[] origin)throws Exception {
        assertSuccess(reply);JSONArray all=reply.optJSONArray("pois"),out=new JSONArray();if(all==null)return out;
        for(int i=0;i<all.length()&&out.length()<12;i++){
            JSONObject p=all.optJSONObject(i);if(p==null)continue;double[] point=null;JSONObject n=p.optJSONObject("navi");
            if(n!=null)try{point=parsePoint(n.optString("entr_location"));}catch(Exception ignored){}
            if(point==null)try{point=parsePoint(p.optString("location"));}catch(Exception ignored){continue;}
            point=wgs(point);String city=text(p,"cityname",32),area=text(p,"adname",32);
            JSONObject place=new JSONObject().put("id",text(p,"id",64)).put("name",text(p,"name",96)).put("address",text(p,"address",160))
                .put("display_area",city+(city.isEmpty()||area.isEmpty()?"":" · ")+area).put("location",new JSONObject().put("coordinate_system","WGS84").put("longitude_deg",point[0]).put("latitude_deg",point[1]));
            if(origin!=null)place.put("distance_m",Math.round(NavMath.distance(origin,point)));out.put(place);
        }return out;
    }
    public JSONArray routes(double[] origin,double[] destination)throws Exception {
        double[] a=NavMath.gcj(origin[0],origin[1]),b=NavMath.gcj(destination[0],destination[1]);
        return routes(api("v5/direction/electrobike","origin="+position(a)+"&destination="+position(b)+"&alternative_route=3&show_fields=cost,polyline,navi"));
    }
    private static double number(JSONObject o,String key,double fallback){double n=o.optDouble(key,Double.NaN);return Double.isFinite(n)?Math.max(0,n):fallback;}
    private static void append(List<double[]> out,String encoded)throws Exception {
        if(encoded==null||encoded.trim().isEmpty())return;
        for(String s:encoded.split(";")){double[] p=parsePoint(s);double[] last=out.isEmpty()?null:out.get(out.size()-1);if(last==null||last[0]!=p[0]||last[1]!=p[1])out.add(p);if(out.size()>8192)throw new JSONException("路线点数超过8192，请选择较近目的地");}
    }
    private static String stringChoice(JSONObject o,String primary,String fallback){return o.has(primary)?o.optString(primary):o.optString(fallback);}
    public static String maneuver(String action,String assistant){String s=action+" "+assistant;
        if(assistant.contains("到达")||assistant.contains("目的地")||action.contains("到达目的地"))return "arrive";
        if(s.matches(".*(驶出环岛|离开环岛|出环岛).*"))return "exit";
        if(s.contains("环岛"))return "roundabout";
        if(s.matches(".*(右后方|右转掉头|向右掉头).*"))return "u_turn_right";
        if(s.matches(".*(左后方|掉头).*"))return "u_turn_left";
        if(s.matches(".*(右前方|稍向右|靠右).*"))return "slight_right";
        if(s.matches(".*(左前方|稍向左|靠左).*"))return "slight_left";
        if(s.matches(".*(急向右|大幅右转).*"))return "sharp_right";
        if(s.matches(".*(急向左|大幅左转).*"))return "sharp_left";
        if(s.contains("右转"))return "right";if(s.contains("左转"))return "left";
        return s.matches(".*(直行|沿|前行|继续).*")?"continue":"unknown";
    }
    public static JSONArray routes(JSONObject reply)throws Exception {
        assertSuccess(reply);JSONObject route=reply.optJSONObject("route");JSONArray paths=route==null?null:route.optJSONArray("paths");
        if(paths==null||paths.length()==0)throw new JSONException("高德未返回可用路线");JSONArray out=new JSONArray();
        for(int i=0;i<Math.min(3,paths.length());i++)out.put(transform(paths.getJSONObject(i)));return out;
    }
    private static JSONObject transform(JSONObject path)throws Exception {
        JSONArray steps=path.optJSONArray("steps");if(steps==null)steps=new JSONArray();if(steps.length()>512)throw new JSONException("路线转向数量超过限制");
        List<double[]> points=new ArrayList<>();JSONArray turns=new JSONArray();double offset=0,duration=0;boolean missing=false;
        for(int i=0;i<steps.length();i++){
            JSONObject step=steps.getJSONObject(i),cost=step.optJSONObject("cost"),navi=step.optJSONObject("navi");if(navi==null)navi=new JSONObject();
            double distance=number(step,"step_distance",number(step,"distance",0));duration+=cost==null?number(step,"duration",0):number(cost,"duration",0);
            List<double[]> geometry=new ArrayList<>();append(geometry,step.optString("polyline"));
            if(geometry.size()<2&&distance>0){JSONArray tmcs=step.optJSONArray("tmcs");List<double[]> recovered=new ArrayList<>();double coverage=0;boolean complete=tmcs!=null;
                if(tmcs!=null)for(int j=0;j<tmcs.length();j++){JSONObject t=tmcs.getJSONObject(j);double d=number(t,"tmc_distance",number(t,"distance",0));if(d==0)continue;List<double[]> segment=new ArrayList<>();append(segment,stringChoice(t,"tmc_polyline","polyline"));if(segment.size()<2){complete=false;break;}for(double[] p:segment)append(recovered,positionPrecise(p));coverage+=d;}
                if(complete&&coverage+1>=distance)geometry=recovered;
            }
            if(distance>0&&geometry.size()<2)missing=true;
            for(double[] p:geometry)append(points,positionPrecise(p));
            String action=navi.has("action")?navi.optString("action"):step.optString("action"),assistant=navi.has("assistant_action")?navi.optString("assistant_action"):step.optString("assistant_action");
            java.util.regex.Matcher exit=java.util.regex.Pattern.compile("第\\s*(\\d{1,2})\\s*个?出口").matcher(action+" "+assistant);
            turns.put(new JSONObject().put("id",i+1).put("type",maneuver(action,assistant)).put("route_offset_m",offset+distance).put("road_name",text(step,step.has("road_name")?"road_name":"road",96)).put("instruction",text(step,"instruction",256)).put("roundabout_exit",exit.find()?Math.min(32,Integer.parseInt(exit.group(1))):0));offset+=distance;
        }
        if(missing||points.size()<2){List<double[]> complete=new ArrayList<>();append(complete,path.optString("polyline"));if(complete.size()<2)throw new JSONException("高德路线缺少完整道路坐标，请重新规划");points=complete;}
        JSONArray polyline=new JSONArray();for(double[] p:points)polyline.put(new JSONObject().put("longitude_deg",p[0]).put("latitude_deg",p[1]));
        double total=number(path,"distance",offset);if(total==0)total=offset;JSONObject cost=path.optJSONObject("cost");long seconds=Math.round(cost==null?number(path,"duration",duration):number(cost,"duration",duration));
        String signature=polyline.toString()+total+"/"+seconds;byte[] hash=MessageDigest.getInstance("SHA-256").digest(signature.getBytes(StandardCharsets.UTF_8));StringBuilder id=new StringBuilder("amap-");for(int i=0;i<10;i++)id.append(String.format(Locale.US,"%02x",hash[i]&255));
        JSONObject result=new JSONObject().put("schema_version",1).put("provider","amap").put("coordinate_system","GCJ-02").put("route_id",id.toString()).put("generated_at_ms",System.currentTimeMillis()).put("total_distance_m",total).put("total_duration_s",seconds).put("polyline",polyline).put("maneuvers",turns).put("traffic",new JSONArray());
        new Route(result);return result; // Apply the existing navigation bounds before offering a candidate.
    }
    private static String positionPrecise(double[] p){return Double.toString(p[0])+","+Double.toString(p[1]);}
    private static JSONObject getJson(String url)throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(8000);c.setReadTimeout(12000);c.setInstanceFollowRedirects(false);c.setRequestProperty("Accept","application/json");
        try{int status=c.getResponseCode();if(status!=200)throw new IOException("HTTP "+status);ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(InputStream in=c.getInputStream()){byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1){if(out.size()+n>2000000)throw new IOException("高德响应过大");out.write(bytes,0,n);}}
            return new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
        }finally{c.disconnect();}
    }
}
