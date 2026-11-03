package top.maler.community;
import org.json.*;
/** The same validated colors drive phone drawing, persistence and the RGB565 BLE payload. */
public final class Appearance {
 public static byte[] encode(JSONObject data)throws Exception {
  int theme=data.getInt("theme"),marker=data.getInt("marker");JSONArray colors=data.getJSONArray("colors");
  if(theme<0||theme>2||marker<0||marker>2||colors.length()!=4)throw new JSONException("配色参数无效");
  Protocol.Writer out=new Protocol.Writer().u8(1).u8(theme).u8(marker).u8(0);
  for(int i=0;i<4;i++){String color=colors.getString(i);if(!color.matches("#[0-9a-fA-F]{6}"))throw new JSONException("颜色需为 #RRGGBB");int rgb=Integer.parseInt(color.substring(1),16);out.u16(((rgb>>19)&31)<<11|((rgb>>10)&63)<<5|((rgb>>3)&31));}
  return out.bytes();
 }
}
