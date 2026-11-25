package com.tri.djicontrol.firebase;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Đẩy ảnh/video lên cloud.
 * (Tên lớp giữ nguyên để không phải sửa nơi gọi; bên trong dùng Cloudinary vì
 * Firebase Storage bắt buộc gói trả phí Blaze.)
 */
public class FirebaseHelper {

    /** Kích thước mỗi phần khi upload file lớn (Cloudinary yêu cầu >= 5MB, trừ phần cuối). */
    private static final int CHUNK_SIZE = 6 * 1024 * 1024;

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.MINUTES)
            .readTimeout(5, TimeUnit.MINUTES)
            .build();

    public interface UploadCallback {
        void onProgress(int percent, long bytesTransferred, long totalBytes);
        void onSuccess(String downloadUrl);
        void onFailure(String message);
    }

    /** Đã điền Cloud name + Upload preset trong CloudConfig chưa. */
    public static boolean isConfigured(Context context) {
        return !CloudConfig.CLOUD_NAME.startsWith("YOUR_") && !CloudConfig.UPLOAD_PRESET.startsWith("YOUR_");
    }

    /** Bản đơn giản: tự hiện Toast kết quả. */
    public static void uploadFileToFirebase(Context context, File localFile, String metaInfo) {
        Toast.makeText(context, "Đang đẩy lên Cloud: " + (localFile != null ? localFile.getName() : ""), Toast.LENGTH_SHORT).show();
        uploadFileToFirebase(context, localFile, metaInfo, new UploadCallback() {
            @Override public void onProgress(int percent, long done, long total) {}

            @Override
            public void onSuccess(String downloadUrl) {
                Toast.makeText(context, "Đã đẩy lên Cloud thành công!", Toast.LENGTH_LONG).show();
            }

            @Override
            public void onFailure(String message) {
                Toast.makeText(context, "Lỗi Cloud: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    /** Upload file lên Cloudinary (thư mục dji_media), callback chạy trên UI thread. */
    public static void uploadFileToFirebase(Context context, File localFile, String metaInfo, UploadCallback callback) {
        uploadFileToFirebase(context, localFile, null, metaInfo, callback);
    }

    /**
     * Như trên, kèm file gốc trên thẻ nhớ drone (nếu có) để lấy đúng thời gian chụp.
     * Metadata có cấu trúc (UploadMeta) được đọc trên luồng nền rồi gửi trong trường "context".
     */
    public static void uploadFileToFirebase(Context context, File localFile, dji.sdk.media.MediaFile mediaFile,
                                            String metaInfo, UploadCallback callback) {
        uploadFileToFirebase(context, localFile, mediaFile, metaInfo, CloudConfig.FOLDER, null, callback);
    }

    /**
     * Như trên nhưng chọn thư mục Cloudinary (vd CloudConfig.WP_FOLDER cho ảnh bay tự động)
     * và tag thêm (nhiều tag ngăn cách bởi dấu phẩy, có thể null).
     */
    public static void uploadFileToFirebase(Context context, File localFile, dji.sdk.media.MediaFile mediaFile,
                                            String metaInfo, String folder, String extraTags,
                                            UploadCallback callback) {
        Handler main = new Handler(Looper.getMainLooper());
        Context app = context.getApplicationContext();

        if (localFile == null || !localFile.exists() || localFile.length() == 0) {
            main.post(() -> callback.onFailure("File không tồn tại trên máy!"));
            return;
        }
        if (!isConfigured(context)) {
            main.post(() -> callback.onFailure("Chưa điền CLOUD_NAME / UPLOAD_PRESET trong CloudConfig.java"));
            return;
        }

        new Thread(() -> {
            try {
                Map<String, String> fields = UploadMeta.build(app, localFile, mediaFile, metaInfo);
                String url = upload(localFile, fields, folder, extraTags, (pct, done, total) ->
                        main.post(() -> callback.onProgress(pct, done, total)));
                main.post(() -> callback.onSuccess(url));
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                main.post(() -> callback.onFailure(msg));
            }
        }, "cloud-upload").start();
    }

    /**
     * Đẩy 1 file dữ liệu dạng văn bản (vd GeoJSON mảnh ruộng) lên Cloudinary (resource_type = raw).
     * Mỗi lần đẩy là 1 bản mới (preset unsigned không cho ghi đè) -> web lấy bản có updated_at mới nhất.
     */
    public static void uploadJson(Context context, String baseName, String json, Map<String, String> fields,
                                  String extraTags, UploadCallback callback) {
        Handler main = new Handler(Looper.getMainLooper());
        if (!isConfigured(context)) {
            main.post(() -> callback.onFailure("Chưa điền CLOUD_NAME / UPLOAD_PRESET trong CloudConfig.java"));
            return;
        }
        new Thread(() -> {
            try {
                String endpoint = "https://api.cloudinary.com/v1_1/" + CloudConfig.CLOUD_NAME + "/raw/upload";
                String fileName = baseName + "_" + System.currentTimeMillis() + ".json";
                byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                MultipartBody body = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("upload_preset", CloudConfig.UPLOAD_PRESET)
                        .addFormDataPart("folder", CloudConfig.FOLDER + "/fields")
                        .addFormDataPart("public_id", fileName) // raw: phần mở rộng nằm trong public_id
                        .addFormDataPart("tags", "dji_controller" + (extraTags != null ? "," + extraTags : ""))
                        .addFormDataPart("context", buildContext(fields))
                        .addFormDataPart("file", fileName,
                                RequestBody.create(bytes, MediaType.parse("application/geo+json")))
                        .build();
                main.post(() -> callback.onProgress(50, bytes.length / 2, bytes.length));
                String resp = executeWithRetry(new Request.Builder().url(endpoint).post(body).build());
                String url = new JSONObject(resp).optString("secure_url", null);
                main.post(() -> callback.onSuccess(url));
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                main.post(() -> callback.onFailure(msg));
            }
        }, "cloud-upload-json").start();
    }

    private interface Progress {
        void update(int percent, long done, long total);
    }

    /** Upload theo từng phần 6MB (ảnh nhỏ chỉ 1 phần) -> có % tiến trình và chịu được mạng chập chờn. */
    private static String upload(File file, Map<String, String> fields, String folder, String extraTags,
                                 Progress progress) throws Exception {
        String endpoint = "https://api.cloudinary.com/v1_1/" + CloudConfig.CLOUD_NAME + "/auto/upload";
        String uploadId = UUID.randomUUID().toString();
        long total = file.length();
        MediaType mime = MediaType.parse(guessMime(file.getName()));
        String publicId = stripExt(file.getName()) + "_" + file.lastModified();

        String context = buildContext(fields);
        String tags = "dji_controller";
        String missionId = fields.get("mission_id");
        if (missionId != null) tags += "," + missionId; // web lọc ảnh theo chuyến bay bằng tag
        if (extraTags != null && !extraTags.isEmpty()) tags += "," + extraTags;

        progress.update(0, 0, total);
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long start = 0;
            String lastBody = null;
            while (start < total) {
                int len = (int) Math.min(CHUNK_SIZE, total - start);
                byte[] buf = new byte[len];
                raf.seek(start);
                raf.readFully(buf);
                long end = start + len - 1;

                MultipartBody body = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("upload_preset", CloudConfig.UPLOAD_PRESET)
                        .addFormDataPart("folder", folder != null ? folder : CloudConfig.FOLDER)
                        .addFormDataPart("public_id", publicId)
                        .addFormDataPart("tags", tags)
                        .addFormDataPart("context", context)
                        .addFormDataPart("file", file.getName(), RequestBody.create(buf, mime))
                        .build();

                Request.Builder req = new Request.Builder().url(endpoint).post(body);
                if (total > CHUNK_SIZE) {
                    req.header("X-Unique-Upload-Id", uploadId)
                       .header("Content-Range", "bytes " + start + "-" + end + "/" + total);
                }

                lastBody = executeWithRetry(req.build());
                start = end + 1;
                progress.update((int) (start * 100 / total), start, total);
            }

            JSONObject json = new JSONObject(lastBody);
            return json.optString("secure_url", null);
        }
    }

    private static String executeWithRetry(Request request) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try (Response resp = client.newCall(request).execute()) {
                String body = resp.body() != null ? resp.body().string() : "";
                if (resp.isSuccessful()) return body;
                String err = body;
                try {
                    err = new JSONObject(body).getJSONObject("error").getString("message");
                } catch (Exception ignored) {}
                // Lỗi 4xx (sai preset, file quá lớn...) thì không thử lại
                if (resp.code() >= 400 && resp.code() < 500) {
                    throw new IllegalStateException(explain(resp.code(), err));
                }
                last = new IllegalStateException("HTTP " + resp.code() + ": " + err);
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                last = e; // lỗi mạng -> thử lại
            }
            Thread.sleep(1500L * attempt);
        }
        throw new Exception("Mất kết nối mạng: " + (last != null ? last.getMessage() : ""));
    }

    private static String explain(int code, String err) {
        String e = err != null ? err.toLowerCase(Locale.US) : "";
        if (e.contains("upload preset") || e.contains("unsigned")) {
            return "Upload preset sai hoặc chưa đặt Signing mode = Unsigned (" + err + ")";
        }
        if (e.contains("file size too large")) {
            return "File vượt giới hạn gói miễn phí Cloudinary (ảnh 10MB, video 100MB)";
        }
        if (code == 401 || e.contains("cloud_name") || e.contains("unknown api")) {
            return "Cloud name sai (" + err + ")";
        }
        return "HTTP " + code + ": " + err;
    }

    /** key1=value1|key2=value2 ... (định dạng "context" của Cloudinary). */
    private static String buildContext(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (e.getValue() == null) continue;
            if (sb.length() > 0) sb.append('|');
            sb.append(e.getKey()).append('=').append(escapeContext(e.getValue()));
        }
        return sb.toString();
    }

    // Cloudinary context: các cặp key=value ngăn cách bởi '|', phải escape '|' và '='
    private static String escapeContext(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("|", "\\|").replace("=", "\\=").replace("\n", "; ");
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String guessMime(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".dng")) return "image/x-adobe-dng";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".mov")) return "video/quicktime";
        return "application/octet-stream";
    }
}
