package top.maler.community;
import java.util.*;
/** Clip real source roads to the complete visible neighbourhood, without ranking away minor roads. */
public final class RoadMap {
 public static final class Road {public int kind; public List<double[]> points; public Road(int k,List<double[]> p){kind=k;points=p;}}
 public static List<Road> clip(List<Road> input,double[] origin){
  return clip(input,origin,500);
 }
 public static List<Road> clip(List<Road> input,double[] origin,int radius){
  return clip(input,origin,-radius,-radius,radius,radius);
 }
 private static List<Road> clip(List<Road> input,double[] origin,double west,double south,double east,double north){
  List<Road> out=new ArrayList<>();Set<String> seen=new HashSet<>();double scale=111320*Math.cos(Math.toRadians(origin[1]));
  for(Road road:input){List<double[]> part=new ArrayList<>();
   for(int i=1;i<road.points.size();i++){
    double[] a=road.points.get(i-1),b=road.points.get(i);double x=(a[0]-origin[0])*scale,y=(a[1]-origin[1])*111320,dx=(b[0]-a[0])*scale,dy=(b[1]-a[1])*111320;
    double lo=0,hi=1;double[] p={-dx,dx,-dy,dy},q={x-west,east-x,y-south,north-y};boolean ok=true;
    for(int j=0;j<4;j++){if(Math.abs(p[j])<1e-12){if(q[j]<0)ok=false;}else{double t=q[j]/p[j];if(p[j]<0)lo=Math.max(lo,t);else hi=Math.min(hi,t);}}
    if(!ok||lo>hi){flush(out,seen,road.kind,part);part=new ArrayList<>();continue;}
    double[] first={a[0]+(b[0]-a[0])*lo,a[1]+(b[1]-a[1])*lo},last={a[0]+(b[0]-a[0])*hi,a[1]+(b[1]-a[1])*hi};
    if(!part.isEmpty()&&NavMath.distance(part.get(part.size()-1),first)>.3){flush(out,seen,road.kind,part);part=new ArrayList<>();}
    if(part.isEmpty())part.add(first);if(NavMath.distance(first,last)>.05)part.add(last);
   }flush(out,seen,road.kind,part);
  }
  // Tile features split streets at intersections. Join identical endpoints to reduce
  // BLE strokes without dropping any road or inventing connecting segments.
  for(int i=0;i<out.size();i++){Road a=out.get(i);boolean joined=true;while(joined){joined=false;for(int j=i+1;j<out.size();j++){Road b=out.get(j);if(a.kind!=b.kind||a.points.size()+b.points.size()>255)continue;
   double[] first=a.points.get(0),last=a.points.get(a.points.size()-1),bf=b.points.get(0),bl=b.points.get(b.points.size()-1);
   List<double[]> next=new ArrayList<>(b.points);if(same(last,bl)||same(first,bf))Collections.reverse(next);
   if(same(last,next.get(0))){a.points.addAll(next.subList(1,next.size()));out.remove(j);joined=true;break;}
   if(same(first,next.get(next.size()-1))){next.remove(next.size()-1);next.addAll(a.points);a.points=next;out.remove(j);joined=true;break;}
  }}}
  int count=0;for(Road r:out)count+=r.points.size();
  if(out.size()>192||count>1536)throw new IllegalArgumentException("周边路网超出容量，未截掉支路；需要缩小视野");
  return out;
 }
 /** Clip in screen axes, preserving every road inside the complete 480x302 map view. */
 public static List<Road> clipView(List<Road> input,MapCoverage.View view){
  double sx=111320*Math.cos(Math.toRadians(view.origin[1]));List<Road> local=new ArrayList<>();
  for(Road r:input){List<double[]> p=new ArrayList<>();for(double[] g:r.points){double[] q=view.local(g);p.add(new double[]{view.origin[0]+q[0]/sx,view.origin[1]+q[1]/111320});}local.add(new Road(r.kind,p));}
  List<Road> clipped=clip(local,view.origin,-MapCoverage.HALF_WIDTH-view.buffer,-MapCoverage.BACKWARD-view.buffer,MapCoverage.HALF_WIDTH+view.buffer,MapCoverage.FORWARD+view.buffer);
  for(Road r:clipped)for(int i=0;i<r.points.size();i++){double[] p=r.points.get(i);r.points.set(i,view.geo((p[0]-view.origin[0])*sx,(p[1]-view.origin[1])*111320));}
  return clipped;
 }
 private static boolean same(double[] a,double[] b){return Math.round(a[0]*1e6)==Math.round(b[0]*1e6)&&Math.round(a[1]*1e6)==Math.round(b[1]*1e6);}
 public static final class Scene {
  public final List<Road> roads,buildings;public final int radius;public final MapCoverage.View view;
  public Scene(List<Road> r,int metres){this(r,Collections.emptyList(),metres);}
  public Scene(List<Road> r,List<Road> b,int metres){this(r,b,metres,null);}
  public Scene(List<Road> r,List<Road> b,int metres,MapCoverage.View v){roads=r;buildings=b;radius=metres;view=v;}
  public byte[] encode(long revision,double[] origin){return RoadMap.encode(revision,origin,roads,buildings,(int)Math.ceil(radius*Math.sqrt(2)));}
 }
 /** Clip actual polygons, then fit the existing firmware's 16-building / 128-point budget. */
 public static List<Road> buildings(List<Road> input,double[] origin,int radius){
  List<Road> candidates=new ArrayList<>();double sx=111320*Math.cos(Math.toRadians(origin[1]));
  for(Road b:input){List<double[]> polygon=new ArrayList<>();for(double[] p:b.points){double[] q={(p[0]-origin[0])*sx,(p[1]-origin[1])*111320};if(polygon.isEmpty()||Math.hypot(q[0]-polygon.get(polygon.size()-1)[0],q[1]-polygon.get(polygon.size()-1)[1])>.05)polygon.add(q);}
   if(polygon.size()>1&&Math.hypot(polygon.get(0)[0]-polygon.get(polygon.size()-1)[0],polygon.get(0)[1]-polygon.get(polygon.size()-1)[1])<.1)polygon.remove(polygon.size()-1);
   for(int edge=0;edge<4&&!polygon.isEmpty();edge++){List<double[]> clipped=new ArrayList<>();double[] a=polygon.get(polygon.size()-1);int axis=edge/2;double bound=edge%2==0?-radius:radius;boolean aIn=edge%2==0?a[axis]>=bound:a[axis]<=bound;
    for(double[] p:polygon){boolean in=edge%2==0?p[axis]>=bound:p[axis]<=bound;if(in!=aIn){double t=(bound-a[axis])/(p[axis]-a[axis]);clipped.add(new double[]{a[0]+t*(p[0]-a[0]),a[1]+t*(p[1]-a[1])});}if(in)clipped.add(p);a=p;aIn=in;}polygon=clipped;
   }
   List<double[]> geo=new ArrayList<>();for(double[] p:polygon){double[] q={origin[0]+p[0]/sx,origin[1]+p[1]/111320};if(geo.isEmpty()||!same(geo.get(geo.size()-1),q))geo.add(q);}
   if(geo.size()>1&&same(geo.get(0),geo.get(geo.size()-1)))geo.remove(geo.size()-1);
   if(geo.size()<3||geo.size()>128)continue;double area=0;for(int i=0;i<geo.size();i++){double[] a=geo.get(i),p=geo.get((i+1)%geo.size());area+=(a[0]-origin[0])*(p[1]-origin[1])-(p[0]-origin[0])*(a[1]-origin[1]);}if(Math.abs(area)*sx*111320<2)continue;
   candidates.add(new Road(0,geo));
  }
  candidates.sort(Comparator.comparingDouble(b->{double min=Double.POSITIVE_INFINITY;for(double[] p:b.points){double x=(p[0]-origin[0])*sx,y=(p[1]-origin[1])*111320;min=Math.min(min,x*x+y*y);}return min;}));
  List<Road> out=new ArrayList<>();int points=0;for(Road b:candidates)if(out.size()<16&&points+b.points.size()<=128){out.add(b);points+=b.points.size();}return out;
 }
 private static void flush(List<Road> out,Set<String> seen,int kind,List<double[]> points){
  if(points.size()<2)return;boolean[] keep=new boolean[points.size()];keep[0]=keep[keep.length-1]=true;simplify(points,0,points.size()-1,keep);
  List<double[]> simple=new ArrayList<>();for(int i=0;i<points.size();i++)if(keep[i])simple.add(points.get(i));
  for(int from=0;from<simple.size()-1;from+=254){List<double[]> piece=new ArrayList<>(simple.subList(from,Math.min(simple.size(),from+255)));
   StringBuilder key=new StringBuilder();for(double[] p:piece)key.append(Math.round(p[0]*1e6)).append(',').append(Math.round(p[1]*1e6)).append(';');
   if(seen.add(key.toString()))out.add(new Road(kind,piece));
  }
 }
 private static void simplify(List<double[]> p,int first,int last,boolean[] keep){
  if(last-first<2)return;double[] a=p.get(first),b=p.get(last);double scale=111320*Math.cos(Math.toRadians(a[1]));double dx=(b[0]-a[0])*scale,dy=(b[1]-a[1])*111320;double max=1.2;int at=-1;
  for(int i=first+1;i<last;i++){double x=(p.get(i)[0]-a[0])*scale,y=(p.get(i)[1]-a[1])*111320,t=Math.max(0,Math.min(1,(x*dx+y*dy)/Math.max(.001,dx*dx+dy*dy)));double d=Math.hypot(x-t*dx,y-t*dy);if(d>max){max=d;at=i;}}
  if(at>=0){keep[at]=true;simplify(p,first,at,keep);simplify(p,at,last,keep);}
 }
 public static byte[] encode(long revision,double[] origin,List<Road> roads){
  return encode(revision,origin,roads,Collections.emptyList(),700);
 }
 private static byte[] encode(long revision,double[] origin,List<Road> roads,List<Road> buildings,int radius){
  int lat=(int)Math.round(origin[1]*1e6),lon=(int)Math.round(origin[0]*1e6);
  if(roads.size()>192||buildings.size()>16)throw new IllegalArgumentException("地图数量超限");
  Protocol.Writer w=new Protocol.Writer().u8(1).u8(2).u32(revision).u32(lat).u32(lon).u16(radius).u8(roads.size()).u8(buildings.size());
  for(Road r:roads){w.u8(r.kind).u8(r.points.size());for(double[] p:r.points)w.varint((int)Math.round(p[1]*1e6)-lat).varint((int)Math.round(p[0]*1e6)-lon);}
  for(Road b:buildings){w.u8(b.kind).u8(b.points.size());for(double[] p:b.points)w.varint((int)Math.round(p[1]*1e6)-lat).varint((int)Math.round(p[0]*1e6)-lon);}
  byte[] data=w.bytes();if(data.length>16384)throw new IllegalArgumentException("周边地图数据超出传输容量");return data;
 }
}
