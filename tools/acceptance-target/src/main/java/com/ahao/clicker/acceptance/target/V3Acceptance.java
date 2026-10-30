package com.ahao.clicker.acceptance.target;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

/** Public controls drive tasks; independent fault controls use the benchmark-only provider. */
public final class V3Acceptance extends Instrumentation {
    private Bundle args;
    private UiAutomation ui;
    private final Uri control=Uri.parse("content://com.ahao.clicker.timing.benchmark");
    private File directory;
    private final JSONObject report=new JSONObject();
    private final ArrayBlockingQueue<String> text=new ArrayBlockingQueue<>(1024);
    private BufferedOutputStream journal;
    private ScheduledExecutorService writer, oracle, observations, terminalSignals;
    private volatile long criticalFrom=Long.MAX_VALUE, criticalTo=Long.MIN_VALUE, attempt;
    private volatile boolean observing;
    private volatile String collectorFailure="";
    private long lastOracleStart=Long.MIN_VALUE/2;
    private int oracleSequence;
    private String appSessionId;

    @Override public void onCreate(Bundle options){super.onCreate(options);args=options;start();}
    private Bundle call(String method,Bundle values) {
        Bundle result=getTargetContext().getContentResolver().call(control,method,null,values);
        if(result==null || (result.getString("error")!=null && !result.getString("error").isEmpty())) throw new IllegalStateException("Measurement "+method+": "+result);
        return result;
    }
    private JSONObject bundle(Bundle bundle) throws Exception {
        JSONObject result=new JSONObject();for(String key:bundle.keySet())result.put(key,bundle.get(key));return result;
    }
    private void row(String kind,JSONObject value) throws Exception {
        value.put("kind",kind).put("attemptId",attempt).put("elapsedNs",SystemClock.elapsedRealtimeNanos());
        if(!text.offer(value.toString()))collectorFailure="driver_text_overflow";
    }
    private boolean critical(){long now=SystemClock.elapsedRealtime();return now>=criticalFrom && now<=criticalTo;}
    private synchronized void flush(boolean force) {
        if(!force && critical())return;
        try {for(;;){String line=text.poll();if(line==null)break;journal.write((line+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));}journal.flush();}
        catch(Exception error){collectorFailure=error.toString();}
    }
    private void status(String phase) {Bundle b=new Bundle();b.putString("v3_phase",phase);sendStatus(1,b);}
    private void shell(String command) throws Exception {
        try(android.os.ParcelFileDescriptor fd=ui.executeShellCommand(command);FileInputStream in=new FileInputStream(fd.getFileDescriptor())){byte[] bytes=new byte[1024];while(in.read(bytes)>0){}}
    }
    private List<AccessibilityNodeInfo> nodes() {
        List<AccessibilityNodeInfo> result=new ArrayList<>();
        for(AccessibilityWindowInfo window:ui.getWindows())walk(window.getRoot(),result);
        return result;
    }
    private void walk(AccessibilityNodeInfo node,List<AccessibilityNodeInfo> out) {
        if(node==null)return;out.add(node);for(int i=0;i<node.getChildCount();i++)walk(node.getChild(i),out);
    }
    private AccessibilityNodeInfo findId(String id,long timeout) throws Exception {
        long end=SystemClock.elapsedRealtime()+timeout;
        do{for(AccessibilityNodeInfo node:nodes())if(id.equals(node.getViewIdResourceName())&&node.isVisibleToUser())return node;Thread.sleep(30);}while(SystemClock.elapsedRealtime()<end);
        throw new IllegalStateException("Public control unavailable: "+id);
    }
    private void click(String id) throws Exception { clickNode(findId(id,3000),id); }
    private long clickNode(AccessibilityNodeInfo node,String id) throws Exception {
        long before=SystemClock.elapsedRealtimeNanos();boolean accepted=node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        row("ui_action",new JSONObject().put("id",id).put("startedNs",before).put("returnedNs",SystemClock.elapsedRealtimeNanos()).put("accepted",accepted));
        if(!accepted)throw new IllegalStateException("Public action rejected: "+id);
        return before;
    }
    private void dragMarker(int[] point) throws Exception {
        AccessibilityNodeInfo marker=null;
        for(AccessibilityNodeInfo node:nodes())if("拖动准星确定点击位置".contentEquals(node.getContentDescription()==null?"":node.getContentDescription()))marker=node;
        if(marker==null)throw new IllegalStateException("No public marker");
        Rect bounds=new Rect();marker.getBoundsInScreen(bounds);
        long down=SystemClock.uptimeMillis();
        for(int i=0;i<=4;i++) {
            float portion=i/4f;
            MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),i==0?MotionEvent.ACTION_DOWN:i==4?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE,
                bounds.exactCenterX()+(point[0]-bounds.exactCenterX())*portion,bounds.exactCenterY()+(point[1]-bounds.exactCenterY())*portion,0);
            try{if(!ui.injectInputEvent(event,true))throw new IllegalStateException("Marker input rejected");}finally{event.recycle();}
            Thread.sleep(40);
        }
        Thread.sleep(250);
    }
    private int wheelValue(AccessibilityNodeInfo wheel) {
        List<AccessibilityNodeInfo> descendants=new ArrayList<>();walk(wheel,descendants);
        for(AccessibilityNodeInfo node:descendants)if("android.widget.EditText".contentEquals(node.getClassName())&&node.getText()!=null)return Integer.parseInt(node.getText().toString());
        throw new IllegalStateException("Picker has no accessible current value");
    }
    private List<AccessibilityNodeInfo> wheels() {
        List<AccessibilityNodeInfo> result=new ArrayList<>();for(AccessibilityNodeInfo node:nodes())if("android.widget.NumberPicker".contentEquals(node.getClassName()))result.add(node);
        result.sort(Comparator.comparingInt(node->{Rect r=new Rect();node.getBoundsInScreen(r);return r.left;}));return result;
    }
    private void setWheels(long target) throws Exception {
        Calendar time=Calendar.getInstance();time.setTimeInMillis(target);
        int[] desired={time.get(Calendar.HOUR_OF_DAY),time.get(Calendar.MINUTE),time.get(Calendar.SECOND),time.get(Calendar.MILLISECOND)};
        int[] ranges={24,60,60,1000};List<AccessibilityNodeInfo> pickers=wheels();
        long pickerEnd=SystemClock.elapsedRealtime()+3000;
        while(pickers.size()!=4&&SystemClock.elapsedRealtime()<pickerEnd){Thread.sleep(50);pickers=wheels();}
        if(pickers.size()!=4) {
            JSONArray snapshot=new JSONArray();
            for(AccessibilityNodeInfo node:nodes()) {
                Rect bounds=new Rect();node.getBoundsInScreen(bounds);
                snapshot.put(new JSONObject().put("class",String.valueOf(node.getClassName())).put("text",String.valueOf(node.getText()))
                    .put("description",String.valueOf(node.getContentDescription())).put("bounds",bounds.toShortString()));
            }
            row("picker_failure_before_stop",new JSONObject().put("nodes",snapshot));flush(true);
            throw new IllegalStateException("Expected four public wheels, found "+pickers.size());
        }
        JSONArray actions=new JSONArray();
        for(int i=0;i<4;i++) {
            AccessibilityNodeInfo picker=pickers.get(i);int current=wheelValue(picker);
            int delta=(desired[i]-current+ranges[i])%ranges[i];if(delta>ranges[i]/2)delta-=ranges[i];
            int action=delta>=0?AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
            long start=SystemClock.elapsedRealtimeNanos();
            for(int n=0;n<Math.abs(delta);n++)if(!picker.performAction(action))throw new IllegalStateException("Wheel scroll rejected");
            actions.put(new JSONObject().put("wheel",i).put("from",current).put("to",desired[i]).put("steps",delta).put("startedNs",start).put("finishedNs",SystemClock.elapsedRealtimeNanos()));
        }
        Thread.sleep(400);
        List<AccessibilityNodeInfo> verify=wheels();
        for(int i=0;i<4;i++)if(wheelValue(verify.get(i))!=desired[i]) {
            row("wheel_failure",new JSONObject().put("wheel",i).put("expected",desired[i]).put("observed",wheelValue(verify.get(i))).put("actions",actions));
            flush(true);throw new IllegalStateException("Public wheel value did not settle: "+i);
        }
        row("wheel_selection",new JSONObject().put("targetEpoch",target).put("actions",actions));
        click("android:id/button1");
    }
    private synchronized void requestOracle(String role,long scheduledNs) {
        long now=SystemClock.elapsedRealtime();
        if(critical() || now-lastOracleStart<1000) {
            try{row("oracle_skipped",new JSONObject().put("role",role).put("scheduledNs",scheduledNs).put("reason",critical()?"critical_region":"minimum_gap"));}catch(Exception e){collectorFailure=e.toString();}
            return;
        }
        lastOracleStart=now;int id=++oracleSequence;
        HttpsURLConnection connection=null;JSONObject record=new JSONObject();
        try {
            record.put("probeId",id).put("role",role).put("scheduledNs",scheduledNs);
            connection=(HttpsURLConnection)new URL("https://api.m.jd.com").openConnection();
            connection.setRequestMethod("OPTIONS");connection.setConnectTimeout(3000);connection.setReadTimeout(3000);connection.setUseCaches(false);connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Cache-Control","no-cache, no-store");
            long sent=SystemClock.elapsedRealtimeNanos();connection.connect();long connected=SystemClock.elapsedRealtimeNanos();
            int code=connection.getResponseCode();java.util.Map<String,List<String>> fields=connection.getHeaderFields();long received=SystemClock.elapsedRealtimeNanos();
            record.put("sentNs",sent).put("connectedNs",connected).put("receivedNs",received).put("httpStatus",code)
                .put("requestId",connection.getHeaderField("x-api-request-id")).put("date",connection.getHeaderField("Date")).put("age",connection.getHeaderField("Age"))
                .put("rawRequestIdValues",new JSONArray(fields.entrySet().stream().filter(e->e.getKey()!=null&&e.getKey().equalsIgnoreCase("x-api-request-id")).flatMap(e->e.getValue().stream()).toArray()));
            record.put("serverEpoch",OracleHeaders.epoch(code,fields));
        }catch(Exception error){try{record.put("error",error.toString());}catch(Exception ignored){}}
        finally{if(connection!=null)connection.disconnect();try{row("oracle",record);}catch(Exception e){collectorFailure=e.toString();}}
    }
    private void sleepUntil(long millis) throws Exception {
        while(SystemClock.elapsedRealtime()<millis) {if(!collectorFailure.isEmpty())throw new IllegalStateException(collectorFailure);Thread.sleep(Math.min(250,Math.max(1,millis-SystemClock.elapsedRealtime())));}
    }
    private void traceGate() throws Exception {
        if(!Boolean.parseBoolean(args.getString("traceGate","false")))return;
        status("TRACE_READY");File signal=new File(directory,"trace-ready");long end=SystemClock.elapsedRealtime()+30000;
        while(!signal.exists()&&SystemClock.elapsedRealtime()<end)Thread.sleep(25);
        if(!signal.exists())throw new IllegalStateException("Trace gate timeout");
        if(!signal.delete())throw new IllegalStateException("Trace signal cleanup failed");
        Thread.sleep(2000);
    }
    @Override public void onStart() {
        boolean passed=false;
        try {
            String id=args.getString("id");if(id==null||!id.matches("[a-zA-Z0-9_-]{1,100}"))throw new IllegalArgumentException("Unique session id required");
            int count=Integer.parseInt(args.getString("samples","1"));long lead=Long.parseLong(args.getString("leadMillis","4000"));
            long setupBudget=Long.parseLong(args.getString("setupBudgetMillis","15000"));
            if(setupBudget<5000||setupBudget>30000)throw new IllegalArgumentException("Setup budget");
            long duration=Long.parseLong(args.getString("durationMillis","0"));boolean probes=Boolean.parseBoolean(args.getString("oracle","false"));
            if(count<1||count>100||lead<500||lead>3600000||duration<0||duration>3600000)throw new IllegalArgumentException("Unbounded V3 run");
            directory=new File(getTargetContext().getExternalFilesDir(null),"v3/"+id);
            String mode=args.getString("mode",Boolean.parseBoolean(args.getString("traceFrames","false"))?"frame":"light");
            boolean off=mode.equals("off");
            boolean lateControl=Boolean.parseBoolean(args.getString("latePrepareControl","false"));
            boolean cancelProbe=Boolean.parseBoolean(args.getString("cancelStallProbe","false"));
            if(lateControl&&cancelProbe)throw new IllegalArgumentException("Separate failure controls");
            if(lateControl||cancelProbe) {
                FailureControl.config(mode,count,duration,lead,setupBudget,Boolean.parseBoolean(args.getString("traceFrames","false")),probes,Boolean.parseBoolean(args.getString("calibrate","false")));
                if(id.length()>80)throw new IllegalArgumentException("Control id too long");
            }
            appSessionId=id;
            report.put("id",id).put("appSessionId",appSessionId).put("controlKind",lateControl?"late_prepare_rejection":cancelProbe?"cancel_pending_then_positive":"none");
            ReceiverJournal.start(getTargetContext(),id,!off);
            journal=new BufferedOutputStream(new java.io.FileOutputStream(new File(directory,"driver.jsonl")),16384);
            writer=Executors.newSingleThreadScheduledExecutor();writer.scheduleWithFixedDelay(()->flush(false),250,250,TimeUnit.MILLISECONDS);
            oracle=Executors.newSingleThreadScheduledExecutor();observations=Executors.newSingleThreadScheduledExecutor();terminalSignals=Executors.newSingleThreadScheduledExecutor();
            observations.scheduleAtFixedRate(()->{
                Bundle pulse=new Bundle();pulse.putString("v3_phase","HEARTBEAT");
                pulse.putLong("heartbeatNs",SystemClock.elapsedRealtimeNanos());pulse.putLong("attemptId",attempt);
                pulse.putBoolean("critical",critical());pulse.putInt("driverQueued",text.size());
                pulse.putString("collectorFailure",collectorFailure);sendStatus(2,pulse);
            },0,1,TimeUnit.SECONDS);
            int pairs=Integer.parseInt(args.getString("oraclePairs","0"));
            if(pairs>0) {
                if(pairs>20)throw new IllegalArgumentException("Pilot pair limit");
                report.put("id",id).put("protocolVersion","timing-v3").put("mode","independent_reference_pilot").put("plannedPairs",pairs);
                for(int pair=1;pair<=pairs;pair++) {
                    attempt=pair;requestOracle("before",SystemClock.elapsedRealtimeNanos());
                    sleepUntil(SystemClock.elapsedRealtime()+1000);
                    requestOracle("after",SystemClock.elapsedRealtimeNanos());
                    sleepUntil(SystemClock.elapsedRealtime()+1000);status("REFERENCE_PAIR_"+pair);flush(false);
                }
                passed=true;return;
            }
            ui=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            AccessibilityServiceInfo service=ui.getServiceInfo();service.flags|=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS|AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;ui.setServiceInfo(service);
            long timeout=SystemClock.elapsedRealtime()+30000;
            while(!call("v3_probe",null).getBoolean("connected")&&SystemClock.elapsedRealtime()<timeout)Thread.sleep(250);
            if(!call("v3_probe",null).getBoolean("connected"))throw new IllegalStateException("No click service");
            Bundle begin=new Bundle();begin.putString("id",id);begin.putString("mode",mode);begin.putBoolean("traceFrames",Boolean.parseBoolean(args.getString("traceFrames","false")));
            call("v3_start",begin);
            if(cancelProbe) {
                Bundle pending=new Bundle();pending.putLong("atElapsed",call("v3_probe",null).getLong("elapsedNs")/1000000+500);pending.putLong("durationMillis",670);
                Bundle queued=call("v3_stall",pending);
                if(!queued.getBoolean("stallPending"))throw new IllegalStateException("Cancellation probe was not queued");
                Bundle closed=call("v3_end",null);
                appSessionId=id+"_restart";begin.putString("id",appSessionId);call("v3_start",begin);report.put("appSessionId",appSessionId);
                Thread.sleep(1200);Bundle replacement=call("v3_probe",null);
                row("cancel_probe",new JSONObject().put("queued",bundle(queued)).put("closed",bundle(closed)).put("replacement",bundle(replacement)));
                FailureControl.cancelled(closed.getLong("stallStartedNs"),closed.getLong("stallEndedNs"),closed.getBoolean("stallPending"),
                    replacement.getLong("stallStartedNs"),replacement.getLong("stallEndedNs"),replacement.getBoolean("stallPending"));
                report.put("pendingStallCancellationPassed",true);
            }
            if(Boolean.parseBoolean(args.getString("calibrate","false"))) {
                Bundle calibration=call("v3_calibrate",null);
                long[] costs=calibration.getLongArray("recordNs");
                JSONArray values=new JSONArray();for(long cost:costs)values.put(cost);
                JSONArray controls=new JSONArray();for(long cost:calibration.getLongArray("controlNs"))controls.put(cost);
                report.put("recordNs",values).put("controlNs",controls).put("mode",mode);passed=true;return;
            }
            call("setupV3",null);
            shell("am start -n com.ahao.clicker/.MainActivity -f 0x10008000");
            click("com.ahao.clicker:id/show_clock");
            shell("am start -n com.ahao.clicker.acceptance.target/.TargetActivity -f 0x10008000");
            timeout=SystemClock.elapsedRealtime()+15000;
            while((!Receipts.snapshot().containsKey("B")||!call("v3_probe",null).getBoolean("fresh"))&&SystemClock.elapsedRealtime()<timeout)Thread.sleep(100);
            if(!call("v3_probe",null).getBoolean("fresh"))throw new IllegalStateException("No fresh source");
            int[] point=Receipts.snapshot().getIntArray("B");if(point==null)throw new IllegalStateException("No target point");dragMarker(point);
            report.put("id",id).put("protocolVersion","timing-v3").put("requestedSamples",count).put("leadMillis",lead).put("durationMillis",duration).put("setupBudgetMillis",setupBudget).put("cachedPublicPlay",true).put("publicTaskFlow",true);
            Bundle identity=new Bundle();identity.putString("title","clock_"+appSessionId);
            row("window_identity",bundle(call("v3_identity",identity)));
            report.put("mode",mode).put("windowTitle","clock_"+appSessionId);
            traceGate();
            if(duration>0) {
                status("CLOCK_STABILIZING");sleepUntil(SystemClock.elapsedRealtime()+10000);
                row("clock_bracket",bundle(call("v3_bracket",null)));
                long started=SystemClock.elapsedRealtime();report.put("stableStartMs",started);long interval=Long.parseLong(args.getString("oracleIntervalMillis","30000"));
                if(interval<1000||interval>60000)throw new IllegalArgumentException("Oracle interval");
                for(long slot=0;slot<=duration/1000;slot++) {
                    long due=started+slot*1000;sleepUntil(due);long stamp=SystemClock.elapsedRealtime();
                    ReceiverJournal.record(500,due,stamp,SystemClock.uptimeMillis(),System.currentTimeMillis());
                    if(probes&&slot*1000%interval==0){long planned=due*1000000;oracle.execute(()->requestOracle("clock",planned));}
                    if(slot>0&&slot%60==0)row("clock_bracket",bundle(call("v3_bracket",null)));
                    if(slot%10==0)status("CLOCK_SLOT_"+slot);
                }
            } else {
                for(int index=0;index<count;index++) {
                    attempt=index+1;ReceiverJournal.begin(attempt);Bundle task=new Bundle();task.putLong("attemptId",attempt);call("v3_attempt",task);
                    criticalFrom=Long.MAX_VALUE;criticalTo=Long.MIN_VALUE;
                    click("com.ahao.clicker:id/click_start_time");
                    long wheelReadyEnd=SystemClock.elapsedRealtime()+3000;
                    while(wheels().size()!=4&&SystemClock.elapsedRealtime()<wheelReadyEnd)Thread.sleep(50);
                    Bundle reference=call("v3_probe",null);long now=SystemClock.elapsedRealtime();
                    long target=reference.getLong("referenceEpoch")+now-reference.getLong("referenceElapsed")+setupBudget+lead;
                    if(Boolean.parseBoolean(args.getString("preservePickerMillis","false"))) {
                        int fraction=wheelValue(wheels().get(3));
                        target=(target/1000)*1000+fraction;if(target<reference.getLong("referenceEpoch")+now-reference.getLong("referenceElapsed")+setupBudget+lead)target+=1000;
                    }
                    long initialDeadline=target-reference.getLong("referenceEpoch")+reference.getLong("referenceElapsed");long plannedPlay=initialDeadline-lead;
                    row("planned",new JSONObject().put("targetEpoch",target).put("initialDeadlineMs",initialDeadline).put("plannedPlayMs",plannedPlay).put("leadMillis",lead));flush(true);
                    if(probes){long due=initialDeadline-3000;oracle.schedule(()->requestOracle("before",due*1000000),Math.max(0,due-SystemClock.elapsedRealtime()),TimeUnit.MILLISECONDS);}
                    setWheels(target);
                    AccessibilityNodeInfo play=findId("com.ahao.clicker:id/toggle_click",3000);
                    TrialGuard.setup(SystemClock.elapsedRealtime(),plannedPlay);
                    sleepUntil(plannedPlay);
                    java.util.concurrent.Future<Bundle> terminal=null;
                    if(!off&&lead<=10000)terminal=terminalSignals.submit(()->{Bundle wait=new Bundle();wait.putLong("timeoutMs",15000);return call("v3_waitTerminal",wait);});
                    long playedAt=clickNode(play,"com.ahao.clicker:id/toggle_click");
                    Bundle state=call(off?"state":"v3_status",null);long actualDeadline=state.getLong(off?"plannedElapsed":"deadline");
                    long armWait=SystemClock.elapsedRealtime()+300;
                    while(actualDeadline<=0&&SystemClock.elapsedRealtime()<armWait){Thread.sleep(10);actualDeadline=call(off?"state":"v3_status",null).getLong(off?"plannedElapsed":"deadline");}
                    if(actualDeadline<=0)throw new IllegalStateException("No arm event after public play");
                    Bundle armState=off?state:call("state",null);
                    row("arm_snapshot",bundle(armState));
                    TrialGuard.arm(target,armState.getLong("targetEpoch"),armState.getLong("plannedElapsed"),
                        armState.getLong("armedElapsed"),playedAt,armState.getLong("elapsed"),armState.getString("phase"));
                    if(lateControl) {
                        Bundle block=new Bundle();block.putLong("atElapsed",actualDeadline-500);block.putLong("durationMillis",670);
                        row("scheduled_control",bundle(call("v3_stall",block)));
                    }
                    long driftBudget=1000*(long)Math.ceil(lead/60000.0);
                    criticalFrom=actualDeadline-750-driftBudget;criticalTo=actualDeadline+3000+driftBudget;
                    ReceiverJournal.criticalStart=criticalFrom;ReceiverJournal.criticalEnd=criticalTo;
                    long nextRead=SystemClock.elapsedRealtime()+1000;
                    while(SystemClock.elapsedRealtime()<actualDeadline+3000) {
                        long stamp=SystemClock.elapsedRealtime();
                        if(lead>10000 && stamp<actualDeadline-10000 && stamp>=nextRead) {
                            actualDeadline=call(off?"state":"v3_status",null).getLong(off?"plannedElapsed":"deadline");nextRead=stamp+1000;
                            criticalFrom=actualDeadline-750-2000;criticalTo=actualDeadline+5000;
                            ReceiverJournal.criticalStart=criticalFrom;ReceiverJournal.criticalEnd=criticalTo;
                        }
                        if(!off&&terminal==null&&stamp>=actualDeadline-10000){terminal=terminalSignals.submit(()->{Bundle wait=new Bundle();wait.putLong("timeoutMs",15000);return call("v3_waitTerminal",wait);});}

                        Thread.sleep(10);
                    }
                    long finish=SystemClock.elapsedRealtime();
                    Bundle ended=terminal==null?call("state",null):terminal.get(3,TimeUnit.SECONDS);
                    Bundle production=off?ended:call("state",null);
                    long terminalPhase=off?com.ahao.clicker.core.RunPhase.valueOf(production.getString("phase")).ordinal():ended.getLong("terminalPhase",-1);
                    row("receipt",new JSONObject().put("downs",ReceiverJournal.downs).put("ups",ReceiverJournal.ups).put("clicks",ReceiverJournal.clicks)
                        .put("downNs",ReceiverJournal.downNs).put("upNs",ReceiverJournal.upNs).put("clickNs",ReceiverJournal.clickNs).put("point",ReceiverJournal.point)
                        .put("touchOpen",ReceiverJournal.touchOpen).put("deadlineMs",actualDeadline).put("receiptCheckedMs",finish)
                        .put("production",bundle(production))
                        .put("terminalNs",ended==null?0:ended.getLong("terminalNs")).put("terminalPhase",terminalPhase));
                    long safeAfter=Math.max(ended==null?finish:ended.getLong("terminalNs")/1000000,ReceiverJournal.clickNs/1000000)+500;
                    sleepUntil(safeAfter);
                    if(lateControl) {
                        FailureControl.rejection(actualDeadline,production.getLong("stallStartedNs"),production.getLong("stallEndedNs"),
                            ReceiverJournal.downs,ReceiverJournal.ups,ReceiverJournal.clicks,ReceiverJournal.touchOpen,production.getString("phase"),
                            production.getInt("failureEvent"),production.getLong("failureReason"),production.containsKey("dispatchedElapsed"));
                        report.put("expectedRejectionPassed",true);
                    } else {
                        TrialGuard.receipt(ReceiverJournal.downs,ReceiverJournal.ups,ReceiverJournal.clicks,ReceiverJournal.point,ReceiverJournal.touchOpen,production.getString("phase"));
                        if(cancelProbe&&(production.getLong("stallStartedNs")!=0||production.getLong("stallEndedNs")!=0||production.getInt("failureEvent")!=0))
                            throw new IllegalStateException("Cancelled fault affected the public positive task");
                    }
                    criticalFrom=Long.MAX_VALUE;criticalTo=Long.MIN_VALUE;ReceiverJournal.criticalStart=Long.MAX_VALUE;ReceiverJournal.criticalEnd=Long.MIN_VALUE;
                    if(probes)requestOracle("after",SystemClock.elapsedRealtimeNanos());
                    sleepUntil(SystemClock.elapsedRealtime()+1000);
                    if(ended!=null&&ended.getLong("terminalPhase")==4)throw new IllegalStateException("Task was stopped; batch will not resume");
                    status("TASK_"+attempt+"_DONE");flush(false);
                }
            }
            passed=true;
        } catch(Throwable error) {
            try{report.put("error",android.util.Log.getStackTraceString(error)).put("failureObservedNs",SystemClock.elapsedRealtimeNanos())
                .put("failureAppObservation",bundle(call("v3_status",null))).put("failureClicks",ReceiverJournal.clicks)
                .put("failureDownNs",ReceiverJournal.downNs).put("failureUpNs",ReceiverJournal.upNs).put("failureClickNs",ReceiverJournal.clickNs);}catch(Exception ignored){}
        } finally {
            observing=false;
            try{call("stop",null);}catch(Exception ignored){}
            if(!passed&&ui!=null)try {
                Thread.sleep(600);JSONArray controls=new JSONArray();
                for(AccessibilityNodeInfo node:nodes()) {
                    String owner=String.valueOf(node.getPackageName());
                    if(!owner.equals("com.ahao.clicker")&&!owner.equals("com.ahao.clicker.acceptance.target"))continue;
                    Rect bounds=new Rect();node.getBoundsInScreen(bounds);
                    controls.put(new JSONObject().put("id",node.getViewIdResourceName()).put("class",String.valueOf(node.getClassName()))
                        .put("text",String.valueOf(node.getText())).put("description",String.valueOf(node.getContentDescription())).put("bounds",bounds.toShortString()));
                }
                report.put("diagnosticsAfterStop",controls);
            }catch(Exception ignored){}
            try{if(oracle!=null){oracle.shutdown();oracle.awaitTermination(10,TimeUnit.SECONDS);}}catch(Exception ignored){}
            if(observations!=null)observations.shutdownNow();
            if(terminalSignals!=null)terminalSignals.shutdownNow();
            try{if(writer!=null){writer.shutdown();writer.awaitTermination(5,TimeUnit.SECONDS);}}catch(Exception ignored){}
            try{report.put("appRecorder",bundle(call("v3_end",null)));}catch(Exception e){try{report.put("recorderError",e.toString());}catch(Exception ignored){}}
            try{ReceiverJournal.stop();report.put("targetDropped",ReceiverJournal.dropped()).put("collectorFailure",collectorFailure);flush(true);if(journal!=null)journal.close();
                report.put("passed",passed).put("finishedElapsedNs",SystemClock.elapsedRealtimeNanos());
                if(directory!=null)try(java.io.FileOutputStream file=new java.io.FileOutputStream(new File(directory,"result.json"))){file.write(report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            }catch(Exception ignored){}
            // Raw calibration arrays stay in result.json, never exceed Binder's transaction limit.
            Bundle result=new Bundle();result.putBoolean("v3_passed",passed);
            result.putString("v3_result_path",directory==null?"":new File(directory,"result.json").getAbsolutePath());
            finish(passed?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
        }
    }
}
