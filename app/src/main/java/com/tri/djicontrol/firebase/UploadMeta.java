package com.tri.djicontrol.firebase;

import android.content.Context;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import com.tri.djicontrol.flight.MissionLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dji.common.error.DJIError;
import dji.common.util.CommonCallbacks;
import dji.sdk.base.BaseProduct;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.media.MediaFile;
import dji.sdk.products.Aircraft;
import dji.sdk.sdkmanager.DJISDKManager;

/**
 * Metadata có cấu trúc gửi kèm mỗi file lên Cloudinary (trường "context").
 * Server web nhận nguyên bộ key này qua webhook -> không phải tách chuỗi caption tiếng Việt.
 *
 * Các key (giá trị luôn là chuỗi, số dùng dấu chấm thập phân, thiếu dữ liệu thì bỏ key):
 *   meta_version  phiên bản định dạng (hiện = 1)
 *   source        "dji_controller"
 *   type          photo | video
 *   file_name     tên file gốc, vd DJI_0012.JPG
 *   variant       original (file gốc từ thẻ nhớ) | live_preview (bản xem nhanh ghi từ luồng camera)
 *   taken_at      thời điểm chụp ISO 8601 có múi giờ, vd 2026-11-22T10:15:30+07:00
 *   lat, lon      toạ độ WGS84 (độ)
 *   alt           độ cao so với điểm cất cánh (m)       — từ XMP của DJI
 *   alt_abs       độ cao tuyệt đối/GPS (m)
 *   heading       hướng mũi drone (độ, -180..180, 0 = Bắc)
 *   gimbal_pitch  góc camera (độ, -90 = nhìn thẳng xuống)
 *   gimbal_yaw    hướng camera (độ)
 *   drone_model   tên drone, vd "Mini 2"
 *   drone_sn      số serial drone
 *   mission_id    mã chuyến bay quét, vd M_20261122_101530 (chỉ có khi file thuộc một chuyến bay)
 *   caption       mô tả dễ đọc cho người (tiếng Việt)
 */
public final class UploadMeta {

    private static final String TAG = "UploadMeta";
    public static final String META_VERSION = "1";
    private static final int XMP_SCAN_BYTES = 512 * 1024;

    private static volatile String droneSn;
    private static volatile String droneModel;

    private UploadMeta() {}

    // =========================================================
    // THÔNG TIN DRONE ĐANG KẾT NỐI
    // =========================================================

    /** Gọi khi drone kết nối để lưu sẵn model + serial (lấy serial là bất đồng bộ). */
    public static void refreshDroneInfo() {
        try {
            BaseProduct product = DJISDKManager.getInstance().getProduct();
            if (product == null) return;
            if (product.getModel() != null) droneModel = product.getModel().getDisplayName();
            if (product instanceof Aircraft) {
                FlightController fc = ((Aircraft) product).getFlightController();
                if (fc != null) {
                    fc.getSerialNumber(new CommonCallbacks.CompletionCallbackWith<String>() {
                        @Override public void onSuccess(String sn) { droneSn = sn; }
                        @Override public void onFailure(DJIError e) {
                            Log.w(TAG, "Không lấy được serial: " + (e != null ? e.getDescription() : ""));
                        }
                    });
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "refreshDroneInfo", e);
        }
    }

    public static String getConnectedDroneModel() {
        if (droneModel == null) {
            try {
                BaseProduct p = DJISDKManager.getInstance().getProduct();
                if (p != null && p.getModel() != null) droneModel = p.getModel().getDisplayName();
            } catch (Exception ignored) {}
        }
        return droneModel;
    }

    public static String getConnectedDroneSn() {
        return droneSn;
    }

    // =========================================================
    // TẠO METADATA
    // =========================================================

    /**
     * @param mediaFile file gốc trên thẻ nhớ drone (null nếu là file đã có sẵn trên điện thoại)
     * @param caption   mô tả dễ đọc (có thể null)
     */
    public static Map<String, String> build(Context context, File file, MediaFile mediaFile, String caption) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("meta_version", META_VERSION);
        m.put("source", "dji_controller");

        String name = mediaFile != null && mediaFile.getFileName() != null ? mediaFile.getFileName() : file.getName();
        boolean video = isVideo(name);
        m.put("type", video ? "video" : "photo");
        m.put("file_name", name);
        // Bản xem nhanh ghi từ luồng camera (chất lượng thấp) -> web phân biệt với bản gốc
        m.put("variant", name.startsWith("LIVE_REC_") ? "live_preview" : "original");

        long takenAt = 0;
        double lat = Double.NaN, lon = Double.NaN;
        try {
            if (!video) {
                ExifInterface exif = new ExifInterface(file.getAbsolutePath());
                String dt = exif.getAttribute("DateTimeOriginal");
                if (dt == null) dt = exif.getAttribute(ExifInterface.TAG_DATETIME);
                takenAt = parseLocal(dt, "yyyy:MM:dd HH:mm:ss");

                float[] ll = new float[2];
                if (exif.getLatLong(ll) && !(ll[0] == 0 && ll[1] == 0)) {
                    lat = ll[0];
                    lon = ll[1];
                }
                double altAbs = exif.getAltitude(Double.NaN);
                if (!Double.isNaN(altAbs)) putNum(m, "alt_abs", altAbs, 2);

                Map<String, String> xmp = readDjiXmp(file);
                if (Double.isNaN(lat)) {
                    lat = parseD(xmp.get("GpsLatitude"));
                    lon = parseD(firstNonNull(xmp.get("GpsLongitude"), xmp.get("GpsLongtitude"))); // DJI viết sai chính tả ở vài model
                }
                putNum(m, "alt", parseD(xmp.get("RelativeAltitude")), 2);
                if (!m.containsKey("alt_abs")) putNum(m, "alt_abs", parseD(xmp.get("AbsoluteAltitude")), 2);
                putNum(m, "heading", parseD(xmp.get("FlightYawDegree")), 1);
                putNum(m, "gimbal_pitch", parseD(xmp.get("GimbalPitchDegree")), 1);
                putNum(m, "gimbal_yaw", parseD(xmp.get("GimbalYawDegree")), 1);
                putStr(m, "drone_sn", xmp.get("DroneSerialNumber"));
            } else {
                readVideoMeta(file, m);
                String loc = m.remove("_loc");
                if (loc != null) {
                    double[] p = parseIso6709(loc);
                    if (p != null) { lat = p[0]; lon = p[1]; }
                }
                String t = m.remove("_time");
                if (t != null) takenAt = Long.parseLong(t);
            }
        } catch (Exception e) {
            Log.w(TAG, "Đọc metadata lỗi: " + name, e);
        }

        // Giờ trên thẻ nhớ drone đáng tin hơn giờ trong file video
        if (mediaFile != null) {
            long t = parseLocal(mediaFile.getDateCreated(), "yyyy-MM-dd HH:mm:ss");
            if (t > 0 && (video || takenAt <= 0)) takenAt = t;
        }

        if (takenAt > 0) m.put("taken_at", formatIso(takenAt));
        if (!Double.isNaN(lat) && !Double.isNaN(lon)) {
            putNum(m, "lat", lat, 7);
            putNum(m, "lon", lon, 7);
        }

        MissionLog.Mission mission = MissionLog.find(context, takenAt, lat, lon);
        if (mission != null) {
            m.put("mission_id", mission.id);
            if (!m.containsKey("drone_sn")) putStr(m, "drone_sn", mission.droneSn);
            putStr(m, "drone_model", mission.droneModel);
        }
        if (!m.containsKey("drone_model")) putStr(m, "drone_model", getConnectedDroneModel());
        // Serial của drone đang kết nối chỉ chắc đúng khi file lấy trực tiếp từ thẻ nhớ drone
        if (!m.containsKey("drone_sn") && mediaFile != null) putStr(m, "drone_sn", droneSn);

        putStr(m, "caption", caption);
        return m;
    }

