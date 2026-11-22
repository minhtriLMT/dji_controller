package com.tri.djicontrol.flight;

import android.content.Context;
import android.content.SharedPreferences;

import com.tri.djicontrol.firebase.UploadMeta;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import dji.common.model.LocationCoordinate2D;

/**
 * Lưu lịch sử các chuyến bay quét (mission) trên điện thoại.
 *
 * Ảnh chụp trong lúc bay nằm trên thẻ nhớ drone, chỉ được tải về/đẩy lên Cloud SAU khi bay xong,
 * nên không thể gắn mã chuyến bay vào file ngay lúc chụp. Thay vào đó mỗi chuyến bay được ghi lại
 * (mã, giờ bắt đầu/kết thúc, vùng bay) và khi upload, ảnh được gán mission_id theo thời điểm chụp
 * (và vị trí GPS nếu có).
 */
public final class MissionLog {

    private static final String PREFS = "mission_log";
    private static final String KEY = "missions";
    private static final int MAX_MISSIONS = 200;

    /** Cho phép lệch giờ giữa đồng hồ camera drone và điện thoại. */
    private static final long BEFORE_TOLERANCE_MS = 60_000;
    private static final long AFTER_TOLERANCE_MS = 120_000;
    /** Chuyến bay không ghi được giờ kết thúc (app bị tắt) -> coi như kéo dài tối đa 1 giờ. */
    private static final long MAX_OPEN_MISSION_MS = 60 * 60_000;
    /** Ảnh phải nằm trong vùng bay mở rộng thêm bấy nhiêu mét. */
    private static final double AREA_MARGIN_M = 60;

    private MissionLog() {}

    public static final class Mission {
        public final String id;
        public final long startMs;
        public final long endMs;
        public final String droneModel;
        public final String droneSn;

        Mission(String id, long startMs, long endMs, String droneModel, String droneSn) {
            this.id = id;
            this.startMs = startMs;
            this.endMs = endMs;
            this.droneModel = droneModel;
            this.droneSn = droneSn;
        }
    }

    /** Ghi nhận bắt đầu một chuyến bay quét, trả về mission_id (vd: M_20261122_101530). */
    public static synchronized String start(Context context, List<LocationCoordinate2D> waypoints,
                                            float altitude, boolean video) {
        return start(context, null, waypoints, altitude, video);
    }

    /**
     * Như trên; existingId != null khi BAY TIẾP nhiệm vụ dở (sau khi thay pin) -> thêm 1 đoạn bay
     * mới cùng mission_id để ảnh của cả 2 lần bay đều thuộc cùng một chuyến.
     */
    public static synchronized String start(Context context, String existingId, List<LocationCoordinate2D> waypoints,
                                            float altitude, boolean video) {
        long now = System.currentTimeMillis();
        String id = existingId != null ? existingId
                : "M_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(now));
        try {
            JSONObject m = new JSONObject();
            m.put("id", id);
            m.put("start", now);
            m.put("end", 0);
            m.put("video", video);
            m.put("alt", altitude);
            m.put("drone_model", nz(UploadMeta.getConnectedDroneModel()));
            m.put("drone_sn", nz(UploadMeta.getConnectedDroneSn()));
            if (waypoints != null && !waypoints.isEmpty()) {
                double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
                for (LocationCoordinate2D p : waypoints) {
                    minLat = Math.min(minLat, p.getLatitude());
                    maxLat = Math.max(maxLat, p.getLatitude());
                    minLon = Math.min(minLon, p.getLongitude());
                    maxLon = Math.max(maxLon, p.getLongitude());
                }
                m.put("min_lat", minLat);
                m.put("max_lat", maxLat);
                m.put("min_lon", minLon);
                m.put("max_lon", maxLon);
            }
            JSONArray arr = load(context);
            arr.put(m);
            while (arr.length() > MAX_MISSIONS) arr.remove(0);
            save(context, arr);
        } catch (Exception ignored) {}
        return id;
    }

    /** Ghi nhận kết thúc chuyến bay. */
    public static synchronized void end(Context context, String missionId, int photoCount, boolean completed) {
        if (missionId == null) return;
        try {
            JSONArray arr = load(context);
            for (int i = arr.length() - 1; i >= 0; i--) {
                JSONObject m = arr.getJSONObject(i);
                if (missionId.equals(m.optString("id"))) {
                    m.put("end", System.currentTimeMillis());
                    m.put("photos", photoCount);
                    m.put("completed", completed);
                    break;
                }
            }
            save(context, arr);
        } catch (Exception ignored) {}
    }

    /**
     * Tìm chuyến bay chứa thời điểm chụp takenAtMs.
     * lat/lon = NaN nếu file không có GPS (khi đó chỉ so theo thời gian).
     */
    public static synchronized Mission find(Context context, long takenAtMs, double lat, double lon) {
        if (context == null || takenAtMs <= 0) return null;
        JSONArray arr = load(context);
        for (int i = arr.length() - 1; i >= 0; i--) {
            JSONObject m = arr.optJSONObject(i);
            if (m == null) continue;
            long start = m.optLong("start");
            long end = m.optLong("end");
            if (end <= 0) end = start + MAX_OPEN_MISSION_MS;
            if (takenAtMs < start - BEFORE_TOLERANCE_MS || takenAtMs > end + AFTER_TOLERANCE_MS) continue;
            if (!Double.isNaN(lat) && !Double.isNaN(lon) && m.has("min_lat") && !insideArea(m, lat, lon)) continue;
            return new Mission(m.optString("id"), start, m.optLong("end"),
                    m.optString("drone_model", ""), m.optString("drone_sn", ""));
        }
        return null;
    }

    private static boolean insideArea(JSONObject m, double lat, double lon) {
        double dLat = AREA_MARGIN_M / 111_320.0;
        double dLon = AREA_MARGIN_M / (111_320.0 * Math.max(0.1, Math.cos(Math.toRadians(lat))));
        return lat >= m.optDouble("min_lat") - dLat && lat <= m.optDouble("max_lat") + dLat
                && lon >= m.optDouble("min_lon") - dLon && lon <= m.optDouble("max_lon") + dLon;
    }

    private static JSONArray load(Context context) {
        try {
            String s = prefs(context).getString(KEY, "[]");
            return new JSONArray(s);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static void save(Context context, JSONArray arr) {
        prefs(context).edit().putString(KEY, arr.toString()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String nz(String s) {
        return s != null ? s : "";
    }
}
