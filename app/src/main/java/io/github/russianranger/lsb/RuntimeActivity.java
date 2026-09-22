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
    private volatile DisplaySession displaySession;
    private volatile boolean viewing;
    private volatile int connectionGeneration;
    private Screen screen;
    private FrameLayout layout;
    private NativePresentation nativeDisplay;
    private String presentationStatus="";
    private TextView status;
    private String displayError="";
    private boolean failureShown;
    private ControllerInput controller;
    private String padSession="";
    private final Runnable padTick=new Runnable(){public void run(){
        if(!viewing)return;
        try{
            ClientRuntime rt=ClientRuntime.get(RuntimeActivity.this);
            // Preparation can outlast many UI ticks. Read the published session once;
            // null means the worker has not created this run's input state yet.
            String session=rt.sessionKey();
            if(!java.util.Objects.equals(padSession,session)){controller.close();padSession=session;}
            if(session!=null&&hasWindowFocus())controller.publish(rt.gamepadState());
        }catch(IOException ignored){}
        ui.postDelayed(this,20);
    }};
    private long lastStats;
    private final Runnable refresh=new Runnable(){public void run(){
        if(!viewing)return;ClientRuntime rt=ClientRuntime.get(RuntimeActivity.this);
        status.setText(rt.status+(presentationStatus.isEmpty()?"":"\n"+presentationStatus)+(displayError.isEmpty()||!rt.launchError.isEmpty()?"":"\n"+displayError));
        DisplaySession display=displaySession;
        if(display!=null&&System.currentTimeMillis()-lastStats>5000){lastStats=System.currentTimeMillis();recordFrames(display);}
        if(!rt.alive()&&!rt.launchError.isEmpty()&&!failureShown){
            failureShown=true;
            new AlertDialog.Builder(RuntimeActivity.this).setTitle("Client launch stopped").setMessage(rt.launchError)
                .setPositiveButton("Back to Client",(d,w)->finish()).setNegativeButton("Stay",null).show();
        }
        ui.postDelayed(this,1000);
    }};
    @Override public void onCreate(Bundle state){
        super.onCreate(state);setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        controller=new ControllerInput(this);
        layout=new FrameLayout(this);layout.setBackgroundColor(Color.BLACK);screen=new Screen();layout.addView(screen,new FrameLayout.LayoutParams(-1,-1));
        Button menu=new Button(this);menu.setText("☰");menu.setTextColor(Color.WHITE);menu.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xcc15334c));
        int size=Math.round(48*getResources().getDisplayMetrics().density);FrameLayout.LayoutParams place=new FrameLayout.LayoutParams(size,size,Gravity.TOP|Gravity.RIGHT);place.setMargins(0,8,8,0);layout.addView(menu,place);
        menu.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("FFXI").setItems(new String[]{"Back to launcher","Keyboard","Send Esc","Controller mapping","Stop client"},(d,which)->{switch(which){case 0:finish();break;case 1:keyboard();break;case 2:tapKey(0xff1b);break;case 3:startActivity(new android.content.Intent(this,MainActivity.class).putExtra("tab","Controller"));break;case 4:startForegroundService(new android.content.Intent(this,RuntimeService.class).setAction("stop"));finish();break;}}).show());
        status=new TextView(this);status.setTextColor(Color.WHITE);status.setTextSize(11);status.setBackgroundColor(0x88000000);status.setMaxLines(3);layout.addView(status,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));setContentView(layout);
    }
    private void button(LinearLayout row,String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setOnClickListener(v->action.run());row.addView(b,new LinearLayout.LayoutParams(0,-2,1));}
    @Override protected void onResume(){super.onResume();viewing=true;connect();ui.post(refresh);ui.post(padTick);}
    @Override protected void onPause(){viewing=false;ui.removeCallbacks(refresh);ui.removeCallbacks(padTick);controller.close();closeNative();releaseAndClose();super.onPause();}
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(!focus&&controller!=null)controller.close();}
    @Override public boolean dispatchGenericMotionEvent(MotionEvent e){if(hasWindowFocus()&&controller!=null&&controller.motion(e))return true;return super.dispatchGenericMotionEvent(e);}
    @Override protected void onDestroy(){closeNative();releaseAndClose();input.shutdown();super.onDestroy();}
    private static final class DisplaySession {
        final RfbConnection connection;final String id;final boolean fast;final int cap;
        DisplaySession(RfbConnection c,String id,boolean fast,int cap){connection=c;this.id=id;this.fast=fast;this.cap=cap;}
    }
    private void recordFrames(DisplaySession display){
        RfbConnection c=display.connection;long[] allocation=screen.allocations();
        ClientRuntime.get(this).recordFrames(display.id,c.stats.sample(System.nanoTime()),c.width,c.height,display.fast,display.cap,allocation[0],allocation[1]);
    }
    private void connect(){
        final int generation=++connectionGeneration;
        new Thread(()->{
            LocalSocket s=null;DisplaySession display=null;
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
                ClientRuntime rt=ClientRuntime.get(this);String id=rt.sessionKey();
                boolean fast=getSharedPreferences("runtime",MODE_PRIVATE).getBoolean("fast_display",true);
                int cap=rt.activeDisplayFps();
                RfbConnection c=new RfbConnection(s.getInputStream(),s.getOutputStream(),screen,fast,getSharedPreferences("runtime",MODE_PRIVATE).getBoolean("compressed_display",true));
                boolean nativeRequested=rt.nativeSurfaceRequested();screen.nativeMode=nativeRequested;
                display=new DisplaySession(c,id,fast,cap);c.handshake(!nativeRequested);
                if(!viewing||generation!=connectionGeneration)return;
                socket=s;connection=c;displaySession=display;ui.post(()->{
                    if(!viewing||generation!=connectionGeneration)return;
                    displayError="";
                    if(nativeRequested)openNative(rt,id,c,generation);
                    else presentationStatus="Standard display";
                });
                while(viewing&&generation==connectionGeneration)c.readUpdate();
            }catch(Exception e){if(viewing&&generation==connectionGeneration)ui.post(()->displayError="Display closed: "+e.getMessage());}
            finally{if(display!=null){recordFrames(display);display.connection.close();}try{if(s!=null)s.close();}catch(Exception ignored){}if(generation==connectionGeneration){connection=null;socket=null;displaySession=null;}}
        },"lsb-display").start();
    }
    private void openNative(ClientRuntime rt,String id,RfbConnection c,int generation){
        closeNative();screen.nativeMode=true;
        nativeDisplay=new NativePresentation(this,rt.nativeFrameSocket(),rt.nativeFramePixels(),rt.nativeFrameReports(id),new NativePresentation.Events(){
            public boolean current(){return generation==connectionGeneration&&java.util.Objects.equals(id,rt.sessionKey());}
            public void size(int w,int h){if(viewing&&generation==connectionGeneration){screen.frameSize(w,h);fitNative();}}
            public void failed(String reason){fallbackNative(c,generation,reason);}
        });
        layout.addView(nativeDisplay,0,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        presentationStatus="Native Surface · shared memory";fitNative();
    }
    private void fallbackNative(RfbConnection c,int generation,String reason){
        if(!viewing||generation!=connectionGeneration||connection!=c)return;
        closeNative();screen.nativeMode=false;screen.resize(c.width,c.height);
        presentationStatus="Using previous display: "+reason;
        send(current->{if(current==c)current.startFrames();});
    }
    private void closeNative(){
        if(nativeDisplay!=null){nativeDisplay.close();layout.removeView(nativeDisplay);nativeDisplay=null;}
    }
    private void fitNative(){
        if(nativeDisplay==null||screen.getWidth()==0||screen.getHeight()==0)return;
        int[] size=screen.frameSize();float scale=Math.min(screen.getWidth()/(float)size[0],screen.getHeight()/(float)size[1]);
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(Math.round(size[0]*scale),Math.round(size[1]*scale),Gravity.CENTER);nativeDisplay.setLayoutParams(p);
    }
    private interface Send{void run(RfbConnection c)throws IOException;}
    private void send(Send task){RfbConnection c=connection;if(c==null||input.isShutdown())return;input.execute(()->{try{task.run(c);}catch(IOException ignored){}});}
    private void tapKey(int sym){send(c->{c.key(sym,true);c.key(sym,false);});}
    private void releaseAndClose(){
        ++connectionGeneration;RfbConnection c=connection;LocalSocket s=socket;connection=null;socket=null;displaySession=null;
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
        if(hasWindowFocus()&&controller!=null&&controller.key(e))return true;
        if(e.getKeyCode()==KeyEvent.KEYCODE_BACK||e.getKeyCode()==KeyEvent.KEYCODE_VOLUME_UP||e.getKeyCode()==KeyEvent.KEYCODE_VOLUME_DOWN||!hasWindowFocus())return super.dispatchKeyEvent(e);
        if(e.getAction()==KeyEvent.ACTION_UP){Integer sym=held.remove(e.getKeyCode());if(sym!=null){send(c->c.key(sym,false));return true;}}
        if(e.getAction()==KeyEvent.ACTION_DOWN){int sym=keysym(e);if(sym!=0){held.put(e.getKeyCode(),sym);send(c->c.key(sym,true));return true;}}
        return super.dispatchKeyEvent(e);
    }
    private final class Screen extends View implements RfbConnection.Screen {
        private Bitmap bitmap;private Canvas bitmapCanvas;private final Object lock=new Object();private final RectF destination=new RectF();private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);private int fw=1280,fh=720;private int[] copy=new int[0];
        private Rgb565Staging staging;
        volatile boolean nativeMode;
        void frameSize(int w,int h){synchronized(lock){fw=w;fh=h;}}
        int[] frameSize(){synchronized(lock){return new int[]{fw,fh};}}
        @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);fitNative();}
        Screen(){super(RuntimeActivity.this);setFocusable(true);setFocusableInTouchMode(true);}
        public void resize(int w,int h){synchronized(lock){fw=w;fh=h;if(nativeMode){bitmap=null;bitmapCanvas=null;if(staging!=null){staging.close();staging=null;}return;}bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);bitmapCanvas=new Canvas(bitmap);fw=w;fh=h;if(staging!=null)staging.close();staging=new Rgb565Staging(w,h);}postInvalidate();}
        public boolean raw565(int x,int y,int w,int h,byte[] data,int length){synchronized(lock){
            if(bitmap==null)return false;
            return staging.apply(bitmapCanvas,x,y,w,h,data,length);
        }}
        long[] allocations(){synchronized(lock){return new long[]{staging==null?0:staging.allocations,staging==null?0:staging.allocatedBytes};}}
        public void pixels(int x,int y,int w,int h,int[] argb){synchronized(lock){if(bitmap!=null)bitmap.setPixels(argb,0,w,x,y,w,h);}}
        public void copy(int x,int y,int w,int h,int sx,int sy){synchronized(lock){if(bitmap==null)return;if(copy.length<w*h)copy=new int[w*h];bitmap.getPixels(copy,0,w,sx,sy,w,h);bitmap.setPixels(copy,0,w,x,y,w,h);}}
        public void updated(){postInvalidateOnAnimation();}
        private void arrange(){float scale=Math.min(getWidth()/(float)fw,getHeight()/(float)fh);float w=fw*scale,h=fh*scale;destination.set((getWidth()-w)/2,(getHeight()-h)/2,(getWidth()+w)/2,(getHeight()+h)/2);}
        @Override protected void onDraw(Canvas canvas){long begin=System.nanoTime();super.onDraw(canvas);synchronized(lock){arrange();if(bitmap!=null)canvas.drawBitmap(bitmap,null,destination,paint);}RfbConnection c=connection;if(c!=null)c.stats.drawn(System.nanoTime()-begin);}
        private void point(MotionEvent e,int mask){int x,y;synchronized(lock){arrange();if(destination.width()<=0)return;x=(int)((e.getX()-destination.left)*fw/destination.width());y=(int)((e.getY()-destination.top)*fh/destination.height());}send(c->c.pointer(x,y,mask));}
        @Override public boolean onTouchEvent(MotionEvent e){switch(e.getActionMasked()){case MotionEvent.ACTION_DOWN:requestFocus();point(e,1);return true;case MotionEvent.ACTION_MOVE:point(e,1);return true;case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:point(e,0);return true;default:return true;}}
        @Override public boolean onGenericMotionEvent(MotionEvent e){if(e.isFromSource(InputDevice.SOURCE_MOUSE)){int mask=((e.getButtonState()&MotionEvent.BUTTON_PRIMARY)!=0?1:0)|((e.getButtonState()&MotionEvent.BUTTON_SECONDARY)!=0?4:0);point(e,mask);return true;}return super.onGenericMotionEvent(e);}
    }
}
