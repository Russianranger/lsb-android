package io.github.russianranger.lsb;

import android.app.AlertDialog;
import android.content.*;
import android.graphics.*;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import io.github.russianranger.lsb.core.FilesEx;
import java.io.*;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE,qualifiers="w920dp-h520dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ServerPageTest {
    private static TextView text(View v,String prefix){
        if(v instanceof TextView&&((TextView)v).getText().toString().startsWith(prefix))return (TextView)v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){TextView found=text(((ViewGroup)v).getChildAt(i),prefix);if(found!=null)return found;}return null;
    }
    private static EditText field(View v,String description){
        if(v instanceof EditText&&description.contentEquals(v.getContentDescription()))return (EditText)v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){EditText found=field(((ViewGroup)v).getChildAt(i),description);if(found!=null)return found;}return null;
    }
    private static void capture(View v,int width,String name)throws Exception{
        v.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));v.layout(0,0,width,v.getMeasuredHeight());
        Bitmap b=Bitmap.createBitmap(width,v.getHeight(),Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);c.drawColor(0xff0c141f);v.draw(c);
        File target=new File("out/ui-previews",name);target.getParentFile().mkdirs();try(OutputStream out=new FileOutputStream(target)){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,out));}b.recycle();
    }
    @Test public void serverControlsSeparateAccountsRestoreAndLaterUpdatesAndClearSecrets()throws Exception{
        Context ctx=RuntimeEnvironment.getApplication();Field singleton=ServerRuntime.class.getDeclaredField("instance");singleton.setAccessible(true);singleton.set(null,null);
        ServerRuntime sr=ServerRuntime.get(ctx);String id="11111111-1111-1111-1111-111111111111";
        FilesEx.text(new File(sr.root,"lsb-server-ready"),"ready");FilesEx.text(new File(sr.root,"lsb-server-tools-v4"),"ready");
        FilesEx.text(new File(sr.state,"active.json"),"{\"current\":\""+id+"\"}");
        FilesEx.text(new File(sr.state,"generations/"+id+"/deployment.json"),"{\"generation\":\""+id+"\",\"accounts\":1,\"characters\":1,\"expected_client\":\"30251204_1\"}");
        FilesEx.text(new File(sr.state,"import.sql"),"a complete staged fixture dump");
        SharedPreferences prefs=ctx.getSharedPreferences("runtime",0);prefs.edit().putBoolean("proot_acceleration",true).putBoolean("dxvk_two_compilers",true).commit();Map<String,?> before=prefs.getAll();
        org.robolectric.android.controller.ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class,new Intent(ctx,MainActivity.class).putExtra("tab","Server")).setup();
        try{
            Field tileField=MainActivity.class.getDeclaredField("tiles");tileField.setAccessible(true);FantasyTiles tiles=(FantasyTiles)tileField.get(controller.get());
            assertNotNull(text(tiles,"◇  Import your working server"));assertNotNull(text(tiles,"◇  Create account"));assertNotNull(text(tiles,"◇  Database backup & restore"));assertNotNull(text(tiles,"◇  Source updates · later"));
            capture(tiles,920,"server-tiles-wide.png");
            text(tiles,"◇  Create account").performClick();
            assertTrue(text(tiles,"Create player account").isEnabled());
            EditText user=field(tiles,"New server account name"),password=field(tiles,"New server account password"),confirm=field(tiles,"Confirm server account password");
            assertNotNull(user);assertNotNull(password);assertNotNull(confirm);assertFalse(password.isSaveEnabled());assertFalse(confirm.isSaveEnabled());
            capture(tiles,920,"server-accounts-wide.png");capture(tiles,400,"server-accounts-narrow.png");
            user.setText("test account");password.setText("private-password");confirm.setText("different");text(tiles,"Create player account").performClick();
            AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);assertTrue(((TextView)dialog.findViewById(android.R.id.message)).getText().toString().contains("do not match"));dialog.dismiss();assertFalse(WorkService.busy);
            controller.pause();assertEquals("",password.getText().toString());assertEquals("",confirm.getText().toString());controller.resume();
            text(tiles,"◇  Database backup & restore").performClick();capture(tiles,920,"server-database-wide.png");capture(tiles,400,"server-database-narrow.png");
            text(tiles,"Restore imported database").performClick();dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            String message=((TextView)dialog.findViewById(android.R.id.message)).getText().toString();assertTrue(message.contains("currently deployed server"));assertTrue(message.contains("does not fetch source"));dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();assertFalse(WorkService.busy);
            assertEquals(before,prefs.getAll());
        }finally{controller.pause().stop().destroy();FilesEx.delete(sr.home);prefs.edit().clear().commit();singleton.set(null,null);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    }
    @Test public void installedRuntimeOffersDependencyUpgradeBeforeDeployment()throws Exception{
        Context ctx=RuntimeEnvironment.getApplication();Field singleton=ServerRuntime.class.getDeclaredField("instance");singleton.setAccessible(true);singleton.set(null,null);
        ServerRuntime sr=ServerRuntime.get(ctx);FilesEx.delete(sr.home);
        FilesEx.text(new File(sr.root,"lsb-server-ready"),"ready");FilesEx.text(new File(sr.root,"lsb-server-tools-v3"),"old tools");
        FilesEx.text(new File(sr.state,"import.sql"),"staged SQL fixture");
        File report=new File(MainActivity.storage(ctx),"server/current/source-report.txt");FilesEx.text(report,"matching server fixture");
        org.robolectric.android.controller.ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class,new Intent(ctx,MainActivity.class).putExtra("tab","Server")).setup();
        try{
            Field tileField=MainActivity.class.getDeclaredField("tiles");tileField.setAccessible(true);FantasyTiles tiles=(FantasyTiles)tileField.get(controller.get());
            text(tiles,"◇  Import your working server").performClick();
            assertTrue(text(tiles,"Update server runtime and build tools").isEnabled());
            assertFalse(text(tiles,"Deploy matching server + database").isEnabled());
            assertFalse(text(tiles,"Build imported revision and deploy").isEnabled());
            assertNotNull(text(tiles,"Update the server runtime to add libraries required by your imported server."));
            capture(tiles,920,"server-runtime-update-wide.png");capture(tiles,400,"server-runtime-update-narrow.png");
        }finally{controller.pause().stop().destroy();FilesEx.delete(sr.home);report.delete();singleton.set(null,null);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    }
}
