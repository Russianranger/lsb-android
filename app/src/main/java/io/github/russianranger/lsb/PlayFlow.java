package io.github.russianranger.lsb;

import java.io.IOException;

/** Wait for supervisor readiness, never just an open login port. */
final class PlayFlow {
    interface Server {
        boolean alive();
        boolean ready();
        String status();
        void start()throws Exception;
    }
    interface Wait {
        long now();
        void pause()throws Exception;
        void progress(String text);
    }
    static void await(Server server,Wait wait,long timeoutMillis)throws Exception {
        long started=wait.now();boolean seen=server.alive();
        if(!seen)server.start();
        while(true){
            if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("Play cancelled");
            if(server.ready())return;
            boolean alive=server.alive();long elapsed=wait.now()-started;
            if(!alive&&(seen||elapsed>=10000))throw new IOException("Server did not become ready: "+server.status());
            seen|=alive;
            if(elapsed>=timeoutMillis)throw new IOException("Server is still loading. Check Server logs before trying Play again.");
            wait.progress("Starting server · "+elapsed/1000+"s\n"+server.status());
            wait.pause();
        }
    }
}
