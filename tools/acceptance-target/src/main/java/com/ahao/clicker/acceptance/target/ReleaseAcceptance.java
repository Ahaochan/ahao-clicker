package com.ahao.clicker.acceptance.target;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/** Drives the installed optimized app through its public UI; receipts remain in this other process. */
public final class ReleaseAcceptance extends Instrumentation {
    private UiAutomation automation;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        JSONObject report = new JSONObject();
        boolean passed = false;
        try {
            automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            AccessibilityServiceInfo info = automation.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            automation.setServiceInfo(info);
            shell("am start -n com.ahao.clicker.acceptance.target/.TargetActivity -f 0x10008000");
            await(() -> Receipts.snapshot().containsKey("B") && Receipts.snapshot().getBoolean("focused"), 10000);
            int[] a = Receipts.snapshot().getIntArray("A"), b = Receipts.snapshot().getIntArray("B");
            Receipts.reset();
            shell("am start -n com.ahao.clicker/.MainActivity -f 0x10008000");
            await(() -> rootIs("com.ahao.clicker"), 10000);
            click("com.ahao.clicker:id/mode_classic", true);
            List<AccessibilityNodeInfo> edits = new ArrayList<>();
            for (AccessibilityNodeInfo node : nodes(automation.getRootInActiveWindow())) if (node.isEditable()) edits.add(node);
            if (edits.size() != 2) throw new IllegalStateException("Expected steps and count inputs, got " + edits.size());
            setText(edits.get(0), a[0] + "," + a[1] + ",100\n" + b[0] + "," + b[1] + ",300");
            setText(edits.get(1), "20");
            click("设为 3 秒后开始，持续 10 秒", false);
            click("启动定时点击", false);
            await(() -> Receipts.snapshot().getBoolean("focused"), 6000);
            await(() -> Receipts.size() == 20, 12000);
            Thread.sleep(700);
            if (Receipts.size() != 20) throw new IllegalStateException("Extra clicks after completion");
            Bundle state = Receipts.snapshot();
            JSONArray events = new JSONArray(state.getString("events"));
            for (int i = 0; i < 20; i++) {
                if (!events.getJSONObject(i).getString("point").equals(i % 2 == 0 ? "A" : "B")) throw new IllegalStateException("Wrong point order");
            }
            JSONArray touches = new JSONArray(state.getString("touches"));
            List<Long> downs = new ArrayList<>(), ups = new ArrayList<>();
            for (int i = 0; i < touches.length(); i++) {
                JSONObject touch = touches.getJSONObject(i);
                if (touch.getInt("action") == 0) downs.add(touch.getLong("eventUptime"));
                if (touch.getInt("action") == 1) ups.add(touch.getLong("eventUptime"));
            }
            if (downs.size() != 20 || ups.size() != 20) throw new IllegalStateException("Missing input events");
            JSONArray gaps = new JSONArray();
            for (int i = 1; i < 20; i++) {
                long gap = downs.get(i) - ups.get(i-1), wait = i % 2 == 0 ? 100 : 300;
                if (gap < wait - 20 || gap > wait + 1200) throw new IllegalStateException("Input interval out of bounds: " + gap);
                gaps.put(gap);
            }
            report.put("received", events).put("inputGapsMillis", gaps).put("count", 20).put("appInstrumentationAttached", false);
            passed = true;
        } catch (Throwable error) {
            try { report.put("error", android.util.Log.getStackTraceString(error)); } catch (Exception ignored) { }
        } finally {
            if (automation != null) shell("am force-stop com.ahao.clicker");
            try {
                report.put("passed", passed);
                java.io.File file = new java.io.File(getTargetContext().getFilesDir(), "release-acceptance.json");
                try (java.io.FileOutputStream stream = new java.io.FileOutputStream(file)) { stream.write(report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            } catch (Exception error) { passed = false; }
            Bundle result = new Bundle(); result.putString("release_acceptance_json", report.toString());
            finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
        }
    }
    private interface Condition { boolean ready() throws Exception; }
    private void await(Condition condition, long timeout) throws Exception {
        long end = SystemClock.elapsedRealtime() + timeout;
        while (!condition.ready()) { if (SystemClock.elapsedRealtime() >= end) throw new IllegalStateException("UI/receipt wait timed out"); Thread.sleep(50); }
    }
    private String shell(String command) {
        try (android.os.ParcelFileDescriptor fd = automation.executeShellCommand(command); FileInputStream input = new FileInputStream(fd.getFileDescriptor())) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString("UTF-8");
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    private boolean rootIs(String name) { AccessibilityNodeInfo root = automation.getRootInActiveWindow(); return root != null && name.contentEquals(root.getPackageName()); }
    private List<AccessibilityNodeInfo> nodes(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> result = new ArrayList<>();
        if (root != null) { result.add(root); for (int i = 0; i < root.getChildCount(); i++) result.addAll(nodes(root.getChild(i))); }
        return result;
    }
    private void setText(AccessibilityNodeInfo node, String text) {
        Bundle args = new Bundle(); args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) throw new IllegalStateException("Could not edit test task");
    }
    private void click(String value, boolean byId) throws Exception {
        for (int attempt = 0; attempt < 12; attempt++) {
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root == null || !"com.ahao.clicker".contentEquals(root.getPackageName())) throw new IllegalStateException("Unexpected foreground app");
            List<AccessibilityNodeInfo> visible = nodes(root);
            for (AccessibilityNodeInfo node : visible) {
                if ((byId ? value.equals(node.getViewIdResourceName()) : value.contentEquals(node.getText() == null ? "" : node.getText())) && node.isVisibleToUser()) {
                    if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw new IllegalStateException("Could not activate " + value);
                    Thread.sleep(250); return;
                }
            }
            for (AccessibilityNodeInfo node : visible) if (node.isScrollable()) { node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); break; }
            Thread.sleep(250);
        }
        throw new IllegalStateException("UI control missing: " + value);
    }
}
