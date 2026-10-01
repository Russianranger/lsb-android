package io.github.russianranger.lsb;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Native Skia pixel/copy checks, including the changing-rectangle allocation regression. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class DisplayBitmapTest {
    private byte[] solid(int w,int h,int value){byte[] b=new byte[w*h*2];for(int i=0;i<b.length;i+=2){b[i]=(byte)value;b[i+1]=(byte)(value>>8);}return b;}

    @Test public void changingRectangleSizesReuseOneAllocationAndPreservePixels(){
        Bitmap frame=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(frame);
        Rgb565Staging stage=new Rgb565Staging(1280,720);
        int[][] sizes={{1280,720},{1260,700},{1240,680},{1220,660}};
        byte[][] payloads=new byte[4][];for(int i=0;i<4;i++)payloads[i]=solid(sizes[i][0],sizes[i][1],i%2==0?0xf800:0x07e0);
        try{
            for(int i=0;i<120;i++){int slot=i%4;assertTrue(stage.apply(canvas,0,0,sizes[slot][0],sizes[slot][1],payloads[slot],payloads[slot].length));}
            assertEquals(0xff00ff00,frame.getPixel(0,0));assertEquals(0xffff0000,frame.getPixel(1279,719));
            System.out.println("Changing rectangle workload: bitmap allocations="+stage.allocations+", allocated bytes="+stage.allocatedBytes);
            assertEquals("Size changes must reuse the transfer allocation",1,stage.allocations);
            assertEquals(1280*720*2,stage.retainedBytes());
        }finally{stage.close();frame.recycle();}
    }

    @Test public void oddWidthsSmallRectanglesAndReconfigurationKeepEveryPixel(){
        Bitmap frame=Bitmap.createBitmap(13,7,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(frame);
        Rgb565Staging stage=new Rgb565Staging(13,7);
        try{
            for(int h=1;h<=7;h++)for(int w=1;w<=13;w++){
                frame.eraseColor(0xff0000ff);byte[] data=solid(w,h,0xf800);
                assertTrue("Odd widths must stay on native copy",stage.apply(canvas,13-w,7-h,w,h,data,data.length));
                for(int y=0;y<7;y++)for(int x=0;x<13;x++)assertEquals(x>=13-w&&y>=7-h?0xffff0000:0xff0000ff,frame.getPixel(x,y));
            }
        }finally{stage.close();frame.recycle();}
    }
}
