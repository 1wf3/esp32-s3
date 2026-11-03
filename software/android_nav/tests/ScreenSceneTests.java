import top.maler.community.*;
import org.json.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
public final class ScreenSceneTests {
 static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
 static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format("%02x",b&255));return s.toString();}
 public static void main(String[] args)throws Exception {
  Path out=Paths.get(args[0]);Files.createDirectories(out);double[] origin={116.4036334,39.9101254};
  double sx=111320*Math.cos(Math.toRadians(origin[1]));List<RoadMap.Road> roads=new ArrayList<>(),buildings=new ArrayList<>();
  for(int i=0;i<8;i++){List<double[]> points=new ArrayList<>();for(int j=0;j<4;j++)points.add(new double[]{origin[0]+(-260+j*150)/sx,origin[1]+(-200+i*65+j*7)/111320.0});roads.add(new RoadMap.Road(i%6,points));}
  for(int i=0;i<22;i++){double x=-220+(i%6)*90,y=-210+(i/6)*100;buildings.add(new RoadMap.Road(0,Arrays.asList(new double[]{origin[0]+x/sx,origin[1]+y/111320},new double[]{origin[0]+(x+32)/sx,origin[1]+y/111320},new double[]{origin[0]+(x+32)/sx,origin[1]+(y+24)/111320},new double[]{origin[0]+x/sx,origin[1]+(y+24)/111320},new double[]{origin[0]+x/sx,origin[1]+y/111320})));}
  RoadMap.Scene scene=new RoadMap.Scene(RoadMap.clip(roads,origin,400),RoadMap.buildings(buildings,origin,400),400);
  check(scene.buildings.size()==16,"retain nearest buildings within protocol capacity");for(RoadMap.Road b:scene.buildings)check(b.points.size()==4,"implicit polygon closure");
  byte[] payload=scene.encode(7,origin);check((payload[17]&255)==16,"buildings included on wire");
  JSONObject view=ScreenFrame.scene(scene,origin,7);check(view.getJSONArray("roads").length()==(payload[16]&255),"same road count on phone and wire");
  List<double[]> route=Arrays.asList(new double[]{origin[0],origin[1]-.0005},origin.clone(),new double[]{origin[0],origin[1]+.0008},new double[]{origin[0]+.0015,origin[1]+.0008});
  for(int heading:new int[]{0,45,90,180,270,359}){
   MapCoverage.View coverage=new MapCoverage.View(origin,heading,100);
   RoadMap.Scene oriented=new RoadMap.Scene(RoadMap.clipView(roads,coverage),scene.buildings,527,coverage);
   payload=oriented.encode(7,origin);view=ScreenFrame.scene(oriented,origin,7);
   check(coverage.covers(origin,heading,0),"oriented scene covers complete screen");
   Protocol.Snapshot n=new Protocol.Snapshot();n.state=3;n.flags=1|2|16|128;n.token=123;n.generation=1;n.heading=heading*100;n.speed=320;n.turnDistance=300;n.maneuver=1;n.total=1000;n.progress=200;
   ScreenFrame frame=new ScreenFrame(origin,route,n);check(frame.json.getJSONArray("origin").getDouble(0)==116.403633,"E6 origin matches wire");
   String data="20 "+hex(payload)+"\n17 "+hex(Protocol.geometry(n.token,n.generation,origin,route))+"\n16 "+hex(n.encode())+"\n";
   Files.write(out.resolve("scene-"+heading+".txt"),data.getBytes(StandardCharsets.UTF_8));
   Files.write(out.resolve("scene-"+heading+".json"),new JSONObject().put("scene",view).put("frame",frame.json).put("appearance",new JSONObject("{\"theme\":1,\"marker\":1,\"colors\":[\"#00FFFF\",\"#62696D\",\"#E0F2FE\",\"#FF0000\"]}")).toString().getBytes(StandardCharsets.UTF_8));
  }
  // A crossing polygon must be clipped, not distorted by linking unrelated nodes.
  List<RoadMap.Road> clipped=RoadMap.buildings(Arrays.asList(new RoadMap.Road(0,Arrays.asList(new double[]{origin[0]-.02,origin[1]-.02},new double[]{origin[0]+.02,origin[1]-.02},new double[]{origin[0]+.02,origin[1]+.02},new double[]{origin[0]-.02,origin[1]+.02}))),origin,200);
  check(clipped.size()==1&&clipped.get(0).points.size()==4,"polygon clipping preserves outline");
  for(double[] p:clipped.get(0).points)check(Math.abs((p[0]-origin[0])*sx)<=200.001&&Math.abs((p[1]-origin[1])*111320)<=200.001,"clipped points within scene");
  System.out.println("Screen scene checks PASS; six wire/preview fixtures generated.");
 }
}
