package io.github.russianranger.lsb;
import android.app.*;
import android.content.*;
import android.os.*;

/** Foreground ownership of one local Windows session, independent of transfers. */
public final class RuntimeService extends Service {
    private volatile Thread worker;
    private PowerManager.WakeLock wake;
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
        if(worker!=null)return START_NOT_STICKY;
        final String renderer=intent.getStringExtra("renderer");final String action=intent.getStringExtra("operation")==null?"probe":intent.getStringExtra("operation");final boolean sound=intent.getBooleanExtra("audio",true);
        wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"lsb:runtime");wake.acquire(2*60*60*1000L);
        worker=new Thread(()->{
            try{ClientRuntime.get(this).run(renderer,sound,action);}
            catch(Exception e){ClientRuntime.get(this).status=e.getMessage();WorkService.append(this,"Runtime: "+e.getClass().getSimpleName()+": "+e.getMessage());}
            finally{new Handler(Looper.getMainLooper()).post(()->{if(wake!=null&&wake.isHeld())wake.release();worker=null;WorkService.generation++;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();});}
        },"lsb-windows");worker.start();return START_NOT_STICKY;
    }
    @Override public void onDestroy(){
        if(wake!=null&&wake.isHeld())wake.release();
        if(worker!=null)new Thread(()->{try{ClientRuntime.get(this).requestStop();}catch(Exception ignored){}},"lsb-runtime-cleanup").start();super.onDestroy();
    }
    @Override public IBinder onBind(Intent i){return null;}
}
