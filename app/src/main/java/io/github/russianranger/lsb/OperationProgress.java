package io.github.russianranger.lsb;

import android.content.Context;
import android.os.SystemClock;
import io.github.russianranger.lsb.core.FilesEx;
import java.io.File;
import java.util.*;
import org.json.*;

/** Shared operation presentation, distinct from the archived diagnostic logs. */
final class OperationProgress {
    static final Slot work=new Slot(),client=new Slot(),server=new Slot();
    static final class Slot {
        String title="",step="",outcome="",lane="";long started,ended,clock;volatile boolean running;
        final ArrayDeque<String> lines=new ArrayDeque<>();
        synchronized void begin(String lane,String title){this.lane=lane;this.title=title;step=title;outcome="";started=System.currentTimeMillis();clock=SystemClock.elapsedRealtime();ended=0;running=true;lines.clear();lines.add(title);}
        synchronized void begin(Context c,String lane,String title){begin(lane,title);persist(c);}
        synchronized void update(String text){if(text==null||text.isEmpty()||text.equals(step))return;step=text;lines.add(text);while(lines.size()>12)lines.removeFirst();}
        synchronized void finish(Context c,String outcome,String text){update(text);this.outcome=outcome;ended=System.currentTimeMillis();running=false;persist(c);}
        private void persist(Context c){try{ClientRuntime.write(new File(c.getFilesDir(),"progress/"+lane+".json"),new JSONObject().put("title",title).put("step",step).put("outcome",outcome).put("started",started).put("ended",ended).put("running",running).toString());}catch(Exception ignored){}}
        synchronized String summary(){if(title.isEmpty())return "Ready";long seconds=running?Math.max(0,(SystemClock.elapsedRealtime()-clock)/1000):Math.max(0,(ended-started)/1000);return (running?title:outcome+" · "+title)+"\n"+step+"\nElapsed · "+duration(seconds);}
        synchronized String details(){return summary()+"\n\nRecent progress for this operation\n"+String.join("\n",lines);}
        synchronized void load(Context c,String lane){if(running||started>0)return;try{JSONObject j=new JSONObject(FilesEx.read(new File(c.getFilesDir(),"progress/"+lane+".json"),16384));this.lane=lane;title=j.getString("title");step=j.getString("step");outcome=j.getString("outcome");started=j.getLong("started");ended=j.getLong("ended");if(j.optBoolean("running")){outcome="Interrupted";step="The previous app process ended before completion was recorded. Check the saved progress before retrying.";ended=started;}lines.add(step);}catch(Exception ignored){}}
    }
    static String duration(long seconds){return seconds>=3600?String.format(Locale.ROOT,"%dh %02dm %02ds",seconds/3600,seconds/60%60,seconds%60):String.format(Locale.ROOT,"%dm %02ds",seconds/60,seconds%60);}
    static void load(Context c){work.load(c,"work");client.load(c,"client");server.load(c,"server");}
    static Slot current(){if(work.running)return work;if(client.running)return client;if(server.running)return server;Slot latest=work;for(Slot s:new Slot[]{client,server})if(s.ended>latest.ended)latest=s;return latest;}
    static void reset(){for(Slot s:new Slot[]{work,client,server})synchronized(s){s.title="";s.step="";s.started=0;s.ended=0;s.running=false;s.lines.clear();}}
}
