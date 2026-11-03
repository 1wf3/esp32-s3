package top.maler.community;

import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class Gateway {
    public final String base;
    private AmapDirect direct;
    public static Gateway direct(String key){return new Gateway(key,true);}
    private Gateway(String key,boolean directMode){base="https://restapi.amap.com/";direct=new AmapDirect(key);}
    public boolean supportsMaps(){return true;}
    public boolean isDirect(){return direct!=null;}
    public void test()throws Exception {if(direct!=null){direct.test();return;}JSONObject h=request("healthz",null);if(!h.optBoolean("ready_for_live_navigation")||!"amap".equals(h.optString("provider")))throw new IOException("导航服务未启用");}
    public Gateway(String value) {
        try {
            URI uri=new URI(value.trim()); String host=uri.getHost();
            boolean lan=host!=null&&(host.equals("localhost")||host.matches("127\\.\\d+\\.\\d+\\.\\d+")||
                host.matches("10\\.\\d+\\.\\d+\\.\\d+")||host.matches("192\\.168\\.\\d+\\.\\d+")||
                host.matches("172\\.(1[6-9]|2\\d|3[01])\\.\\d+\\.\\d+"));
            if(host==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||
                !("https".equals(uri.getScheme())||("http".equals(uri.getScheme())&&lan)))
                throw new IllegalArgumentException("填写 HTTPS 网关根地址；局域网测试允许 http://电脑IP:8787/");
            base=value.trim().endsWith("/")?value.trim():value.trim()+"/";
        } catch(URISyntaxException e){throw new IllegalArgumentException("网关地址格式无效");}
    }
    public JSONObject request(String path,JSONObject body)throws Exception {
        if(direct!=null)throw new IOException("高德直连模式提供路线导航，未启用周边道路底图");
        HttpURLConnection c=(HttpURLConnection)new URL(base+path).openConnection();
        c.setConnectTimeout(8000);c.setReadTimeout(12000);c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Accept","application/json");
        try {
            if(body!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");
                try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
            int status=c.getResponseCode();
            InputStream raw=status>=200&&status<300?c.getInputStream():c.getErrorStream();
            if(raw==null)throw new IOException("网关 HTTP "+status);
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(InputStream in=raw){byte[] chunk=new byte[8192];int n;while((n=in.read(chunk))!=-1){if(out.size()+n>2000000)throw new IOException("响应过大");out.write(chunk,0,n);}}
            JSONObject result=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
            if(status<200||status>=300)throw new IOException("网关 HTTP "+status+"："+result.optJSONObject("error"));
            return result;
        } finally {c.disconnect();}
    }
    public JSONArray search(String keywords,double[] wgs)throws Exception {
        if(direct!=null)return direct.search(keywords,wgs);
        String q="v1/places?keywords="+URLEncoder.encode(keywords,"UTF-8");
        if(wgs!=null)q+="&longitude_deg="+wgs[0]+"&latitude_deg="+wgs[1];
        JSONObject reply=request(q,null);
        if(reply.getInt("protocol_version")!=1)throw new IOException("网关版本不支持");
        return reply.getJSONArray("places");
    }
    public JSONArray routes(long id,double[] origin,double[] destination,boolean reroute)throws Exception {
        if(direct!=null)return direct.routes(origin,destination);
        JSONObject body=new JSONObject().put("protocol_version",1).put("request_id",id).put("route_mode","driving")
            .put("origin",point(origin)).put("destination",point(destination)).put("is_reroute",reroute);
        JSONObject reply=request("v1/route-options",body);
        if(reply.getInt("protocol_version")!=1||reply.getLong("request_id")!=id)throw new IOException("路线响应编号不一致");
        JSONArray routes=reply.getJSONArray("routes");
        if(routes.length()<1||routes.length()>3)throw new IOException("候选路线数量无效");
        return routes;
    }
    private JSONObject point(double[] p)throws JSONException {return new JSONObject().put("coordinate_system","WGS84").put("longitude_deg",p[0]).put("latitude_deg",p[1]);}
}
