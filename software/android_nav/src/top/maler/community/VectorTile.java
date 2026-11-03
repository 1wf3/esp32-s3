package top.maler.community;
import java.io.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Bounded MVT 2.x reader for OpenMapTiles / Shortbread road and building layers. */
public final class VectorTile {
 static final class Pbf {
  final byte[] bytes;int at=0;Pbf(byte[] b){bytes=b;}
  boolean more(){return at<bytes.length;}
  long varint()throws IOException {long n=0;for(int i=0;i<10;i++){if(!more())throw new IOException("短矢量数据");int b=bytes[at++]&255;if(i==9&&(b&254)!=0)throw new IOException("矢量整数溢出");n|=(long)(b&127)<<(i*7);if(b<128)return n;}throw new IOException("无效矢量整数");}
  byte[] blob()throws IOException {long n=varint();if(n<0||n>bytes.length-at)throw new IOException("矢量长度无效");byte[] b=Arrays.copyOfRange(bytes,at,at+(int)n);at+=(int)n;return b;}
  String string()throws IOException{return new String(blob(),StandardCharsets.UTF_8);}
  void skip(int wire)throws IOException {if(wire==0)varint();else if(wire==2)blob();else if(wire==1||wire==5){at+=wire==1?8:4;if(at>bytes.length)throw new IOException("短矢量数据");}else throw new IOException("矢量字段无效");}
 }
 public static JSONObject decode(byte[] bytes,int x,int y)throws Exception {
  return decode(bytes,14,x,y);
 }
 public static JSONObject decode(byte[] bytes,int zoom,int x,int y)throws Exception {
  if(zoom<0||zoom>14||bytes.length>2000000||x<0||x>=(1<<zoom)||y<0||y>=(1<<zoom))throw new IOException("矢量瓦片超出范围");
  JSONArray roads=new JSONArray(),buildings=new JSONArray(),labels=new JSONArray();Pbf tile=new Pbf(bytes);int[] pointCount={0};
  while(tile.more()){int tag=(int)tile.varint();if(tag==26)layer(tile.blob(),zoom,x,y,roads,buildings,labels,pointCount);else tile.skip(tag&7);}
  return new JSONObject().put("coordinate_system","GCJ-02").put("schema_version",1).put("roads",roads).put("buildings",buildings).put("labels",labels).put("source","OpenStreetMap Shortbread");
 }
 private static void layer(byte[] bytes,int zoom,int x,int y,JSONArray roads,JSONArray buildings,JSONArray labels,int[] count)throws Exception {
  Pbf p=new Pbf(bytes);String name="";int extent=4096;List<byte[]> features=new ArrayList<>();List<String> keys=new ArrayList<>(),values=new ArrayList<>();
  while(p.more()){int tag=(int)p.varint();switch(tag){case 10:name=p.string();break;case 18:features.add(p.blob());break;case 26:keys.add(p.string());break;case 34:values.add(value(p.blob()));break;case 40:extent=(int)p.varint();break;default:p.skip(tag&7);}}
  boolean building=name.equals("buildings")||name.equals("building"),labelsOnly=name.equals("street_labels")||name.equals("transportation_name");
  if(!building&&!labelsOnly&&!name.equals("streets")&&!name.equals("transportation"))return;
  if(extent<1||extent>65536||features.size()>20000)throw new IOException("矢量图层过大");
  for(byte[] f:features){Pbf fp=new Pbf(f);byte[] geometry=null,tags=null;int type=0;
   while(fp.more()){int tag=(int)fp.varint();if(tag==18)tags=fp.blob();else if(tag==24)type=(int)fp.varint();else if(tag==34)geometry=fp.blob();else fp.skip(tag&7);}
   if(geometry==null||(type!=2&&type!=3))continue;
   Map<String,String> props=new HashMap<>();if(tags!=null){Pbf t=new Pbf(tags);while(t.more()){int k=(int)t.varint(),v=(int)t.varint();if(k<0||k>=keys.size()||v<0||v>=values.size())throw new IOException("矢量属性无效");props.put(keys.get(k),values.get(v));}}
   String kind=props.getOrDefault("kind",props.getOrDefault("class","other")),label=props.getOrDefault("name:zh",props.getOrDefault("name",""));
   if(kind.equals("minor"))kind="residential";
   if(name.startsWith("transportation")&&Arrays.asList("rail","transit","aerialway").contains(kind))continue;
   if(labelsOnly&&label.isEmpty())continue;
   List<JSONArray> parts=geometry(geometry,zoom,x,y,extent,count);
   for(JSONArray points:parts){if(points.length()<(type==3?3:2))continue;
    JSONObject out=new JSONObject().put("points_e6",points).put("class",kind).put("name",label);
    (building?buildings:labelsOnly?labels:roads).put(out);
   }
  }
 }
 private static String value(byte[] bytes)throws Exception {Pbf p=new Pbf(bytes);String v="";while(p.more()){int tag=(int)p.varint();if(tag==10)v=p.string();else if(tag==32||tag==40||tag==56)v=Long.toString(p.varint());else if(tag==48){long n=p.varint();v=Long.toString((n>>>1)^-(n&1));}else p.skip(tag&7);}return v;}
 private static List<JSONArray> geometry(byte[] bytes,int zoom,int tileX,int tileY,int extent,int[] count)throws Exception {
  Pbf p=new Pbf(bytes);List<JSONArray> parts=new ArrayList<>();JSONArray line=null;long x=0,y=0;
  while(p.more()){int command=(int)p.varint(),id=command&7,n=command>>>3;if(n<1||n>100000)throw new IOException("矢量命令无效");
   if(id==7){if(n!=1||line==null||line.length()<3)throw new IOException("矢量闭合无效");continue;}
   if(id!=1&&id!=2)throw new IOException("矢量命令不支持");
   for(int i=0;i<n;i++){long dx=p.varint(),dy=p.varint();x+=(dx>>>1)^-(dx&1);y+=(dy>>>1)^-(dy&1);if(Math.abs(x)>extent*4L||Math.abs(y)>extent*4L||++count[0]>120000)throw new IOException("矢量点数或坐标超限");
    if(id==1){line=new JSONArray();parts.add(line);}if(line==null)throw new IOException("矢量缺少起点");
    double lon=(tileX+x/(double)extent)/(1<<zoom)*360-180,lat=Math.toDegrees(Math.atan(Math.sinh(Math.PI*(1-2*(tileY+y/(double)extent)/(1<<zoom)))));double[] gcj=NavMath.gcj(lon,lat);
    line.put(new JSONArray().put(Math.round(gcj[1]*1e6)).put(Math.round(gcj[0]*1e6)));
   }
  }return parts;
 }
}
