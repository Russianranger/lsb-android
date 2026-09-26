package io.github.russianranger.lsb;
import android.app.*;
import android.content.*;
import android.os.*;

/** Server lifetime is independent of client display and app navigation. */
public final class ServerService extends Service {
    private Thread worker;private PowerManager.WakeLock wake;
    public int onStartCommand(Intent intent,int flags,int id){
        NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("server","LandSandBoat server",NotificationManager.IMPORTANCE_LOW));
        PendingIntent view=PendingIntent.getActivity(this,21,new Intent(this,MainActivity.class).putExtra("tab","Server"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,22,new Intent(this,ServerService.class).setAction("stop"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        startForeground(3,new Notification.Builder(this,"server").setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("LandSandBoat server").setContentText("Managed server and database").setContentIntent(view).addAction(new Notification.Action.Builder(null,"Stop server",stop).build()).setOngoing(true).build());
        if(intent==null){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY;}
        if("stop".equals(intent.getAction())){try{ServerRuntime.get(this).stop();}catch(Exception ignored){}if(worker==null){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}return START_NOT_STICKY;}
        if(worker!=null)return START_NOT_STICKY;
        if(WorkService.busy||ServerRuntime.get(this).alive()){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY;}
        wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"lsb:server");wake.acquire();
        worker=new Thread(()->{
            try{ServerRuntime.get(this).perform("start",false,s->{});}
            catch(Exception e){ServerRuntime.get(this).status=e.getMessage();WorkService.append(this,"Server: "+e.getMessage());}
            finally{new Handler(Looper.getMainLooper()).post(()->{if(wake!=null&&wake.isHeld())wake.release();worker=null;WorkService.generation++;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();});}
        },"lsb-server");worker.start();return START_NOT_STICKY;
    }
    public void onDestroy(){try{if(worker!=null)ServerRuntime.get(this).stop();}catch(Exception ignored){}if(wake!=null&&wake.isHeld())wake.release();super.onDestroy();}
    public IBinder onBind(Intent intent){return null;}
}
