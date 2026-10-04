package io.github.russianranger.lsb;

import android.content.*;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import io.github.russianranger.lsb.core.FilesEx;
import java.io.*;
import java.util.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class ExportedBackupsTest {
    static final String AUTHORITY="lsb.test.exports";
    private Context context;private Provider provider;private Uri uri;
    public static class Provider extends DocumentsProvider {
        boolean available=true,failWrite;int deleted;File archive;
        @Override public boolean onCreate(){return true;}
        @Override public Cursor queryRoots(String[] projection){return new MatrixCursor(new String[]{DocumentsContract.Root.COLUMN_ROOT_ID});}
        @Override public Cursor queryDocument(String id,String[] projection)throws FileNotFoundException {
            if(!available)throw new FileNotFoundException("removed volume");
            MatrixCursor rows=new MatrixCursor(new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_FLAGS});
            rows.addRow(new Object[]{id,"working.zip",archive.length(),DocumentsContract.Document.FLAG_SUPPORTS_DELETE});return rows;
        }
        @Override public Cursor queryChildDocuments(String parent,String[] projection,String order){return queryRoots(projection);}
        @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal)throws FileNotFoundException {if(failWrite&&mode.contains("w"))throw new FileNotFoundException("fixture write failure");return ParcelFileDescriptor.open(archive,ParcelFileDescriptor.parseMode(mode));}
        @Override public void deleteDocument(String id)throws FileNotFoundException {deleted++;if(!archive.delete())throw new FileNotFoundException("delete failed");available=false;}
    }
    @Before public void before()throws Exception {
        context=RuntimeEnvironment.getApplication();WorkService.busy=false;SessionBackup.active=false;SessionBackup.recoveryError="";
        ClientRuntime.resetAfterRestore();ServerRuntime.resetAfterRestore();
        FilesEx.delete(new File(context.getApplicationInfo().dataDir,"no_backup/exported-backups.json"));FilesEx.delete(new File(context.getFilesDir(),"working-combination.json"));
        provider=new Provider();ProviderInfo info=new ProviderInfo();info.authority=AUTHORITY;info.exported=true;info.grantUriPermissions=true;info.readPermission="android.permission.MANAGE_DOCUMENTS";info.writePermission=info.readPermission;provider.attachInfo(context,info);
        ShadowContentResolver.registerProviderInternal(AUTHORITY,provider);
        provider.archive=File.createTempFile("external-backup-",".zip");FilesEx.text(provider.archive,"archive");uri=DocumentsContract.buildDocumentUri(AUTHORITY,"working.zip");
    }
    @After public void after()throws Exception {
        WorkService.busy=false;SessionBackup.active=false;SessionBackup.recoveryError="";
        FilesEx.delete(new File(context.getApplicationInfo().dataDir,"no_backup/exported-backups.json"));FilesEx.delete(new File(context.getFilesDir(),"working-combination.json"));provider.archive.delete();
        int flags=ExportedBackups.persistedFlags(context,uri);if(flags!=0)context.getContentResolver().releasePersistableUriPermission(uri,flags);
        ClientRuntime.resetAfterRestore();ServerRuntime.resetAfterRestore();
    }
    private void grant(){context.getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);}
    @Test public void oldWorkingCombinationExportRemainsVisibleAndCanReconnectWithoutCopying()throws Exception {
        try(Cursor probe=context.getContentResolver().query(uri,new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_FLAGS},(android.os.Bundle)null,null)){assertNotNull(probe);assertTrue(probe.moveToFirst());}
        InstallationSummary.recordSaved(context,new JSONObject().put("client","fixture"),uri.toString());
        ExportedBackups.Entry old=ExportedBackups.list(context).get(0);assertEquals("working-backup",old.kind);assertFalse(old.accessible);assertEquals(7,old.bytes);
        long before=provider.archive.lastModified();grant();ExportedBackups.record(context,uri,"located");
        List<ExportedBackups.Entry> found=ExportedBackups.list(context);assertEquals(1,found.size());assertTrue(found.get(0).accessible);assertTrue(found.get(0).canDelete);assertEquals("working-backup",found.get(0).kind);assertEquals(before,provider.archive.lastModified());
        assertFalse(new File(context.getFilesDir(),"working.zip").exists());
    }
    @Test public void unavailableExportIsStillListedAndCannotBeDeleted()throws Exception {
        grant();ExportedBackups.record(context,uri,"backup");provider.available=false;
        ExportedBackups.Entry entry=ExportedBackups.list(context).get(0);assertFalse(entry.accessible);assertEquals("working.zip",entry.name);
        try{ExportedBackups.delete(context,uri);fail("Unavailable export deleted");}catch(IOException expected){}assertEquals(0,provider.deleted);
    }
    @Test public void deleteRemovesOnlyRegisteredExternalArchiveAndReleasesGrantWithoutResurrectingLegacyRow()throws Exception {
        File active=new File(context.getFilesDir(),"preserved-client");FilesEx.text(active,"keep");
        try{
            InstallationSummary.recordSaved(context,new JSONObject(),uri.toString());grant();ExportedBackups.record(context,uri,"working-backup");
            ExportedBackups.delete(context,uri);assertEquals(1,provider.deleted);assertFalse(provider.archive.exists());assertTrue(ExportedBackups.list(context).isEmpty());assertEquals(0,ExportedBackups.persistedFlags(context,uri));assertEquals("keep",FilesEx.read(active,100));
        }finally{FilesEx.delete(active);}
    }
    @Test public void unregisteredDocumentsAndMaintenanceCannotRemoveArchives()throws Exception {
        grant();try{ExportedBackups.delete(context,uri);fail("Unregistered document deleted");}catch(IOException expected){}
        ExportedBackups.record(context,uri,"backup");WorkService.busy=true;
        try{ExportedBackups.delete(context,uri);fail("Maintenance guard bypassed");}catch(IOException expected){}finally{WorkService.busy=false;}
        assertTrue(provider.archive.isFile());assertEquals(0,provider.deleted);
    }
    private WorkService.Job exportJob(MainActivity activity)throws Exception {
        java.lang.reflect.Field pending=MainActivity.class.getDeclaredField("pending");pending.setAccessible(true);pending.set(activity,"backup");
        activity.onActivityResult(21,android.app.Activity.RESULT_OK,new Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
        pending=WorkService.class.getDeclaredField("pending");pending.setAccessible(true);WorkService.Job job=(WorkService.Job)pending.get(null);pending.set(null,null);assertNotNull(job);return job;
    }
    @Test public void successfulExportRetainsAccessAfterJobAndActivityClose()throws Exception {
        ActivityController<MainActivity> activity=Robolectric.buildActivity(MainActivity.class).setup();WorkService.Job job=null;
        try {
            job=exportJob(activity.get());assertTrue(job.run(context,s->{}).contains("Export completed"));job.close();
            assertEquals(1,ExportedBackups.list(context).size());assertTrue(ExportedBackups.list(context).get(0).accessible);
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION,ExportedBackups.persistedFlags(context,uri));assertTrue(provider.archive.length()>7);
        }finally{if(job!=null)job.close();WorkService.busy=false;activity.pause().stop().destroy();}
    }
    @Test public void failedExportDoesNotRecordBackupAndReleasesNewGrant()throws Exception {
        ActivityController<MainActivity> activity=Robolectric.buildActivity(MainActivity.class).setup();WorkService.Job job=null;
        try {
            provider.failWrite=true;job=exportJob(activity.get());try{job.run(context,s->{});fail("Export unexpectedly succeeded");}catch(IOException expected){}job.close();
            assertTrue(ExportedBackups.list(context).isEmpty());assertEquals(0,ExportedBackups.persistedFlags(context,uri));
        }finally{if(job!=null)job.close();WorkService.busy=false;activity.pause().stop().destroy();}
    }
    @Test public void cancelledBeforeWorkerStartsDoesNotLeakGrantOrTouchArchive()throws Exception {
        ActivityController<MainActivity> activity=Robolectric.buildActivity(MainActivity.class).setup();WorkService.Job job=null;
        try {
            job=exportJob(activity.get());job.close();assertEquals(0,ExportedBackups.persistedFlags(context,uri));assertTrue(ExportedBackups.list(context).isEmpty());assertEquals("archive",FilesEx.read(provider.archive,100));
        }finally{if(job!=null)job.close();WorkService.busy=false;activity.pause().stop().destroy();}
    }
}
