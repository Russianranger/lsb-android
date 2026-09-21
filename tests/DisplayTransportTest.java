package io.github.russianranger.lsb;
import java.io.*;
import java.util.*;

public class DisplayTransportTest {
    static class Screen implements RfbConnection.Screen {
        int[] pixels;int updates;
        public void resize(int w,int h){}
        public void pixels(int x,int y,int w,int h,int[] values){pixels=Arrays.copyOf(values,w*h);}
        public void copy(int x,int y,int w,int h,int sx,int sy){}
        public void updated(){updates++;}
    }
    static byte[] frame(int encoding,int x,int w,byte[] pixels)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeByte(0);out.writeByte(0);out.writeShort(1);out.writeShort(x);out.writeShort(0);out.writeShort(w);out.writeShort(1);out.writeInt(encoding);out.write(pixels);return bytes.toByteArray();
    }
    public static void main(String[] args)throws Exception{
        Screen s=new Screen();RfbConnection c=new RfbConnection(new ByteArrayInputStream(frame(0,0,3,new byte[]{0,(byte)0xf8,(byte)0xe0,7,31,0})),new ByteArrayOutputStream(),s,true);c.width=3;c.height=1;c.readUpdate();
        if(!Arrays.equals(s.pixels,new int[]{0xffff0000,0xff00ff00,0xff0000ff})||s.updates!=1)throw new AssertionError("RGB565 conversion/color order");
        final boolean[] raw={false};Screen nativeScreen=new Screen(){public boolean raw565(int x,int y,int w,int h,byte[] data,int length){raw[0]=length==6&&data[1]==(byte)0xf8;return true;}};
        c=new RfbConnection(new ByteArrayInputStream(frame(0,0,3,new byte[]{0,(byte)0xf8,(byte)0xe0,7,31,0})),new ByteArrayOutputStream(),nativeScreen,true);c.width=3;c.height=1;c.readUpdate();if(!raw[0]||nativeScreen.pixels!=null)throw new AssertionError("Native path did not bypass Java pixel conversion");
        c=new RfbConnection(new ByteArrayInputStream(frame(0,2,3,new byte[6])),new ByteArrayOutputStream(),s,true);c.width=3;c.height=1;try{c.readUpdate();throw new AssertionError("Invalid rectangle accepted");}catch(IOException expected){}
        c=new RfbConnection(new ByteArrayInputStream(frame(0,0,1,new byte[]{1})),new ByteArrayOutputStream(),s,true);c.width=1;c.height=1;try{c.readUpdate();throw new AssertionError("Truncated frame accepted");}catch(IOException expected){}
        System.out.println("PASS: RGB565 colors, native-copy bypass, rectangle bounds and truncated transport");
    }
}
