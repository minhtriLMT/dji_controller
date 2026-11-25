package com.tri.djicontrol.field;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Danh sách mảnh ruộng đã lưu (tên + 4 góc + lịch sử bay) để chọn lại và bay lại về sau.
 * Lưu trong bộ nhớ máy; có thể đẩy từng ruộng lên Cloudinary dưới dạng GeoJSON.
 */
public final class SavedFieldStore {

    public static final String STATUS_NEW = "new";         // chưa bay
    public static final String STATUS_PARTIAL = "partial"; // bay dở (pin yếu / dừng giữa chừng)
    public static final String STATUS_DONE = "done";       // đã quét xong ít nhất 1 lần

    private static final String PREFS = "saved_fields";
    private static final String KEY = "fields";

    private SavedFieldStore() {}

    public static final class SavedField {
        public String id;
        public String name;
        public double[] lats = new double[0], lngs = new double[0];
        public double areaHa;
        public long createdAt, updatedAt, lastFlownAt;
        public String status = STATUS_NEW;
        public int flightCount;              // số lần quét XONG
        public List<String> missionIds = new ArrayList<>();
        // Thông số bay lần cuối (nạp lại khi chọn ruộng)
        public float altitude = 10f, speed = 3f;
        public double spacing = 5.0;
        public double margin = 3.0;          // lề an toàn cách bờ (m)
        public boolean video;
        // Cloud
        public String cloudUrl;
        public long cloudSyncedAt;

        public double centerLat() {
            double s = 0;
            for (double v : lats) s += v;
            return lats.length > 0 ? s / lats.length : 0;
        }

        public double centerLng() {
            double s = 0;
            for (double v : lngs) s += v;
            return lngs.length > 0 ? s / lngs.length : 0;
        }

        public String statusLabel() {
            switch (status) {
                case STATUS_DONE: return "Đã bay " + flightCount + " lần";
                case STATUS_PARTIAL: return "Đang bay dở";
                default: return "Chưa bay";
            }
        }

        /** Có thay đổi chưa đẩy lên Cloud. */
        public boolean needsSync() {
            return cloudSyncedAt < updatedAt;
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", name);
            JSONArray la = new JSONArray(), ln = new JSONArray();
            for (double v : lats) la.put(v);
            for (double v : lngs) ln.put(v);
            o.put("lats", la);
            o.put("lngs", ln);
            o.put("areaHa", areaHa);
            o.put("createdAt", createdAt);
            o.put("updatedAt", updatedAt);
            o.put("lastFlownAt", lastFlownAt);
            o.put("status", status);
            o.put("flightCount", flightCount);
            o.put("missionIds", new JSONArray(missionIds));
            o.put("altitude", altitude);
            o.put("speed", speed);
            o.put("spacing", spacing);
            o.put("margin", margin);
            o.put("video", video);
            if (cloudUrl != null) o.put("cloudUrl", cloudUrl);
            o.put("cloudSyncedAt", cloudSyncedAt);
            return o;
        }

        static SavedField fromJson(JSONObject o) throws Exception {
            SavedField f = new SavedField();
            f.id = o.getString("id");
            f.name = o.optString("name", f.id);
            JSONArray la = o.getJSONArray("lats"), ln = o.getJSONArray("lngs");
            f.lats = new double[la.length()];
            f.lngs = new double[ln.length()];
            for (int i = 0; i < la.length(); i++) f.lats[i] = la.getDouble(i);
            for (int i = 0; i < ln.length(); i++) f.lngs[i] = ln.getDouble(i);
            f.areaHa = o.optDouble("areaHa", 0);
            f.createdAt = o.optLong("createdAt");
            f.updatedAt = o.optLong("updatedAt", f.createdAt);
            f.lastFlownAt = o.optLong("lastFlownAt");
            f.status = o.optString("status", STATUS_NEW);
            f.flightCount = o.optInt("flightCount");
            JSONArray m = o.optJSONArray("missionIds");
            if (m != null) for (int i = 0; i < m.length(); i++) f.missionIds.add(m.getString(i));
            f.altitude = (float) o.optDouble("altitude", 10);
            f.speed = (float) o.optDouble("speed", 3);
            f.spacing = o.optDouble("spacing", 5);
            f.margin = o.optDouble("margin", 3);
            f.video = o.optBoolean("video", false);
            f.cloudUrl = o.optString("cloudUrl", null);
            f.cloudSyncedAt = o.optLong("cloudSyncedAt");
            return f;
        }
    }

