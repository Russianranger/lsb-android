package io.github.russianranger.lsb;

import android.content.Context;
import io.github.russianranger.lsb.core.*;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.file.*;

/** Immutable native Wine overlay and independent prefixes; no baseline replacement. */
final class FexRuntime {
    final File home;
    final JSONObject manifest;
    final String hash;
    FexRuntime(Context context,File runtimeHome)throws Exception {
        home=new File(runtimeHome,"fex");home.mkdirs();
        try(InputStream in=context.getAssets().open("runtime/fex-bundle.json")){
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;
            while((n=in.read(b))!=-1){if(out.size()+n>16384)throw new IOException("FEX manifest exceeds limits");out.write(b,0,n);}
            manifest=new JSONObject(out.toString("UTF-8"));
        }
        hash=manifest.getString("sha256");
        if(manifest.getInt("format")!=1||!manifest.getString("candidate").equals("wine10-arm64-fex2510")||!hash.matches("[0-9a-f]{64}"))throw new IOException("Unsupported FEX runtime manifest");
    }
    File wine(){return new File(home,"wine-"+hash);}
    boolean installed(){
        try{return ClientRuntime.sha(new File(wine(),"lsb-fex.json")).equals(manifest.getString("manifest_sha256"));}
        catch(Exception e){return false;}
    }
    String install(SafeZip.Progress progress)throws Exception {
        if(installed())return "FEX runtime installed. Select FEX to test it.";
        if(home.getUsableSpace()<3L*1073741824L)throw new IOException("Keep 3 GiB free for FEX and its separate Windows environment");
        File archive=new File(home,"download.tar.gz"),stage=new File(home,"install");
        TarExtractor.remove(stage);stage.mkdirs();
        try{
            download(archive,progress);
            if(!ClientRuntime.sha(archive).equals(hash))throw new IOException("FEX archive checksum mismatch");
            TarExtractor.extract(archive,stage,n->progress.update("Unpacking FEX runtime · "+n+" files"));
            if(!ClientRuntime.sha(new File(stage,"lsb-fex.json")).equals(manifest.getString("manifest_sha256")))throw new IOException("FEX component manifest mismatch");
            for(String path:new String[]{"bin/wine","bin/wineserver","lib/wine/aarch64-windows/libwow64fex.dll","lib/wine/i386-windows/ntdll.dll"})
                if(!new File(stage,path).isFile())throw new IOException("Incomplete FEX runtime: "+path);
            ClientRuntime.interrupted();TarExtractor.remove(wine());
            if(!stage.renameTo(wine()))throw new IOException("Could not activate FEX runtime");
            return "FEX installed. Select FEX, then run Windows checks before launching the client.";
        }finally{archive.delete();TarExtractor.remove(stage);}
    }
    private void download(File target,SafeZip.Progress progress)throws Exception {
        URL url=new URL(manifest.getString("url"));long expected=manifest.getLong("bytes");
        if(expected<=0||expected>2L*1073741824L)throw new IOException("Invalid FEX archive size");
        for(int redirect=0;redirect<8;redirect++){
            ClientRuntime.interrupted();if(!url.getProtocol().equals("https"))throw new IOException("FEX download requires HTTPS");
            HttpURLConnection c=(HttpURLConnection)url.openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(20000);c.setReadTimeout(30000);
            try{
                int code=c.getResponseCode();
                if(code>=300&&code<400){url=new URL(url,c.getHeaderField("Location"));continue;}
                if(code!=200)throw new IOException("FEX download failed (HTTP "+code+")");
                long total=0,last=0;byte[] b=new byte[1048576];
                try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(target)){
                    int n;while((n=in.read(b))!=-1){ClientRuntime.interrupted();total+=n;if(total>expected)throw new IOException("FEX download exceeds expected size");out.write(b,0,n);
                        if(total-last>4*1048576){progress.update("Downloading FEX · "+total/1048576+" / "+expected/1048576+" MiB");last=total;}}
                }
                if(total!=expected)throw new IOException("FEX download was incomplete");return;
            }finally{c.disconnect();}
        }
        throw new IOException("Too many FEX download redirects");
    }
    File prefix(File source,String generation,SafeZip.Progress progress)throws Exception {
        if(!installed())throw new IOException("Install FEX on the Runtime tab first");
        if(!generation.matches("probe|[0-9a-f-]{36}"))throw new IOException("Invalid FEX prefix identity");
        File destination=new File(home,"prefixes/"+hash+"/"+generation),marker=new File(destination,"lsb-runtime-engine.json");
        String identity=new JSONObject().put("engine","fex").put("runtime",hash).toString();
        if(Files.isSymbolicLink(destination.toPath()))throw new IOException("FEX environment cannot be a link");
        if(marker.isFile()){
            JSONObject found=new JSONObject(ClientRuntime.read(marker,4096));
            if(!"fex".equals(found.optString("engine"))||!hash.equals(found.optString("runtime")))throw new IOException("FEX Windows environment identity mismatch");
            return destination;
        }
        File stage=new File(destination.getParentFile(),generation+".new");
        TarExtractor.remove(stage);stage.getParentFile().mkdirs();
        try{
            if(source.isDirectory())PreparedClientStore.copyRuntimePrefix(source,stage,progress);else stage.mkdirs();
            ClientRuntime.write(new File(stage,"lsb-runtime-engine.json"),identity);
            new File(stage,"lsb-prefix-ready.json").delete();ClientRuntime.interrupted();
            // Only a prior incomplete FEX copy can occupy this exact destination.
            if(destination.exists())throw new IOException("Incomplete FEX environment needs recovery; baseline remains available");
            if(!stage.renameTo(destination))throw new IOException("Could not activate separate FEX environment");
            return destination;
        }finally{TarExtractor.remove(stage);}
    }
}
