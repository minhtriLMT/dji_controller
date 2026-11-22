package com.tri.djicontrol.flight;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import dji.common.model.LocationCoordinate2D;

/**
 * Tiến độ của nhiệm vụ bay quét bị dừng giữa chừng (pin yếu / drone tự về nhà),
 * lưu vào bộ nhớ máy để sau khi thay pin có thể bay tiếp đúng chỗ đang dở
 * (kể cả khi tắt app hoặc khởi động lại drone).
 */
public final class MissionCheckpoint {

    private static final String PREFS = "mission_checkpoint";
    private static final String KEY = "checkpoint";

    public String missionId;
    /** Toàn bộ lộ trình gốc. */
    public List<LocationCoordinate2D> waypoints = new ArrayList<>();
    /** Waypoint (trong lộ trình gốc) drone đang bay tới lúc dừng. */
    public int nextIndex;
    /** Đã bắt đầu quét chưa (chưa thì bay lại từ đầu lộ trình). */
    public boolean scanStarted;
    /** Vị trí drone lúc dừng -> điểm bắt đầu khi bay tiếp. */
    public double resumeLat, resumeLng;
    /** Vị trí ảnh cuối cùng (để ảnh tiếp theo cách đúng khoảng cách). NaN nếu chưa chụp. */
    public double lastShotLat = Double.NaN, lastShotLng = Double.NaN;
    public float scanAltitude, speed;
    public boolean video;
    public double photoDistance;
    public int photoCount;
    public int batteryAtPause;
    public String reason;
    public long savedAtMs;

    /** % lộ trình đã quét (0..100). */
    public int progressPercent() {
        if (waypoints.size() < 2) return 0;
        int done = scanStarted ? Math.max(0, nextIndex - 1) : 0;
        return (int) Math.round(done * 100.0 / (waypoints.size() - 1));
    }

    // =========================================================
    // LƯU / ĐỌC
    // =========================================================
    public void save(Context context) {
        try {
            JSONObject o = new JSONObject();
            o.put("missionId", missionId);
            JSONArray wps = new JSONArray();
            for (LocationCoordinate2D p : waypoints) {
                wps.put(new JSONArray().put(p.getLatitude()).put(p.getLongitude()));
            }
            o.put("waypoints", wps);
            o.put("nextIndex", nextIndex);
            o.put("scanStarted", scanStarted);
            o.put("resumeLat", resumeLat);
            o.put("resumeLng", resumeLng);
            if (!Double.isNaN(lastShotLat)) {
                o.put("lastShotLat", lastShotLat);
                o.put("lastShotLng", lastShotLng);
            }
            o.put("scanAltitude", scanAltitude);
            o.put("speed", speed);
            o.put("video", video);
            o.put("photoDistance", photoDistance);
            o.put("photoCount", photoCount);
            o.put("batteryAtPause", batteryAtPause);
            o.put("reason", reason);
            o.put("savedAtMs", savedAtMs);
            prefs(context).edit().putString(KEY, o.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** null nếu không có nhiệm vụ đang dở. */
    public static MissionCheckpoint load(Context context) {
        String s = prefs(context).getString(KEY, null);
        if (s == null) return null;
        try {
            JSONObject o = new JSONObject(s);
            MissionCheckpoint c = new MissionCheckpoint();
            c.missionId = o.optString("missionId", null);
            JSONArray wps = o.getJSONArray("waypoints");
            for (int i = 0; i < wps.length(); i++) {
                JSONArray p = wps.getJSONArray(i);
                c.waypoints.add(new LocationCoordinate2D(p.getDouble(0), p.getDouble(1)));
            }
            c.nextIndex = o.getInt("nextIndex");
            c.scanStarted = o.optBoolean("scanStarted", false);
            c.resumeLat = o.getDouble("resumeLat");
            c.resumeLng = o.getDouble("resumeLng");
            c.lastShotLat = o.has("lastShotLat") ? o.getDouble("lastShotLat") : Double.NaN;
            c.lastShotLng = o.has("lastShotLng") ? o.getDouble("lastShotLng") : Double.NaN;
            c.scanAltitude = (float) o.getDouble("scanAltitude");
            c.speed = (float) o.getDouble("speed");
            c.video = o.getBoolean("video");
            c.photoDistance = o.getDouble("photoDistance");
            c.photoCount = o.optInt("photoCount", 0);
            c.batteryAtPause = o.optInt("batteryAtPause", 0);
            c.reason = o.optString("reason", "");
            c.savedAtMs = o.optLong("savedAtMs", 0);
            if (c.waypoints.size() < 2 || c.nextIndex >= c.waypoints.size()) return null;
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    public static void clear(Context context) {
        prefs(context).edit().remove(KEY).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
