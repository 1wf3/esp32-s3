import top.maler.community.MirrorCrop;
import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import javax.imageio.ImageIO;
import java.io.File;
import java.util.Arrays;

public final class MirrorCropChecks {
 static int checks;static final int W=480,H=1067,BLUE=0xff076cff;
 static void check(boolean value,String name){if(!value)throw new AssertionError(name);checks++;}
 static int[] scene(){int[] p=new int[W*H];Arrays.fill(p,0xffeeeeee);return p;}
 static void circle(int[] p,int cx,int cy,int radius,int color){for(int y=cy-radius;y<=cy+radius;y++)for(int x=cx-radius;x<=cx+radius;x++)if(x>=0&&x<W&&y>=0&&y<H&&(x-cx)*(x-cx)+(y-cy)*(y-cy)<=radius*radius)p[y*W+x]=color;}
 static void arrow(int[] p,int cy){circle(p,240,cy,20,0xffffffff);for(int y=-13;y<=10;y++)for(int x=-14;x<=14;x++)if(Math.abs(x)<(y+14)*.6)p[(cy+y)*W+240+x]=BLUE;}
 public static void main(String[] args)throws Exception{
  MirrorCrop crop=new MirrorCrop();int[] p=scene();arrow(p,800);
  circle(p,65,250,38,BLUE);circle(p,65,250,34,0xffffffff);
  for(int y=380;y<690;y++)for(int x=300;x<325;x++)p[y*W+x]=0xff00bcee;
  circle(p,330,320,4,BLUE);
  MirrorCrop.Window a=crop.locate(p,W,W,H,0);
  check(a.tracking,"recognize filled navigation arrow among UI ring, POI and route");
  check(Math.abs(a.top+a.height*.65-800)<8,"place arrow at lower-middle anchor");
  check(a.width*2==a.height*3,"preserve 3:2 crop");
  p=scene();arrow(p,430);a=crop.locate(p,W,W,H,200);
  check(Math.abs(a.top+a.height*.65-430)<8,"follow large marker displacement without manual dragging");
  p=scene();a=crop.locate(p,W,W,H,1000);check(a.tracking,"brief occlusion preserves last crop");
  a=crop.locate(p,W,W,H,3000);check(!a.tracking,"missing marker expires instead of inventing location");
  check(a.top>=0&&a.top+a.height<=H,"fallback stays within captured frame");
  crop=new MirrorCrop();p=scene();circle(p,240,500,35,BLUE);circle(p,240,500,31,0xffffffff);
  a=crop.locate(p,W,W,H,0);check(!a.tracking,"hollow speed dial is not a position marker");
  if(args.length>0){BufferedImage source=ImageIO.read(new File(args[0]));int h=Math.round(source.getHeight()*480f/source.getWidth());BufferedImage scaled=new BufferedImage(480,h,BufferedImage.TYPE_INT_RGB);Graphics2D g=scaled.createGraphics();g.drawImage(source,0,0,480,h,null);g.dispose();int[] photo=scaled.getRGB(0,0,480,h,null,0,480);a=new MirrorCrop().locate(photo,480,480,h,0);check(a.tracking,"real AMap navigation screenshot recognizes arrow");check(Math.abs(a.top+a.height*.65-h*.49)<45,"real screenshot selects position arrow rather than another blue object");System.out.println("Real screenshot crop top="+a.top+" height="+a.height);}
  System.out.println("Mirror crop PASS: "+checks+" checks");
 }
}
