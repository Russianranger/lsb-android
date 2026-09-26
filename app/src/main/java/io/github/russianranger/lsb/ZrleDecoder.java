package io.github.russianranger.lsb;

import java.io.IOException;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Bounded lossless RFB ZRLE for our negotiated little-endian RGB565/RGB888 formats. */
final class ZrleDecoder implements AutoCloseable {
    // One zlib stream per connection, including across CopyRect/raw frames and resize.
    private final Inflater inflater=new Inflater();
    private byte[] unpacked=new byte[0];
    private final int[] palette=new int[128],tile=new int[64*64];
    private int at,end,pixelBytes;
    static int decodedLimit(int w,int h,boolean rgb565){
        if(w<1||h<1||w>4096||h>2160||(long)w*h>4_194_304)throw new IllegalArgumentException("Invalid ZRLE dimensions");
        int bpp=rgb565?2:3;
        return w*h*(bpp+1)+((w+63)/64)*((h+63)/64)*(1+127*bpp);
    }
    static int compressedLimit(int w,int h,boolean rgb565){return decodedLimit(w,h,rgb565)+65536;}
    void decode(byte[] compressed,int length,int w,int h,boolean rgb565,byte[] packed,int[] argb)throws IOException {
        int limit=decodedLimit(w,h,rgb565);
        if(length<1||length>compressed.length||length>limit+65536)throw new IOException("Invalid ZRLE compressed length");
        if(rgb565?(packed==null||packed.length<w*h*2):(argb==null||argb.length<w*h))throw new IOException("Invalid ZRLE output buffer");
        if(unpacked.length<limit+1)unpacked=new byte[limit+1];
        if(!inflater.needsInput())throw new IOException("Unconsumed ZRLE stream");
        inflater.setInput(compressed,0,length);at=end=0;pixelBytes=rgb565?2:3;
        try{
            while(!inflater.needsInput()){
                int n=inflater.inflate(unpacked,end,limit+1-end);end+=n;
                if(end>limit)throw new IOException("ZRLE expansion exceeds rectangle limits");
                if(inflater.finished()||inflater.needsDictionary())throw new IOException("Unexpected ZRLE stream end or dictionary");
                if(n==0&&!inflater.needsInput())throw new IOException("Stalled ZRLE stream");
            }
        }catch(DataFormatException e){throw new IOException("Invalid ZRLE compression",e);}
        for(int ty=0;ty<h;ty+=64)for(int tx=0;tx<w;tx+=64){
            int tw=Math.min(64,w-tx),th=Math.min(64,h-ty),count=tw*th,mode=u8();
            if(mode==0&&rgb565){
                // High-colour scenes use raw tiles inside zlib: bulk-copy rows,
                // retaining the RGB565 native bitmap path without per-pixel Java work.
                need(count*2);
                for(int row=0;row<th;row++){System.arraycopy(unpacked,at,packed,((ty+row)*w+tx)*2,tw*2);at+=tw*2;}
                continue;
            }
            if(mode==0){for(int n=0;n<count;n++)tile[n]=pixel();}
            else if(mode==1){Arrays.fill(tile,0,count,pixel());}
            else if(mode>=2&&mode<=16){
                for(int n=0;n<mode;n++)palette[n]=pixel();
                int bits=mode==2?1:mode<=4?2:4,mask=(1<<bits)-1,n=0;
                for(int row=0;row<th;row++){
                    int value=0,left=0;
                    for(int col=0;col<tw;col++){
                        if(left==0){value=u8();left=8;}left-=bits;int index=(value>>left)&mask;
                        if(index>=mode)throw new IOException("ZRLE palette index outside palette");
                        tile[n++]=palette[index];
                    }
                }
            }else if(mode==128){
                for(int n=0;n<count;){int colour=pixel(),run=runLength(count-n);Arrays.fill(tile,n,n+run,colour);n+=run;}
            }else if(mode>=130){
                int colours=mode-128;for(int n=0;n<colours;n++)palette[n]=pixel();
                for(int n=0;n<count;){
                    int value=u8(),index=value&127;
                    if(index>=colours)throw new IOException("ZRLE run palette index outside palette");
                    int run=(value&128)==0?1:runLength(count-n);Arrays.fill(tile,n,n+run,palette[index]);n+=run;
                }
            }else throw new IOException("Invalid ZRLE tile mode");
            for(int row=0;row<th;row++){
                int source=row*tw,dest=(ty+row)*w+tx;
                if(rgb565){for(int col=0;col<tw;col++){int value=tile[source+col],pos=(dest+col)*2;packed[pos]=(byte)value;packed[pos+1]=(byte)(value>>8);}}
                else System.arraycopy(tile,source,argb,dest,tw);
            }
        }
        if(at!=end)throw new IOException("Extra data after ZRLE rectangle");
    }
    private int runLength(int remaining)throws IOException {
        int length=1,value;
        do{value=u8();length+=value;if(length>remaining)throw new IOException("ZRLE run exceeds tile");}while(value==255);
        return length;
    }
    private void need(int bytes)throws IOException {if(bytes>end-at)throw new IOException("Truncated ZRLE tile");}
    private int u8()throws IOException {need(1);return unpacked[at++]&255;}
    private int pixel()throws IOException {
        need(pixelBytes);int value=(unpacked[at]&255)|((unpacked[at+1]&255)<<8);at+=2;
        if(pixelBytes==3)value|=0xff000000|((unpacked[at++]&255)<<16);
        return value;
    }
    public void close(){inflater.end();}
}
