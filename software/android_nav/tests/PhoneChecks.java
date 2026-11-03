package top.maler.community.checks;
import android.app.Instrumentation;
import android.os.Bundle;
import java.net.*;
import java.io.*;
import java.util.concurrent.*;
/** Separate, same-signature test APK. Reports no keys, request URLs or private positions. */
public final class PhoneChecks extends Instrumentation {
 private Bundle args;public void onCreate(Bundle b){super.onCreate(b);args=b;start();}
 public void onStart(){Bundle result=new Bundle();try{
  if("probe".equals(args.getString("mode"))){

   String[] endpoints={"https://vector.openstreetmap.org/shortbread_v1/tilejson.json","https://tiles.openfreemap.org/planet/latest","https://build-metadata.protomaps.dev/builds.json","https://restapi.amap.com/"};
   ExecutorService pool=Executors.newFixedThreadPool(4);java.util.List<Future<String>> tasks=new java.util.ArrayList<>();
   for(String endpoint:endpoints)tasks.add(pool.submit(()->{HttpURLConnection c=(HttpURLConnection)new URL(endpoint).openConnection();c.setConnectTimeout(5000);c.setReadTimeout(5000);c.setRequestProperty("User-Agent","MotoNavPersonal/0.4");try{int status=c.getResponseCode();try(InputStream in=status<400?c.getInputStream():c.getErrorStream()){if(in!=null)in.read();}return "HTTP "+status;}catch(Exception e){return e.getClass().getSimpleName()+": "+e.getMessage();}finally{c.disconnect();}}));
   for(int i=0;i<endpoints.length;i++)try{result.putString("source_"+i,tasks.get(i).get(12,TimeUnit.SECONDS));}catch(Exception e){result.putString("source_"+i,"deadline");}pool.shutdownNow();
  }else{
   Class<?> store=Class.forName("top.maler.community.AmapKeyStore");String key=(String)store.getMethod("read",android.content.Context.class).invoke(null,getTargetContext());
   String cipher=getTargetContext().getSharedPreferences("nav",0).getString("amap_key_encrypted","");
   if(key.isEmpty()||cipher.isEmpty()||cipher.contains(key))throw new Exception("Encrypted key persistence failed");result.putString("key_storage","PASS: encrypted value persisted and decrypted");
   String expected=args.getString("expectedColor");if(expected!=null){if(!expected.startsWith("#"))expected="#"+expected;org.json.JSONObject appearance=new org.json.JSONObject(getTargetContext().getSharedPreferences("nav",0).getString("appearance","{}"));if(!expected.equalsIgnoreCase(appearance.getJSONArray("colors").getString(0)))throw new Exception("Appearance persistence mismatch");result.putString("appearance_persistence","PASS: expected route color restored");}
  }
  String restore=args.getString("restoreColor");if(restore!=null){if(!restore.matches("[0-9a-fA-F]{6}"))throw new Exception("Invalid restore color");org.json.JSONObject appearance=new org.json.JSONObject(getTargetContext().getSharedPreferences("nav",0).getString("appearance","{}"));appearance.getJSONArray("colors").put(0,"#"+restore);Class.forName("top.maler.community.Appearance").getMethod("encode",org.json.JSONObject.class).invoke(null,appearance);if(!getTargetContext().getSharedPreferences("nav",0).edit().putString("appearance",appearance.toString()).commit())throw new Exception("Restore persistence failed");result.putString("appearance_restore","PASS: original route color restored");}
  finish(-1,result);
 }catch(Throwable e){result.putString("failure",e.getClass().getSimpleName());finish(0,result);}}
}
