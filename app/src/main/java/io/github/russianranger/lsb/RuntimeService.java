package io.github.russianranger.lsb;
import android.app.*;
import android.content.*;
import android.os.*;
import io.github.russianranger.lsb.core.LoginRequest;

/** Foreground ownership of one local Windows session, independent of transfers. */
public final class RuntimeService extends Service {
    private volatile Thread worker;
    private volatile boolean cancelled;
    private PowerManager.WakeLock wake;
    private static LoginRequest pendingLogin;
    private static String pendingToken;
    static synchronized String queueLogin(LoginRequest request){
        clearLogin();pendingLogin=request;pendingToken=java.util.UUID.randomUUID().toString();final String ticket=pendingToken;
        new Handler(Looper.getMainLooper()).postDelayed(()->{synchronized(RuntimeService.class){if(ticket.equals(pendingToken))clearLogin();}},30000);
        return ticket;
    }
    static synchronized void clearLogin(){if(pendingLogin!=null)pendingLogin.close();pendingLogin=null;pendingToken=null;}
    private static synchronized LoginRequest takeLogin(String token){
        if(token==null||!token.equals(pendingToken))return null;
        LoginRequest request=pendingLogin;pendingLogin=null;pendingToken=null;return request;
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("runtime","Windows runtime",NotificationManager.IMPORTANCE_LOW));
        PendingIntent view=PendingIntent.getActivity(this,5,new Intent(this,RuntimeActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,6,new Intent(this,RuntimeService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        startForeground(2,new Notification.Builder(this,"runtime").setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("LSB client runtime").setContentText("Open the display or stop the runtime").setContentIntent(view).addAction(new Notification.Action.Builder(null,"Stop",stop).build()).setOngoing(true).build());
        if(intent==null){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY;}
        if("stop".equals(intent.getAction())){
            cancelled=true;Thread running=worker;if(running!=null)running.interrupt();
            new Thread(()->{try{ClientRuntime.get(this).requestStop();}catch(Exception e){WorkService.append(this,"Runtime stop: "+e.getMessage());}finally{if(worker==null){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}}},"lsb-runtime-stop").start();return START_NOT_STICKY;
        }
        if(worker!=null){clearLogin();return START_NOT_STICKY;}
        cancelled=false;
        final String renderer=intent.getStringExtra("renderer");final String action=intent.getStringExtra("operation")==null?"probe":intent.getStringExtra("operation");final boolean sound=intent.getBooleanExtra("audio",true);
        final LoginRequest login="launch".equals(action)?takeLogin(intent.getStringExtra("login_ticket")):null;
        final String displayProfile=intent.getStringExtra("display_profile")==null?"windowed720":intent.getStringExtra("display_profile");
        final boolean startupTrace=intent.getBooleanExtra("startup_trace",false);
        final boolean play=intent.getBooleanExtra("play_flow",false),managed=intent.getBooleanExtra("managed_server",false);
        wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"lsb:runtime");wake.acquire(2*60*60*1000L);
        worker=new Thread(()->{
            ClientRuntime rt=ClientRuntime.get(this);
            try{
                if(play){
                    if(!"launch".equals(action)||login==null)throw new java.io.IOException("Enter your account and password on Play again");
                    rt.reservePlay();
                    if(cancelled)throw new java.io.InterruptedIOException("Play cancelled");
                    if(managed){
                        if(!"127.0.0.1".equals(login.host))throw new java.io.IOException("Managed server play requires the local server address");
                        ServerRuntime sr=ServerRuntime.get(this);
                        if(!sr.installed()||!sr.deployment().has("generation"))throw new java.io.IOException("Finish server setup before choosing Play");
                        PlayFlow.await(new PlayFlow.Server(){
                            public boolean alive(){return sr.alive();}
                            public boolean ready(){return sr.ready();}
                            public String status(){return sr.status;}
                            public void start(){startForegroundService(new Intent(RuntimeService.this,ServerService.class));}
                        },new PlayFlow.Wait(){
                            public long now(){return SystemClock.elapsedRealtime();}
                            public void pause()throws Exception{Thread.sleep(250);}
                            public void progress(String text){rt.status=text;}
                        },30*60*1000L);
                    }
                }
                rt.run(renderer,sound,action,login,displayProfile,startupTrace);
            }
            catch(Exception e){rt.status=cancelled||Thread.currentThread().isInterrupted()?(play?"Play cancelled. The managed server can be stopped from Server.":"Runtime cancelled."):String.valueOf(e.getMessage());if("launch".equals(action))rt.launchError=rt.status;WorkService.append(this,"Runtime: "+e.getClass().getSimpleName()+": "+rt.status);}
            finally{rt.releasePlay();if(login!=null)login.close();Thread.interrupted();new Handler(Looper.getMainLooper()).post(()->{if(wake!=null&&wake.isHeld())wake.release();worker=null;WorkService.generation++;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();});}
        },"lsb-windows");worker.start();return START_NOT_STICKY;
    }
    @Override public void onDestroy(){
        if(wake!=null&&wake.isHeld())wake.release();
        if(worker!=null)new Thread(()->{try{ClientRuntime.get(this).requestStop();}catch(Exception ignored){}},"lsb-runtime-cleanup").start();super.onDestroy();
    }
    @Override public IBinder onBind(Intent i){return null;}
}
