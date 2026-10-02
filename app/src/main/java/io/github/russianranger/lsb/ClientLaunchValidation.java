package io.github.russianranger.lsb;

import io.github.russianranger.lsb.core.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Reuses the activated inventory; hashes critical binaries on every launch. */
final class ClientLaunchValidation {
    static File path(File root,String relative,boolean directory)throws IOException {
        if(relative==null||relative.isEmpty()||relative.startsWith("/")||relative.contains("\\")||relative.contains(":"))throw new IOException("Invalid prepared client path");
        File file=root;if(Files.isSymbolicLink(file.toPath()))throw new IOException("Prepared client contains a symbolic link");
        for(String part:relative.split("/",-1)){
            if(part.isEmpty()||part.equals(".")||part.equals("..")||part.indexOf('\0')>=0)throw new IOException("Invalid prepared client path");
            file=new File(file,part);if(Files.isSymbolicLink(file.toPath()))throw new IOException("Prepared client contains a symbolic link");
        }
        if(directory?!file.isDirectory():!file.isFile())throw new IOException("Prepared client file is missing: "+relative);
        return file;
    }
    static JSONObject manifest(File generation,Properties meta,SafeZip.Progress progress)throws Exception {
        File inventory=new File(generation,"source-inventory.json"),initialized=new File(generation,"initialization-passed.json");
        if(Files.isSymbolicLink(inventory.toPath())||Files.isSymbolicLink(initialized.toPath()))throw new IOException("Prepared client receipt contains a symbolic link");
        String inventoryHash=meta.getProperty("inventorySha256","");
        if(!inventory.isFile()||!initialized.isFile()||inventoryHash.isEmpty())return null;
        JSONObject receipt=new JSONObject(ClientRuntime.read(initialized,262144));
        if(!"passed".equals(receipt.optString("status"))||!generation.getName().equals(receipt.optString("generation")))return null;
        if(!inventoryHash.equals(FilesEx.hash(inventory)))throw new IOException("Prepared client inventory changed; run full inspection before retrying");
        File root=new File(generation,"client");String core=meta.getProperty("core",""),game=meta.getProperty("game",""),pol=meta.getProperty("pol",""),loader=meta.getProperty("loader","");
        String region=meta.getProperty("region","");if(!Arrays.asList("US","EU","JP").contains(region))throw new IOException("Invalid prepared client region");
        JSONObject source=new JSONObject(ClientRuntime.read(inventory,262144));
        if(!core.equals(source.optString("selectedPlayOnline"))||!pol.equals(source.optString("playOnlineRoot"))||source.optBoolean("playOnlinePatchCache")||!source.optBoolean("playOnlineExePresent"))return null;
        path(root,game,true);File viewer=path(root,pol,true);
        File rom=ClientInspector.child(path(root,game,true),"ROM");if(rom==null)throw new IOException("Missing FFXI ROM data");path(root,FilesEx.relative(root,rom),true);
        if(FilesEx.children(rom).length==0)throw new IOException("Missing FFXI ROM data");
        JSONArray entries=source.getJSONArray("keyFiles");JSONObject keys=new JSONObject();Set<String> seen=new HashSet<>();boolean hasCore=false,hasEntry=false,hasMain=false,hasLoader=loader.isEmpty();
        if(entries.length()>8)throw new IOException("Invalid prepared client inventory");
        for(int i=0;i<entries.length();i++){
            JSONObject entry=entries.getJSONObject(i);String relative=entry.getString("path");
            if(!seen.add(relative.toLowerCase(Locale.ROOT)))throw new IOException("Duplicate prepared client key file");
            File file=path(root,relative,false);ClientInspector.requireX86(file);progress.update("Checking "+file.getName());
            String digest=FilesEx.hash(file);if(!digest.equals(entry.getString("sha256")))throw new IOException("Prepared client binary changed: "+file.getName()+". Run full inspection or prepare the updated client.");
            keys.put(relative,digest);hasCore|=relative.equals(core);hasLoader|=relative.equals(loader);
            if(file.getParentFile().equals(new File(root,game))){hasEntry|=file.getName().equalsIgnoreCase("ffxi.dll");hasMain|=file.getName().equalsIgnoreCase("ffximain.dll");}
        }
        if(entries.length()>8||!hasCore||!hasEntry||!hasMain||!hasLoader)throw new IOException("Prepared inventory is missing required client binaries");
        File executable=ClientInspector.child(viewer,"pol.exe");if(executable==null)throw new IOException("Prepared PlayOnline executable is missing");
        String executablePath=FilesEx.relative(root,executable);path(root,executablePath,false);ClientInspector.requireX86(executable);keys.put(executablePath,FilesEx.hash(executable));
        return new JSONObject().put("format",1).put("generation",generation.getName()).put("region",region).put("pol",pol).put("core",core).put("game",game).put("loader",loader).put("key_files",keys).put("inventory_sha256",inventoryHash);
    }
}