    // =========================================================
    // ĐỌC / GHI
    // =========================================================
    /** Mới cập nhật trước. */
    public static synchronized List<SavedField> list(Context ctx) {
        List<SavedField> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(SavedField.fromJson(arr.getJSONObject(i)));
        } catch (Exception ignored) {}
        out.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        return out;
    }

    public static synchronized SavedField get(Context ctx, String id) {
        if (id == null) return null;
        for (SavedField f : list(ctx)) if (id.equals(f.id)) return f;
        return null;
    }

    /** Ruộng có chuyến bay missionId (để đánh dấu đã bay khi nhiệm vụ kết thúc). */
    public static synchronized SavedField findByMission(Context ctx, String missionId) {
        if (missionId == null) return null;
        for (SavedField f : list(ctx)) if (f.missionIds.contains(missionId)) return f;
        return null;
    }

    /** Được gọi (trên luồng gọi put/delete) mỗi khi danh sách ruộng thay đổi — để bản đồ vẽ lại. */
    private static Runnable changeListener;

    public static void setOnChangeListener(Runnable listener) {
        changeListener = listener;
    }

    private static void notifyChanged() {
        Runnable l = changeListener;
        if (l != null) l.run();
    }

    /** Thêm mới hoặc ghi đè theo id. */
    public static synchronized void put(Context ctx, SavedField field) {
        if (field.id == null) {
            field.id = "F_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            field.createdAt = System.currentTimeMillis();
        }
        List<SavedField> all = list(ctx);
        all.removeIf(f -> f.id.equals(field.id));
        all.add(field);
        save(ctx, all);
        notifyChanged();
    }

    public static synchronized void delete(Context ctx, String id) {
        List<SavedField> all = list(ctx);
        all.removeIf(f -> f.id.equals(id));
        save(ctx, all);
        notifyChanged();
    }

    private static void save(Context ctx, List<SavedField> all) {
        try {
            JSONArray arr = new JSONArray();
            for (SavedField f : all) arr.put(f.toJson());
            prefs(ctx).edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // =========================================================
    // DỮ LIỆU ĐẨY LÊN CLOUD
    // =========================================================
    /** GeoJSON Feature (Polygon) — web vẽ thẳng lên bản đồ được (Leaflet / Mapbox / Google Maps). */
    public static String toGeoJson(SavedField f) {
        try {
            JSONArray ring = new JSONArray();
            for (int i = 0; i < f.lats.length; i++) {
                ring.put(new JSONArray().put(f.lngs[i]).put(f.lats[i])); // GeoJSON: [kinh độ, vĩ độ]
            }
            if (f.lats.length > 0) ring.put(new JSONArray().put(f.lngs[0]).put(f.lats[0])); // khép kín
            JSONObject geometry = new JSONObject()
                    .put("type", "Polygon")
                    .put("coordinates", new JSONArray().put(ring));
            JSONObject props = new JSONObject()
                    .put("field_id", f.id)
                    .put("field_name", f.name)
                    .put("area_ha", round(f.areaHa, 4))
                    .put("status", f.status)
                    .put("flight_count", f.flightCount)
                    .put("mission_ids", new JSONArray(f.missionIds))
                    .put("created_at", iso(f.createdAt))
                    .put("updated_at", iso(f.updatedAt))
                    .put("last_flown_at", f.lastFlownAt > 0 ? iso(f.lastFlownAt) : JSONObject.NULL)
                    .put("flight_params", new JSONObject()
                            .put("altitude_m", f.altitude)
                            .put("line_spacing_m", f.spacing)
                            .put("edge_margin_m", f.margin)
                            .put("speed_mps", f.speed)
                            .put("mode", f.video ? "video" : "photo"));
            return new JSONObject()
                    .put("type", "Feature")
                    .put("geometry", geometry)
                    .put("properties", props)
                    .toString(2);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Metadata gửi kèm (trường "context" của Cloudinary) — server nhận qua webhook như với ảnh. */
    public static Map<String, String> cloudContext(SavedField f) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("meta_version", "1");
        m.put("source", "dji_controller");
        m.put("type", "field");
        m.put("field_id", f.id);
        m.put("field_name", f.name);
        m.put("area_ha", String.format(Locale.US, "%.4f", f.areaHa));
        m.put("center_lat", String.format(Locale.US, "%.7f", f.centerLat()));
        m.put("center_lon", String.format(Locale.US, "%.7f", f.centerLng()));
        StringBuilder corners = new StringBuilder();
        for (int i = 0; i < f.lats.length; i++) {
            if (i > 0) corners.append(';');
            corners.append(String.format(Locale.US, "%.7f,%.7f", f.lats[i], f.lngs[i]));
        }
        m.put("corners", corners.toString()); // "lat,lon;lat,lon;..."
        m.put("status", f.status);
        m.put("flight_count", String.valueOf(f.flightCount));
        if (!f.missionIds.isEmpty()) m.put("mission_ids", String.join(",", f.missionIds));
        if (f.lastFlownAt > 0) m.put("last_flown_at", iso(f.lastFlownAt));
        m.put("updated_at", iso(f.updatedAt));
        return m;
    }

    private static String iso(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(new Date(ms));
    }

    private static double round(double v, int d) {
        double p = Math.pow(10, d);
        return Math.round(v * p) / p;
    }
}
