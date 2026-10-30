package com.ahao.clicker.acceptance.target;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import org.json.JSONArray;
import org.json.JSONObject;

/** Measures the real optimized clicker from the independent receipt process. */
public final class TimingAcceptance extends Instrumentation {
    private Bundle options;
    private UiAutomation automation;
    private JSONObject lastPlayControl=new JSONObject();
    private final JSONArray stageHistory=new JSONArray();
    private String stage="initialization";
    private int currentIndex=-1;
    private final Uri control = Uri.parse("content://com.ahao.clicker.timing.benchmark");
    @Override public void onCreate(Bundle args) { super.onCreate(args); options = args; start(); }
    private Bundle call(String method, Bundle args) {
        Bundle value = getTargetContext().getContentResolver().call(control, method, null, args);
        if (value == null || value.containsKey("error")) throw new IllegalStateException(value == null ? "No benchmark control" : value.getString("error"));
        return value;
    }
    private void readinessStatus(String value) {
        Bundle status=new Bundle(); status.putString("readiness",value); sendStatus(1,status);
    }
    private long waitForService(long timeout) throws Exception {
        long started=SystemClock.elapsedRealtime(); boolean announced=false;
        while (!call("state",null).getBoolean("connected")) {
            if(!announced) { readinessStatus("ACCESSIBILITY_REQUIRED"); announced=true; }
            if(SystemClock.elapsedRealtime()-started>=timeout) {
                throw new IllegalStateException("Accessibility service did not connect; enable the clicker service and rerun");
            }
            Thread.sleep(250);
        }
        return SystemClock.elapsedRealtime()-started;
    }
    private AccessibilityNodeInfo find(AccessibilityNodeInfo root) {
        if (root == null) return null;
        if ("com.ahao.clicker:id/toggle_click".equals(root.getViewIdResourceName())) return root;
        for (int i=0; i<root.getChildCount(); i++) { AccessibilityNodeInfo found=find(root.getChild(i)); if (found!=null) return found; }
        return null;
    }
    private void play() throws Exception {
        long end=SystemClock.elapsedRealtime()+3_000;
        while (SystemClock.elapsedRealtime()<end) {
            for (AccessibilityWindowInfo window:automation.getWindows()) {
                AccessibilityNodeInfo node=find(window.getRoot());
                if (node!=null) {
                    lastPlayControl=new JSONObject().put("id",node.getViewIdResourceName()).put("description",String.valueOf(node.getContentDescription()))
                        .put("enabled",node.isEnabled()).put("clickable",node.isClickable()).put("visible",node.isVisibleToUser())
                        .put("actionStartedElapsed",SystemClock.elapsedRealtime());
                    boolean accepted=node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    lastPlayControl.put("actionAccepted",accepted).put("actionReturnedElapsed",SystemClock.elapsedRealtime());
                    if(accepted) return;
                }
            }
            Thread.sleep(30);
        }
        throw new IllegalStateException("No floating play control");
    }
    private JSONObject bundleJson(Bundle value) throws Exception {
        JSONObject result=new JSONObject();
        for(String key:value.keySet()) result.put(key,value.get(key));
        return result;
    }
    private void recordStage(String name, Bundle state) throws Exception {
        stage=name;
        JSONObject value=new JSONObject().put("index",currentIndex).put("stage",name).put("driverElapsed",SystemClock.elapsedRealtime());
        if(state!=null) value.put("appState",bundleJson(state));
        stageHistory.put(value);
    }
    private void captureFailure(JSONObject report, Bundle observed, long started) throws Exception {
        JSONObject snapshot=new JSONObject().put("stage",stage).put("index",currentIndex).put("startedElapsed",started);
        if(observed!=null) snapshot.put("appState",bundleJson(observed));
        try {
            Bundle diagnostic=call("diagnose",null);
            snapshot.put("diagnoseState",bundleJson(diagnostic));
            if(observed==null) snapshot.put("appState",bundleJson(diagnostic));
        } catch(Exception error) { snapshot.put("diagnoseError",error.toString()); }
        Bundle receipts=Receipts.snapshot();
        snapshot.put("receiptsObservedElapsed",SystemClock.elapsedRealtime()).put("targetFocused",receipts.getBoolean("focused"))
            .put("targetTouches",new JSONArray(receipts.getString("touches"))).put("targetEvents",new JSONArray(receipts.getString("events")))
            .put("lastPlayControl",lastPlayControl).put("finishedElapsed",SystemClock.elapsedRealtime());
        report.put("failureSnapshot",snapshot);
    }
    private void awaitTraceBarrier(JSONObject report) throws Exception {
        stage="trace_barrier";
        java.io.File signal=new java.io.File(getTargetContext().getFilesDir(),"trace-ready");
        long waitStarted=SystemClock.elapsedRealtime();
        while(!signal.exists() && SystemClock.elapsedRealtime()-waitStarted<30_000) Thread.sleep(25);
        if(!signal.exists()) throw new IllegalStateException("Trace barrier timed out");
        if(!signal.delete()) throw new IllegalStateException("Trace barrier cleanup failed");
        report.put("traceBarrierWaitMillis",SystemClock.elapsedRealtime()-waitStarted);
    }
    @Override public void onStart() {
        JSONObject report=new JSONObject(); JSONArray samples=new JSONArray(); JSONArray soak=new JSONArray(); boolean passed=false;
        try {
            int count=Integer.parseInt(options.getString("samples","30"));
            long soakMillis=Long.parseLong(options.getString("soakMillis","0"));
            long readinessTimeout=Long.parseLong(options.getString("readinessTimeoutMillis","120000"));
            long afterPlayDelay=Long.parseLong(options.getString("afterPlayDelayMillis","0"));
            long beforePlayDelay=Long.parseLong(options.getString("beforePlayDelayMillis","150"));
            long leadMillis=Long.parseLong(options.getString("leadMillis","1800"));
            report.put("requestedSamples",count).put("leadMillis",leadMillis);
            report.put("diagnosticAfterPlayDelayMillis",afterPlayDelay);
            report.put("diagnosticBeforePlayDelayMillis",beforePlayDelay);
            boolean waitForTrace=Boolean.parseBoolean(options.getString("waitForTrace","false"));
            report.put("waitForTrace",waitForTrace);
            boolean traceBeforeLaunch=Boolean.parseBoolean(options.getString("traceBeforeTargetLaunch","false"));
            report.put("traceBeforeTargetLaunch",traceBeforeLaunch);
            if(traceBeforeLaunch && !waitForTrace) throw new IllegalArgumentException("Process barrier needs host tracing");
            if(count<1 || count>100 || soakMillis<0 || soakMillis>3_600_000 || leadMillis<500 || leadMillis>30_000 || readinessTimeout<500 || readinessTimeout>120_000 || afterPlayDelay<0 || afterPlayDelay>4000 || beforePlayDelay<0 || beforePlayDelay>4000) {
                throw new IllegalArgumentException("Unbounded benchmark");
            }
            automation=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            AccessibilityServiceInfo info=automation.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            automation.setServiceInfo(info);
            report.put("readinessWaitMillis",waitForService(readinessTimeout));
            if(waitForTrace && traceBeforeLaunch) { readinessStatus("PROCESS_READY"); awaitTraceBarrier(report); }
            try (android.os.ParcelFileDescriptor fd=automation.executeShellCommand("am start -n com.ahao.clicker.acceptance.target/.TargetActivity -f 0x10008000");
                 java.io.FileInputStream input=new java.io.FileInputStream(fd.getFileDescriptor())) {
                byte[] response=new byte[1024]; while(input.read(response)>0) { /* Drain the bounded shell command output. */ }
            }
            long end=SystemClock.elapsedRealtime()+10_000;
            while ((!Receipts.snapshot().containsKey("B") || !Receipts.snapshot().getBoolean("focused")) && SystemClock.elapsedRealtime()<end) Thread.sleep(50);
            int[] point=Receipts.snapshot().getIntArray("B");
            if(point==null || !Receipts.snapshot().getBoolean("focused")) throw new IllegalStateException("Target did not focus");
            call("sync",null);
            end=SystemClock.elapsedRealtime()+12_000;
            while (!call("state",null).getBoolean("fresh") && SystemClock.elapsedRealtime()<end) Thread.sleep(150);
            if(!call("state",null).getBoolean("fresh")) throw new IllegalStateException("No fresh time sample before measurement");
            readinessStatus("READY");
            if(waitForTrace && !traceBeforeLaunch) awaitTraceBarrier(report);
            for(int i=0;i<count;i++) {
                currentIndex=i;
                Receipts.reset();
                Bundle args=new Bundle(); args.putFloat("x",point[0]); args.putFloat("y",point[1]); args.putLong("delay",soakMillis>0?soakMillis:leadMillis);
                Bundle prepared=call("prepare",args);
                recordStage("prepared",prepared);
                stage="play_action";
                Thread.sleep(beforePlayDelay); play();
                recordStage("play_returned",null);
                if(afterPlayDelay>0) Thread.sleep(afterPlayDelay); // Test-only late observation; not an input delay in the product.
                stage="arm_state_check";
                long stateReadStarted=SystemClock.elapsedRealtime();
                Bundle armed=call("state",null);
                recordStage("arm_state_check",armed);
                if(!"WAITING".equals(armed.getString("phase"))) {
                    captureFailure(report,armed,stateReadStarted);
                    throw new IllegalStateException("Arm checkpoint expected WAITING, observed "+armed.getString("phase"));
                }
                long deadline=armed.getLong("plannedElapsed");
                if(soakMillis>0) {
                    while (SystemClock.elapsedRealtime()<deadline-1_000) {
                        Bundle state=call("state",null);
                        JSONObject observation=new JSONObject();
                        for(String key:new String[]{"elapsed","sourceEpoch","taskEpoch","sourceAnchorElapsed","lastSuccessElapsed","rtt","deviceEpoch","plannedElapsed"}) if(state.containsKey(key)) observation.put(key,state.getLong(key));
                        observation.put("fresh",state.getBoolean("fresh")).put("phase",state.getString("phase"));
                        soak.put(observation);
                        Bundle update=new Bundle(); update.putString("observation",observation.toString()); sendStatus(1,update);
                        if(!"WAITING".equals(state.getString("phase"))) throw new IllegalStateException("Long wait stopped: "+state.getString("phase"));
                        deadline=state.getLong("plannedElapsed");
                        Thread.sleep(Math.min(30_000,Math.max(1,deadline-SystemClock.elapsedRealtime()-1_000)));
                    }
                }
                stage="receipt_check";
                Thread.sleep(Math.max(1,deadline-SystemClock.elapsedRealtime()+750));
                Bundle state=call("state",null), receipts=Receipts.snapshot();
                JSONArray touches=new JSONArray(receipts.getString("touches"));
                JSONArray events=new JSONArray(receipts.getString("events"));
                if(events.length()!=1 || !"B".equals(events.getJSONObject(0).getString("point"))) {
                    JSONObject failure=new JSONObject();
                    for(String key:state.keySet()) failure.put(key,state.get(key));
                    report.put("failureState",failure).put("failureTouches",touches).put("failureEvents",events)
                        .put("failureTargetFocused",receipts.getBoolean("focused")).put("failureIndex",i);
                    throw new IllegalStateException("Expected one actual B receipt: "+events);
                }
                JSONObject down=null,up=null;
                for(int j=0;j<touches.length();j++) {
                    JSONObject touch=touches.getJSONObject(j);
                    if(touch.getInt("action")==0) { if(down!=null) throw new IllegalStateException("Extra DOWN"); down=touch; }
                    if(touch.getInt("action")==1) { if(up!=null) throw new IllegalStateException("Extra UP"); up=touch; }
                }
                if(down==null || up==null || !"COMPLETED".equals(state.getString("phase"))) throw new IllegalStateException("Incomplete real gesture");
                JSONObject sample=new JSONObject().put("index",i).put("targetEpoch",prepared.getLong("target"));
                for(String key:new String[]{"plannedElapsed","dispatchedElapsed","callbackElapsed","rtt"}) sample.put(key,state.getLong(key));
                sample.put("downReceivedElapsed",down.getLong("elapsed")).put("downEventUptime",down.getLong("eventUptime"))
                    .put("upReceivedElapsed",up.getLong("elapsed")).put("upEventUptime",up.getLong("eventUptime"))
                    .put("clickReceivedElapsed",events.getJSONObject(0).getLong("elapsed"));
                samples.put(sample); Bundle update=new Bundle(); update.putString("sample",sample.toString()); sendStatus(1,update);
                call("stop",null); Thread.sleep(100);
            }
            report.put("appInstrumentationAttached",false).put("optimized",true); passed=true;
        } catch(Throwable error) {
            try { report.put("error",android.util.Log.getStackTraceString(error)); } catch(Exception ignored){}
            if(!report.has("failureSnapshot")) {
                try { captureFailure(report,null,SystemClock.elapsedRealtime()); }
                catch(Exception snapshotError) { try { report.put("snapshotError",snapshotError.toString()); } catch(Exception ignored){} }
            }
        }
        finally {
            try { call("stop",null); } catch(Exception ignored){}
            try {
                report.put("passed",passed).put("samples",samples).put("soak",soak).put("stageHistory",stageHistory);
                try(java.io.FileOutputStream out=getTargetContext().openFileOutput("timing-acceptance.json",0)) { out.write(report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            } catch(Exception ignored){}
            Bundle result=new Bundle(); result.putString("timing_json",report.toString()); finish(passed?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
        }
    }
}
