package io.github.russianranger.lsb;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE,qualifiers="w920dp-h520dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FantasyTilesTest {
    private static void layout(View view,int width){
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        view.layout(0,0,width,view.getMeasuredHeight());
    }
    private static TextView text(View v,String prefix){
        if(v instanceof TextView&&((TextView)v).getText().toString().startsWith(prefix))return (TextView)v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){TextView found=text(((ViewGroup)v).getChildAt(i),prefix);if(found!=null)return found;}
        return null;
    }
    private static void capture(View view,String name)throws Exception{
        File output=new File("out/ui-previews",name);output.getParentFile().mkdirs();
        Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(bitmap);canvas.drawColor(0xff0c141f);view.draw(canvas);
        try(OutputStream stream=new FileOutputStream(output)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,stream));}bitmap.recycle();
    }
    @Test public void panelsSpanTheirRowAndPreserveEditingAcrossCollapseAndResize(){
        Context ctx=RuntimeEnvironment.getApplication();String[] selected={""};
        FantasyTiles tiles=new FantasyTiles(ctx,"",value->selected[0]=value);
        EditText field=new EditText(ctx);field.setText("unsaved form value");
        for(int i=0;i<5;i++)tiles.addSection("Section "+i,i==1?field:new TextView(ctx));
        layout(tiles,920);assertEquals(2,tiles.getChildCount());
        assertEquals(3,((ViewGroup)tiles.getChildAt(0)).getChildCount());
        text(tiles,"◇  Section 1").performClick();layout(tiles,920);
        assertEquals("Section 1",selected[0]);assertSame(field,tiles.getChildAt(1));assertTrue(field.getWidth()>880);
        text(tiles,"◇  Section 1").performClick();layout(tiles,920);assertNull(field.getParent());
        text(tiles,"◇  Section 1").performClick();layout(tiles,400);
        assertEquals(2,((ViewGroup)tiles.getChildAt(0)).getChildCount());assertSame(field,tiles.getChildAt(1));
        assertEquals("unsaved form value",field.getText().toString());
        text(tiles,"◇  Section 4").performClick();layout(tiles,400);assertNull(field.getParent());
        assertEquals("Section 4",selected[0]);
        assertEquals(1,FantasyTiles.columnsFor(360,1.5f));assertEquals(2,FantasyTiles.columnsFor(920,1.5f));
    }
    @Test public void realLauncherGroupsControlsWithoutChangingSavedRuntimeChoices()throws Exception{
        Context ctx=RuntimeEnvironment.getApplication();Field instance=ClientRuntime.class.getDeclaredField("instance");instance.setAccessible(true);instance.set(null,null);
        ClientRuntime runtime=ClientRuntime.get(ctx);File state=new File(runtime.home,"clients/state.properties");state.getParentFile().mkdirs();
        Files.write(state.toPath(),"current=12345678-1234-1234-1234-123456789abc\n".getBytes("UTF-8"));
        android.content.SharedPreferences prefs=ctx.getSharedPreferences("runtime",0);
        prefs.edit().putBoolean("fex",true).putBoolean("fex_x87",true).putBoolean("dxvk_271",true).putBoolean("turnip_sysmem",false).putBoolean("dxvk_two_compilers",false).putInt("display_fps",60).commit();
        java.util.Map<String,?> before=prefs.getAll();
        org.robolectric.android.controller.ActivityController<MainActivity> activity=Robolectric.buildActivity(MainActivity.class).setup();
        try{
            Field f=MainActivity.class.getDeclaredField("tiles");f.setAccessible(true);FantasyTiles tiles=(FantasyTiles)f.get(activity.get());
            layout(tiles,920);assertNotNull(text(tiles,"◇  Proven fixes"));assertNotNull(text(tiles,"◇  Past experiments"));
            assertNotNull(text(tiles,"◇  Export support ZIP"));
            capture(tiles,"client-tiles-wide.png");
            text(tiles,"◇  Proven fixes").performClick();layout(tiles,920);
            assertTrue(((CheckBox)text(tiles,"FEX / native")).isChecked());
            assertTrue(((CheckBox)text(tiles,"DXVK 2.7.1")).isChecked());
            assertNull(text(tiles,"Turnip system-memory rendering"));
            assertFalse(((CheckBox)text(tiles,"Two shader compiler workers")).isChecked());
            capture(tiles,"proven-fixes-wide.png");
            text(tiles,"◇  Export support ZIP").performClick();
            android.content.Intent export=org.robolectric.Shadows.shadowOf(activity.get()).getNextStartedActivityForResult().intent;
            assertEquals(android.content.Intent.ACTION_CREATE_DOCUMENT,export.getAction());assertEquals("application/zip",export.getType());assertEquals("lsb-support.zip",export.getStringExtra(android.content.Intent.EXTRA_TITLE));
            assertNotNull(text(tiles,"FEX / native"));
            text(tiles,"◇  Past experiments").performClick();layout(tiles,400);
            assertFalse(((CheckBox)text(tiles,"Turnip system-memory rendering")).isChecked());
            assertNull(text(tiles,"Two shader compiler workers"));
            assertNull(text(tiles,"FEX / native"));assertEquals(before,prefs.getAll());
            capture(tiles,"past-experiments-narrow.png");
            text(tiles,"◇  Graphics & display").performClick();layout(tiles,920);
            CheckBox borderless=(CheckBox)text(tiles,"Remove game window borders");assertTrue(borderless.isChecked());
            borderless.performClick();assertFalse(prefs.getBoolean("borderless",true));
            text(tiles,"◇  Past experiments").performClick();layout(tiles,920);
            CheckBox staged=(CheckBox)text(tiles,"Staged geometry uploads");assertFalse(staged.isChecked());
            staged.performClick();assertTrue(prefs.getBoolean("dxvk_staged_buffers",false));
            ((CheckBox)text(tiles,"Turnip system-memory rendering")).performClick();assertTrue(prefs.getBoolean("turnip_sysmem",false));
            capture(tiles,"past-experiments-wide.png");
            text(tiles,"◇  Optimization trials").performClick();layout(tiles,920);
            Spinner trial=findTrial(tiles);assertNotNull(trial);assertEquals(0,trial.getSelectedItemPosition());
            String[] values={"none","one_compiler","cached_dynamic","gpl_fast","syscall_filter"};assertEquals(values.length,trial.getCount());
            for(int i=1;i<values.length;i++){
                trial.setSelection(i);org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
                assertEquals(values[i],prefs.getString("performance_trial","none"));
                org.json.JSONObject selected=new org.json.JSONObject();runtime.applyGraphicsSettings(selected);assertEquals(values[i],selected.getString("performance_trial"));
                assertNotNull(text(tiles,new String[]{"","A uses one compiler worker.","D places dynamic geometry buffers","E skips background compilation","F enables syscall filtering"}[i]));
                layout(tiles,920);capture(tiles,"optimization-trial-"+values[i]+"-wide.png");
                layout(tiles,400);capture(tiles,"optimization-trial-"+values[i]+"-narrow.png");
            }
            layout(tiles,920);capture(tiles,"optimization-trials-wide.png");layout(tiles,400);capture(tiles,"optimization-trials-narrow.png");
            text(tiles,"◇  Proven fixes").performClick();layout(tiles,920);
            text(tiles,"Use tested shader settings").performClick();
            assertSame("Applying a profile must preserve the current form",tiles,f.get(activity.get()));layout(tiles,920);
            assertTrue(prefs.getBoolean("dxvk_two_compilers",false));assertEquals("none",prefs.getString("performance_trial",""));assertEquals(0,trial.getSelectedItemPosition());
            assertFalse(prefs.getBoolean("turnip_sysmem",true));assertFalse(prefs.getBoolean("dxvk_staged_buffers",true));
            assertFalse(prefs.getBoolean("borderless",true));assertTrue(prefs.getBoolean("fex",false));
            assertTrue(prefs.getBoolean("fex_x87",false));assertTrue(prefs.getBoolean("dxvk_271",false));assertEquals(60,prefs.getInt("display_fps",0));
            org.json.JSONObject request=new org.json.JSONObject();runtime.applyGraphicsSettings(request);
            assertTrue(request.getBoolean("dxvk_two_compilers"));assertFalse(request.getBoolean("turnip_sysmem"));assertFalse(request.getBoolean("dxvk_staged_buffers"));
            assertTrue(((CheckBox)text(tiles,"Two shader compiler workers")).isChecked());
            capture(tiles,"tested-shader-settings-wide.png");
            android.widget.LinearLayout page=(android.widget.LinearLayout)((android.view.ViewGroup)activity.get().findViewById(android.R.id.content)).getChildAt(0);
            // The exact navigation label is a button; the version subtitle starts similarly.
            java.util.ArrayList<android.widget.Button> tabs=new java.util.ArrayList<>();collectTabs(page,tabs);assertEquals(6,tabs.size());
            for(android.widget.Button tab:tabs)assertTrue(tab.getBackground() instanceof android.graphics.drawable.RippleDrawable);
            page.measure(View.MeasureSpec.makeMeasureSpec(920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(620,View.MeasureSpec.EXACTLY));page.layout(0,0,920,620);capture(page,"launcher-tabs-wide.png");
        }finally{activity.pause().stop().destroy();Files.deleteIfExists(state.toPath());prefs.edit().clear().commit();instance.set(null,null);}
    }
    private static Spinner findTrial(View v){
        if(v instanceof Spinner&&"Performance trial".contentEquals(v.getContentDescription()))return (Spinner)v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){Spinner found=findTrial(((ViewGroup)v).getChildAt(i));if(found!=null)return found;}return null;
    }
    private static void collectTabs(View v,java.util.List<android.widget.Button> result){
        if(v instanceof android.widget.Button&&java.util.Arrays.asList("Client","Controller","Server","Runtime","Profile","Diagnostics").contains(((android.widget.Button)v).getText().toString()))result.add((android.widget.Button)v);
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)collectTabs(((ViewGroup)v).getChildAt(i),result);
    }
}
