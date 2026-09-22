package io.github.russianranger.lsb;

import java.io.*;
import java.util.*;
import java.util.zip.Deflater;

/** Independent wire fixtures for every ZRLE tile family and malformed streams. */
public final class ZrleTest {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static byte[] compress(Deflater z,byte[] input){
        z.setInput(input);ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
        do{n=z.deflate(buffer,0,buffer.length,Deflater.SYNC_FLUSH);out.write(buffer,0,n);}while(n==buffer.length||!z.needsInput());
        return out.toByteArray();
    }
    static int colour(int n,boolean fast){return fast?((n*379+173)&65535):(0xff000000|((n*37917+379)&0xffffff));}
    static void pixel(ByteArrayOutputStream out,int colour,boolean fast){out.write(colour);out.write(colour>>8);if(!fast)out.write(colour>>16);}
    static void run(ByteArrayOutputStream out,int count){int n=count-1;while(n>=255){out.write(255);n-=255;}out.write(n);}
    static byte[] fixture(int w,int h,int mode,boolean fast,int[] expected){
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for(int y=0;y<h;y+=64)for(int x=0;x<w;x+=64){
            int tw=Math.min(64,w-x),th=Math.min(64,h-y),count=tw*th;out.write(mode);
            int colours=mode>=130?mode-128:mode;
            if(mode>=1&&mode<=16||mode>=130)for(int i=0;i<colours;i++)pixel(out,colour(i,fast),fast);
            if(mode==0){for(int row=0;row<th;row++)for(int col=0;col<tw;col++){int v=colour((y+row)*w+x+col,fast);pixel(out,v,fast);expected[(y+row)*w+x+col]=v;}}
            else if(mode==1){for(int row=0;row<th;row++)Arrays.fill(expected,(y+row)*w+x,(y+row)*w+x+tw,colour(0,fast));}
            else if(mode<=16){
                int bits=mode==2?1:mode<=4?2:4;
                for(int row=0;row<th;row++){
                    int packed=0,used=0;
                    for(int col=0;col<tw;col++){
                        int i=(row+col)%mode;expected[(y+row)*w+x+col]=colour(i,fast);packed=(packed<<bits)|i;used+=bits;
                        if(used==8){out.write(packed);packed=used=0;}
                    }
                    if(used!=0)out.write(packed<<(8-used));
                }
            }else{
                if(mode==128){pixel(out,colour(0,fast),fast);run(out,count);}
                else if(count==1)out.write(colours-1);
                else{out.write(128);run(out,count-1);out.write(colours-1);}
                for(int row=0;row<th;row++)for(int col=0;col<tw;col++)expected[(y+row)*w+x+col]=colour(mode>=130&&row*tw+col==count-1?colours-1:0,fast);
            }
        }
        return out.toByteArray();
    }
    static void verify(ZrleDecoder decoder,Deflater compressor,int w,int h,int mode,boolean fast)throws Exception {
        int[] expected=new int[w*h],actual=new int[w*h];byte[] packed=new byte[w*h*2];
        byte[] compressed=compress(compressor,fixture(w,h,mode,fast,expected));decoder.decode(compressed,compressed.length,w,h,fast,packed,actual);
        if(fast)for(int i=0;i<actual.length;i++)actual[i]=(packed[i*2]&255)|((packed[i*2+1]&255)<<8);
        check(Arrays.equals(expected,actual),"Pixel mismatch: mode="+mode+" fast="+fast+" size="+w+"x"+h);
    }
    static void reject(byte[] data,int w,int h,String why)throws Exception {
        Deflater compressor=new Deflater(1);try(ZrleDecoder decoder=new ZrleDecoder()){
            byte[] z=compress(compressor,data);
            try{decoder.decode(z,z.length,w,h,true,new byte[w*h*2],null);throw new AssertionError("Accepted "+why);}catch(IOException expected){checks++;}
        }finally{compressor.end();}
    }
    static byte[] response(int encoding,int w,int h,byte[] data)throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(b);
        out.writeByte(0);out.writeByte(0);out.writeShort(1);out.writeShort(0);out.writeShort(0);out.writeShort(w);out.writeShort(h);out.writeInt(encoding);if(encoding==16)out.writeInt(data.length);out.write(data);return b.toByteArray();
    }
    static void protocol()throws Exception {
        ByteArrayOutputStream hello=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(hello);
        out.writeBytes("RFB 003.008\n");out.writeByte(1);out.writeByte(1);out.writeInt(0);out.writeShort(3);out.writeShort(1);out.write(new byte[16]);out.writeInt(0);
        for(boolean compressed:new boolean[]{false,true}){
            ByteArrayOutputStream sent=new ByteArrayOutputStream();RfbConnection c=new RfbConnection(new ByteArrayInputStream(hello.toByteArray()),sent,new DisplayTransportTest.Screen(),true,compressed);c.handshake(false);
            DataInputStream messages=new DataInputStream(new ByteArrayInputStream(sent.toByteArray()));messages.skipBytes(14+20);
            check(messages.readUnsignedByte()==2,"SetEncodings");messages.readByte();int count=messages.readUnsignedShort();
            check(count==(compressed?4:3),"Encoding count");check(messages.readInt()==(compressed?16:0),"Preferred encoding/fallback");
            messages.skipBytes((count-1)*4);check(messages.available()==0,"Input-only handshake must not request pixels");
            sent.reset();c.key(0xff1b,true);check(sent.size()==8,"Input-only connection retains keyboard");
            sent.reset();c.startFrames();check(sent.size()==10&&sent.toByteArray()[0]==3&&sent.toByteArray()[1]==0,"Fallback requests a full framebuffer");c.close();
        }
        Deflater z=new Deflater(1);
        try{
            byte[] first=compress(z,new byte[]{0,0,(byte)0xf8,(byte)0xe0,7,31,0});
            byte[] next=compress(z,new byte[]{1,(byte)0xff,(byte)0xff});
            ByteArrayOutputStream replies=new ByteArrayOutputStream();replies.write(response(16,3,1,first));replies.write(response(0,3,1,new byte[]{31,0,31,0,31,0}));replies.write(response(-223,1,1,new byte[0]));replies.write(response(16,1,1,next));
            DisplayTransportTest.Screen screen=new DisplayTransportTest.Screen();ByteArrayOutputStream sent=new ByteArrayOutputStream();
            RfbConnection c=new RfbConnection(new ByteArrayInputStream(replies.toByteArray()),sent,screen,true,true);c.width=3;c.height=1;
            try{
                c.readUpdate();check(Arrays.equals(screen.pixels,new int[]{0xffff0000,0xff00ff00,0xff0000ff}),"ZRLE through actual RGB565 fallback");
                c.readUpdate();check(screen.pixels[0]==0xff0000ff,"Raw fallback within compressed connection");
                c.readUpdate();check(sent.toByteArray()[21]==0,"Resize must request full new framebuffer");
                c.readUpdate();check(screen.pixels[0]==-1,"Persistent stream after raw and resize");
                double[] stats=c.stats.sample(System.nanoTime()).values;check(stats[22]==2&&stats[23]==1,"Actual encoding counts");check(stats[21]==first.length+next.length+8+6,"Actual encoded payload bytes");
            }finally{c.close();}
        }finally{z.end();}
        ByteArrayOutputStream bad=new ByteArrayOutputStream();bad.write(response(16,1,1,new byte[]{1}));byte[] bytes=bad.toByteArray();Arrays.fill(bytes,16,20,(byte)0x7f);
        RfbConnection c=new RfbConnection(new ByteArrayInputStream(bytes),new ByteArrayOutputStream(),new DisplayTransportTest.Screen(),true,true);c.width=c.height=1;
        try{c.readUpdate();throw new AssertionError("Oversized compressed payload accepted");}catch(IOException expected){checks++;}finally{c.close();}
    }
    public static void main(String[] args)throws Exception {
        for(boolean fast:new boolean[]{true,false}){
            Deflater compressor=new Deflater(1);
            try(ZrleDecoder decoder=new ZrleDecoder()){
                for(int mode:new int[]{0,1,2,3,4,5,16,128,130,255}){
                    verify(decoder,compressor,65,67,mode,fast);verify(decoder,compressor,1,1,mode,fast);verify(decoder,compressor,17,3,mode,fast);
                }
                verify(decoder,compressor,1280,720,0,fast);
            }finally{compressor.end();}
        }
        reject(new byte[]{17},1,1,"reserved mode");reject(new byte[]{(byte)129},1,1,"reserved RLE mode");
        reject(new byte[]{0,1},1,1,"truncated pixel");reject(new byte[]{1,1,0,42},1,1,"trailing decompressed data");
        reject(new byte[]{(byte)128,1,0,1},1,1,"run overflow");
        reject(new byte[]{3,1,0,2,0,3,0,(byte)0xc0},1,1,"packed palette overflow");
        reject(new byte[]{(byte)130,1,0,2,0,2},1,1,"run palette overflow");
        reject(new byte[ZrleDecoder.decodedLimit(1,1,true)+1],1,1,"excess inflation");
        try(ZrleDecoder decoder=new ZrleDecoder()){
            try{decoder.decode(new byte[]{1,2,3,4,5},5,1,1,true,new byte[2],null);throw new AssertionError("Corrupt zlib accepted");}catch(IOException expected){checks++;}
        }
        protocol();System.out.println("PASS: "+checks+" ZRLE pixel, persistent stream, fallback, resize and malformed-input checks");
    }
}
