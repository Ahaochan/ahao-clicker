package com.ahao.clicker.acceptance.target;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URL;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;

/** Independent process: never reads the app parser/reference to construct the external oracle. */
public final class ClockDriftAcceptance extends Instrumentation {
    private Bundle options;
    private UiAutomation automation;
    private final JSONObject report = new JSONObject();
    private final JSONArray rows = new JSONArray();
    private final Uri control = Uri.parse("content://com.ahao.clicker.timing.benchmark");
    @Override public void onCreate(Bundle args) { super.onCreate(args); options=args; start(); }
    private Bundle call(String method) {
        Bundle value=getTargetContext().getContentResolver().call(control,method,null,null);
        if(value==null || value.containsKey("error")) throw new IllegalStateException("Clock control: "+value);
        return value;
    }
    private void persist() throws Exception {
        try(java.io.FileOutputStream out=getTargetContext().openFileOutput("clock-drift.json",0)) {
            out.write(report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    private void screenshot(String name) throws Exception {
        android.graphics.Bitmap bitmap=automation.takeScreenshot();
        if(bitmap==null) throw new IllegalStateException("No screenshot");
        try(java.io.FileOutputStream out=getTargetContext().openFileOutput(name,0)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out); }
        finally { bitmap.recycle(); }
    }
    private JSONObject observe() throws Exception {
        HttpsURLConnection connection=(HttpsURLConnection)new URL("https://api.m.jd.com").openConnection();
        connection.setRequestMethod("OPTIONS"); connection.setConnectTimeout(3000); connection.setReadTimeout(3000);
        connection.setUseCaches(false); connection.setInstanceFollowRedirects(false);
        try {
            connection.connect();
            long sent=SystemClock.elapsedRealtime();
            int status=connection.getResponseCode();
            String id=connection.getHeaderField("x-api-request-id");
            long received=SystemClock.elapsedRealtime();
            if(status<200 || status>=300 || connection.getHeaderFieldInt("Age",0)>0) throw new IllegalStateException("Oracle status/cache "+status);
            Matcher matcher=Pattern.compile("^[0-9]+-[0-9]+-([0-9]{13})$").matcher(id==null?"":id);
            if(!matcher.matches() || received-sent>2000) throw new IllegalStateException("Oracle header/RTT invalid");
            long epoch=Long.parseLong(matcher.group(1)), midpoint=sent+(received-sent)/2;
            Bundle state=call("state");
            long elapsed=state.getLong("elapsed"), drawnAt=state.getLong("drawnElapsed");
            String text=state.getString("drawnText","");
            String[] digits=text.split(":");
            if(digits.length!=4 || drawnAt<0) throw new IllegalStateException("Clock has not actually drawn: "+text);
            long shown=((Long.parseLong(digits[0])*60+Long.parseLong(digits[1]))*60+Long.parseLong(digits[2]))*1000+Long.parseLong(digits[3]);
            long expectedEpoch=epoch+elapsed-midpoint;
            long expectedDay=Math.floorMod(expectedEpoch+TimeZone.getDefault().getOffset(expectedEpoch),86400000);
            long error=Math.floorMod(shown-expectedDay+43200000,86400000)-43200000;
            JSONObject row=new JSONObject().put("elapsed",elapsed).put("drawnText",text).put("drawnElapsed",drawnAt)
                .put("drawAgeMillis",elapsed-drawnAt).put("draws",state.getLong("draws"))
                .put("oracleEpoch",epoch).put("oracleSentElapsed",sent).put("oracleReceivedElapsed",received)
                .put("oracleRttMillis",received-sent).put("oracleUncertaintyMillis",(received-sent+1)/2)
                .put("displayErrorMillis",error).put("referenceErrorMillis",state.getLong("sourceEpoch")-expectedEpoch)
                .put("referenceToDisplayLagMillis",state.getLong("sourceEpoch")-expectedEpoch-error)
                .put("minimumAbsErrorMillis",Math.max(0,Math.abs(error)-(received-sent+1)/2))
                .put("maximumAbsErrorMillis",Math.abs(error)+(received-sent+1)/2)
                .put("sourceRttMillis",state.getLong("rtt")).put("sourceAnchorElapsed",state.getLong("sourceAnchorElapsed"))
                .put("lastSuccessElapsed",state.getLong("lastSuccessElapsed")).put("fresh",state.getBoolean("fresh"))
                .put("deviceEpoch",state.getLong("deviceEpoch")).put("uptime",state.getLong("uptime"))
                .put("targetFocused",Receipts.snapshot().getBoolean("focused"));
            return row;
        } finally { connection.disconnect(); }
    }
    @Override public void onStart() {
        boolean passed=false;
        try {
            long duration=Long.parseLong(options.getString("durationMillis","3600000"));
            long interval=Long.parseLong(options.getString("sampleMillis","30000"));
            long limit=Long.parseLong(options.getString("maxErrorMillis","1000"));
            if(duration<1000 || duration>7200000 || interval<1000 || interval>60000 || limit<1 || limit>1000) throw new IllegalArgumentException("Unbounded observation");
            automation=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            long end=SystemClock.elapsedRealtime()+30000;
            while(!call("state").getBoolean("connected") && SystemClock.elapsedRealtime()<end) Thread.sleep(250);
            if(!call("state").getBoolean("connected")) throw new IllegalStateException("Service disconnected");
            call("clock");
            // Move the main Activity to the background. The overlay must own periodic synchronization itself.
            try(android.os.ParcelFileDescriptor fd=automation.executeShellCommand("am start -n com.ahao.clicker.acceptance.target/.TargetActivity -f 0x10008000");
                java.io.FileInputStream input=new java.io.FileInputStream(fd.getFileDescriptor())) {
                byte[] buffer=new byte[1024]; while(input.read(buffer)>0) { }
            }
            end=SystemClock.elapsedRealtime()+10000;
            while(!Receipts.snapshot().getBoolean("focused") && SystemClock.elapsedRealtime()<end) Thread.sleep(50);
            if(!Receipts.snapshot().getBoolean("focused")) throw new IllegalStateException("Main Activity did not move to background");
            Receipts.reset();
            end=SystemClock.elapsedRealtime()+15000;
            while(!call("state").getBoolean("fresh") && SystemClock.elapsedRealtime()<end) Thread.sleep(150);
            if(!call("state").getBoolean("fresh")) throw new IllegalStateException("No fresh JD sample");
            Thread.sleep(500); screenshot("clock-start.png");
            report.put("rows",rows).put("requestedDurationMillis",duration).put("sampleMillis",interval)
                .put("oracle","Separate process OPTIONS https://api.m.jd.com; RTT midpoint")
                .put("displayObservation","OnDraw traversal characters; no forced update/draw")
                .put("appInstrumentationAttached",false).put("syncIntervalMillis",60000).put("errorLimitMillis",limit);
            long started=SystemClock.elapsedRealtime();
            report.put("startedElapsed",started);
            for(long index=0;;index++) {
                long scheduled=started+Math.min(index*interval,duration);
                Thread.sleep(Math.max(1,scheduled-SystemClock.elapsedRealtime()));
                if(SystemClock.elapsedRealtime()-scheduled>5000) throw new IllegalStateException("Sampling stalled; refusing catch-up request burst");
                JSONObject row;
                try { row=observe(); }
                catch(Exception error) { row=new JSONObject().put("elapsed",SystemClock.elapsedRealtime()).put("error",error.toString()); }
                rows.put(row); persist();
                Bundle update=new Bundle(); update.putString("clock_sample",row.toString()); sendStatus(1,update);
                if(scheduled==started+duration) break;
            }
            report.put("observedDurationMillis",SystemClock.elapsedRealtime()-started);
            screenshot("clock-end.png");
            int valid=0,overLimit=0,confirmed=0,inconclusive=0,within=0; java.util.Set<Long> anchors=new java.util.HashSet<>();
            for(int i=0;i<rows.length();i++) {
                JSONObject row=rows.getJSONObject(i);
                if(row.has("error")) continue;
                valid++; anchors.add(row.getLong("lastSuccessElapsed"));
                if(Math.abs(row.getLong("displayErrorMillis"))>limit) overLimit++;
                if(row.getLong("minimumAbsErrorMillis")>limit) confirmed++;
                else if(row.getLong("maximumAbsErrorMillis")<=limit) within++;
                else inconclusive++;
                if(!row.getBoolean("fresh") || row.getLong("drawAgeMillis")>=500) throw new IllegalStateException("Clock stale: "+row);
            }
            report.put("validSamples",valid).put("failedOracleSamples",rows.length()-valid).put("distinctSuccesses",anchors.size())
                .put("estimatedOverLimitSamples",overLimit).put("confirmedOverLimitSamples",confirmed)
                .put("inconclusiveSamples",inconclusive).put("withinLimitConsideringOracleRtt",within)
                .put("strictLimitCertified",within==rows.length()).put("estimatedLimitPassed",overLimit==0);
            if(valid<rows.length()*0.95 || (duration>=120000 && anchors.size()<duration/60000-1)) throw new IllegalStateException("Insufficient independent evidence/synchronizations");
            report.put("unexpectedTargetClicks",Receipts.snapshot().getInt("count"));
            if(Receipts.snapshot().getInt("count")!=0) throw new IllegalStateException("Observation must not execute clicks");
            if(overLimit>0) throw new IllegalStateException("Estimated "+limit+" ms target exceeded in "+overLimit+" samples");
            passed=true;
        } catch(Throwable error) { try { report.put("error",android.util.Log.getStackTraceString(error)); } catch(Exception ignored) {} }
        finally {
            try { call("stop"); } catch(Exception ignored) {}
            try { report.put("passed",passed).put("rows",rows); persist(); } catch(Exception ignored) {}
            Bundle result=new Bundle(); result.putString("clock_drift_json",report.toString()); finish(passed?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
        }
    }
}
