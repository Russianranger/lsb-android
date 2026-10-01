package io.github.russianranger.lsb;

import android.content.Context;
import io.github.russianranger.lsb.core.*;
import org.json.*;
import java.io.*;
import java.util.*;

/** Small recorded identities only. Never scan game files while drawing a page. */
final class InstallationSummary {
    static JSONObject snapshot(Context c)throws Exception {
        ClientRuntime rt=ClientRuntime.get(c);PreparedClientStore store=rt.prepared();File current=store.selected("current");
        JSONObject result=new JSONObject().put("format",1).put("captured_at",System.currentTimeMillis());
        boolean local=SetupGuide.local(c);result.put("managed",local).put("host",local?"127.0.0.1":MainActivity.store(c).config().host);
        if(current!=null){
            Properties meta=store.metadata(current);JSONObject client=new JSONObject().put("generation",current.getName()).put("region",meta.getProperty("region","unknown"));
            client.put("version",meta.getProperty("clientVersion",c.getSharedPreferences("compatibility",0).getString(current.getName(),"unknown")));
            File inventory=new File(current,"source-inventory.json");
            if(inventory.isFile()){
                JSONArray keys=new JSONObject(FilesEx.read(inventory,262144)).optJSONArray("keyFiles");
                if(keys!=null)for(int i=0;i<keys.length();i++){JSONObject key=keys.getJSONObject(i);if(key.optString("path").replace('\\','/').toLowerCase(Locale.ROOT).endsWith("/xiloader.exe")||key.optString("path").equalsIgnoreCase("xiloader.exe"))client.put("loader",key.optString("path")).put("loader_sha256",key.optString("sha256"));}
            }
            result.put("client",client);
        }
        if(local)result.put("server",ServerRuntime.get(c).deployment());
        return result;
    }
    static String shortId(String id){return id==null||id.isEmpty()?"unknown":id.substring(0,Math.min(12,id.length()));}
    static String describe(JSONObject value){
        JSONObject client=value.optJSONObject("client"),server=value.optJSONObject("server");StringBuilder out=new StringBuilder();
        if(client==null)out.append("Client · No activated preparation");
        else out.append("Client · ").append(shortId(client.optString("generation"))).append(" · ").append(client.optString("region","unknown")).append("\nClient version · ").append(client.optString("version","unknown")).append("\nLoader · ").append(client.optString("loader","not recorded")).append("\nLoader SHA-256 · ").append(shortId(client.optString("loader_sha256")));
        if(!value.optBoolean("managed"))out.append("\nExternal server · ").append(value.optString("host")).append("\nServer/database version · Managed outside this app");
        else if(server==null||!server.has("generation"))out.append("\nServer · Nothing deployed");
        else out.append("\nServer · ").append(shortId(server.optString("generation"))).append("\nBuild · ").append(server.optString("build_id").isEmpty()?"Imported deployment":shortId(server.optString("build_id"))).append("\nExpected client · ").append(server.optString("expected_client","unknown")).append("\nDatabase · ").append(server.optString("database","unknown")).append(" · same server generation");
        return out.toString();
    }
    static JSONObject saved(Context c)throws Exception {
        File f=new File(c.getFilesDir(),"working-combination.json");return f.isFile()?new JSONObject(FilesEx.read(f,262144)):new JSONObject();
    }
    static void recordSaved(Context c,JSONObject combination,String destination)throws Exception {
        JSONObject receipt=new JSONObject().put("format",1).put("saved_at",System.currentTimeMillis()).put("destination",destination).put("combination",combination);
        ClientRuntime.write(new File(c.getFilesDir(),"working-combination.json"),receipt.toString(2));
    }
}
