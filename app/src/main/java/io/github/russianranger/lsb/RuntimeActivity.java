package io.github.russianranger.lsb;
import android.app.*;
import android.graphics.*;
import android.net.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** In-app RFB surface over an owner-only Unix socket; no external display app. */
public final class RuntimeActivity extends Activity {
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService input=Executors.newSingleThreadExecutor();
    private final Map<Integer,Integer> held=new HashMap<>();
    private volatile LocalSocket socket;
    private volatile RfbConnection connection;
    private volatile boolean viewing;
    private volatile int connectionGeneration;
    private Screen screen;
    private TextView status;
    private String displayError="";
    private boolean failureShown;
    private final Runnable refresh=new Runnable(){public void run(){
        if(!viewing)return;ClientRuntime rt=ClientRuntime.get(RuntimeActivity.this);
        status.setText(rt.status+(displayError.isEmpty()||!rt.launchError.isEmpty()?"":"\n"+displayError));
        if(!rt.alive()&&!rt.launchError.isEmpty()&&!failureShown){
            failureShown=true;
            new AlertDialog.Builder(RuntimeActivity.this).setTitle("Client launch stopped").setMessage(rt.launchError)
                .setPositiveButton("Back to Client",(d,w)->finish()).setNegativeButton("Stay",null).show();
        }
        ui.postDelayed(this,1000);
    }};
    @Override public void onCreate(Bundle state){
        super.onCreate(state);setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setBackgroundColor(Color.BLACK);
        LinearLayout controls=new LinearLayout(this);
        button(controls,"Back",()->finish());button(controls,"Keyboard",this::keyboard);button(controls,"Esc",()->tapKey(0xff1b));
        button(controls,"Stop",()->{startForegroundService(new android.content.Intent(this,RuntimeService.class).setAction("stop"));finish();});
        layout.addView(controls);screen=new Screen();layout.addView(screen,new LinearLayout.LayoutParams(-1,0,1));
        status=new TextView(this);status.setTextColor(Color.WHITE);status.setTextSize(14);status.setMaxLines(4);layout.addView(status);setContentView(layout);
    }
    private void button(LinearLayout row,String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setOnClickListener(v->action.run());row.addView(b,new LinearLayout.LayoutParams(0,-2,1));}
    @Override protected void onResume(){super.onResume();viewing=true;connect();ui.post(refresh);}
    @Override protected void onPause(){viewing=false;ui.removeCallbacks(refresh);releaseAndClose();super.onPause();}
    @Override protected void onDestroy(){releaseAndClose();input.shutdown();super.onDestroy();}
    private void connect(){
        final int generation=++connectionGeneration;
        new Thread(()->{
            LocalSocket s=null;
            try{
                // A full client copy can take minutes before the display server exists.
                // Wait for the owned operation, not the old one-minute probe deadline.
                for(int n=0;viewing&&generation==connectionGeneration;n++){
                    ClientRuntime rt=ClientRuntime.get(this);
                    if(rt.displaySocket().exists())break;
                    if(n>25&&!rt.alive())return;
                    Thread.sleep(200);
                }
                if(!viewing||generation!=connectionGeneration)return;
                s=new LocalSocket();s.connect(new LocalSocketAddress(ClientRuntime.get(this).displaySocket().getPath(),LocalSocketAddress.Namespace.FILESYSTEM));
                if(s.getPeerCredentials().getUid()!=android.os.Process.myUid())throw new IOException("Display owner mismatch");
                RfbConnection c=new RfbConnection(s.getInputStream(),s.getOutputStream(),screen);c.handshake();
                if(!viewing||generation!=connectionGeneration)return;
                socket=s;connection=c;ui.post(()->displayError="");
                while(viewing&&generation==connectionGeneration)c.readUpdate();
            }catch(Exception e){if(viewing&&generation==connectionGeneration)ui.post(()->displayError="Display closed: "+e.getMessage());}
            finally{try{if(s!=null)s.close();}catch(Exception ignored){}if(generation==connectionGeneration){connection=null;socket=null;}}
        },"lsb-display").start();
    }
    private interface Send{void run(RfbConnection c)throws IOException;}
    private void send(Send task){RfbConnection c=connection;if(c==null||input.isShutdown())return;input.execute(()->{try{task.run(c);}catch(IOException ignored){}});}
    private void tapKey(int sym){send(c->{c.key(sym,true);c.key(sym,false);});}
    private void releaseAndClose(){
        ++connectionGeneration;RfbConnection c=connection;LocalSocket s=socket;connection=null;socket=null;
        Collection<Integer> keys=new ArrayList<>(held.values());held.clear();
        if(!input.isShutdown())input.execute(()->{try{if(c!=null){for(int sym:keys)c.key(sym,false);c.pointer(0,0,0);}}catch(Exception ignored){}finally{try{if(s!=null)s.close();}catch(Exception ignored){}}});
    }
    private void keyboard(){
        EditText text=new EditText(this);text.setSingleLine(true);text.setHint("Type a few characters");
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Send keys to Windows").setView(text).setNegativeButton("Cancel",null).setPositiveButton("Send + Enter",(d,w)->{
            String value=text.getText().toString();send(c->{for(int i=0;i<value.length();){int cp=value.codePointAt(i);i+=Character.charCount(cp);int sym=cp<=255?cp:0x01000000|cp;c.key(sym,true);c.key(sym,false);}c.key(0xff0d,true);c.key(0xff0d,false);});
        }).create();dialog.getWindow();dialog.setOnShowListener(d->{text.requestFocus();dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);});dialog.show();
    }
    private int keysym(KeyEvent e){
        switch(e.getKeyCode()){
            case KeyEvent.KEYCODE_ENTER:case KeyEvent.KEYCODE_BUTTON_A:return 0xff0d;
            case KeyEvent.KEYCODE_ESCAPE:case KeyEvent.KEYCODE_BUTTON_B:return 0xff1b;
            case KeyEvent.KEYCODE_DEL:return 0xff08;case KeyEvent.KEYCODE_TAB:return 0xff09;
            case KeyEvent.KEYCODE_DPAD_LEFT:return 0xff51;case KeyEvent.KEYCODE_DPAD_UP:return 0xff52;case KeyEvent.KEYCODE_DPAD_RIGHT:return 0xff53;case KeyEvent.KEYCODE_DPAD_DOWN:return 0xff54;
            case KeyEvent.KEYCODE_SHIFT_LEFT:return 0xffe1;case KeyEvent.KEYCODE_SHIFT_RIGHT:return 0xffe2;
            case KeyEvent.KEYCODE_CTRL_LEFT:return 0xffe3;case KeyEvent.KEYCODE_ALT_LEFT:return 0xffe9;
            default:int cp=e.getUnicodeChar();return cp>0?(cp<=255?cp:0x01000000|cp):0;
        }
    }
    @Override public boolean dispatchKeyEvent(KeyEvent e){
        if(e.getKeyCode()==KeyEvent.KEYCODE_BACK||e.getKeyCode()==KeyEvent.KEYCODE_VOLUME_UP||e.getKeyCode()==KeyEvent.KEYCODE_VOLUME_DOWN||!hasWindowFocus())return super.dispatchKeyEvent(e);
        if(e.getAction()==KeyEvent.ACTION_UP){Integer sym=held.remove(e.getKeyCode());if(sym!=null){send(c->c.key(sym,false));return true;}}
        if(e.getAction()==KeyEvent.ACTION_DOWN){int sym=keysym(e);if(sym!=0){held.put(e.getKeyCode(),sym);send(c->c.key(sym,true));return true;}}
        return super.dispatchKeyEvent(e);
    }
    private final class Screen extends View implements RfbConnection.Screen {
        private Bitmap bitmap;private final Object lock=new Object();private final RectF destination=new RectF();private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);private int fw=1280,fh=720;private int[] copy=new int[0];
        Screen(){super(RuntimeActivity.this);setFocusable(true);setFocusableInTouchMode(true);}
        public void resize(int w,int h){synchronized(lock){bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);fw=w;fh=h;}postInvalidate();}
        public void pixels(int x,int y,int w,int h,int[] argb){synchronized(lock){if(bitmap!=null)bitmap.setPixels(argb,0,w,x,y,w,h);}}
        public void copy(int x,int y,int w,int h,int sx,int sy){synchronized(lock){if(bitmap==null)return;if(copy.length<w*h)copy=new int[w*h];bitmap.getPixels(copy,0,w,sx,sy,w,h);bitmap.setPixels(copy,0,w,x,y,w,h);}}
        public void updated(){postInvalidateOnAnimation();}
        private void arrange(){float scale=Math.min(getWidth()/(float)fw,getHeight()/(float)fh);float w=fw*scale,h=fh*scale;destination.set((getWidth()-w)/2,(getHeight()-h)/2,(getWidth()+w)/2,(getHeight()+h)/2);}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);synchronized(lock){arrange();if(bitmap!=null)canvas.drawBitmap(bitmap,null,destination,paint);}}
        private void point(MotionEvent e,int mask){int x,y;synchronized(lock){arrange();if(destination.width()<=0)return;x=(int)((e.getX()-destination.left)*fw/destination.width());y=(int)((e.getY()-destination.top)*fh/destination.height());}send(c->c.pointer(x,y,mask));}
        @Override public boolean onTouchEvent(MotionEvent e){switch(e.getActionMasked()){case MotionEvent.ACTION_DOWN:requestFocus();point(e,1);return true;case MotionEvent.ACTION_MOVE:point(e,1);return true;case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:point(e,0);return true;default:return true;}}
        @Override public boolean onGenericMotionEvent(MotionEvent e){if(e.isFromSource(InputDevice.SOURCE_MOUSE)){int mask=((e.getButtonState()&MotionEvent.BUTTON_PRIMARY)!=0?1:0)|((e.getButtonState()&MotionEvent.BUTTON_SECONDARY)!=0?4:0);point(e,mask);return true;}return super.onGenericMotionEvent(e);}
    }
}
