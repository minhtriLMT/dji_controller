package com.tri.djicontrol.flight;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import dji.common.camera.SettingsDefinitions;
import dji.common.error.DJIError;
import dji.sdk.camera.Camera;
import dji.sdk.media.DownloadListener;
import dji.sdk.media.MediaFile;
import dji.sdk.media.MediaManager;

import com.tri.djicontrol.StorageAdapter;
import com.tri.djicontrol.firebase.CloudConfig;
import com.tri.djicontrol.firebase.FirebaseHelper;

/**
 * Bay tự động (WP), chế độ chụp đứng yên: tại mỗi điểm dừng, sau khi chụp xong thì
 * tải ngay ảnh gốc vừa chụp từ thẻ nhớ drone về điện thoại rồi đẩy lên Cloudinary
 * (thư mục riêng CloudConfig.WP_FOLDER).
 *
 * Trình tự 1 ảnh: chuyển camera sang chế độ tải (Playback / MEDIA_DOWNLOAD) -> làm mới danh sách
 * file -> tải ảnh vừa chụp -> trả camera về chế độ chụp -> báo xong (drone bay tiếp).
 * Upload lên Cloudinary chạy nền bằng mạng điện thoại, không bắt drone phải đợi.
 *
 * Mọi callback chạy trên main thread. Chỉ xử lý 1 ảnh mỗi lúc.
 */
public class WpPhotoUploader {

    private static final String TAG = "WpPhotoUploader";
    /** Quá thời gian này mà chưa tải xong thì bỏ ảnh này (vẫn còn trên thẻ nhớ) để drone bay tiếp. */
    private static final long TRANSFER_TIMEOUT_MS = 30_000;
    /** Đợi camera ổn định sau khi đổi chế độ. */
    private static final long MODE_SETTLE_MS = 800;
    /** Tag thêm cho ảnh WP trên Cloudinary (ngoài tag dji_controller + mission_id). */
    public static final String CLOUD_TAG = "wp_mission";

    public interface StepCallback {
        /** Đã tải xong (hoặc bỏ qua) ảnh và camera đã về chế độ chụp -> drone được bay tiếp. */
        void onReadyToContinue(boolean downloaded, String message);
    }

    public interface UploadListener {
        /** Cập nhật số ảnh đã đẩy lên / lỗi / đang chờ. */
        void onUploadStats(int uploaded, int failed, int pending, String message);
    }

    private final Context appContext;
    private final Camera camera;
    private final boolean flat;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File dir;
    private final Set<String> handledNames = new HashSet<>();
    private UploadListener uploadListener;

    private volatile String lastNewFileName;
    private int token = 0;
    private boolean busy = false;
    private boolean switchedMode = false;
    private StepCallback pendingCallback;
    private Runnable timeoutTask;

    private int uploaded = 0, failed = 0, pending = 0;

    public WpPhotoUploader(Context context, Camera camera, boolean flatCamera) {
        this.appContext = context.getApplicationContext();
        this.camera = camera;
        this.flat = flatCamera;
        File base = appContext.getExternalFilesDir(null);
        dir = new File(base != null ? base : appContext.getFilesDir(), "DJI_WP_Upload");
        if (!dir.exists()) dir.mkdirs();
    }

    public void setUploadListener(UploadListener l) {
        uploadListener = l;
    }

    public static boolean isAvailable(Context context) {
        return FirebaseHelper.isConfigured(context);
    }

    /** Bắt đầu nghe ảnh mới camera vừa ghi để biết đúng tên file cần tải. */
    public void attach() {
        if (camera == null) return;
        try {
            camera.setMediaFileCallback(f -> {
                if (f != null && f.getFileName() != null) lastNewFileName = f.getFileName();
            });
        } catch (Exception e) {
            Log.w(TAG, "setMediaFileCallback", e);
        }
    }

    public void detach() {
        cancel();
        if (camera == null) return;
        try { camera.setMediaFileCallback(null); } catch (Exception ignored) {}
    }

    public boolean isBusy() {
        return busy;
    }

    /** Hủy ảnh đang tải (vd người dùng bấm dừng / pin yếu). Không gọi lại StepCallback. */
    public void cancel() {
        if (!busy) return;
        token++;
        busy = false;
        pendingCallback = null;
        if (timeoutTask != null) main.removeCallbacks(timeoutTask);
        StorageAdapter.resumeScheduler();
        restoreCamera(null);
    }

