package io.github.russianranger.lsb;

import android.app.*;
import android.content.*;
import android.os.*;
import io.github.russianranger.lsb.core.*;
import java.io.*;
import java.util.concurrent.*;

/** One foreground transfer at a time; does not retain an Activity or its views. */
public final class WorkService extends Service {
    public interface Job { String run(Context context, SafeZip.Progress progress) throws Exception; }
    private static Job pending;
    public static volatile boolean busy;
    public static volatile String message = "Ready";
    public static volatile String result = "";
    public static volatile long generation;
    private static volatile Thread runningThread;
    private static volatile boolean cancellationRequested;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private PowerManager.WakeLock wake;
    private long lastNotice;
    public static synchronized boolean submit(Context context, String title, Job job) {
        if (busy || ClientRuntime.get(context).alive()) return false;
        pending = job; busy = true; cancellationRequested = false; message = title; result = "";
        try { context.startForegroundService(new Intent(context, WorkService.class)); return true; }
        catch (RuntimeException e) { pending = null; busy = false; message = "Could not start transfer: " + e.getMessage(); generation++; return false; }
    }
    public static void cancel() { cancellationRequested = true; Thread t = runningThread; if (t != null) t.interrupt(); }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("transfers", "Imports and backups", NotificationManager.IMPORTANCE_LOW));
        startForeground(1, notification(message));
        final Job job;
        synchronized (WorkService.class) { job = pending; pending = null; }
        if (job == null) { busy = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY; }
        wake = ((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lsb:transfer");
        wake.acquire(2 * 60 * 60 * 1000L);
        append(this, "Started: " + message);
        worker.submit(() -> {
            try {
                runningThread = Thread.currentThread();
                if (cancellationRequested) throw new InterruptedIOException("Operation cancelled");
                result = job.run(getApplicationContext(), text -> {
                    message = text;
                    if (System.currentTimeMillis() - lastNotice > 1200) {
                        lastNotice = System.currentTimeMillis(); nm.notify(1, notification(text));
                    }
                });
                message = "Completed"; append(this, "Completed: " + result);
            } catch (Exception e) {
                message = cancellationRequested || Thread.currentThread().isInterrupted() ? "Cancelled" : "Failed";
                result = e.getClass().getSimpleName() + ": " + e.getMessage();
                append(this, message + ": " + result);
            } finally {
                runningThread = null;
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (wake != null && wake.isHeld()) wake.release();
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId);
                    busy = false; generation++;
                });
            }
        });
        return START_NOT_STICKY;
    }
    private Notification notification(String text) {
        PendingIntent launch = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "transfers").setContentTitle("LSB Android").setContentText(text).setSmallIcon(android.R.drawable.stat_sys_download).setContentIntent(launch).setOngoing(true).build();
    }
    public static synchronized void append(Context context, String line) {
        try {
            File f = new File(context.getFilesDir(), "operations.log");
            if (f.length() > 131072) FilesEx.text(f, "Older operations rotated.\n");
            try (FileWriter out = new FileWriter(f, true)) { out.write(new java.util.Date() + " " + line + "\n"); }
        } catch (IOException ignored) { }
    }
    // Android 15 dataSync foreground-service time budget.
    public void onTimeout(int startId, int fgsType) { cancel(); stopSelf(); }
    @Override public void onDestroy() {
        worker.shutdownNow(); if (wake != null && wake.isHeld()) wake.release(); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
