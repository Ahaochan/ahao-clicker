package com.ahao.clicker.acceptance.target;

import android.os.Bundle;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;

final class Receipts {
    private static final ArrayList<JSONObject> events = new ArrayList<>();
    private static final ArrayList<JSONObject> touches = new ArrayList<>();
    private static final Bundle points = new Bundle();
    private static boolean focused;

    static synchronized void point(String name, int x, int y) { points.putIntArray(name, new int[]{x, y}); }
    static synchronized void reset() { events.clear(); touches.clear(); }
    static synchronized int size() { return events.size(); }
    static synchronized void focused(boolean value) { focused = value; }
    static synchronized void touch(android.view.MotionEvent event) {
        if (touches.size() >= 4096) return;
        try { touches.add(new JSONObject().put("action", event.getActionMasked()).put("x", event.getRawX()).put("y", event.getRawY()).put("elapsed", SystemClock.elapsedRealtime())); }
        catch (org.json.JSONException error) { throw new IllegalStateException(error); }
    }
    static synchronized void click(String name) {
        if (events.size() >= 2048) throw new IllegalStateException("Receipt bound exceeded");
        try {
            events.add(new JSONObject().put("point", name).put("elapsed", SystemClock.elapsedRealtime())
                .put("epoch", System.currentTimeMillis()));
        } catch (org.json.JSONException error) { throw new IllegalStateException(error); }
    }
    static synchronized Bundle snapshot() {
        Bundle result = new Bundle(points);
        result.putString("events", new JSONArray(events).toString());
        result.putString("touches", new JSONArray(touches).toString());
        result.putInt("count", events.size());
        result.putBoolean("focused", focused);
        return result;
    }
}
