package top.maler.community;

import org.json.*;
import java.util.*;

/** Prototype navigation engine. WGS84 GPS fixes cross one explicit GCJ-02 boundary. */
public final class Route {
    public final JSONObject source;
    public final double[][] points;
    public final double[] cumulative;
    public final double total;
    public final long duration,token;
    private final JSONArray maneuvers;
    private boolean tracked=false;
    private double progress=0;
    private long previousFix=0;
    private int arrivalSamples=0;
    public static final String[] MANEUVERS={"unknown","continue","slight_left","left","sharp_left","u_turn_left","slight_right","right","sharp_right","u_turn_right","roundabout","exit","arrive"};
    public Route(JSONObject route)throws JSONException {
        source=route;
        if(!"GCJ-02".equals(route.getString("coordinate_system"))||!"amap".equals(route.getString("provider"))||route.getInt("schema_version")!=1)
            throw new JSONException("路线来源或坐标系不支持");
        JSONArray polyline=route.getJSONArray("polyline");
        if(polyline.length()<2||polyline.length()>8192)throw new JSONException("路线点数异常");
        points=new double[polyline.length()][2];cumulative=new double[points.length];
        for(int i=0;i<points.length;i++){
            JSONObject p=polyline.getJSONObject(i);points[i]=point(p);
            if(i>0)cumulative[i]=cumulative[i-1]+NavMath.distance(points[i-1],points[i]);
        }
        total=route.getDouble("total_distance_m");duration=route.getLong("total_duration_s");
        if(!Double.isFinite(total)||total<=0||total>10000000||duration<0||duration>604800||cumulative[cumulative.length-1]<1)
            throw new JSONException("路线长度异常");
        token=Protocol.token(route.getString("route_id"));maneuvers=route.getJSONArray("maneuvers");
        if(maneuvers.length()>512)throw new JSONException("转向数量异常");
    }
    public static double[] point(JSONObject p)throws JSONException {
        double a=p.getDouble("longitude_deg"),b=p.getDouble("latitude_deg");
        if(!Double.isFinite(a)||!Double.isFinite(b)||Math.abs(a)>180||Math.abs(b)>90)throw new JSONException("坐标异常");
        return new double[]{a,b};
    }
    public Protocol.Snapshot update(double[] gcj,float speed,float heading,float accuracy,long fixTime,int network,int page)throws JSONException {
        double elapsed=previousFix==0?1:Math.min(15,Math.max(0,(fixTime-previousFix)/1000.0));
        NavMath.Match m=NavMath.match(points,cumulative,gcj,progress,Math.max(45,speed*elapsed*3+20),tracked);
        boolean off=m.segment<0||m.offset>45;
        if(!off){progress=m.along;tracked=true;}
        if(fixTime!=previousFix){
            boolean near=!off&&cumulative[cumulative.length-1]-progress<40&&NavMath.distance(gcj,points[points.length-1])<25;
            arrivalSamples=near?Math.min(3,arrivalSamples+1):0;previousFix=fixTime;
        }
        double along=progress/cumulative[cumulative.length-1]*total;
        Protocol.Snapshot s=new Protocol.Snapshot();s.state=arrivalSamples>=3?5:3;s.network=network;s.page=page;
        s.token=token;s.flags=1|2;s.total=Math.round(total);s.progress=Math.round(along);
        s.remaining=Math.max(0,Math.round(total-along));s.duration=Math.round(duration*s.remaining/total);
        s.speed=(int)Math.min(65535,Math.round(Math.max(0,speed)*36));
        if(speed<.42)s.speed=0;
        if(!Float.isFinite(heading)||speed<1.4)heading=(float)NavMath.heading(points[Math.max(0,m.segment)],points[Math.max(1,m.segment+1)]);
        s.heading=(int)Math.round((heading%360+360)%360*100)%36000;
        s.accuracy=(int)Math.min(65535,Math.round(accuracy*10));s.cross=(int)Math.min(65535,Math.round(m.offset*10));
        if(off)s.flags|=8;
        for(int i=0;i<maneuvers.length();i++){
            JSONObject turn=maneuvers.getJSONObject(i);double at=turn.getDouble("route_offset_m");
            if(at+3<along&&i+1<maneuvers.length())continue;
            s.maneuverId=turn.getLong("id");s.maneuver=Math.max(0,Arrays.asList(MANEUVERS).indexOf(turn.getString("type")));
            s.turnDistance=Math.max(0,Math.round(at-along));s.road=turn.optString("road_name");s.instruction=turn.optString("instruction");
            s.exit=turn.optInt("roundabout_exit");s.flags|=16;break;
        }
        return s;
    }
    public List<double[]> window(double[] origin){
        int start=1;while(start<points.length-1&&cumulative[start]<progress)start++;
        int from=Math.max(0,start-2),to=start;
        while(to<points.length-1&&cumulative[to]<progress+600)to++;
        ArrayList<double[]> window=new ArrayList<>();
        int count=Math.min(24,to-from+1);
        for(int i=0;i<count;i++){int at=from+(int)Math.round(i*(to-from)/(double)Math.max(1,count-1));window.add(points[at]);}
        return window;
    }
}
