package io.github.russianranger.lsb;

import android.content.Context;
import android.content.SharedPreferences;
import io.github.russianranger.lsb.core.*;
import org.json.*;

/** Progress comes from installed receipts, so imports/restores resume naturally. */
final class SetupGuide {
    static final class Step {
        final String id,title,detail,action;
        Step(String id,String title,String detail,String action){this.id=id;this.title=title;this.detail=detail;this.action=action;}
        boolean ready(){return id.equals("ready");}
    }
    static boolean local(Context c)throws Exception {
        String mode=c.getSharedPreferences("setup",0).getString("mode","");
        if(!mode.isEmpty())return mode.equals("local");
        String host=MainActivity.store(c).config().host;
        return (host.equals("127.0.0.1")||host.equals("localhost"))&&ServerRuntime.get(c).deployment().has("generation");
    }
    static void restored(Context c)throws Exception {
        SharedPreferences p=c.getSharedPreferences("setup",0);
        boolean managed=local(c);
        p.edit().putBoolean("restored",true).remove("restore_pending").putString("mode",managed?"local":"external")
            .putBoolean("connection_confirmed",true).apply();
    }
    static Step next(Context c)throws Exception {
        ClientRuntime rt=ClientRuntime.get(c);ServerRuntime sr=ServerRuntime.get(c);ClientStore source=MainActivity.store(c);
        SharedPreferences p=c.getSharedPreferences("setup",0),runtime=c.getSharedPreferences("runtime",0);
        JSONObject prepared=rt.preparationState();boolean current=prepared.has("current");
        if(p.getBoolean("restore_pending",false))return new Step("restore","Restore your complete backup","Choose the complete session ZIP exported by LSB. The saved client, runtimes, server, database and settings are restored together.","Choose complete session backup");
        if(p.getString("mode","").isEmpty()&&!current&&!source.hasClient())return new Step("choose","How would you like to play?","Choose a setup path. You can change it later.","");
        if(!rt.installed())return new Step("runtime","Install the client runtime","Download the Windows runtime once. Existing client imports and server data are retained.","Install client runtime");
        if((runtime.getBoolean("fex",false)||runtime.getString("updater_engine","").equals("fex"))&&!rt.fexInstalled())
            return new Step("fex","Install the selected gameplay engine","The selected FEX preset needs its runtime download. Your Box64 installation remains available.","Install FEX runtime");
        if(source.hasPendingImport())return new Step("selection","Choose the PlayOnline installation","Your ZIP contains more than one PlayOnline copy. Select the installed copy to finish import.","Choose PlayOnline copy");
        if(!source.hasClient()&&!current)return new Step("client","Bring your FFXI installation","Import a ZIP containing PlayOnline and FINAL FANTASY XI. Use client files compatible with your chosen server.","Import client ZIP");
        if(!current){
            JSONArray files=new JSONObject(source.inventory()).optJSONArray("keyFiles");boolean loader=false;
            if(files!=null)for(int i=0;i<files.length();i++)if(files.getJSONObject(i).optString("path").toLowerCase(java.util.Locale.ROOT).endsWith("xiloader.exe"))loader=true;
            if(!loader)return new Step("loader","Add your server's xiloader","Choose the xiloader.exe supplied for your server. This is kept with the prepared client.","Import xiloader.exe");
            if(!p.getBoolean("connection_confirmed",false))return new Step("connection","Confirm your connection and client region","Use the managed server on this device or the address supplied by your server administrator. Confirm the client region before preparation.","Save connection and continue");
            return new Step("prepare","Prepare the imported client","Creates a working copy and checks the client in its Windows environment. Interrupted preparation can be retried; a staged update is continued on Client.","Prepare client");
        }
        if(local(c)){
            if(!sr.installed()||!sr.toolsCurrent())return new Step("server_runtime","Install server build tools","Install the ARM64 server runtime, compiler, MariaDB and jemalloc.","Install server build tools");
            if(!sr.deployment().has("generation")){
                JSONObject build=sr.buildState(),selected=build.getJSONObject("selected_source"),compiled=build.getJSONObject("build"),staged=build.getJSONObject("staged");
                if(selected.length()==0)return new Step("source","Choose your server source","Fetch a revision or import your server ZIP on Build. Keep it compatible with the client and loader you imported.","Fetch or import server source");
                JSONObject builtSource=compiled.optJSONObject("selected_source");String snapshot=selected.optString("snapshot_id");
                if(!compiled.optString("state").equals("passed")||!compiled.optString("allocator").equals("jemalloc")||compiled.optString("build_id").isEmpty()
                    ||builtSource==null||snapshot.isEmpty()||!snapshot.equals(builtSource.optString("snapshot_id")))
                    return new Step("build","Build the selected source","Build with jemalloc and choose the number of workers on Build. An older build is retained separately; this step follows your currently fetched source.","Build server with jemalloc");
                if(!staged.has("generation")||!compiled.optString("build_id").equals(staged.optString("build_id"))||staged.optBoolean("stale_database"))
                    return new Step("database","Prepare the server database","Choose a fresh database or import your SQL backup, then stage it with the completed build.","Prepare database on Build");
                if(!staged.optString("state").equals("checked"))return new Step("check","Check the staged server and database","Verify the selected build and its database before deployment.","Check staging on Build");
                return new Step("deploy","Deploy the checked server","Review the staged build and player counts, then deploy that exact pair.","Deploy checked pair on Build");
            }
            if(sr.deployment().optInt("accounts",0)==0&&!p.getBoolean("account_ready",false))return new Step("account","Create your player account","Create an account on Server, or continue if you already have one in this database.","Create player account");
        }
        return new Step("ready","Ready to play",p.getBoolean("restored",false)?"Backup restored. Your saved runtime choices and prepared client are retained.":"Setup is complete. Use Play to start your selected server and client.","Go to Play");
    }
}
