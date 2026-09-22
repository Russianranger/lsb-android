package io.github.russianranger.lsb;

/** Transport/display counters. Neither redraws nor RFB updates measure game FPS. */
final class ClientFrameStats {
    final long started;
    private long since,updates,draws,receiveNs,decodeNs,drawNs,pixels;
    private long conversionNs,applyNs,maxGapNs,maxDecodeNs,maxDrawNs,gaps50,gaps100;
    private long totalUpdates,totalDraws,totalPixels,payloadBytes,totalPayloadBytes,zrleRects,rawRects,zrleNs;
    synchronized void payload(long bytes,boolean compressed){payloadBytes+=bytes;totalPayloadBytes+=bytes;if(compressed)zrleRects++;else rawRects++;}
    synchronized void compressedDecode(long ns){zrleNs+=ns;}
    private long generation,drawnGeneration,lastReceived;
    ClientFrameStats(){this(System.nanoTime());}
    ClientFrameStats(long now){started=since=now;}
    synchronized void stages(long conversion,long apply){conversionNs+=conversion;applyNs+=apply;}
    synchronized void received(long now,long receive,long decode,long rawPixels){
        if(lastReceived!=0){long gap=now-lastReceived;maxGapNs=Math.max(maxGapNs,gap);if(gap>50_000_000)gaps50++;if(gap>100_000_000)gaps100++;}
        updates++;totalUpdates++;generation++;lastReceived=now;receiveNs+=receive;decodeNs+=decode;pixels+=rawPixels;totalPixels+=rawPixels;
        maxDecodeNs=Math.max(maxDecodeNs,decode);
    }
    synchronized void drawn(long nanos){
        // UI invalidation/focus changes may redraw the same bitmap. Count once.
        if(drawnGeneration!=generation){draws++;totalDraws++;drawNs+=nanos;maxDrawNs=Math.max(maxDrawNs,nanos);drawnGeneration=generation;}
    }
    static final class Sample {
        final long connection;
        final double[] values;
        Sample(long connection,double[] values){this.connection=connection;this.values=values;}
    }
    synchronized Sample sample(long now){
        double seconds=(now-since)/1e9;
        if(seconds<=0)return null;
        double[] result={seconds,updates/seconds,draws/seconds,
            updates==0?0:receiveNs/1e6/updates,updates==0?0:decodeNs/1e6/updates,
            draws==0?0:drawNs/1e6/draws,pixels/seconds,
            lastReceived==0?-1:(now-lastReceived)/1e9,
            updates==0?0:conversionNs/1e6/updates,updates==0?0:applyNs/1e6/updates,
            (since-started)/1e9,updates,draws,maxGapNs/1e6,maxDecodeNs/1e6,maxDrawNs/1e6,gaps50,gaps100,
            totalUpdates,totalDraws,totalPixels,payloadBytes,zrleRects,rawRects,totalPayloadBytes,zrleNs/1e6};
        since=now;updates=draws=receiveNs=decodeNs=drawNs=pixels=conversionNs=applyNs=0;
        maxGapNs=maxDecodeNs=maxDrawNs=gaps50=gaps100=0;payloadBytes=zrleRects=rawRects=zrleNs=0;
        return new Sample(started,result);
    }
}
