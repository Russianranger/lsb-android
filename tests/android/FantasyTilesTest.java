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
        prefs.edit().putBoolean("fex",true).putBoolean("fex_x87",true).putBoolean("dxvk_271",true).putBoolean("turnip_sysmem",false).putInt("display_fps",60).commit();
        java.util.Map<String,?> before=prefs.getAll();
        org.robolectric.android.controller.ActivityController<MainActivity> activity=Robolectric.buildActivity(MainActivity.class).setup();
        try{
            Field f=MainActivity.class.getDeclaredField("tiles");f.setAccessible(true);FantasyTiles tiles=(FantasyTiles)f.get(activity.get());
            layout(tiles,920);assertNotNull(text(tiles,"◇  Proven fixes"));assertNotNull(text(tiles,"◇  Past experiments"));
            capture(tiles,"client-tiles-wide.png");
            text(tiles,"◇  Proven fixes").performClick();layout(tiles,920);
            assertTrue(((CheckBox)text(tiles,"FEX / native")).isChecked());
            assertTrue(((CheckBox)text(tiles,"DXVK 2.7.1")).isChecked());
            assertNull(text(tiles,"Turnip system-memory rendering"));
            capture(tiles,"proven-fixes-wide.png");
            text(tiles,"◇  Past experiments").performClick();layout(tiles,400);
            assertFalse(((CheckBox)text(tiles,"Turnip system-memory rendering")).isChecked());
            assertNull(text(tiles,"FEX / native"));assertEquals(before,prefs.getAll());
            capture(tiles,"past-experiments-narrow.png");
        }finally{activity.pause().stop().destroy();Files.deleteIfExists(state.toPath());prefs.edit().clear().commit();instance.set(null,null);}
    }
}
