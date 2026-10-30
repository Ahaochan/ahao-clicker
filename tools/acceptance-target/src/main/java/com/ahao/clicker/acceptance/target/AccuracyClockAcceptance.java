package com.ahao.clicker.acceptance.target;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

/** Pixel observations of the composed emulator display, not an OnDraw character reader. */
public final class AccuracyClockAcceptance extends Instrumentation {
    private Bundle args;
    private UiAutomation ui;
    private File directory;
    private long lastProbe=Long.MIN_VALUE/2;
    private final JSONObject report=new JSONObject();
    private final JSONArray samples=new JSONArray();
    private final Uri uri=Uri.parse("content://com.ahao.clicker.timing.benchmark");
    @Override public void onCreate(Bundle options){super.onCreate(options);args=options;start();}
    private Bundle call(String method,Bundle extras) {
        Bundle value=getTargetContext().getContentResolver().call(uri,method,null,extras);
        if(value==null||(value.getString("error")!=null&&!value.getString("error").isEmpty()))throw new IllegalStateException(method+": "+value);
        return value;
    }
    private JSONObject json(Bundle b)throws Exception {
        JSONObject value=new JSONObject();for(String key:b.keySet())value.put(key,b.get(key));return value;
    }
    private void saveJson(String name,JSONObject value)throws Exception {
        try(FileOutputStream out=new FileOutputStream(new File(directory,name))){out.write(value.toString().getBytes(StandardCharsets.UTF_8));}
    }
    private void png(String name,Bitmap bitmap)throws Exception {
        try(FileOutputStream out=new FileOutputStream(new File(directory,name))){
            if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IllegalStateException("PNG encode failed");
        }
    }
    private JSONObject probe()throws Exception {
        long pause=lastProbe+1000-SystemClock.elapsedRealtime();if(pause>0)Thread.sleep(pause);
        lastProbe=SystemClock.elapsedRealtime();JSONObject row=new JSONObject();
        HttpsURLConnection connection=null;
        try {
            connection=(HttpsURLConnection)new URL("https://api.m.jd.com").openConnection();
            connection.setRequestMethod("OPTIONS");connection.setConnectTimeout(3000);connection.setReadTimeout(3000);
            connection.setUseCaches(false);connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Cache-Control","no-cache, no-store");
            long sent=SystemClock.elapsedRealtimeNanos();connection.connect();long connected=SystemClock.elapsedRealtimeNanos();
            int status=connection.getResponseCode();java.util.Map<String,java.util.List<String>> headers=connection.getHeaderFields();
            long received=SystemClock.elapsedRealtimeNanos();
            row.put("sentNs",sent).put("connectedNs",connected).put("receivedNs",received).put("httpStatus",status)
                .put("requestId",connection.getHeaderField("x-api-request-id")).put("age",connection.getHeaderField("Age"))
                .put("date",connection.getHeaderField("Date"));
            JSONArray raw=new JSONArray();
            for(java.util.Map.Entry<String,java.util.List<String>> entry:headers.entrySet())
                if(entry.getKey()!=null&&entry.getKey().equalsIgnoreCase("x-api-request-id"))for(String v:entry.getValue())raw.put(v);
            row.put("rawRequestIds",raw).put("serverEpoch",OracleHeaders.epoch(status,headers));
            if(received-sent>2_000_000_000L)throw new IllegalStateException("Reference RTT over 2000ms");
        } catch(Exception e){row.put("error",e.toString());}
        finally {if(connection!=null)connection.disconnect();}
        return row;
    }
    private void templates(Bundle geometry)throws Exception {
        int width=geometry.getInt("width"),height=geometry.getInt("height");
        if(width<=0||height<=0||geometry.getFloat("textWidth")<=0)throw new IllegalStateException("Invalid geometry");
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(geometry.getInt("textColor"));
        paint.setTypeface(Typeface.MONOSPACE);paint.setTextSize(geometry.getFloat("textSize"));
        float scale=Math.min(1f,width/geometry.getFloat("textWidth"));
        for(int digit=0;digit<10;digit++) {
            Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
            try {
                Canvas canvas=new Canvas(bitmap);canvas.drawColor(geometry.getInt("backgroundColor"));canvas.scale(scale,scale);
                String value=(""+digit+digit+":"+digit+digit+":"+digit+digit+":"+digit+digit+digit);
                canvas.drawText(value,0,(height/scale-paint.ascent()-paint.descent())/2,paint);
                png("template-"+digit+".png",bitmap);
            }finally{bitmap.recycle();}
        }
        // Independently known valid strings for offline decoder control, never production state.
        String[] values={"09:18:27:345","23:59:58:670","00:00:00:000"};
        JSONArray controls=new JSONArray();
        for(int i=0;i<values.length;i++) {
            Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
            try {Canvas c=new Canvas(bitmap);c.drawColor(geometry.getInt("backgroundColor"));c.scale(scale,scale);
                c.drawText(values[i],0,(height/scale-paint.ascent()-paint.descent())/2,paint);png("control-"+i+".png",bitmap);
            }finally{bitmap.recycle();}
            controls.put(new JSONObject().put("file","control-"+i+".png").put("expected",values[i]));
        }
        report.put("decoderControls",controls);
    }
    @Override public void onStart() {
        boolean passed=false;ScheduledExecutorService heartbeat=null;boolean begun=false;
        try {
            String id=args.getString("id","");long duration=Long.parseLong(args.getString("durationMillis","3600000"));
            long interval=Long.parseLong(args.getString("sampleMillis","30000"));
            if(!id.matches("[a-zA-Z0-9_-]{1,80}")||duration<1000||duration>3600000||interval<3000||interval>60000)
                throw new IllegalArgumentException("Invalid bounded observation");
            directory=new File(getTargetContext().getExternalFilesDir(null),"accuracy/"+id);
            if(directory.exists()||!directory.mkdirs())throw new IllegalStateException("Preserve existing directory");
            report.put("id",id).put("durationMillis",duration).put("sampleMillis",interval).put("plannedSamples",duration/interval+1)
                .put("samples",samples).put("clockSource","JD").put("syncIntervalMillis",60000)
                .put("observation","UiAutomation composed-display pixels; capture time bracket; not physical panel light")
                .put("oracle","Independent JD OPTIONS before/after; includes full connection bracket")
                .put("highFrequencyObserver",false);
            report.put("zoneOffsetMillis",java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()));
            ui=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            heartbeat=Executors.newSingleThreadScheduledExecutor();heartbeat.scheduleWithFixedDelay(()->{
                Bundle pulse=new Bundle();pulse.putLong("heartbeatNs",SystemClock.elapsedRealtimeNanos());sendStatus(2,pulse);
            },0,1,TimeUnit.SECONDS);
            long timeout=SystemClock.elapsedRealtime()+30000;
            while(!call("v3_probe",null).getBoolean("connected")&&SystemClock.elapsedRealtime()<timeout)Thread.sleep(250);
            if(!call("v3_probe",null).getBoolean("connected"))throw new IllegalStateException("Service disconnected");
            Bundle start=new Bundle();start.putString("id",id);start.putString("mode","off");start.putBoolean("traceFrames",false);
            call("v3_start",start);begun=true;
            call("clock",null);
            try(android.os.ParcelFileDescriptor fd=ui.executeShellCommand("am start -n com.ahao.clicker.acceptance.target/.TargetActivity -f 0x10008000");
                java.io.FileInputStream input=new java.io.FileInputStream(fd.getFileDescriptor())) {
                byte[] buffer=new byte[1024];while(input.read(buffer)>0) { }
            }
            timeout=SystemClock.elapsedRealtime()+15000;
            while(!call("v3_probe",null).getBoolean("fresh")&&SystemClock.elapsedRealtime()<timeout)Thread.sleep(150);
            if(!call("v3_probe",null).getBoolean("fresh"))throw new IllegalStateException("No fresh app sample");
            timeout=SystemClock.elapsedRealtime()+10000;
            while((Receipts.snapshot().getIntArray("B")==null||!Receipts.snapshot().getBoolean("focused"))&&SystemClock.elapsedRealtime()<timeout)Thread.sleep(100);
            if(Receipts.snapshot().getIntArray("B")==null||!Receipts.snapshot().getBoolean("focused"))throw new IllegalStateException("Target not ready");
            Thread.sleep(500);Bundle geometry=call("v3_geometry",null);report.put("geometry",json(geometry));templates(geometry);
            long warmBefore=SystemClock.elapsedRealtimeNanos();Bitmap warmup=ui.takeScreenshot();long warmAfter=SystemClock.elapsedRealtimeNanos();
            if(warmup==null)throw new IllegalStateException("Warmup screenshot null");
            try{png("screen-warmup.png",warmup);}finally{warmup.recycle();}
            report.put("screenshotWarmup",new JSONObject().put("beforeNs",warmBefore).put("afterNs",warmAfter).put("includedInSamples",false));
            Thread.sleep(1000);
            long started=SystemClock.elapsedRealtime();report.put("startedElapsed",started);saveJson("result.json",report);
            for(long slot=0;;slot++) {
                long due=started+Math.min(slot*interval,duration);Thread.sleep(Math.max(1,due-SystemClock.elapsedRealtime()));
                if(SystemClock.elapsedRealtime()-due>5000)throw new IllegalStateException("Sampling stalled; no catch-up burst");
                JSONObject row=new JSONObject().put("slot",slot).put("scheduledElapsed",due);
                try {
                    row.put("oracleBefore",probe());
                    long before=SystemClock.elapsedRealtimeNanos();Bitmap image=ui.takeScreenshot();long after=SystemClock.elapsedRealtimeNanos();
                    if(image==null)throw new IllegalStateException("Screenshot null");
                    row.put("captureBeforeNs",before).put("captureAfterNs",after).put("imageWidth",image.getWidth()).put("imageHeight",image.getHeight());
                    String filename=String.format(java.util.Locale.ROOT,"screen-%03d.png",slot);row.put("image",filename);
                    try{png(filename,image);}finally{image.recycle();}
                    row.put("appState",json(call("state",null))).put("oracleAfter",probe());
                    if(!Receipts.snapshot().getBoolean("focused"))throw new IllegalStateException("Target not foreground");
                }catch(Exception e){row.put("error",e.toString());}
                samples.put(row);saveJson("result.json",report);
                Bundle progress=new Bundle();progress.putLong("slot",slot);sendStatus(1,progress);
                if(due==started+duration)break;
            }
            report.put("observedDurationMillis",SystemClock.elapsedRealtime()-started);passed=true;
        }catch(Throwable e){try{report.put("error",android.util.Log.getStackTraceString(e));}catch(Exception ignored){}}
        finally {
            if(begun)try{report.put("appRecorder",json(call("v3_end",null)));call("stop",null);}catch(Exception e){try{report.put("cleanupError",e.toString());}catch(Exception ignored){}}
            if(heartbeat!=null)heartbeat.shutdownNow();
            try{report.put("collectionCompleted",passed);if(directory!=null)saveJson("result.json",report);}catch(Exception ignored){}
            Bundle result=new Bundle();result.putString("accuracyDirectory",directory==null?"":directory.toString());result.putBoolean("collectionCompleted",passed);
            finish(passed?android.app.Activity.RESULT_OK:android.app.Activity.RESULT_CANCELED,result);
        }
    }
}
