package top.maler.community;

import java.util.Arrays;

/** Finds the filled blue navigation arrow, excluding cyan routes and hollow UI rings. */
public final class MirrorCrop {
    public static final class Window {
        public final int left,top,width,height;
        public final boolean tracking;
        Window(int x,int y,int w,int h,boolean found){left=x;top=y;width=w;height=h;tracking=found;}
    }
    private byte[] seen=new byte[0];
    private int[] queue=new int[0];
    private float markerY;
    private long seenAt=-1;
    private int previousHeight;
    private static boolean blue(int c){int r=c>>>16&255,g=c>>>8&255,b=c&255;return b>145&&r<125&&g<195&&b-g>65&&b-r>90;}
    public Window locate(int[] pixels,int stride,int width,int height,long now){
        int count=width*height;
        if(seen.length<count){seen=new byte[count];queue=new int[count];}else Arrays.fill(seen,(byte)0);
        if(previousHeight!=height){previousHeight=height;seenAt=-1;}
        int x0=width*12/100,x1=width*88/100,y0=height*17/100,y1=height*86/100;
        float best=0;int bestY=0;
        float unit=width/480f;
        for(int y=y0;y<y1;y++)for(int x=x0;x<x1;x++){
            int at=y*width+x;if(seen[at]!=0||!blue(pixels[y*stride+x]))continue;
            int head=0,tail=1,minX=x,maxX=x,minY=y,maxY=y;long sumY=0;
            queue[0]=at;seen[at]=1;
            while(head<tail){int v=queue[head++],vy=v/width,vx=v%width;sumY+=vy;minX=Math.min(minX,vx);maxX=Math.max(maxX,vx);minY=Math.min(minY,vy);maxY=Math.max(maxY,vy);
                for(int k=0;k<4;k++){int nx=vx+(k==0?-1:k==1?1:0),ny=vy+(k==2?-1:k==3?1:0);if(nx<x0||nx>=x1||ny<y0||ny>=y1)continue;int n=ny*width+nx;if(seen[n]==0&&blue(pixels[ny*stride+nx])){seen[n]=1;queue[tail++]=n;}}
            }
            int w=maxX-minX+1,h=maxY-minY+1;
            if(tail<40*unit*unit||tail>4500*unit*unit||w<9*unit||h<9*unit||w>64*unit||h>76*unit||w<.5f*h||w>1.8f*h||tail<.24f*w*h)continue;
            int white=0,samples=0,pad=Math.max(2,Math.round(5*unit));
            for(int yy=Math.max(y0,minY-pad);yy<=Math.min(y1-1,maxY+pad);yy++)for(int xx=Math.max(x0,minX-pad);xx<=Math.min(x1-1,maxX+pad);xx++){
                if(xx>=minX&&xx<=maxX&&yy>=minY&&yy<=maxY)continue;
                int c=pixels[yy*stride+xx],r=c>>>16&255,g=c>>>8&255,b=c&255;samples++;
                if(r>205&&g>205&&b>205&&Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b))<32)white++;
            }
            if(samples==0||white<.20f*samples)continue;
            float cy=sumY/(float)tail,cx=(minX+maxX)*.5f;
            float distance=Math.abs(cx-width*.5f)/width+Math.abs(cy-(seenAt>=0?markerY:height*.55f))/height;
            float score=tail*(.5f+white/(float)samples)/(1+distance*3);
            if(score>best){best=score;bestY=Math.round(cy);}
        }
        if(best>0){markerY=seenAt<0||Math.abs(bestY-markerY)>60*unit?bestY:markerY*.25f+bestY*.75f;seenAt=now;}
        int cw=width,ch=Math.round(width/1.5f);if(ch>height){ch=height;cw=Math.round(height*1.5f);}
        boolean tracking=seenAt>=0&&now-seenAt<2000;
        int top=tracking?Math.round(markerY-ch*.65f):Math.round((height-ch)*.42f);
        return new Window((width-cw)/2,Math.max(0,Math.min(height-ch,top)),cw,ch,tracking);
    }
}
