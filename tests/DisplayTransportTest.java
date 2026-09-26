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
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void refresh()throws Exception {
        ByteArrayOutputStream replies=new ByteArrayOutputStream();
        replies.write(frame(0,0,3,new byte[6])); // Initial image, before the manual action.
        replies.write(frame(0,0,2,new byte[4]));
        replies.write(frame(1,0,3,new byte[4])); // CopyRect must not prove a fresh image.
        replies.write(frame(0,0,2,new byte[4])); // Overlap must not inflate coverage.
        replies.write(frame(0,2,1,new byte[2]));
        replies.write(frame(0,0,1,new byte[2])); // Normal updates continue afterwards.
        Screen screen=new Screen();ByteArrayOutputStream sent=new ByteArrayOutputStream();
        RfbConnection c=new RfbConnection(new ByteArrayInputStream(replies.toByteArray()),sent,screen,true);c.width=3;c.height=1;
        c.readUpdate();check(sent.size()==10&&sent.toByteArray()[1]==1,"Initial update requests the next incremental frame");
        check(c.refreshDisplay(),"Manual refresh accepted");
        check(sent.size()==20&&sent.toByteArray()[11]==0,"Manual action emits exactly one full-frame request");
        check(!c.refreshDisplay()&&sent.size()==20,"Pending refresh rejects duplicate requests");
        for(int i=0;i<3;i++){c.readUpdate();check(c.stats.refresh(System.nanoTime()).result.equals("pending"),"Partial, copied or overlapping rectangles cannot complete refresh");}
        c.readUpdate();ClientFrameStats.Refresh result=c.stats.refresh(System.nanoTime());
        check(result.count==1&&result.result.equals("full_frame_received")&&result.elapsedMs>=0,"Full received pixel coverage completes the refresh");
        c.readUpdate();check(screen.updates==6&&sent.toByteArray()[sent.size()-9]==1,"Single existing reader continues incremental updates");
        check(c.refreshDisplay(),"Another explicit refresh is allowed after completion");c.close();
        check(c.stats.refresh(System.nanoTime()).result.equals("closed"),"Closing a pending refresh is reported");
        c=new RfbConnection(new ByteArrayInputStream(frame(-223,0,2,new byte[0])),new ByteArrayOutputStream(),new Screen(),true);c.width=3;c.height=1;
        c.refreshDisplay();c.readUpdate();check(c.stats.refresh(System.nanoTime()).result.equals("resized"),"Resize terminates old-size coverage");c.close();
        OutputStream broken=new OutputStream(){public void write(int value)throws IOException{throw new IOException("fixture");}};
        c=new RfbConnection(new ByteArrayInputStream(new byte[0]),broken,new Screen(),true);c.width=3;c.height=1;
        try{c.refreshDisplay();throw new AssertionError("Failed write must propagate");}catch(IOException expected){}
        check(c.stats.refresh(System.nanoTime()).result.equals("write_failed"),"Write failure is a fixed diagnostic result");c.close();
        ClientFrameStats stats=new ClientFrameStats(1);stats.requestedRefresh(1);
        result=stats.refresh(1+ClientFrameStats.REFRESH_TIMEOUT_NS);
        check(result.result.equals("timed_out")&&result.elapsedMs==15000,"Refresh timeout is bounded");
        stats.finishRefresh(Long.MAX_VALUE,"full_frame_received");check(stats.refresh(Long.MAX_VALUE).result.equals("timed_out"),"Late replies cannot overwrite timeout");
        for(int i=0;i<10001;i++)stats.requestedRefresh(i+1);
        check(stats.refresh(10002).count==10000,"Diagnostic count is bounded");
    }
    public static void main(String[] args)throws Exception{
        Screen s=new Screen();RfbConnection c=new RfbConnection(new ByteArrayInputStream(frame(0,0,3,new byte[]{0,(byte)0xf8,(byte)0xe0,7,31,0})),new ByteArrayOutputStream(),s,true);c.width=3;c.height=1;c.readUpdate();
        if(!Arrays.equals(s.pixels,new int[]{0xffff0000,0xff00ff00,0xff0000ff})||s.updates!=1)throw new AssertionError("RGB565 conversion/color order");
        final boolean[] raw={false};Screen nativeScreen=new Screen(){public boolean raw565(int x,int y,int w,int h,byte[] data,int length){raw[0]=length==6&&data[1]==(byte)0xf8;return true;}};
        c=new RfbConnection(new ByteArrayInputStream(frame(0,0,3,new byte[]{0,(byte)0xf8,(byte)0xe0,7,31,0})),new ByteArrayOutputStream(),nativeScreen,true);c.width=3;c.height=1;c.readUpdate();if(!raw[0]||nativeScreen.pixels!=null)throw new AssertionError("Native path did not bypass Java pixel conversion");
        c=new RfbConnection(new ByteArrayInputStream(frame(0,2,3,new byte[6])),new ByteArrayOutputStream(),s,true);c.width=3;c.height=1;try{c.readUpdate();throw new AssertionError("Invalid rectangle accepted");}catch(IOException expected){}
        c=new RfbConnection(new ByteArrayInputStream(frame(0,0,1,new byte[]{1})),new ByteArrayOutputStream(),s,true);c.width=1;c.height=1;try{c.readUpdate();throw new AssertionError("Truncated frame accepted");}catch(IOException expected){}
        refresh();
        System.out.println("PASS: RGB565 colors, native-copy bypass, rectangle bounds, truncated transport and bounded manual full refresh");
    }
}