    /** Tải ảnh vừa chụp rồi đẩy lên Cloud. cb được gọi đúng 1 lần (trừ khi bị cancel). */
    public void transferLatest(StepCallback cb) {
        if (camera == null || busy) {
            main.post(() -> cb.onReadyToContinue(false, "Camera bận"));
            return;
        }
        busy = true;
        final int my = ++token;
        pendingCallback = cb;

        timeoutTask = () -> {
            if (my != token) return;
            Log.w(TAG, "Tải ảnh quá lâu, bỏ qua");
            finishStep(my, false, "Tải ảnh quá lâu, bỏ qua (ảnh vẫn trên thẻ nhớ)");
        };
        main.postDelayed(timeoutTask, TRANSFER_TIMEOUT_MS);

        enterDownloadMode(() -> {
            if (my != token) return;
            MediaManager mm = camera.getMediaManager();
            if (mm == null) {
                finishStep(my, false, "MediaManager chưa sẵn sàng");
                return;
            }
            refreshSdList(mm, () -> {
                if (my != token) return;
                // Đọc tên lúc này (camera báo file mới có thể trễ hơn callback chụp)
                MediaFile target = pickFile(mm, lastNewFileName);
                if (target == null) {
                    finishStep(my, false, "Không tìm thấy ảnh vừa chụp trên thẻ nhớ");
                    return;
                }
                download(my, target);
            });
        });
    }

    // =========================================================
    // CHẾ ĐỘ CAMERA
    // =========================================================
    private void enterDownloadMode(Runnable then) {
        switchedMode = false;
        try {
            if (flat) {
                // Mini 2 / Air 2...: phải vào Playback mới đọc/tải được file trên thẻ
                camera.enterPlayback(err -> {
                    if (err != null) Log.w(TAG, "enterPlayback: " + err.getDescription());
                    switchedMode = true;
                    main.postDelayed(then, MODE_SETTLE_MS);
                });
                return;
            }
            boolean supported = true;
            try { supported = camera.isMediaDownloadModeSupported(); } catch (Exception ignored) {}
            if (supported) {
                camera.setMode(SettingsDefinitions.CameraMode.MEDIA_DOWNLOAD, err -> {
                    if (err != null) Log.w(TAG, "setMode MEDIA_DOWNLOAD: " + err.getDescription());
                    switchedMode = true;
                    main.postDelayed(then, MODE_SETTLE_MS);
                });
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "enterDownloadMode", e);
        }
        main.post(then);
    }

