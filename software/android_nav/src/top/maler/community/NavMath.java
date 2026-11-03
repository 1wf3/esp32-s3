package top.maler.community;

/** Pure navigation math, coordinate boundaries and continuity-aware matching. */
public final class NavMath {
    public static double distance(double[] a,double[] b) {
        double n=Math.toRadians(b[1]-a[1])*6371000;
        double e=Math.toRadians(b[0]-a[0])*6371000*Math.cos(Math.toRadians((a[1]+b[1])/2));
        return Math.hypot(n,e);
    }
    public static double[] gcj(double lon,double lat) {
        if(lon<72.004||lon>137.8347||lat<.8293||lat>55.8271)return new double[]{lon,lat};
        double x=lon-105,y=lat-35;
        double a=-100+2*x+3*y+.2*y*y+.1*x*y+.2*Math.sqrt(Math.abs(x));
        a+=(20*Math.sin(6*x*Math.PI)+20*Math.sin(2*x*Math.PI))*2/3;
        a+=(20*Math.sin(y*Math.PI)+40*Math.sin(y/3*Math.PI))*2/3;
        a+=(160*Math.sin(y/12*Math.PI)+320*Math.sin(y*Math.PI/30))*2/3;
        double b=300+x+2*y+.1*x*x+.1*x*y+.1*Math.sqrt(Math.abs(x));
        b+=(20*Math.sin(6*x*Math.PI)+20*Math.sin(2*x*Math.PI))*2/3;
        b+=(20*Math.sin(x*Math.PI)+40*Math.sin(x/3*Math.PI))*2/3;
        b+=(150*Math.sin(x/12*Math.PI)+300*Math.sin(x/30*Math.PI))*2/3;
        double r=Math.toRadians(lat),magic=1-.00669342162296594323*Math.sin(r)*Math.sin(r),sqrt=Math.sqrt(magic);
        return new double[]{lon+b*180/(6378245/sqrt*Math.cos(r)*Math.PI),lat+a*180/(6378245*(1-.00669342162296594323)/(magic*sqrt)*Math.PI)};
    }
    public static final class Match {
        public int segment=-1; public double along=0,offset=Double.POSITIVE_INFINITY,fraction=0;
    }
    public static Match match(double[][] points,double[] cumulative,double[] position,double previous,double budget,boolean tracked) {
        Match best=new Match();
        double scale=111320*Math.cos(Math.toRadians(position[1]));
        for(int i=1;i<points.length;i++){
            double ax=(points[i-1][0]-position[0])*scale,ay=(points[i-1][1]-position[1])*111320;
            double bx=(points[i][0]-position[0])*scale,by=(points[i][1]-position[1])*111320;
            double dx=bx-ax,dy=by-ay,len=dx*dx+dy*dy;
            double t=len<.0001?0:Math.max(0,Math.min(1,-(ax*dx+ay*dy)/len));
            double along=cumulative[i-1]+t*(cumulative[i]-cumulative[i-1]);
            if(tracked&&(along<previous-25||along>previous+budget))continue;
            double d=Math.hypot(ax+t*dx,ay+t*dy);
            if(d<best.offset){best.segment=i-1;best.fraction=t;best.offset=d;best.along=along;}
        }return best;
    }
    public static boolean fresh(long now,long sampleTime,float accuracy){return now>=sampleTime&&now-sampleTime<=15000&&accuracy>0&&accuracy<=50;}
    public static boolean preferFix(long now,long oldTime,float oldAccuracy,long newTime,float newAccuracy){
        if(newTime>now||newTime<oldTime||now-newTime>15000||newAccuracy<=0||!Float.isFinite(newAccuracy))return false;
        return !fresh(now,oldTime,oldAccuracy)||fresh(now,newTime,newAccuracy);
    }
    public static double heading(double[] a,double[] b){return (Math.toDegrees(Math.atan2((b[0]-a[0])*Math.cos(Math.toRadians(a[1])),b[1]-a[1]))+360)%360;}
}
