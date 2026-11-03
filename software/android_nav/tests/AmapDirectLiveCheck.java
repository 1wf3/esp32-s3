import top.maler.community.*;
import org.json.*;
/** Optional real-provider check; reads key from the child process environment, never arguments. */
public final class AmapDirectLiveCheck {
    public static void main(String[] args){try{
        AmapDirect client=new AmapDirect(System.getenv("AMAP_WEB_SERVICE_KEY"));client.test();
        JSONArray places=client.search("北京站",null);if(places.length()==0)throw new Exception("公开地点搜索未返回结果");
        JSONArray routes=client.routes(new double[]{116.397389,39.908722},new double[]{116.410886,39.92015});
        if(routes.length()==0)throw new Exception("公开测试起终点未返回路线");
        for(int i=0;i<routes.length();i++)new Route(routes.getJSONObject(i));
        System.out.println("Live AMap PASS: search results="+places.length()+", usable routes="+routes.length()+"; key and locations omitted.");
    }catch(Exception e){System.out.println("Live AMap check failed: "+e.getMessage());System.exit(1);}}
}
