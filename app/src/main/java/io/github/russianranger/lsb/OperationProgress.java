package io.github.russianranger.lsb;

import android.content.Context;
import android.os.*;
import io.github.russianranger.lsb.core.FilesEx;
import java.io.File;
import java.util.*;
import java.util.regex.*;
import org.json.*;

/** Small durable progress receipts; diagnostic logs remain separately archived. */
final class OperationProgress {
    static final Slot work=new Slot(),client=new Slot(),server=new Slot();
    static final class Slot {
        String title="",step="",outcome="",lane="";long started,ended,clock,elapsed,updated,lastWrite;
        volatile boolean running;
        final ArrayDeque<String> lines=new ArrayDeque<>();
        private Context context;private Handler heartbeat;
        private long copyTotal,copyFirst,copyClock,remaining=-1;
        private static final Pattern COPY=Pattern.compile("Copying working installation · (\\d+) / (\\d+) MiB");
        private final Runnable tick=new Runnable(){public void run(){synchronized(Slot.this){if(running){checkpoint();heartbeat.postDelayed(this,5000);}}}};
        synchronized void begin(String lane,String title){
            clear();this.lane=lane;this.title=title;step=title;started=System.currentTimeMillis();clock=SystemClock.elapsedRealtime();running=true;lines.add(title);
        }
        synchronized void begin(Context c,String lane,String title){
            begin(lane,title);context=c.getApplicationContext();persist();heartbeat=new Handler(Looper.getMainLooper());heartbeat.postDelayed(tick,5000);
        }
        synchronized void update(String text){
            if(text!=null&&!text.isEmpty()&&!text.equals(step)){
                step=text;lines.add(text);while(lines.size()>12)lines.removeFirst();estimate(text);
            }
            if(running&&context!=null&&SystemClock.elapsedRealtime()-lastWrite>=2000)persist();
        }
        private void estimate(String text){
            Matcher m=COPY.matcher(text);if(!m.matches()){copyTotal=0;remaining=-1;return;}
            try{
                long done=Long.parseLong(m.group(1)),total=Long.parseLong(m.group(2)),now=SystemClock.elapsedRealtime();
                if(total<=0||done>total){remaining=-1;return;}
                if(copyTotal!=total||done<copyFirst){copyTotal=total;copyFirst=done;copyClock=now;remaining=-1;}
                else if(now-copyClock>=5000&&done>copyFirst)remaining=(long)((double)(total-done)*(now-copyClock)/(done-copyFirst)/1000);
            }catch(NumberFormatException ignored){remaining=-1;}
        }
        synchronized void checkpoint(){if(running&&context!=null&&SystemClock.elapsedRealtime()-lastWrite>=5000)persist();}
        synchronized void finish(Context c,String outcome,String text){
            update(text);elapsed=elapsedMillis();this.outcome=outcome;ended=System.currentTimeMillis();running=false;
            if(heartbeat!=null)heartbeat.removeCallbacks(tick);context=c.getApplicationContext();remaining=-1;persist();
        }
        private long elapsedMillis(){return running?Math.max(0,SystemClock.elapsedRealtime()-clock):elapsed;}
        private void persist(){
            if(context==null)return;
            try{
                updated=System.currentTimeMillis();
                ClientRuntime.write(new File(context.getFilesDir(),"progress/"+lane+".json"),new JSONObject().put("title",title).put("step",step).put("outcome",outcome)
                    .put("started",started).put("ended",ended).put("updated",updated).put("elapsed_millis",elapsedMillis()).put("running",running).put("lines",new JSONArray(lines)).toString());
                lastWrite=SystemClock.elapsedRealtime();
            }catch(Exception ignored){}
        }
        synchronized String summary(){
            if(title.isEmpty())return "Ready";
            return (running?title:outcome+" · "+title)+"\n"+step+"\nElapsed · "+duration(elapsedMillis()/1000)+(running&&remaining>=0?" · about "+duration(remaining)+" remaining":"");
        }
        synchronized String details(){return summary()+"\n\nRecent progress for this operation\n"+String.join("\n",lines);}
        synchronized void load(Context c,String lane){
            if(running||started>0)return;
            try{
                JSONObject j=new JSONObject(FilesEx.read(new File(c.getFilesDir(),"progress/"+lane+".json"),65536));
                this.lane=lane;title=j.getString("title");step=j.getString("step");outcome=j.getString("outcome");started=j.getLong("started");ended=j.getLong("ended");
                updated=j.optLong("updated",Math.max(started,ended));elapsed=j.optLong("elapsed_millis",Math.max(0,ended-started));
                JSONArray recent=j.optJSONArray("lines");if(recent!=null)for(int i=Math.max(0,recent.length()-12);i<recent.length();i++)lines.add(recent.getString(i));
                if(lines.isEmpty())lines.add(step);
                if(j.optBoolean("running")){
                    outcome="Interrupted";ended=updated;lines.add("The previous app process ended before completion was recorded. The last saved step is shown above. Check Diagnostics before retrying.");
                    while(lines.size()>12)lines.removeFirst();context=c.getApplicationContext();persist();
                }
            }catch(Exception ignored){}
        }
        synchronized void clear(){
            if(heartbeat!=null)heartbeat.removeCallbacks(tick);heartbeat=null;context=null;title="";step="";outcome="";started=0;ended=0;clock=0;elapsed=0;updated=0;lastWrite=0;running=false;lines.clear();copyTotal=0;remaining=-1;
        }
    }
    static String duration(long seconds){return seconds>=3600?String.format(Locale.ROOT,"%dh %02dm %02ds",seconds/3600,seconds/60%60,seconds%60):String.format(Locale.ROOT,"%dm %02ds",seconds/60,seconds%60);}
    static void load(Context c){work.load(c,"work");client.load(c,"client");server.load(c,"server");}
    static Slot current(){if(work.running)return work;if(client.running)return client;if(server.running)return server;Slot latest=work;for(Slot s:new Slot[]{client,server})if(s.ended>latest.ended)latest=s;return latest;}
    static void reset(){for(Slot s:new Slot[]{work,client,server})s.clear();}
}
