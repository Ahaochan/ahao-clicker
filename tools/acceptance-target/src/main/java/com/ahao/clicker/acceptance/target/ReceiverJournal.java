package com.ahao.clicker.acceptance.target;

import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import com.ahao.clicker.core.TimingBuffer;
import java.io.BufferedOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Entry-point timestamps precede legacy receipt JSON and target View work. */
final class ReceiverJournal {
    static volatile boolean active;
    static volatile long attempt, downNs, upNs, clickNs, downUptime, upUptime;
    static volatile int downs, ups, clicks, point;
    static volatile boolean touchOpen;
    static volatile String failure="";
    static volatile long criticalStart=Long.MAX_VALUE, criticalEnd=Long.MIN_VALUE;
    private static TimingBuffer buffer;
    private static BufferedOutputStream output;
    private static ScheduledExecutorService worker;
    private static final long[] drain=new long[8192];
    private static final ByteBuffer bytes=ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN);

    static synchronized void start(Context context,String id,boolean binary) throws Exception {
        if(active || worker!=null || !id.matches("[a-zA-Z0-9_-]{1,100}")) throw new IllegalStateException("Target session conflict");
        File directory=new File(context.getExternalFilesDir(null),"v3/"+id);
        if(directory.exists() || !directory.mkdirs()) throw new IllegalStateException("Target session exists");
        output=new BufferedOutputStream(new java.io.FileOutputStream(new File(directory,"receipts.bin")),65536);
        buffer=binary?new TimingBuffer(8192):null;failure="";attempt=0;active=true;
        criticalStart=Long.MAX_VALUE;criticalEnd=Long.MIN_VALUE;
        worker=Executors.newSingleThreadScheduledExecutor(task->{Thread t=new Thread(task,"receipt-journal");t.setDaemon(true);return t;});
        worker.scheduleWithFixedDelay(()->flush(false),250,250,TimeUnit.MILLISECONDS);
    }
    static void begin(long id) {
        if(touchOpen) throw new IllegalStateException("Unclosed prior touch");
        attempt=id;downs=0;ups=0;clicks=0;point=0;downNs=0;upNs=0;clickNs=0;
        record(300,id,0,0,0);
    }
    static void record(int event,long a,long b,long c,long d) {
        if(active && buffer!=null && !buffer.record(SystemClock.elapsedRealtimeNanos(),Process.myTid(),event,a,b,c,d,attempt)) failure="target_buffer_overflow";
    }
    static void touch(android.view.MotionEvent event) {
        long entered=SystemClock.elapsedRealtimeNanos();
        if(!active) return;
        int action=event.getActionMasked();
        if(action==0){downNs=entered;downUptime=event.getEventTime();downs++;touchOpen=true;}
        if(action==1){upNs=entered;upUptime=event.getEventTime();ups++;touchOpen=false;}
        if(action==3) touchOpen=false;
        long packed=((long)Float.floatToRawIntBits(event.getRawX())<<32)|(Float.floatToRawIntBits(event.getRawY())&0xffffffffL);
        if(buffer!=null && !buffer.record(entered,Process.myTid(),400+action,event.getEventTime(),event.getDownTime(),packed,0,attempt)) failure="target_buffer_overflow";
    }
    static void click(String name) {
        long entered=SystemClock.elapsedRealtimeNanos();
        if(!active) return;
        clickNs=entered;clicks++;point=name.charAt(0);
        if(buffer!=null && !buffer.record(entered,Process.myTid(),420,point,0,0,0,attempt)) failure="target_buffer_overflow";
    }
    private static synchronized void flush(boolean force) {
        long now=SystemClock.elapsedRealtime();
        if(!force && now>=criticalStart && now<=criticalEnd) return;
        try {
            for(;;){int n=buffer==null?0:buffer.drain(drain);if(n==0)break;bytes.clear();for(int i=0;i<n*8;i++)bytes.putLong(drain[i]);output.write(bytes.array(),0,n*64);}
            if(output!=null)output.flush();
        }catch(Exception error){failure=error.toString();}
    }
    static void stop() throws Exception {
        active=false;
        ScheduledExecutorService service=worker;worker=null;
        if(service!=null){service.shutdown();service.awaitTermination(5,TimeUnit.SECONDS);}
        synchronized(ReceiverJournal.class){flush(true);if(output!=null){output.close();output=null;}}
    }
    static long dropped(){return buffer==null?0:buffer.getDropped();}
}