    /** Trả camera về chế độ chụp ảnh đơn (giống FlightMissionManager.prepareCamera). */
    private void restoreCamera(Runnable then) {
        Runnable done = () -> { if (then != null) main.postDelayed(then, MODE_SETTLE_MS); };
        if (!switchedMode) {
            if (then != null) main.post(then);
            return;
        }
        switchedMode = false;
        try {
            if (flat) {
                camera.exitPlayback(ignored -> camera.setFlatMode(SettingsDefinitions.FlatCameraMode.PHOTO_SINGLE,
                        err -> {
                            if (err != null) Log.w(TAG, "setFlatMode: " + err.getDescription());
                            main.post(done);
                        }));
            } else {
                camera.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO, err -> {
                    if (err != null) Log.w(TAG, "setMode SHOOT_PHOTO: " + err.getDescription());
                    main.post(done);
                });
            }
        } catch (Exception e) {
            Log.w(TAG, "restoreCamera", e);
            main.post(done);
        }
    }

    // =========================================================
    // TÌM + TẢI FILE
    // =========================================================
    private void refreshSdList(MediaManager mm, Runnable then) {
        try {
            mm.refreshFileListOfStorageLocation(SettingsDefinitions.StorageLocation.SDCARD, err -> {
                if (err != null) Log.w(TAG, "refresh SD: " + err.getDescription());
                main.post(then);
            });
        } catch (Exception e) {
            try {
                mm.refreshFileList(err -> main.post(then));
            } catch (Exception e2) {
                main.post(then);
            }
        }
    }

    /** Ưu tiên đúng tên camera vừa báo; không có thì lấy ảnh mới nhất chưa xử lý. */
    private MediaFile pickFile(MediaManager mm, String expectedName) {
        List<MediaFile> all = new ArrayList<>();
        try {
            List<MediaFile> sd = mm.getSDCardFileListSnapshot();
            if (sd != null) all.addAll(sd);
        } catch (Exception ignored) {}
        if (all.isEmpty()) {
            try {
                List<MediaFile> in = mm.getInternalStorageFileListSnapshot();
                if (in != null) all.addAll(in);
            } catch (Exception ignored) {}
        }

        MediaFile newest = null;
        for (MediaFile f : all) {
            if (f == null || f.getFileName() == null || !isPhoto(f)) continue;
            if (expectedName != null && expectedName.equalsIgnoreCase(f.getFileName())
                    && !handledNames.contains(f.getFileName())) {
                return f;
            }
            if (handledNames.contains(f.getFileName())) continue;
            if (newest == null || f.getTimeCreated() > newest.getTimeCreated()) newest = f;
        }
        return newest;
    }

    private static boolean isPhoto(MediaFile f) {
        MediaFile.MediaType t = f.getMediaType();
        return t != MediaFile.MediaType.MOV && t != MediaFile.MediaType.MP4;
    }

    private void download(int my, MediaFile target) {
        final String name = target.getFileName();
        handledNames.add(name);
        StorageAdapter.suspendScheduler(() -> {
            if (my != token) return;
            target.fetchFileData(dir, StorageAdapter.baseName(name), new DownloadListener<String>() {
                @Override public void onStart() {}
                @Override public void onRateUpdate(long total, long current, long persize) {}
                @Override public void onProgress(long total, long current) {}
                @Override public void onRealtimeDataUpdate(byte[] bytes, long l, boolean b) {}

                @Override
                public void onSuccess(String filePath) {
                    main.post(() -> {
                        StorageAdapter.resumeScheduler();
                        File real = StorageAdapter.resolveDownloadedFile(dir, name, filePath);
                        if (real != null) startUpload(real, target);
                        if (my != token) return; // đã timeout / hủy: vẫn upload nhưng không báo bước
                        finishStep(my, real != null, real != null
                                ? "Đã tải " + name + ", đang đẩy lên Cloud"
                                : "Tải xong nhưng không thấy file " + name);
                    });
                }

                @Override
                public void onFailure(DJIError error) {
                    main.post(() -> {
                        StorageAdapter.resumeScheduler();
                        handledNames.remove(name); // để lần sau còn thử lại được
                        if (my != token) return;
                        finishStep(my, false, "Lỗi tải " + name + ": "
                                + (error != null ? error.getDescription() : "thất bại"));
                    });
                }
            });
        });
    }

    private void finishStep(int my, boolean ok, String msg) {
        if (my != token || !busy) return;
        if (timeoutTask != null) main.removeCallbacks(timeoutTask);
        token++; // các callback trễ của bước này bị bỏ qua
        busy = false;
        StepCallback cb = pendingCallback;
        pendingCallback = null;
        if (!ok) failed++;
        notifyStats(msg);
        restoreCamera(() -> { if (cb != null) cb.onReadyToContinue(ok, msg); });
    }

    // =========================================================
    // ĐẨY LÊN CLOUDINARY (chạy nền, drone không phải đợi)
    // =========================================================
    private void startUpload(File file, MediaFile mediaFile) {
        pending++;
        notifyStats("Đang đẩy " + file.getName() + " lên Cloud");
        String meta = StorageAdapter.buildMetaInfo(file, mediaFile);
        FirebaseHelper.uploadFileToFirebase(appContext, file, mediaFile, meta, CloudConfig.WP_FOLDER, CLOUD_TAG,
                new FirebaseHelper.UploadCallback() {
                    @Override public void onProgress(int percent, long done, long total) {}

                    @Override
                    public void onSuccess(String url) {
                        pending--;
                        uploaded++;
                        notifyStats("Đã đẩy " + file.getName() + " lên Cloud");
                    }

                    @Override
                    public void onFailure(String message) {
                        pending--;
                        failed++;
                        notifyStats("Lỗi Cloud (" + file.getName() + "): " + message);
                    }
                });
    }

    private void notifyStats(String msg) {
        Log.i(TAG, String.format(Locale.US, "lên=%d lỗi=%d chờ=%d | %s", uploaded, failed, pending, msg));
        if (uploadListener != null) uploadListener.onUploadStats(uploaded, failed, pending, msg);
    }
}