    // =========================================================
    // ĐỌC XMP CỦA DJI (drone-dji:RelativeAltitude, GimbalPitchDegree...)
    // =========================================================
    private static final Pattern XMP_ATTR = Pattern.compile("drone-dji:(\\w+)=\"([^\"]*)\"");
    private static final Pattern XMP_ELEM = Pattern.compile("<drone-dji:(\\w+)>([^<]*)</drone-dji:\\1>");

    public static Map<String, String> readDjiXmp(File file) {
        Map<String, String> out = new HashMap<>();
        byte[] buf = new byte[(int) Math.min(XMP_SCAN_BYTES, file.length())];
        try (InputStream in = new FileInputStream(file)) {
            int read = 0;
            while (read < buf.length) {
                int n = in.read(buf, read, buf.length - read);
                if (n < 0) break;
                read += n;
            }
            String s = new String(buf, 0, read, StandardCharsets.ISO_8859_1);
            int i = s.indexOf("drone-dji:");
            if (i < 0) return out;
            Matcher a = XMP_ATTR.matcher(s);
            while (a.find()) out.put(a.group(1), a.group(2).trim());
            Matcher e = XMP_ELEM.matcher(s);
            while (e.find()) out.put(e.group(1), e.group(2).trim());
        } catch (Exception ignored) {}
        return out;
    }

    private static void readVideoMeta(File file, Map<String, String> m) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(file.getAbsolutePath());
            String loc = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION);
            if (loc != null) m.put("_loc", loc);
            String date = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE); // 20261122T031530.000Z
            if (date != null) {
                SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date d = f.parse(date);
                // Bỏ giá trị mặc định 1904/1970 khi file không ghi ngày
                if (d != null && d.getTime() > 946684800000L) m.put("_time", String.valueOf(d.getTime()));
            }
        } catch (Exception ignored) {
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    /** "+10.1234+106.5678/" hoặc "+10.1234+106.5678+12.0/" -> {lat, lon}. */
    private static double[] parseIso6709(String s) {
        Matcher mt = Pattern.compile("([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)").matcher(s);
        if (!mt.find()) return null;
        double lat = Double.parseDouble(mt.group(1));
        double lon = Double.parseDouble(mt.group(2));
        if (lat == 0 && lon == 0) return null;
        return new double[]{lat, lon};
    }

    // =========================================================
    // TIỆN ÍCH
    // =========================================================
    private static boolean isVideo(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".mp4") || n.endsWith(".mov");
    }

    /** Giờ trong EXIF/thẻ nhớ không có múi giờ -> hiểu theo múi giờ điện thoại. */
    private static long parseLocal(String s, String pattern) {
        if (s == null || s.trim().isEmpty()) return 0;
        try {
            Date d = new SimpleDateFormat(pattern, Locale.US).parse(s.trim());
            return d != null ? d.getTime() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String formatIso(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(new Date(ms));
    }

    private static double parseD(String s) {
        if (s == null) return Double.NaN;
        try {
            return Double.parseDouble(s.trim().replace("+", ""));
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private static void putNum(Map<String, String> m, String key, double v, int decimals) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return;
        m.put(key, String.format(Locale.US, "%." + decimals + "f", v));
    }

    private static void putStr(Map<String, String> m, String key, String v) {
        if (v != null && !v.trim().isEmpty()) m.put(key, v.trim());
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }
}
