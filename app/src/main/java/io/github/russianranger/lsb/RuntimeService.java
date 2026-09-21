package io.github.russianranger.lsb;
import android.app.*;
import android.content.*;
import android.os.*;
import io.github.russianranger.lsb.core.LoginRequest;

/** Foreground ownership of one local Windows session, independent of transfers. */
public final class RuntimeService extends Service {
    private volatile Thread worker;
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
            new Thread(()->{try{ClientRuntime.get(this).requestStop();}catch(Exception e){WorkService.append(this,"Runtime stop: "+e.getMessage());}finally{if(worker==null){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}}},"lsb-runtime-stop").start();return START_NOT_STICKY;
        }
        if(worker!=null){clearLogin();return START_NOT_STICKY;}
        final String renderer=intent.getStringExtra("renderer");final String action=intent.getStringExtra("operation")==null?"probe":intent.getStringExtra("operation");final boolean sound=intent.getBooleanExtra("audio",true);
        final LoginRequest login="launch".equals(action)?takeLogin(intent.getStringExtra("login_ticket")):null;
        final String displayProfile=intent.getStringExtra("display_profile")==null?"windowed720":intent.getStringExtra("display_profile");
        final boolean startupTrace=intent.getBooleanExtra("startup_trace",false);
        wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"lsb:runtime");wake.acquire(2*60*60*1000L);
        worker=new Thread(()->{
            try{ClientRuntime.get(this).run(renderer,sound,action,login,displayProfile,startupTrace);}
            catch(Exception e){ClientRuntime.get(this).status=e.getMessage();WorkService.append(this,"Runtime: "+e.getClass().getSimpleName()+": "+e.getMessage());}
            finally{if(login!=null)login.close();new Handler(Looper.getMainLooper()).post(()->{if(wake!=null&&wake.isHeld())wake.release();worker=null;WorkService.generation++;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();});}
        },"lsb-windows");worker.start();return START_NOT_STICKY;
    }
    @Override public void onDestroy(){
        if(wake!=null&&wake.isHeld())wake.release();
        if(worker!=null)new Thread(()->{try{ClientRuntime.get(this).requestStop();}catch(Exception ignored){}},"lsb-runtime-cleanup").start();super.onDestroy();
    }
    @Override public IBinder onBind(Intent i){return null;}
}
