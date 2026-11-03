import top.maler.community.*;
import org.json.*;
/** Public base-map smoke check; does not read keys or use the route API. */
public final class MapUpdateLiveCheck {
 public static void main(String[] args)throws Exception{
  DirectMap client=new DirectMap();double[] wgs={116.397,39.908},gcj=NavMath.gcj(wgs[0],wgs[1]);
  long start=System.nanoTime();JSONObject near=client.load(wgs);
  System.out.println("Live neighbourhood: z="+near.getInt("zoom")+", roads="+near.getJSONArray("roads").length()+", elapsed_ms="+(System.nanoTime()-start)/1_000_000);
  for(int heading:new int[]{0,45,90,180,270,359}){
   RoadMap.Scene scene=MapTiles.scene(near,gcj,heading);
   if(!scene.view.covers(gcj,heading,0))throw new AssertionError("Incomplete visible extent");
   System.out.println("Live screen: heading="+heading+", roads="+scene.roads.size()+", buffer_m="+scene.view.buffer+", bytes="+scene.encode(1,gcj).length);
  }
  JSONObject wide=client.load(wgs,10_000,10_000);
  if(wide.getInt("zoom")>=near.getInt("zoom")||wide.getJSONArray("roads").length()==0)throw new AssertionError("Zoom-out loading failed");
  System.out.println("Live overview: z="+wide.getInt("zoom")+", roads="+wide.getJSONArray("roads").length()+", coverage="+wide.getString("coverage_key"));
 }
}
