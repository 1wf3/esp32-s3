package top.maler.community;

/** Pure map coverage calculations. Distances are metres, source coordinates WGS84. */
public final class MapCoverage {
 // Must match PhoneRenderer::mapPixelsPerMetre and ScreenMap.pixelsPerMetre.
 public static final double SCREEN_SCALE=1.2, HALF_WIDTH=240/SCREEN_SCALE, FORWARD=194/SCREEN_SCALE, BACKWARD=108/SCREEN_SCALE;
 public static final long RECHECK_MS=60_000, MIN_REQUEST_MS=2_000;
 public final double[] origin;
 public final double halfWidth,halfHeight;
 public final int zoom,west,east,north,south;
 public MapCoverage(double[] centre,double width,double height){
  if(centre==null||centre.length!=2||!Double.isFinite(centre[0])||!Double.isFinite(centre[1])||Math.abs(centre[0])>180||Math.abs(centre[1])>85||!Double.isFinite(width)||!Double.isFinite(height)||width<=0||height<=0||width>30_000_000||height>30_000_000)throw new IllegalArgumentException("底图范围无效");
  origin=centre.clone();halfWidth=width;halfHeight=height;
  double dy=height/111320,dx=width/(111320*Math.max(.08,Math.cos(Math.toRadians(centre[1]))));
  int z=14,w,e,n,s;
  do{w=x(centre[0]-dx,z);e=x(centre[0]+dx,z);n=y(centre[1]+dy,z);s=y(centre[1]-dy,z);if((long)(e-w+1)*(s-n+1)<=16||z==0)break;z--;}while(true);
  zoom=z;west=w;east=e;north=n;south=s;
 }
 private static int x(double lon,int z){int size=1<<z;return Math.max(0,Math.min(size-1,(int)Math.floor((lon+180)/360*size)));}
 private static int y(double lat,int z){int size=1<<z;double r=Math.toRadians(Math.max(-85,Math.min(85,lat)));return Math.max(0,Math.min(size-1,(int)Math.floor((1-Math.log(Math.tan(Math.PI/4+r/2))/Math.PI)/2*size)));}
 public String key(){return zoom+"/"+west+"/"+north+"/"+east+"/"+south;}
 /** Does this padded request still cover the new visible rectangle? */
 public boolean contains(MapCoverage visible){
  double e=Math.abs(visible.origin[0]-origin[0])*111320*Math.cos(Math.toRadians(origin[1]));
  double n=Math.abs(visible.origin[1]-origin[1])*111320;
  return e+visible.halfWidth<=halfWidth&&n+visible.halfHeight<=halfHeight;
 }
 public static boolean phoneDue(long now,long attempted,long loadedAt,MapCoverage loaded,int requestedZoom,MapCoverage visible){
  if(attempted!=0&&now-attempted<MIN_REQUEST_MS)return false;
  return loaded==null||!loaded.contains(visible)||requestedZoom!=visible.zoom||now-loadedAt>=RECHECK_MS;
 }
 public static boolean screenDue(long now,long attempted,long loadedAt,View loaded,boolean acknowledged,double[] position,double heading,double speed){
  if(attempted!=0&&now-attempted<3_000)return false;
  if(loaded!=null&&!acknowledged&&now-attempted<15_000)return false;
  return loaded==null||!acknowledged||!loaded.covers(position,heading,speed)||now-loadedAt>=RECHECK_MS;
 }
 public static final class View {
  public final double[] origin;public final double heading,buffer;
  public View(double[] centre,double angle,double margin){origin=centre.clone();heading=angle;buffer=margin;}
  public double[] local(double[] p){double e=(p[0]-origin[0])*111320*Math.cos(Math.toRadians(origin[1])),n=(p[1]-origin[1])*111320,a=Math.toRadians(heading);return new double[]{e*Math.cos(a)-n*Math.sin(a),e*Math.sin(a)+n*Math.cos(a)};}
  public double[] geo(double right,double forward){double a=Math.toRadians(heading),e=right*Math.cos(a)+forward*Math.sin(a),n=-right*Math.sin(a)+forward*Math.cos(a);return new double[]{origin[0]+e/(111320*Math.cos(Math.toRadians(origin[1]))),origin[1]+n/111320};}
  public boolean covers(double[] centre,double angle,double speed){
   View next=new View(centre,angle,0);double ahead=Math.min(80,Math.max(0,speed)*4);
   for(double r:new double[]{-HALF_WIDTH,HALF_WIDTH})for(double f:new double[]{-BACKWARD,FORWARD+ahead}){double[] p=local(next.geo(r,f));if(p[0]<-HALF_WIDTH-buffer-.05||p[0]>HALF_WIDTH+buffer+.05||p[1]<-BACKWARD-buffer-.05||p[1]>FORWARD+buffer+.05)return false;}
   return true;
  }
 }
}
