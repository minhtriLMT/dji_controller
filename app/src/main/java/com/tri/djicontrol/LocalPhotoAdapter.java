package com.tri.djicontrol;

import android.app.Dialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.tri.djicontrol.firebase.FirebaseHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tab "Ảnh trên điện thoại" của Kho lưu trữ: ảnh chụp trong lúc quay video (khung hình live view)
 * khi camera drone không hỗ trợ chụp lúc quay. Hỗ trợ chọn nhiều + TẢI VỀ / CLOUD / XÓA
 * giống tab thẻ nhớ drone (dùng chung thanh nút trong dialog_storage).
 */
public class LocalPhotoAdapter extends BaseAdapter {

    public static final String PREFIX = "PHOTO_IN_VIDEO_";
    /** Bản xem nhanh ghi từ luồng camera trực tiếp (xem LiveStreamRecorder). */
    public static final String LIVE_PREFIX = "LIVE_REC_";

    private final Context context;
    private final LayoutInflater inflater;
    private final List<File> files = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> memCache = new LruCache<String, Bitmap>(
            (int) (Runtime.getRuntime().maxMemory() / 1024 / 16)) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount() / 1024;
        }
    };

    private boolean isSelectionMode = false;
    private final Set<File> selectedFiles = new LinkedHashSet<>();
    private StorageAdapter.OnSelectionListener selectionListener;

    public LocalPhotoAdapter(Context context) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
    }

    private static File galleryDir() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "DJI_Photos");
    }

    private File privateDir() {
        return new File(context.getExternalFilesDir(null), "DJI_Photos");
    }

    /** Bản xem nhanh (ghi từ luồng camera) sau khi được chép vào Bộ sưu tập. */
    private static File videoGalleryDir() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "DJI_Videos");
    }

    static boolean isVideo(File f) {
        return f.getName().toLowerCase(Locale.US).endsWith(".mp4");
    }

    /** File nằm trong Bộ sưu tập (DCIM) hay chỉ trong thư mục riêng của app. */
    private static boolean isInGallery(File f) {
        File parent = f.getParentFile();
        if (parent == null) return false;
        String p = parent.getAbsolutePath();
        return p.equals(galleryDir().getAbsolutePath()) || p.equals(videoGalleryDir().getAbsolutePath());
    }

    /** Quét lại ảnh + bản xem nhanh video trên điện thoại, trả về số file tìm được. */
    public int reload() {
        files.clear();
        Set<String> seen = new HashSet<>();
        // DCIM trước: nếu trùng tên thì ưu tiên bản đã nằm trong Bộ sưu tập
        for (File dir : new File[]{galleryDir(), privateDir()}) {
            File[] list = dir.listFiles();
            if (list == null) continue;
            for (File f : list) {
                String n = f.getName();
                if (f.isFile() && f.length() > 0 && n.startsWith(PREFIX)
                        && n.toLowerCase(Locale.US).endsWith(".jpg") && seen.add(n)) {
                    files.add(f);
                }
            }
        }
        for (File dir : new File[]{videoGalleryDir(), MainActivity.liveRecordDir(context)}) {
            File[] list = dir.listFiles();
            if (list == null) continue;
            for (File f : list) {
                String n = f.getName();
                if (f.isFile() && f.length() > 0 && n.startsWith(LIVE_PREFIX) && isVideo(f) && seen.add(n)) {
                    files.add(f);
                }
            }
        }
        Collections.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        selectedFiles.retainAll(files);
        notifyDataSetChanged();
        notifySelectionCount();
        return files.size();
    }

    public void shutdown() {
        worker.shutdownNow();
    }

    // =========================================================
    // CHỌN NHIỀU (giống StorageAdapter)
    // =========================================================
    public void setOnSelectionListener(StorageAdapter.OnSelectionListener listener) {
        this.selectionListener = listener;
    }

    public void setSelectionMode(boolean selectionMode) {
        if (isSelectionMode == selectionMode) return;
        isSelectionMode = selectionMode;
        if (!selectionMode) selectedFiles.clear();
        notifyDataSetChanged();
        if (selectionListener != null) {
            selectionListener.onSelectionModeChanged(selectionMode);
            selectionListener.onSelectionChanged(selectedFiles.size());
        }
    }

    private void toggleSelection(File f) {
        if (!selectedFiles.remove(f)) selectedFiles.add(f);
        notifyDataSetChanged();
        notifySelectionCount();
    }

    private void notifySelectionCount() {
        if (selectionListener != null) selectionListener.onSelectionChanged(selectedFiles.size());
    }

    /** Lấy danh sách đã chọn rồi thoát chế độ chọn. Trả về null (kèm Toast) nếu chưa chọn gì. */
    private List<File> takeSelection(String actionLabel) {
        if (selectedFiles.isEmpty()) {
            Toast.makeText(context, "Vui lòng chọn ít nhất 1 file để " + actionLabel + "!", Toast.LENGTH_SHORT).show();
            return null;
        }
        List<File> list = new ArrayList<>(selectedFiles);
        setSelectionMode(false);
        return list;
    }

    // =========================================================
    // NÚT XÓA
    // =========================================================
    public void deleteSelected() {
        if (selectedFiles.isEmpty()) {
            Toast.makeText(context, "Vui lòng chọn ít nhất 1 file để xóa!", Toast.LENGTH_SHORT).show();
            return;
        }
        int count = selectedFiles.size();
        new AlertDialog.Builder(context)
                .setTitle("Xác nhận xóa")
                .setMessage("Bạn có chắc chắn muốn xóa vĩnh viễn " + count + " file đã chọn khỏi điện thoại?")
                .setPositiveButton("XÓA", (d, i) -> {
                    List<File> toDelete = takeSelection("xóa");
                    if (toDelete == null) return;
                    int ok = 0;
                    List<String> paths = new ArrayList<>();
                    for (File f : toDelete) {
                        if (f.delete() || !f.exists()) {
                            ok++;
                            paths.add(f.getAbsolutePath());
                            memCache.remove(f.getAbsolutePath());
                        }
                    }
                    // Cập nhật MediaStore để ảnh cũng biến mất khỏi Bộ sưu tập
                    if (!paths.isEmpty()) {
                        MediaScannerConnection.scanFile(context, paths.toArray(new String[0]), null, null);
                    }
                    reload();
                    int failed = toDelete.size() - ok;
                    Toast.makeText(context, failed == 0
                            ? "Đã xóa thành công " + ok + " file!"
                            : "Đã xóa " + ok + " file. Thất bại: " + failed + " file", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("HỦY", null)
                .show();
    }

    // =========================================================
    // NÚT TẢI VỀ: đưa ảnh vào Bộ sưu tập (DCIM/DJI_Photos)
    // Ảnh lưu dự phòng trong thư mục riêng của app không hiện trong Bộ sưu tập -> chép sang.
    // =========================================================
    public void saveSelectedToGallery() {
        List<File> list = takeSelection("tải về");
        if (list == null) return;
        Toast.makeText(context, "Đang lưu " + list.size() + " file vào Bộ sưu tập...", Toast.LENGTH_SHORT).show();
        worker.execute(() -> {
            int saved = 0, already = 0, failed = 0;
            for (File f : list) {
                if (isInGallery(f)) { already++; continue; }
                if (copyToGallery(f)) {
                    saved++;
                    f.delete(); // bỏ bản riêng để không bị trùng
                } else {
                    failed++;
                }
            }
            final int s = saved, a = already, fl = failed;
            main.post(() -> {
                reload();
                StringBuilder msg = new StringBuilder();
                if (s > 0) msg.append("Đã lưu ").append(s).append(" file vào Bộ sưu tập. ");
                if (a > 0) msg.append(a).append(" file đã có sẵn trong Bộ sưu tập. ");
                if (fl > 0) msg.append("Lỗi: ").append(fl).append(" file.");
                Toast.makeText(context, msg.toString().trim(), Toast.LENGTH_LONG).show();
            });
        });
    }

    private boolean copyToGallery(File src) {
        boolean video = isVideo(src);
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, src.getName());
        values.put(MediaStore.MediaColumns.MIME_TYPE, video ? "video/mp4" : "image/jpeg");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DCIM + (video ? "/DJI_Videos" : "/DJI_Photos"));
        Uri uri = null;
        try {
            uri = resolver.insert(video
                    ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
            if (uri == null) return false;
            try (InputStream in = new FileInputStream(src); OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) throw new Exception("no stream");
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return true;
        } catch (Exception e) {
            if (uri != null) {
                try { resolver.delete(uri, null, null); } catch (Exception ignored) {}
            }
            return false;
        }
    }

    // =========================================================
    // NÚT CLOUD: đẩy lần lượt từng ảnh
    // =========================================================
    public void uploadSelectedToCloud() {
        if (!FirebaseHelper.isConfigured(context)) {
            Toast.makeText(context, "Chưa cấu hình Cloud (CloudConfig.java)!", Toast.LENGTH_LONG).show();
            return;
        }
        List<File> list = takeSelection("đẩy lên Cloud");
        if (list == null) return;
        Toast.makeText(context, "Bắt đầu đẩy " + list.size() + " file lên Cloud...", Toast.LENGTH_SHORT).show();
        uploadAt(list, 0, 0);
    }

    private void uploadAt(List<File> list, int index, int okCount) {
        if (index >= list.size()) {
            int failed = list.size() - okCount;
            Toast.makeText(context, failed == 0
                    ? "Đã đẩy thành công " + okCount + " file lên Cloud!"
                    : "Đã đẩy " + okCount + " file. Thất bại: " + failed + " file", Toast.LENGTH_LONG).show();
            return;
        }
        File f = list.get(index);
        FirebaseHelper.uploadFileToFirebase(context, f, StorageAdapter.buildMetaInfo(f, null),
                new FirebaseHelper.UploadCallback() {
                    @Override public void onProgress(int percent, long done, long total) {}

                    @Override
                    public void onSuccess(String downloadUrl) {
                        uploadAt(list, index + 1, okCount + 1);
                    }

                    @Override
                    public void onFailure(String message) {
                        android.util.Log.d("DJI_STORAGE", "Cloud lỗi " + f.getName() + ": " + message);
                        uploadAt(list, index + 1, okCount);
                    }
                });
    }

    // =========================================================
    // HIỂN THỊ
    // =========================================================
    @Override public int getCount() { return files.size(); }
    @Override public Object getItem(int position) { return files.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView != null ? convertView
                : inflater.inflate(R.layout.item_storage, parent, false);

        View itemRoot = view.findViewById(R.id.itemRoot);
        ImageView img = view.findViewById(R.id.imgMedia);
        TextView tvType = view.findViewById(R.id.tvMediaType);
        TextView tvName = view.findViewById(R.id.tvMediaName);
        TextView tvDate = view.findViewById(R.id.tvMediaDate);
        CheckBox cb = view.findViewById(R.id.cbSelect);

        File file = files.get(position);
        boolean selected = selectedFiles.contains(file);
        if (cb != null) {
            cb.setVisibility(isSelectionMode ? View.VISIBLE : View.GONE);
            cb.setChecked(selected);
        }
        if (itemRoot != null) itemRoot.setSelected(isSelectionMode && selected); // viền xanh (bg_st_item)
        boolean video = isVideo(file);
        tvType.setText(video ? "XEM NHANH" : "ẢNH");
        tvType.setBackgroundResource(video ? R.drawable.bg_st_badge_video : R.drawable.bg_st_badge_photo);
        View playOverlay = view.findViewById(R.id.tvPlayOverlay);
        if (playOverlay != null) playOverlay.setVisibility(video ? View.VISIBLE : View.GONE);
        tvName.setText(file.getName());
        tvDate.setText(new SimpleDateFormat("dd/MM/yyyy • HH:mm", Locale.US)
                .format(new Date(file.lastModified())));

        String key = file.getAbsolutePath();
        img.setTag(key);
        Bitmap cached = memCache.get(key);
        if (cached != null) {
            img.setImageBitmap(cached);
        } else {
            img.setImageDrawable(null);
            worker.execute(() -> {
                Bitmap bmp = video ? videoThumb(file) : decodeSampled(file, 240);
                if (bmp == null) return;
                memCache.put(key, bmp);
                main.post(() -> {
                    if (key.equals(img.getTag())) img.setImageBitmap(bmp);
                });
            });
        }

        // Nhấn giữ: bật chế độ chọn nhiều. Nhấn: xem ảnh hoặc chọn/bỏ chọn.
        view.setOnLongClickListener(v -> {
            if (!isSelectionMode) {
                setSelectionMode(true);
                selectedFiles.add(file);
                notifyDataSetChanged();
                notifySelectionCount();
            } else {
                toggleSelection(file);
            }
            return true;
        });
        view.setOnClickListener(v -> {
            if (isSelectionMode) toggleSelection(file);
            else if (video) showLocalVideo(file);
            else showFullImage(file);
        });
        return view;
    }

    private static Bitmap videoThumb(File file) {
        try {
            return android.media.ThumbnailUtils.createVideoThumbnail(file, new android.util.Size(320, 240), null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Phát bản xem nhanh ngay trên điện thoại. */
    private void showLocalVideo(File file) {
        Dialog d = new Dialog(context, R.style.CfViewer);
        d.setContentView(R.layout.dialog_media_viewer);
        String subtitle = new SimpleDateFormat("dd/MM/yyyy • HH:mm", Locale.US).format(new Date(file.lastModified()))
                + String.format(Locale.US, " • %.1f MB", file.length() / 1048576.0);
        StorageAdapter.setupViewerHeader(d, file.getName(), subtitle, true);
        ((TextView) d.findViewById(R.id.viewerTypeChip)).setText("XEM NHANH");
        ((TextView) d.findViewById(R.id.viewerHint)).setText(
                "Bản xem nhanh ghi từ luồng camera • Bản gốc chất lượng cao vẫn nằm trên thẻ nhớ drone");

        d.findViewById(R.id.viewerImage).setVisibility(View.GONE);
        android.widget.VideoView vv = d.findViewById(R.id.viewerVideo);
        vv.setVisibility(View.VISIBLE);
        android.widget.MediaController mc = new android.widget.MediaController(context);
        mc.setAnchorView(vv);
        vv.setMediaController(mc);
        vv.setVideoPath(file.getAbsolutePath());
        vv.setOnPreparedListener(mp -> vv.start());
        vv.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(context, "Không phát được video này!", Toast.LENGTH_SHORT).show();
            return true;
        });

        android.widget.Button btnSave = d.findViewById(R.id.viewerBtnDownload);
        if (isInGallery(file)) {
            btnSave.setVisibility(View.GONE);
        } else {
            btnSave.setText("Lưu vào máy");
            btnSave.setOnClickListener(v -> {
                btnSave.setEnabled(false);
                worker.execute(() -> {
                    boolean ok = copyToGallery(file);
                    main.post(() -> {
                        btnSave.setEnabled(!ok);
                        btnSave.setText(ok ? "Đã lưu ✓" : "Lưu vào máy");
                        Toast.makeText(context, ok ? "Đã lưu vào DCIM/DJI_Videos!" : "Lỗi lưu video!",
                                Toast.LENGTH_SHORT).show();
                    });
                });
            });
        }
        setupCloudButton(d.findViewById(R.id.viewerBtnCloud), file);
        d.setOnDismissListener(x -> {
            try { vv.stopPlayback(); } catch (Exception ignored) {}
        });
        d.show();
    }

    private void setupCloudButton(android.widget.Button btnCloud, File file) {
        btnCloud.setOnClickListener(v -> {
            btnCloud.setEnabled(false);
            btnCloud.setText("Đang đẩy...");
            FirebaseHelper.uploadFileToFirebase(context, file, StorageAdapter.buildMetaInfo(file, null),
                    new FirebaseHelper.UploadCallback() {
                        @Override public void onProgress(int percent, long done, long total) {
                            btnCloud.setText("Cloud " + percent + "%");
                        }
                        @Override public void onSuccess(String url) {
                            btnCloud.setEnabled(true);
                            btnCloud.setText("Đã lên Cloud ✓");
                        }
                        @Override public void onFailure(String message) {
                            btnCloud.setEnabled(true);
                            btnCloud.setText("Lên Cloud");
                            Toast.makeText(context, "Lỗi Cloud: " + message, Toast.LENGTH_LONG).show();
                        }
                    });
        });
    }

    private static Bitmap decodeSampled(File file, int reqSize) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= reqSize && o.outHeight / (sample * 2) >= reqSize) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), o);
        } catch (Exception e) {
            return null;
        }
    }

    private void showFullImage(File file) {
        Dialog d = new Dialog(context, R.style.CfViewer);
        d.setContentView(R.layout.dialog_media_viewer);
        String subtitle = new SimpleDateFormat("dd/MM/yyyy • HH:mm", Locale.US).format(new Date(file.lastModified()))
                + String.format(Locale.US, " • %.1f MB", file.length() / 1048576.0);
        StorageAdapter.setupViewerHeader(d, file.getName(), subtitle, false);
        ((TextView) d.findViewById(R.id.viewerHint)).setText("Ảnh đã lưu trên điện thoại");

        // Ảnh đã nằm trên máy -> không cần nút Tải về; giữ nút đẩy lên Cloud
        d.findViewById(R.id.viewerBtnDownload).setVisibility(View.GONE);
        setupCloudButton(d.findViewById(R.id.viewerBtnCloud), file);

        View loading = d.findViewById(R.id.viewerLoading);
        loading.setVisibility(View.VISIBLE);
        ((TextView) d.findViewById(R.id.viewerLoadingTitle)).setText("Đang mở ảnh...");
        ImageView iv = d.findViewById(R.id.viewerImage);
        d.show();
        worker.execute(() -> {
            Bitmap bmp = decodeSampled(file, 1600);
            main.post(() -> {
                loading.setVisibility(View.GONE);
                if (bmp != null) iv.setImageBitmap(bmp);
                else Toast.makeText(context, "Không mở được ảnh!", Toast.LENGTH_SHORT).show();
            });
        });
    }
}
