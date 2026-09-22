package io.github.russianranger.lsb;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import java.nio.ByteBuffer;

/** Software-only transfer scratch; never handed to a View or hardware Canvas. */
final class Rgb565Staging {
    private final int width,height;
    private Bitmap tile;
    private byte[] wrapped,padded;
    private ByteBuffer bytes,paddedBytes;
    long allocations,allocatedBytes;
    Rgb565Staging(int width,int height){this.width=width;this.height=height;}
    boolean apply(Canvas target,int x,int y,int w,int h,byte[] data,int length){
        if(w<1||h<1||w>width||h>height||length!=w*h*2||length>data.length)throw new IllegalArgumentException("Invalid RGB565 rectangle");
        if(target.isHardwareAccelerated())throw new IllegalArgumentException("Transfer bitmap requires a synchronous software Canvas");
        if(tile==null){
            tile=Bitmap.createBitmap(width,height,Bitmap.Config.RGB_565);
            allocations++;allocatedBytes+=tile.getAllocationByteCount();
        }
        // The previous software draw has finished. Only this staging bitmap changes
        // shape; the framebuffer queued by View.onDraw is never reconfigured.
        if(tile.getWidth()!=w||tile.getHeight()!=h)tile.reconfigure(w,h,Bitmap.Config.RGB_565);
        int stride=tile.getRowBytes();ByteBuffer source;
        if(stride==w*2){
            if(wrapped!=data){wrapped=data;bytes=ByteBuffer.wrap(data);}
            source=bytes;source.limit(length);source.position(0);
        }else{
            int size=stride*h;
            if(padded==null||padded.length<size){padded=new byte[size];paddedBytes=ByteBuffer.wrap(padded);}
            for(int row=0;row<h;row++)System.arraycopy(data,row*w*2,padded,row*stride,w*2);
            source=paddedBytes;source.limit(size);source.position(0);
        }
        tile.copyPixelsFromBuffer(source);target.drawBitmap(tile,x,y,null);return true;
    }
    int retainedBytes(){return tile==null?0:tile.getAllocationByteCount();}
    void close(){if(tile!=null){tile.recycle();tile=null;}wrapped=padded=null;bytes=paddedBytes=null;}
}
