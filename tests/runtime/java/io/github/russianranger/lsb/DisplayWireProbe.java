package io.github.russianranger.lsb;

import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;

/** Runs the production Android protocol decoder against the real ARM64 server. */
public final class DisplayWireProbe {
    static final class Screen implements RfbConnection.Screen {
        final boolean fast;int width,height,updates;byte[] frame;
        Screen(boolean fast){this.fast=fast;}
        public void resize(int w,int h){width=w;height=h;frame=new byte[w*h*(fast?2:4)];}
        public boolean raw565(int x,int y,int w,int h,byte[] data,int length){
            if(!fast)throw new AssertionError("Unexpected RGB565");
            for(int row=0;row<h;row++)System.arraycopy(data,row*w*2,frame,((y+row)*width+x)*2,w*2);return true;
        }
        public void pixels(int x,int y,int w,int h,int[] data){
            if(fast)throw new AssertionError("Fast copy was bypassed");
            for(int row=0;row<h;row++)for(int col=0;col<w;col++){
                int pos=((y+row)*width+x+col)*4,value=data[row*w+col];
                for(int b=0;b<4;b++)frame[pos+b]=(byte)(value>>(8*b));
            }
        }
        public void copy(int x,int y,int w,int h,int sx,int sy){
            int bytes=fast?2:4;byte[] saved=new byte[w*h*bytes];
            for(int row=0;row<h;row++)System.arraycopy(frame,((sy+row)*width+sx)*bytes,saved,row*w*bytes,w*bytes);
            for(int row=0;row<h;row++)System.arraycopy(saved,row*w*bytes,frame,((y+row)*width+x)*bytes,w*bytes);
        }
        public void updated(){updates++;}
        long crc(){CRC32 crc=new CRC32();crc.update(frame);return crc.getValue();}
    }
    public static void main(String[] args)throws Exception {
        for(boolean fast:new boolean[]{true,false}){
            long expected=-1;double rawBytes=0;
            for(boolean compressed:new boolean[]{false,true}){
                Screen screen=new Screen(fast);
                try(SocketChannel socket=SocketChannel.open(StandardProtocolFamily.UNIX)){
                    socket.connect(UnixDomainSocketAddress.of(args[0]));
                    RfbConnection c=new RfbConnection(Channels.newInputStream(socket),Channels.newOutputStream(socket),screen,fast,compressed);
                    try{
                        c.handshake(false);if(c.width!=1280||c.height!=720)throw new AssertionError("Unexpected fixture size");
                        for(int frame=0;frame<3;frame++){
                            int before=screen.updates;c.request(false);
                            while(screen.updates==before)c.readUpdate();
                            long crc=screen.crc();if(expected<0)expected=crc;
                            if(crc!=expected)throw new AssertionError("Raw/ZRLE pixels changed, fast="+fast+" frame="+frame);
                        }
                        double[] s=c.stats.sample(System.nanoTime()).values;
                        if(compressed){
                            if(s[22]<1)throw new AssertionError("Server never selected ZRLE");
                            if(s[21]>=rawBytes/2)throw new AssertionError("Compression did not reduce fixture transfer by at least half");
                        }else rawBytes=s[21];
                        System.out.println("PASS: actual server "+(fast?"RGB565":"RGB888")+" "+(compressed?"ZRLE":"raw")+" crc="+expected+" payload_bytes="+(long)s[21]+" zrle_rectangles="+(long)s[22]+" raw_rectangles="+(long)s[23]+" decode_ms="+s[4]);
                    }finally{c.close();}
                }
            }
        }
    }
}
