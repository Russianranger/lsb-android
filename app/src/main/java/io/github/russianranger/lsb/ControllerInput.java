package io.github.russianranger.lsb;

import android.content.*;
import android.view.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;

/** Physical Android controller -> app-owned virtual DirectInput joystick. */
final class ControllerInput implements Closeable {
    static final String[] NAMES={"A","B","X","Y","LB","RB","Select","Start","L3","R3","LT","RT","D-pad up","D-pad right","D-pad down","D-pad left"};
    static final int[] KEYS={96,97,99,100,102,103,109,108,106,107,104,105,19,22,20,21};
    private final SharedPreferences prefs;
    private final boolean[] down=new boolean[16];
    private final float[] axes=new float[4];
    private MappedByteBuffer map;
    private RandomAccessFile file;
    private int sequence;
    private int physicalHat;
    private boolean leftTrigger,rightTrigger;
    ControllerInput(Context c){prefs=c.getSharedPreferences("controller",0);}
    static boolean isController(int source){return (source&InputDevice.SOURCE_GAMEPAD)==InputDevice.SOURCE_GAMEPAD||(source&InputDevice.SOURCE_JOYSTICK)==InputDevice.SOURCE_JOYSTICK;}
    boolean enabled(){return prefs.getBoolean("enabled",true);}
    boolean key(KeyEvent e){
        if(!enabled()||!isController(e.getSource()))return false;
        for(int i=0;i<KEYS.length;i++)if(KEYS[i]==e.getKeyCode()){
            if(e.getAction()==KeyEvent.ACTION_DOWN)down[i]=true;
            else if(e.getAction()==KeyEvent.ACTION_UP)down[i]=false;
            return true;
        }
        return false;
    }
    boolean motion(MotionEvent e){
        if(!enabled()||!isController(e.getSource()))return false;
        float dead=prefs.getInt("deadzone",18)/100f;
        int[] ids={MotionEvent.AXIS_X,MotionEvent.AXIS_Y,MotionEvent.AXIS_Z,MotionEvent.AXIS_RZ};
        for(int i=0;i<4;i++){
            float v=e.getAxisValue(ids[i]);
            if(i>=2&&e.getDevice()!=null&&e.getDevice().getMotionRange(ids[i],e.getSource())==null)v=e.getAxisValue(i==2?MotionEvent.AXIS_RX:MotionEvent.AXIS_RY);
            axes[i]=Math.abs(v)<=dead?0:Math.copySign(Math.min(1,(Math.abs(v)-dead)/(1-dead)),v);
        }
        leftTrigger=Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER),e.getAxisValue(MotionEvent.AXIS_BRAKE))>.5f;
        rightTrigger=Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER),e.getAxisValue(MotionEvent.AXIS_GAS))>.5f;
        physicalHat=(e.getAxisValue(MotionEvent.AXIS_HAT_Y)<-.5?1:0)|(e.getAxisValue(MotionEvent.AXIS_HAT_X)>.5?2:0)|(e.getAxisValue(MotionEvent.AXIS_HAT_Y)>.5?4:0)|(e.getAxisValue(MotionEvent.AXIS_HAT_X)<-.5?8:0);
        return true;
    }
    void publish(File path)throws IOException {
        if(map==null){
            if(!path.isFile())return;
            file=new RandomAccessFile(path,"rw");if(file.length()!=64){file.close();file=null;return;}
            map=file.getChannel().map(FileChannel.MapMode.READ_WRITE,0,64);map.order(ByteOrder.LITTLE_ENDIAN);
        }
        int buttons=0,hat=0;
        for(int i=0;i<16;i++)if(down[i]||(i==10&&leftTrigger)||(i==11&&rightTrigger)||(i>=12&&(physicalHat&(1<<(i-12)))!=0)){
            int target=prefs.getInt("button_"+i,i<12?i+1:17+i-12);
            if(target>=1&&target<=16)buttons|=1<<(target-1);
            else if(target>=17&&target<=20)hat|=1<<(target-17);
        }
        // Separate monitor releases order the odd marker, payload and even marker
        // for the native seqlock reader on ARM64.
        synchronized(map){map.putInt(4,++sequence);}
        synchronized(map){
            map.putInt(0,0x4c534247);map.putInt(8,enabled()?buttons:0);map.putInt(12,enabled()?hat:0);
            for(int i=0;i<4;i++){
                int source=prefs.getBoolean("swap_sticks",false)?(i+2)%4:i;
                float value=enabled()?axes[source]:0;
                if((i==1&&prefs.getBoolean("invert_left_y",false))||(i==3&&prefs.getBoolean("invert_right_y",false)))value=-value;
                map.putShort(16+i*2,(short)Math.round(value*32767));
            }
            map.putLong(32,System.nanoTime()/1000000);
        }
        synchronized(map){map.putInt(4,++sequence);}
    }
    void reset(){java.util.Arrays.fill(down,false);java.util.Arrays.fill(axes,0);physicalHat=0;leftTrigger=rightTrigger=false;}
    public void close(){reset();try{if(map!=null)publish(null);}catch(Exception ignored){}map=null;try{if(file!=null)file.close();}catch(IOException ignored){}file=null;}
}
