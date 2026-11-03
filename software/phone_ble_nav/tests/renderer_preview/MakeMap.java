import top.maler.community.*;
import java.util.*;import java.nio.file.*;
public class MakeMap {public static void main(String[] args)throws Exception {
List<String> lines=Files.readAllLines(Paths.get(args[0]));String[] o=lines.get(0).split(" ");double[] origin={Double.parseDouble(o[0]),Double.parseDouble(o[1])};List<RoadMap.Road> roads=new ArrayList<>();
for(String line:lines.subList(1,lines.size())){String[] v=line.split(" ");List<double[]> points=new ArrayList<>();for(int i=1;i<v.length;i+=2)points.add(new double[]{Double.parseDouble(v[i]),Double.parseDouble(v[i+1])});roads.add(new RoadMap.Road(Integer.parseInt(v[0]),points));}
roads=RoadMap.clip(roads,origin);byte[] map=RoadMap.encode(1,origin,roads);StringBuilder hex=new StringBuilder("20 ");for(byte b:map)hex.append(String.format("%02x",b&255));
Files.write(Paths.get(args[1]),(hex+"\n").getBytes("UTF-8"));System.out.println("Clipped real roads: "+roads.size()+", payload bytes: "+map.length);
}}
