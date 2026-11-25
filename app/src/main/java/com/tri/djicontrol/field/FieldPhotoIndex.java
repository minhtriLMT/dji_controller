package com.tri.djicontrol.field;

import android.content.Context;
import android.media.ExifInterface;
import android.os.Environment;

import com.tri.djicontrol.firebase.UploadMeta;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tìm các ảnh đã có trên điện thoại (tải từ thẻ nhớ drone / ảnh bay tự động) chụp bên trong 1 mảnh ruộng,
 * dựa vào toạ độ GPS trong EXIF/XMP của ảnh DJI. Kèm độ cao, hướng mũi, góc gimbal để bay lại đúng chỗ.
 *
 * Gọi {@link #findInField} ở luồng nền (đọc file).
 */
public final class FieldPhotoIndex {

    public static final class Photo {
        public final File file;
        public final double lat, lng;
        /** Độ cao so với điểm cất cánh (m), NaN nếu ảnh không ghi. */
        public final float relAlt;
        /** Hướng mũi drone và góc gimbal lúc chụp (độ), NaN nếu không có. */
        public final float yaw, gimbalPitch;
        public final long takenAt;

        Photo(File file, double lat, double lng, float relAlt, float yaw, float gimbalPitch, long takenAt) {
            this.file = file;
            this.lat = lat;
            this.lng = lng;
            this.relAlt = relAlt;
            this.yaw = yaw;
            this.gimbalPitch = gimbalPitch;
            this.takenAt = takenAt;
        }
    }

    /** Cache theo đường dẫn + thời điểm sửa file (đọc EXIF/XMP tốn thời gian). null = ảnh không có GPS. */
    private static final Map<String, Photo> CACHE = new HashMap<>();
    private static final Map<String, Long> CACHE_MTIME = new HashMap<>();

    private FieldPhotoIndex() {}

    /** Các thư mục app lưu ảnh tải từ drone. */
    public static List<File> photoDirs(Context ctx) {
        List<File> dirs = new ArrayList<>();
        File base = ctx.getExternalFilesDir(null);
        if (base != null) {
            for (String n : new String[]{"DJI_WP_Upload", "DJI_Photos", "DJI_Downloads", "DJI_Upload"}) {
                dirs.add(new File(base, n));
            }
        }
        dirs.add(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "DJI_Photos"));
        return dirs;
    }

    /** Ảnh chụp bên trong ruộng (mới nhất trước). Trùng tên file ở nhiều thư mục chỉ lấy 1. */
    public static List<Photo> findInField(Context ctx, SavedFieldStore.SavedField field) {
        List<Photo> out = new ArrayList<>();
        if (field == null || field.lats == null || field.lats.length < 3) return out;
        double[][] ring = sortedRing(field);
        Map<String, Photo> byName = new HashMap<>();
        for (File dir : photoDirs(ctx)) {
            File[] list = dir.listFiles();
            if (list == null) continue;
            for (File f : list) {
                if (!f.isFile() || !isPhoto(f.getName())) continue;
                Photo p = read(f);
                if (p == null || !inside(ring, p.lat, p.lng)) continue;
                String key = f.getName().toLowerCase(Locale.US);
                if (!byName.containsKey(key)) byName.put(key, p);
            }
        }
        out.addAll(byName.values());
        out.sort((a, b) -> Long.compare(b.takenAt, a.takenAt));
        return out;
    }

    private static boolean isPhoto(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".jpg") || n.endsWith(".jpeg");
    }

    private static synchronized Photo read(File f) {
        String key = f.getAbsolutePath();
        Long m = CACHE_MTIME.get(key);
        if (m != null && m == f.lastModified()) return CACHE.get(key);
        Photo p = null;
        try {
            ExifInterface exif = new ExifInterface(f.getAbsolutePath());
            float[] ll = new float[2];
            double lat = Double.NaN, lng = Double.NaN;
            if (exif.getLatLong(ll) && !(ll[0] == 0 && ll[1] == 0)) {
                lat = ll[0];
                lng = ll[1];
            }
            Map<String, String> xmp = UploadMeta.readDjiXmp(f);
            if (Double.isNaN(lat)) {
                lat = parse(xmp.get("GpsLatitude"));
                String lo = xmp.get("GpsLongitude");
                lng = parse(lo != null ? lo : xmp.get("GpsLongtitude"));
            }
            if (!Double.isNaN(lat) && !Double.isNaN(lng)) {
                long t = 0;
                String dt = exif.getAttribute("DateTimeOriginal");
                if (dt == null) dt = exif.getAttribute(ExifInterface.TAG_DATETIME);
                if (dt != null) {
                    try {
                        java.util.Date d = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(dt);
                        if (d != null) t = d.getTime();
                    } catch (Exception ignored) {}
                }
                if (t <= 0) t = f.lastModified();
                p = new Photo(f, lat, lng, (float) parse(xmp.get("RelativeAltitude")),
                        (float) parse(xmp.get("FlightYawDegree")), (float) parse(xmp.get("GimbalPitchDegree")), t);
            }
        } catch (Exception ignored) {}
        CACHE.put(key, p);
        CACHE_MTIME.put(key, f.lastModified());
        return p;
    }

    private static double parse(String s) {
        if (s == null) return Double.NaN;
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return Double.NaN; }
    }

    /** Các góc ruộng sắp theo vòng quanh tâm (giống cách vẽ trên bản đồ). [i] = {lat, lng}. */
    private static double[][] sortedRing(SavedFieldStore.SavedField f) {
        int n = Math.min(f.lats.length, f.lngs.length);
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < n; i++) pts.add(new double[]{f.lats[i], f.lngs[i]});
        double cLat = 0, cLng = 0;
        for (double[] p : pts) { cLat += p[0]; cLng += p[1]; }
        final double lat0 = cLat / n, lng0 = cLng / n;
        pts.sort((a, b) -> Double.compare(Math.atan2(a[0] - lat0, a[1] - lng0), Math.atan2(b[0] - lat0, b[1] - lng0)));
        return pts.toArray(new double[0][]);
    }

    /** Điểm nằm trong đa giác (ray casting), nới thêm ~3 m để ảnh sát bờ ruộng vẫn được tính. */
    private static boolean inside(double[][] ring, double lat, double lng) {
        boolean in = false;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            double yi = ring[i][0], xi = ring[i][1], yj = ring[j][0], xj = ring[j][1];
            if ((yi > lat) != (yj > lat) && lng < (xj - xi) * (lat - yi) / (yj - yi) + xi) in = !in;
        }
        if (in) return true;
        double tol = 3.0 / 111_320.0;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            if (distToSegment(lat, lng, ring[j], ring[i]) < tol) return true;
        }
        return false;
    }

    private static double distToSegment(double lat, double lng, double[] a, double[] b) {
        double k = Math.cos(Math.toRadians(lat));
        double px = lng * k, py = lat, ax = a[1] * k, ay = a[0], bx = b[1] * k, by = b[0];
        double dx = bx - ax, dy = by - ay;
        double t = dx == 0 && dy == 0 ? 0 : ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy);
        t = Math.max(0, Math.min(1, t));
        double cx = ax + t * dx - px, cy = ay + t * dy - py;
        return Math.sqrt(cx * cx + cy * cy);
    }
}
