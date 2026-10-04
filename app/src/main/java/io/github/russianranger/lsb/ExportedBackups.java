package io.github.russianranger.lsb;

import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import io.github.russianranger.lsb.core.*;
import org.json.*;
import java.io.*;
import java.util.*;

/** Receipts and document grants only; never makes an internal copy of an export. */
final class ExportedBackups {
    static final class Entry {
        final Uri uri;final String kind;final long saved;
        String name;long bytes;boolean accessible,canDelete;
        Entry(JSONObject value)throws Exception {
            uri=Uri.parse(value.getString("uri"));kind=value.optString("kind","backup");saved=value.optLong("saved_at");
            name=value.optString("name",kind.equals("working-backup")?"lsb-working-combination.zip":"Exported backup");bytes=value.optLong("bytes",-1);
        }
    }
    static boolean trackedKind(String kind){return kind.equals("working-backup")||kind.equals("backup")||kind.equals("server-db");}
    private static File registry(Context c){return new File(c.getApplicationInfo().dataDir,"no_backup/exported-backups.json");}
    private static JSONArray receipts(Context c)throws Exception {
        File file=registry(c);JSONArray values=file.isFile()?new JSONObject(FilesEx.read(file,1048576)).getJSONArray("exports"):new JSONArray();
        // Old versions recorded the working-combination URI but released its grant.
        // Keep it visible even when the user must select the existing document again.
        JSONObject old=InstallationSummary.saved(c);String destination=old.optString("destination");
        if(destination.startsWith("content://")&&!contains(values,destination))values.put(new JSONObject().put("uri",destination).put("kind","working-backup").put("saved_at",old.optLong("saved_at")).put("name","lsb-working-combination.zip"));
        return values;
    }
    private static boolean contains(JSONArray values,String uri)throws Exception {for(int i=0;i<values.length();i++)if(values.getJSONObject(i).optString("uri").equals(uri))return true;return false;}
    private static void save(Context c,JSONArray values)throws Exception {
        ClientRuntime.write(registry(c),new JSONObject().put("format",1).put("exports",values).toString());
    }
    static int persistedFlags(Context c,Uri uri) {
        int flags=0;
        for(UriPermission grant:c.getContentResolver().getPersistedUriPermissions())if(grant.getUri().equals(uri)){
            if(grant.isReadPermission())flags|=Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if(grant.isWritePermission())flags|=Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        }
        return flags;
    }
    static synchronized void record(Context c,Uri uri,String kind)throws Exception {
        if(!"content".equals(uri.getScheme()))throw new IOException("Choose a document from Android Files");
        JSONArray values=receipts(c),next=new JSONArray();JSONObject value=null;
        for(int i=0;i<values.length();i++){JSONObject previous=values.getJSONObject(i);if(previous.optString("uri").equals(uri.toString()))value=previous;else next.put(previous);}
        if(value==null)value=new JSONObject().put("uri",uri.toString()).put("saved_at",System.currentTimeMillis());
        // Locating a receipt retains its original backup type and creation date.
        if(!kind.equals("located"))value.put("kind",kind).put("saved_at",System.currentTimeMillis());
        Entry entry=new Entry(value);inspect(c,entry);
        value.put("name",entry.name).put("bytes",entry.bytes);next.put(value);save(c,next);
    }
    static synchronized List<Entry> list(Context c)throws Exception {
        if(SessionBackup.active||!SessionBackup.recoveryError.isEmpty())throw new IOException("Wait for session recovery before reading backup receipts");
        JSONArray values=receipts(c);List<Entry> result=new ArrayList<>();
        for(int i=0;i<values.length();i++){SafeZip.checkCancelled();Entry entry=new Entry(values.getJSONObject(i));if(!"content".equals(entry.uri.getScheme()))continue;inspect(c,entry);result.add(entry);}
        result.sort((a,b)->Long.compare(b.saved,a.saved));return result;
    }
    private static void inspect(Context c,Entry entry) {
        try(Cursor cursor=c.getContentResolver().query(entry.uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE,DocumentsContract.Document.COLUMN_FLAGS},(android.os.Bundle)null,null)) {
            if(cursor==null||!cursor.moveToFirst())return;
            int name=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME),bytes=cursor.getColumnIndex(OpenableColumns.SIZE),flags=cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS);
            if(name>=0&&!cursor.isNull(name))entry.name=cursor.getString(name);
            if(bytes>=0&&!cursor.isNull(bytes))entry.bytes=cursor.getLong(bytes);
            entry.accessible=(persistedFlags(c,entry.uri)&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0;
            entry.canDelete=entry.accessible&&(persistedFlags(c,entry.uri)&Intent.FLAG_GRANT_WRITE_URI_PERMISSION)!=0&&flags>=0&&(cursor.getInt(flags)&DocumentsContract.Document.FLAG_SUPPORTS_DELETE)!=0;
        }catch(Exception ignored){} // An unavailable volume/provider remains a visible receipt.
    }
    static synchronized void delete(Context c,Uri uri)throws Exception {
        if(WorkService.busy||SessionBackup.active||!SessionBackup.recoveryError.isEmpty())throw new IOException("Wait for maintenance before deleting an exported backup");
        Entry found=null;for(Entry entry:list(c))if(entry.uri.equals(uri)){found=entry;break;}
        if(found==null||!found.canDelete)throw new IOException("Locate the exported document again before deleting it");
        if(!DocumentsContract.deleteDocument(c.getContentResolver(),uri))throw new IOException("Android Files did not delete the exported backup");
        JSONArray next=new JSONArray(),values=receipts(c);
        for(int i=0;i<values.length();i++)if(!values.getJSONObject(i).optString("uri").equals(uri.toString()))next.put(values.getJSONObject(i));
        // Do not let the legacy working-combination receipt resurrect a deleted row.
        JSONObject old=InstallationSummary.saved(c);if(old.optString("destination").equals(uri.toString())){
            old.remove("destination");old.put("export_deleted_at",System.currentTimeMillis());ClientRuntime.write(new File(c.getFilesDir(),"working-combination.json"),old.toString(2));
        }
        save(c,next);int flags=persistedFlags(c,uri);if(flags!=0)try{c.getContentResolver().releasePersistableUriPermission(uri,flags);}catch(SecurityException ignored){}
    }
}
