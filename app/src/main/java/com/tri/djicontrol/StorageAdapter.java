package com.tri.djicontrol;

import com.tri.djicontrol.ui.AppToast;

import android.app.Dialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import androidx.core.content.FileProvider;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.ExifInterface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import android.util.LruCache;

import dji.common.error.DJIError;
import dji.sdk.base.BaseProduct;
import dji.sdk.media.DownloadListener;
import dji.sdk.media.FetchMediaTask;
import dji.sdk.media.FetchMediaTaskContent;
import dji.sdk.media.FetchMediaTaskScheduler;
import dji.sdk.media.MediaFile;
import dji.sdk.media.MediaManager;
import dji.sdk.products.Aircraft;
import dji.sdk.sdkmanager.DJISDKManager;
import com.tri.djicontrol.firebase.FirebaseHelper;

public class StorageAdapter extends BaseAdapter {

    public interface OnSelectionListener {
        void onSelectionChanged(int selectedCount);
        void onSelectionModeChanged(boolean isSelectionMode);
    }

    private final Context context;
    private final List<MediaFile> mediaFiles;
    private final LayoutInflater inflater;

    private boolean isSelectionMode = false;
    private final Set<MediaFile> selectedFiles = new HashSet<>();
    private OnSelectionListener selectionListener;

    // Cache thumbnail trên RAM để cuộn lưới không phải decode lại file
    private final LruCache<String, Bitmap> memCache = new LruCache<String, Bitmap>(
            (int) (Runtime.getRuntime().maxMemory() / 1024 / 8)) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount() / 1024;
        }
    };
    // Tránh xếp hàng trùng 1 file nhiều lần khi GridView tái sử dụng view
    private final Set<String> pendingThumbs = new HashSet<>();

    public StorageAdapter(Context context, List<MediaFile> mediaFiles) {
        this.context = context;
        this.mediaFiles = mediaFiles;
        this.inflater = LayoutInflater.from(context);
    }

    // =========================================================
    // TIỆN ÍCH: SCHEDULER CỦA DJI (tải thumbnail/preview tuần tự)
    // SDK DJI không cho gọi fetchThumbnail/fetchPreview song song -> báo bận, ảnh trống.
    // =========================================================
    public static FetchMediaTaskScheduler getScheduler() {
        try {
            BaseProduct product = DJISDKManager.getInstance().getProduct();
            if (product instanceof Aircraft && ((Aircraft) product).getCamera() != null) {
                MediaManager mm = ((Aircraft) product).getCamera().getMediaManager();
                if (mm != null) return mm.getScheduler();
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static void resumeScheduler() {
        FetchMediaTaskScheduler scheduler = getScheduler();
        if (scheduler != null) {
            try { scheduler.resume(null); } catch (Exception ignored) {}
        }
    }

    /** Tạm dừng scheduler trước khi tải file gốc (fetchFileData), sau đó chạy then. */
    public static void suspendScheduler(Runnable then) {
        FetchMediaTaskScheduler scheduler = getScheduler();
        Handler main = new Handler(Looper.getMainLooper());
        if (scheduler == null) {
            main.post(then);
            return;
        }
        try {
            scheduler.suspend(error -> main.postDelayed(then, 200));
        } catch (Exception e) {
            main.post(then);
        }
    }

    // =========================================================
    // TIỆN ÍCH: TÌM FILE THỰC SỰ SAU KHI fetchFileData
    // SDK tự thêm phần mở rộng vào tên file và onSuccess có thể trả về thư mục,
    // nên không được giả định đường dẫn = destDir/fileName.
    // =========================================================
    public static String baseName(String fileName) {
        if (fileName == null) return null;
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    public static File resolveDownloadedFile(File dir, String fileName, String returnedPath) {
        if (returnedPath != null && !returnedPath.isEmpty()) {
            File f = new File(returnedPath);
            if (f.isFile() && f.length() > 0) return f;
        }
        if (dir == null || fileName == null) return null;
        File exact = new File(dir, fileName);
        if (exact.isFile() && exact.length() > 0) return exact;

        String base = baseName(fileName).toLowerCase(Locale.US);
        File best = null;
        File[] list = dir.listFiles();
        if (list != null) {
            for (File f : list) {
                String n = f.getName().toLowerCase(Locale.US);
                if (f.isFile() && f.length() > 0 && (n.equals(base) || n.startsWith(base + "."))) {
                    if (best == null || f.lastModified() > best.lastModified()) best = f;
                }
            }
        }
        return best;
    }

    private static String thumbKey(MediaFile f) {
        return f.getFileName() + "_" + f.getTimeCreated();
    }

    public void setOnSelectionListener(OnSelectionListener listener) {
        this.selectionListener = listener;
    }

    public boolean isSelectionMode() {
        return isSelectionMode;
    }

    public void setSelectionMode(boolean selectionMode) {
        if (this.isSelectionMode != selectionMode) {
            this.isSelectionMode = selectionMode;
            if (!selectionMode) {
                selectedFiles.clear();
            }
            notifyDataSetChanged();
            if (selectionListener != null) {
                selectionListener.onSelectionModeChanged(selectionMode);
                selectionListener.onSelectionChanged(selectedFiles.size());
            }
        }
    }

    public Set<MediaFile> getSelectedFiles() {
        return selectedFiles;
    }

    public void clearSelection() {
        selectedFiles.clear();
        notifyDataSetChanged();
        if (selectionListener != null) {
            selectionListener.onSelectionChanged(0);
        }
    }

    private void toggleSelection(MediaFile mediaFile) {
        if (selectedFiles.contains(mediaFile)) {
            selectedFiles.remove(mediaFile);
        } else {
            selectedFiles.add(mediaFile);
        }
        notifyDataSetChanged();
        if (selectionListener != null) {
            selectionListener.onSelectionChanged(selectedFiles.size());
        }
    }

    @Override public int getCount() { return mediaFiles.size(); }
    @Override public Object getItem(int position) { return mediaFiles.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        if (view == null) {
            view = inflater.inflate(R.layout.item_storage, parent, false);
        }

        View itemRoot = view.findViewById(R.id.itemRoot);
        ImageView imgMedia = view.findViewById(R.id.imgMedia);
        TextView tvMediaType = view.findViewById(R.id.tvMediaType);
        TextView tvMediaName = view.findViewById(R.id.tvMediaName);
        TextView tvMediaDate = view.findViewById(R.id.tvMediaDate);
        CheckBox cbSelect = view.findViewById(R.id.cbSelect);

        MediaFile mediaFile = mediaFiles.get(position);
        tvMediaName.setText(mediaFile.getFileName());

        MediaFile.MediaType type = mediaFile.getMediaType();
        boolean isVideo = (type == MediaFile.MediaType.MOV || type == MediaFile.MediaType.MP4);
        tvMediaType.setText(isVideo ? "VIDEO" : "ẢNH");
        tvMediaType.setBackgroundResource(isVideo ? R.drawable.bg_st_badge_video : R.drawable.bg_st_badge_photo);
        View playOverlay = view.findViewById(R.id.tvPlayOverlay);
        if (playOverlay != null) playOverlay.setVisibility(isVideo ? View.VISIBLE : View.GONE);

        String dateCreated = mediaFile.getDateCreated();
        if (dateCreated != null && !dateCreated.isEmpty()) {
            try {
                SimpleDateFormat inputFormat = new SimpleDateFormat("yyyy-MM-dd kk:mm:ss", Locale.US);
                SimpleDateFormat outputFormat = new SimpleDateFormat("dd/MM/yyyy • HH:mm", Locale.US);
                Date date = inputFormat.parse(dateCreated);
                tvMediaDate.setText(date != null ? outputFormat.format(date) : dateCreated);
            } catch (Exception e) {
                tvMediaDate.setText(dateCreated);
            }
        } else {
            tvMediaDate.setText("Không có thời gian");
        }

        boolean isSelected = selectedFiles.contains(mediaFile);

        if (isSelectionMode) {
            if (cbSelect != null) {
                cbSelect.setVisibility(View.VISIBLE);
                cbSelect.setChecked(isSelected);
            }
            if (itemRoot != null) itemRoot.setSelected(isSelected); // viền xanh (bg_st_item)
        } else {
            if (cbSelect != null) {
                cbSelect.setVisibility(View.GONE);
            }
            if (itemRoot != null) itemRoot.setSelected(false);
        }

        imgMedia.setImageDrawable(null); // nền tối của khung thumbnail hiện trong lúc chờ tải
        imgMedia.setTag(mediaFile);

        // Nạp Thumbnail (1. RAM -> 2. Disk Cache cục bộ -> 3. Xếp hàng tải từ Drone)
        String key = thumbKey(mediaFile);
        Bitmap thumbnail = memCache.get(key);
        if (thumbnail == null) thumbnail = mediaFile.getThumbnail();
        if (thumbnail != null) {
            memCache.put(key, thumbnail);
            imgMedia.setImageBitmap(thumbnail);
        } else {
            File cacheDir = context.getExternalCacheDir() != null ? context.getExternalCacheDir() : context.getCacheDir();
            File thumbCacheFile = new File(cacheDir, "thumb_" + mediaFile.getFileName() + "_" + mediaFile.getTimeCreated() + ".jpg");
            Bitmap cachedBitmap = null;
            if (thumbCacheFile.exists() && thumbCacheFile.length() > 0) {
                cachedBitmap = BitmapFactory.decodeFile(thumbCacheFile.getAbsolutePath());
            }
            if (cachedBitmap != null) {
                memCache.put(key, cachedBitmap);
                imgMedia.setImageBitmap(cachedBitmap);
            } else {
                fetchThumbnailFromDrone(mediaFile, thumbCacheFile, imgMedia);
            }
        }

        // Nhấn giữ lâu: Kích hoạt chế độ chọn nhiều
        view.setOnLongClickListener(v -> {
            if (!isSelectionMode) {
                setSelectionMode(true);
                selectedFiles.add(mediaFile);
                notifyDataSetChanged();
                if (selectionListener != null) {
                    selectionListener.onSelectionChanged(selectedFiles.size());
                }
            } else {
                toggleSelection(mediaFile);
            }
            return true;
        });

        // Bấm chọn Item: Mở xem hoặc Toggle chọn khi ở Selection Mode
        view.setOnClickListener(v -> {
            if (isSelectionMode) {
                toggleSelection(mediaFile);
            } else {
                if (isVideo) {
                    showVideoDialog(context, mediaFile);
                } else {
                    showImageDialog(context, mediaFile);
                }
            }
        });

        return view;
    }

    private void fetchThumbnailFromDrone(MediaFile mediaFile, File thumbCacheFile, ImageView imgMedia) {
        String key = thumbKey(mediaFile);
        synchronized (pendingThumbs) {
            if (!pendingThumbs.add(key)) return; // đã xếp hàng, chờ callback
        }

        Runnable onDone = () -> {
            synchronized (pendingThumbs) {
                pendingThumbs.remove(key);
            }
            Bitmap bitmap = mediaFile.getThumbnail();
            if (bitmap == null) return;
            memCache.put(key, bitmap);
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(thumbCacheFile)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out);
            } catch (Exception ignored) {}

            // Tag của ImageView đổi khi view bị tái sử dụng -> chỉ set nếu vẫn đúng file
            imgMedia.post(() -> {
                if (imgMedia.getTag() == mediaFile) {
                    imgMedia.setImageBitmap(bitmap);
                }
            });
        };

        FetchMediaTaskScheduler scheduler = getScheduler();
        if (scheduler != null) {
            scheduler.resume(null);
            scheduler.moveTaskToEnd(new FetchMediaTask(mediaFile, FetchMediaTaskContent.THUMBNAIL,
                    (file, option, error) -> {
                        if (error != null) {
                            synchronized (pendingThumbs) { pendingThumbs.remove(key); }
                            return;
                        }
                        onDone.run();
                    }));
        } else {
            mediaFile.fetchThumbnail(error -> {
                if (error != null) {
                    synchronized (pendingThumbs) { pendingThumbs.remove(key); }
                    return;
                }
                onDone.run();
            });
        }
    }

    // =========================================================
    // 1. HÀM XEM ẢNH TỐC ĐỘ CAO (DÙNG PREVIEW IMAGE / THUMBNAIL FALLBACK)
    // =========================================================
    private void showImageDialog(Context context, MediaFile mediaFile) {
        Dialog dialog = new Dialog(context, R.style.CfViewer);
        dialog.setContentView(R.layout.dialog_media_viewer);
        setupViewerHeader(dialog, mediaFile.getFileName(), formatMediaSubtitle(mediaFile), false);
        ((TextView) dialog.findViewById(R.id.viewerHint))
                .setText("Chụm 2 ngón để phóng to • Tải về máy để có ảnh gốc độ phân giải đầy đủ");

        // Lớp chờ: chỉ hiện vòng xoay trên nền trong suốt để vẫn thấy ảnh thu nhỏ phía sau
        View progressBar = dialog.findViewById(R.id.viewerLoading);
        progressBar.setBackgroundColor(Color.TRANSPARENT);
        progressBar.setVisibility(View.VISIBLE);
        ((TextView) dialog.findViewById(R.id.viewerLoadingTitle)).setText("Đang tải ảnh xem trước...");

        ImageView imageView = dialog.findViewById(R.id.viewerImage);

        // Hiện Thumbnail tạm thời trong khi chờ
        if (mediaFile.getThumbnail() != null) {
            imageView.setImageBitmap(mediaFile.getThumbnail());
        }

        // Tải ảnh Preview (ưu tiên lên đầu hàng đợi của scheduler, không chạy song song với thumbnail)
        Handler main = new Handler(Looper.getMainLooper());
        Runnable showThumbFallback = () -> main.post(() -> {
            progressBar.setVisibility(View.GONE);
            Bitmap thumb = memCache.get(thumbKey(mediaFile));
            if (thumb == null) thumb = mediaFile.getThumbnail();
            if (thumb != null) {
                imageView.setImageBitmap(thumb);
            } else {
                AppToast.makeText(context, "Ảnh lỗi hoặc không thể xem trước!", Toast.LENGTH_SHORT).show();
            }
        });

        FetchMediaTaskScheduler scheduler = getScheduler();
        if (scheduler != null) {
            scheduler.resume(null);
            scheduler.moveTaskToNext(new FetchMediaTask(mediaFile, FetchMediaTaskContent.PREVIEW,
                    (file, option, error) -> {
                        if (error == null && mediaFile.getPreview() != null) {
                            main.post(() -> {
                                progressBar.setVisibility(View.GONE);
                                imageView.setImageBitmap(mediaFile.getPreview());
                            });
                        } else {
                            // Camera không hỗ trợ Preview -> lấy Thumbnail
                            scheduler.moveTaskToNext(new FetchMediaTask(mediaFile, FetchMediaTaskContent.THUMBNAIL,
                                    (f2, o2, e2) -> showThumbFallback.run()));
                        }
                    }));
        } else {
            mediaFile.fetchPreview(error -> {
                if (error == null && mediaFile.getPreview() != null) {
                    main.post(() -> {
                        progressBar.setVisibility(View.GONE);
                        imageView.setImageBitmap(mediaFile.getPreview());
                    });
                } else {
                    mediaFile.fetchThumbnail(thumbError -> showThumbFallback.run());
                }
            });
        }

        Button btnDownload = dialog.findViewById(R.id.viewerBtnDownload);
        Button btnFirebase = dialog.findViewById(R.id.viewerBtnCloud);

        // Lưu file thực tế đã tải về (để nút Firebase dùng đúng đường dẫn)
        final File[] downloadedFile = {null};

        btnDownload.setOnClickListener(v -> {
            // 1. Tạo thư mục công khai DCIM/DJI_Photos để lưu vào Bộ sưu tập điện thoại
            File downloadDir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    "DJI_Photos"
            );
            if (!downloadDir.exists()) {
                boolean created = downloadDir.mkdirs();
                if (!created && !downloadDir.exists()) {
                    downloadDir = new File(context.getExternalFilesDir(null), "DJI_Downloads");
                    if (!downloadDir.exists()) {
                        downloadDir.mkdirs();
                    }
                }
            }

            final File finalDownloadDir = downloadDir;
            String fileName = mediaFile.getFileName();

            // Đã tải trước đó rồi thì không tải lại
            File existing = resolveDownloadedFile(finalDownloadDir, fileName, null);
            if (existing != null && (mediaFile.getFileSize() <= 0 || existing.length() >= mediaFile.getFileSize())) {
                downloadedFile[0] = existing;
                processAndShowMetadata(context, existing.getAbsolutePath(), false);
                return;
            }

            AppToast.makeText(context, "Đang tải ảnh gốc về máy...", Toast.LENGTH_SHORT).show();
            btnDownload.setEnabled(false);

            // 2. Dừng hàng đợi thumbnail rồi mới tải file gốc (tránh lỗi drone "bận")
            suspendScheduler(() -> mediaFile.fetchFileData(
                    finalDownloadDir,
                    baseName(fileName), // SDK tự thêm phần mở rộng
                    new DownloadListener<String>() {
                        @Override public void onStart() {}
                        @Override public void onRateUpdate(long total, long current, long persize) {}
                        @Override public void onProgress(long total, long current) {}

                        @Override
                        public void onSuccess(String filePath) {
                            resumeScheduler();
                            File realFile = resolveDownloadedFile(finalDownloadDir, fileName, filePath);
                            main.post(() -> {
                                btnDownload.setEnabled(true);
                                if (realFile == null) {
                                    AppToast.makeText(context, "Tải xong nhưng không tìm thấy file trong " + finalDownloadDir.getAbsolutePath(), Toast.LENGTH_LONG).show();
                                    return;
                                }
                                downloadedFile[0] = realFile;
                                // Ghi nhận vào Bộ sưu tập + đọc EXIF
                                processAndShowMetadata(context, realFile.getAbsolutePath(), false);
                            });
                        }

                        @Override
                        public void onFailure(DJIError djiError) {
                            resumeScheduler();
                            main.post(() -> {
                                btnDownload.setEnabled(true);
                                AppToast.makeText(context, "Lỗi tải file: " + (djiError != null ? djiError.getDescription() : "Thất bại"), Toast.LENGTH_SHORT).show();
                            });
                        }

                        @Override public void onRealtimeDataUpdate(byte[] bytes, long l, boolean b) {}
                    }
            ));
        });

        // Đẩy thẳng lên Firebase: nếu chưa tải về máy thì tự tải từ drone trước
        btnFirebase.setOnClickListener(v -> startFirebaseUpload(context, mediaFile, btnFirebase, "Lên Cloud"));

        dialog.show();
    }

    /** Thanh trên của trình xem: nút đóng, tên file, dòng phụ và nhãn ẢNH/VIDEO. */
    static void setupViewerHeader(Dialog dialog, String title, String subtitle, boolean video) {
        ((TextView) dialog.findViewById(R.id.viewerTitle)).setText(title);
        ((TextView) dialog.findViewById(R.id.viewerSubtitle)).setText(subtitle);
        TextView chip = dialog.findViewById(R.id.viewerTypeChip);
        chip.setText(video ? "VIDEO" : "ẢNH");
        chip.setBackgroundResource(video ? R.drawable.bg_st_badge_video : R.drawable.bg_st_badge_photo);
        dialog.findViewById(R.id.viewerClose).setOnClickListener(v -> dialog.dismiss());
    }

    /** "22/11/2026 • 10:15 • 5.2 MB" (+ thời lượng nếu là video). */
    private static String formatMediaSubtitle(MediaFile mediaFile) {
        StringBuilder sb = new StringBuilder();
        String created = mediaFile.getDateCreated();
        if (created != null && !created.isEmpty()) {
            try {
                Date d = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(created);
                sb.append(d != null ? new SimpleDateFormat("dd/MM/yyyy • HH:mm", Locale.US).format(d) : created);
            } catch (Exception e) {
                sb.append(created);
            }
        }
        long size = mediaFile.getFileSize();
        if (size > 0) {
            if (sb.length() > 0) sb.append(" • ");
            sb.append(String.format(Locale.US, "%.1f MB", size / 1048576.0));
        }
        MediaFile.MediaType type = mediaFile.getMediaType();
        if (type == MediaFile.MediaType.MOV || type == MediaFile.MediaType.MP4) {
            int sec = Math.round(mediaFile.getDurationInSeconds());
            if (sec > 0) {
                if (sb.length() > 0) sb.append(" • ");
                sb.append(String.format(Locale.US, "%d:%02d", sec / 60, sec % 60));
            }
        }
        return sb.toString();
    }

    // =========================================================
    // HÀM PHÁT VIDEO TRỰC TIẾP VỚI BỘ ĐẾM TIẾN TRÌNH & PROGRESS BAR UI
    // =========================================================
    private void showVideoDialog(Context context, MediaFile mediaFile) {
        Dialog dialog = new Dialog(context, R.style.CfViewer);
        dialog.setContentView(R.layout.dialog_media_viewer);
        setupViewerHeader(dialog, mediaFile.getFileName(), formatMediaSubtitle(mediaFile), true);
        ((TextView) dialog.findViewById(R.id.viewerHint))
                .setText("Video được nạp từ thẻ nhớ drone về máy rồi mới phát");

        dialog.findViewById(R.id.viewerImage).setVisibility(View.GONE);

        // 1. VideoView (hiện ngay từ đầu để SurfaceHolder khởi tạo sẵn)
        VideoView videoView = dialog.findViewById(R.id.viewerVideo);
        videoView.setVisibility(View.VISIBLE);
        MediaController mediaController = new MediaController(context);
        mediaController.setAnchorView(videoView);
        videoView.setMediaController(mediaController);

        // 2. Lớp chờ tải đè lên VideoView: thanh tiến trình + dung lượng + tốc độ
        LinearLayout loadingLayout = dialog.findViewById(R.id.viewerLoading);
        loadingLayout.setVisibility(View.VISIBLE);
        dialog.findViewById(R.id.viewerSpinner).setVisibility(View.GONE);
        TextView tvTitle = dialog.findViewById(R.id.viewerLoadingTitle);
        tvTitle.setText("Đang nạp video từ drone");
        ProgressBar progressBar = dialog.findViewById(R.id.viewerProgress);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        TextView tvProgressText = dialog.findViewById(R.id.viewerProgressText);
        tvProgressText.setText("0.0 MB / 0.0 MB (0%)");
        Button btnRetry = dialog.findViewById(R.id.viewerRetry);

        // 3. Thanh nút dưới
        Button btnDownload = dialog.findViewById(R.id.viewerBtnDownload);
        Button btnExternal = dialog.findViewById(R.id.viewerBtnExternal);
        btnExternal.setVisibility(View.VISIBLE);
        Button btnVideoFirebase = dialog.findViewById(R.id.viewerBtnCloud);

        File destDir = new File(context.getExternalFilesDir(null), "DJI_Video_Cache");
        if (!destDir.exists()) destDir.mkdirs();

        File publicDir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                "DJI_Videos"
        );
        if (!publicDir.exists()) publicDir.mkdirs();

        File localVideoFile = new File(destDir, mediaFile.getFileName());

        long expectedSize = mediaFile.getFileSize();

        // Kiểm tra xem file đã có trong bộ sưu tập công khai DCIM hoặc bộ nhớ cache chưa
        // (tên file thực tế có thể khác hoa/thường phần mở rộng -> dùng resolveDownloadedFile)
        File publicFound = resolveDownloadedFile(publicDir, mediaFile.getFileName(), null);
        File cacheFound = resolveDownloadedFile(destDir, mediaFile.getFileName(), null);

        File readyFile = null;
        if (isCompleteVideo(publicFound, expectedSize)) {
            readyFile = publicFound;
        } else if (isCompleteVideo(cacheFound, expectedSize)) {
            readyFile = cacheFound;
        } else if (cacheFound != null) {
            // Xóa file dở dang trong cache (không đụng vào file của người dùng trong DCIM)
            cacheFound.delete();
        }

        boolean isCached = (readyFile != null);
        java.util.concurrent.atomic.AtomicReference<String> playFilePath = new java.util.concurrent.atomic.AtomicReference<>(
                isCached ? readyFile.getAbsolutePath() : localVideoFile.getAbsolutePath()
        );

        final boolean[] isDownloading = {!isCached};

        Runnable playVideoAction = () -> {
            new Handler(Looper.getMainLooper()).post(() -> {
                loadingLayout.setVisibility(View.GONE);
                String targetPath = playFilePath.get();
                File targetFile = new File(targetPath);

                videoView.post(() -> {
                    try {
                        videoView.setVideoPath(targetPath);
                        videoView.setOnPreparedListener(mp -> {
                            mp.setLooping(true);
                            videoView.start();
                        });
                        videoView.setOnErrorListener((mp, what, extra) -> {
                            AppToast.makeText(context, "Chuyển sang Trình phát Video điện thoại...", Toast.LENGTH_SHORT).show();
                            openSystemVideoPlayer(context, targetFile);
                            return true;
                        });
                    } catch (Exception e) {
                        openSystemVideoPlayer(context, targetFile);
                    }
                });
            });
        };

        Runnable copyToGalleryAction = () -> {
            File downloadDest = new File(publicDir, mediaFile.getFileName());
            File currentFile = new File(playFilePath.get());

            if (currentFile.exists() && currentFile.length() > 0) {
                if (currentFile.getAbsolutePath().equals(downloadDest.getAbsolutePath())) {
                    AppToast.makeText(context, "Video đã có sẵn trong DCIM/DJI_Videos!", Toast.LENGTH_SHORT).show();
                    return;
                }
                AppToast.makeText(context, "Đang lưu video vào DCIM/DJI_Videos...", Toast.LENGTH_SHORT).show();
                try (InputStream in = new FileInputStream(currentFile);
                     OutputStream out = new java.io.FileOutputStream(downloadDest)) {
                    byte[] buffer = new byte[16384];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    out.flush();
                    MediaScannerConnection.scanFile(context, new String[]{downloadDest.getAbsolutePath()}, new String[]{"video/*"}, null);
                    AppToast.makeText(context, "Đã lưu video vào DCIM/DJI_Videos!", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    AppToast.makeText(context, "Lỗi lưu video: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            } else {
                AppToast.makeText(context, "File video chưa nạp xong, vui lòng đợi!", Toast.LENGTH_SHORT).show();
            }
        };

        btnDownload.setOnClickListener(v -> copyToGalleryAction.run());

        btnVideoFirebase.setOnClickListener(v -> {
            if (isDownloading[0]) {
                AppToast.makeText(context, "Vui lòng đợi nạp xong video từ drone!", Toast.LENGTH_SHORT).show();
                return;
            }
            startFirebaseUpload(context, mediaFile, btnVideoFirebase, "Lên Cloud");
        });

        btnExternal.setOnClickListener(v -> {
            File currentFile = new File(playFilePath.get());
            if (currentFile.exists() && currentFile.length() > 0) {
                openSystemVideoPlayer(context, currentFile);
            } else {
                AppToast.makeText(context, "Vui lòng đợi nạp xong video từ drone!", Toast.LENGTH_SHORT).show();
            }
        });

        Runnable startDownloadProcess = new Runnable() {
            @Override
            public void run() {
                btnRetry.setVisibility(View.GONE);
                tvProgressText.setText("Bắt đầu nạp video từ drone...");
                isDownloading[0] = true;

                // Tải vào destDir (app-internal external storage) để không bị chặn bởi Scoped Storage.
                // Dừng scheduler thumbnail trước, nếu không drone sẽ báo bận và video không tải được.
                suspendScheduler(() -> mediaFile.fetchFileData(destDir, baseName(mediaFile.getFileName()), new DownloadListener<String>() {
                    private long lastTime = System.currentTimeMillis();
                    private long lastBytes = 0;

                    @Override public void onStart() {
                        new Handler(Looper.getMainLooper()).post(() -> tvProgressText.setText("Đang kết nối truyền dữ liệu video..."));
                    }
                    @Override public void onRateUpdate(long total, long current, long persize) {}

                    @Override
                    public void onProgress(long total, long current) {
                        long currentTime = System.currentTimeMillis();
                        long timeDiff = currentTime - lastTime;
                        double speedKBps = 0;
                        if (timeDiff >= 400) {
                            speedKBps = ((current - lastBytes) / 1024.0) / (timeDiff / 1000.0);
                            lastTime = currentTime;
                            lastBytes = current;
                        }

                        long totalBytes = expectedSize > 0 ? expectedSize : total;
                        int percent = totalBytes > 0 ? (int) (((double) current / totalBytes) * 100) : 0;
                        double currMB = current / (1024.0 * 1024.0);
                        double totalMB = totalBytes / (1024.0 * 1024.0);

                        String speedStr = speedKBps >= 1024 ? String.format(Locale.US, "%.2f MB/s", speedKBps / 1024.0) : String.format(Locale.US, "%.0f KB/s", speedKBps);

                        new Handler(Looper.getMainLooper()).post(() -> {
                            progressBar.setProgress(percent);
                            tvProgressText.setText(String.format(Locale.US, "%.1f MB / %.1f MB (%d%%) • %s", currMB, totalMB, percent, speedStr));
                        });
                    }

                    @Override
                    public void onSuccess(String filePath) {
                        isDownloading[0] = false;
                        resumeScheduler();
                        // filePath có thể là thư mục -> tìm đúng file video vừa tải
                        File realFile = resolveDownloadedFile(destDir, mediaFile.getFileName(), filePath);
                        if (realFile == null) {
                            new Handler(Looper.getMainLooper()).post(() -> {
                                tvProgressText.setText("Tải xong nhưng không tìm thấy file video!");
                                btnRetry.setVisibility(View.VISIBLE);
                            });
                            return;
                        }
                        playFilePath.set(realFile.getAbsolutePath());
                        playVideoAction.run();
                    }

                    @Override
                    public void onFailure(DJIError error) {
                        isDownloading[0] = false;
                        resumeScheduler();
                        new Handler(Looper.getMainLooper()).post(() -> {
                            tvProgressText.setText("Lỗi nạp video: " + (error != null ? error.getDescription() : "Thất bại"));
                            btnRetry.setVisibility(View.VISIBLE);
                        });
                    }

                    @Override public void onRealtimeDataUpdate(byte[] bytes, long l, boolean b) {}
                }));
            }
        };

        btnRetry.setOnClickListener(v -> {
            try {
                mediaFile.stopFetchingFileData(null);
            } catch (Exception ignored) {}
            new Handler(Looper.getMainLooper()).postDelayed(startDownloadProcess, 300);
        });

        if (isCached) {
            playVideoAction.run();
        } else {
            // Cho 300ms delay để giải phóng bất kỳ task thumbnail nào đang chạy dở trước khi bắt đầu nạp video
            new Handler(Looper.getMainLooper()).postDelayed(startDownloadProcess, 300);
        }

        dialog.setOnDismissListener(d -> {
            try { videoView.stopPlayback(); } catch (Exception ignored) {}
            if (isDownloading[0]) {
                try {
                    mediaFile.stopFetchingFileData(null);
                } catch (Exception ignored) {}
                isDownloading[0] = false;
            }
            resumeScheduler();
        });

        dialog.show();
    }

    /** Gắn tiến trình upload vào chữ trên nút, khoá nút trong lúc chạy. */
    private void startFirebaseUpload(Context context, MediaFile mediaFile, Button button, String idleText) {
        button.setEnabled(false);
        button.setText("ĐANG CHUẨN BỊ...");
        uploadMediaToFirebase(context, mediaFile, new FirebaseUploadListener() {
            @Override
            public void onStatus(String status) {
                // Nút nhỏ: chỉ hiện phần trăm
                String shortText = status.startsWith("Tải từ drone") ? "TẢI TỪ DRONE" : "CLOUD";
                String pct = status.replaceAll(".*?(\\d+%).*", "$1");
                button.setText(pct.endsWith("%") ? shortText + " " + pct : shortText + "...");
            }

            @Override
            public void onDone(boolean success, String message) {
                button.setEnabled(true);
                button.setText(success ? "ĐÃ LÊN CLOUD ✓" : idleText);
                AppToast.makeText(context, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private static boolean isCompleteVideo(File f, long expectedSize) {
        if (f == null || !f.isFile() || f.length() <= 0) return false;
        return expectedSize > 0 ? f.length() >= expectedSize : f.length() > 2 * 1024 * 1024;
    }

    // Mở bằng Trình phát Video ngoài của hệ điều hành điện thoại
    private void openSystemVideoPlayer(Context context, File videoFile) {
        if (videoFile == null || !videoFile.exists()) {
            AppToast.makeText(context, "File video chưa sẵn sàng!", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri uri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    videoFile
            );

            String name = videoFile.getName().toLowerCase(Locale.US);
            String mimeType = "video/*";
            if (name.endsWith(".mov")) {
                mimeType = "video/quicktime";
            } else if (name.endsWith(".mp4")) {
                mimeType = "video/mp4";
            }

            intent.setDataAndType(uri, mimeType);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            try {
                List<ResolveInfo> resInfoList = context.getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
                for (ResolveInfo resolveInfo : resInfoList) {
                    String packageName = resolveInfo.activityInfo.packageName;
                    context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
            } catch (Exception ignored) {}

            context.startActivity(Intent.createChooser(intent, "Mở video bằng..."));
        } catch (Exception e) {
            AppToast.makeText(context, "Lỗi mở trình phát video: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    private void processAndShowMetadata(Context context, String filePath, boolean forFirebase) {
        try {
            File sourceFile = new File(filePath);
            if (!sourceFile.exists()) return;

            ExifInterface exif = new ExifInterface(filePath);
            String dateTime = exif.getAttribute(ExifInterface.TAG_DATETIME);
            String displayTime = "Không xác định";
            if (dateTime != null) {
                try {
                    SimpleDateFormat exifFormat = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US);
                    SimpleDateFormat newFormat = new SimpleDateFormat("EEEE, 'ngày' dd/MM/yyyy 'lúc' HH:mm:ss", new Locale("vi", "VN"));
                    Date d = exifFormat.parse(dateTime);
                    if (d != null) displayTime = newFormat.format(d);
                } catch (Exception ignored) {}
            }

            float[] latLong = new float[2];
            boolean hasGps = exif.getLatLong(latLong);
            String gpsInfo = hasGps ? String.format(Locale.US, "Vĩ độ: %.6f\nKinh độ: %.6f", latLong[0], latLong[1]) : "Chưa lưu GPS";
            String metaInfo = "Thời gian chụp: " + displayTime + "\n" + gpsInfo;

            if (!forFirebase) {
                // Đăng ký file với MediaScanner của Android để Album điện thoại nhận diện ngay
                String lower = filePath.toLowerCase(Locale.US);
                boolean isVideo = lower.endsWith(".mp4") || lower.endsWith(".mov");
                MediaScannerConnection.scanFile(
                        context,
                        new String[]{sourceFile.getAbsolutePath()},
                        new String[]{isVideo ? "video/*" : "image/*"},
                        null
                );

                AppToast.makeText(context, "Đã lưu thành công vào Bộ sưu tập điện thoại!", Toast.LENGTH_LONG).show();
            } else {
                FirebaseHelper.uploadFileToFirebase(context, sourceFile, metaInfo);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // =========================================================
    // ĐẨY FILE TỪ KHO LƯU TRỮ DRONE LÊN FIREBASE
    // Tự tìm bản đã tải về máy; chưa có thì tải từ drone về thư mục app rồi upload.
    // =========================================================
    public interface FirebaseUploadListener {
        void onStatus(String status);          // "Đang tải từ drone 40%", "Đang đẩy lên Firebase 60%"...
        void onDone(boolean success, String message);
    }

    /** Tìm bản sao đầy đủ của mediaFile đã có trên điện thoại (DCIM hoặc cache của app). */
    public static File findLocalCopy(Context context, MediaFile mediaFile) {
        String name = mediaFile.getFileName();
        long expected = mediaFile.getFileSize();
        File dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        File[] dirs = {
                new File(dcim, "DJI_Photos"),
                new File(dcim, "DJI_Videos"),
                new File(context.getExternalFilesDir(null), "DJI_Video_Cache"),
                new File(context.getExternalFilesDir(null), "DJI_Downloads"),
                new File(context.getExternalFilesDir(null), "DJI_Upload")
        };
        for (File dir : dirs) {
            File f = resolveDownloadedFile(dir, name, null);
            if (f != null && (expected <= 0 || f.length() >= expected)) return f;
        }
        return null;
    }

    /** Thông tin kèm theo file (thời gian chụp + GPS nếu là ảnh có EXIF). Không bao giờ ném lỗi. */
    public static String buildMetaInfo(File file, MediaFile mediaFile) {
        String displayTime = mediaFile != null && mediaFile.getDateCreated() != null
                ? mediaFile.getDateCreated() : "Không xác định";
        String gpsInfo = "Chưa lưu GPS";
        String lower = file.getName().toLowerCase(Locale.US);
        boolean isVideo = lower.endsWith(".mp4") || lower.endsWith(".mov");
        if (!isVideo) {
            try {
                ExifInterface exif = new ExifInterface(file.getAbsolutePath());
                String dateTime = exif.getAttribute(ExifInterface.TAG_DATETIME);
                if (dateTime != null) {
                    SimpleDateFormat exifFormat = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US);
                    SimpleDateFormat newFormat = new SimpleDateFormat("EEEE, 'ngày' dd/MM/yyyy 'lúc' HH:mm:ss", new Locale("vi", "VN"));
                    Date d = exifFormat.parse(dateTime);
                    if (d != null) displayTime = newFormat.format(d);
                }
                float[] latLong = new float[2];
                if (exif.getLatLong(latLong)) {
                    gpsInfo = String.format(Locale.US, "Vĩ độ: %.6f\nKinh độ: %.6f", latLong[0], latLong[1]);
                }
            } catch (Exception ignored) {}
        }
        return "Loại: " + (isVideo ? "Video" : "Ảnh") + "\nThời gian chụp: " + displayTime + "\n" + gpsInfo;
    }

    public static void uploadMediaToFirebase(Context context, MediaFile mediaFile, FirebaseUploadListener listener) {
        Handler main = new Handler(Looper.getMainLooper());
        if (!FirebaseHelper.isConfigured(context)) {
            main.post(() -> listener.onDone(false,
                    "Chưa cấu hình Cloud: điền CLOUD_NAME và UPLOAD_PRESET trong CloudConfig.java"));
            return;
        }

        File local = findLocalCopy(context, mediaFile);
        if (local != null) {
            uploadLocalFile(context, local, mediaFile, listener);
            return;
        }

        // Chưa có trên máy -> tải từ drone về thư mục riêng của app
        File uploadDir = new File(context.getExternalFilesDir(null), "DJI_Upload");
        if (!uploadDir.exists()) uploadDir.mkdirs();
        String fileName = mediaFile.getFileName();
        main.post(() -> listener.onStatus("Đang tải từ drone: " + fileName));

        suspendScheduler(() -> mediaFile.fetchFileData(uploadDir, baseName(fileName), new DownloadListener<String>() {
            private int lastPct = -1;

            @Override public void onStart() {}
            @Override public void onRateUpdate(long total, long current, long persize) {}

            @Override
            public void onProgress(long total, long current) {
                long t = mediaFile.getFileSize() > 0 ? mediaFile.getFileSize() : total;
                int pct = t > 0 ? (int) (current * 100 / t) : 0;
                if (pct != lastPct) {
                    lastPct = pct;
                    main.post(() -> listener.onStatus("Tải từ drone " + fileName + ": " + pct + "%"));
                }
            }

            @Override
            public void onSuccess(String filePath) {
                resumeScheduler();
                File realFile = resolveDownloadedFile(uploadDir, fileName, filePath);
                if (realFile == null) {
                    main.post(() -> listener.onDone(false, "Tải xong nhưng không tìm thấy file " + fileName));
                    return;
                }
                uploadLocalFile(context, realFile, mediaFile, listener);
            }

            @Override
            public void onFailure(DJIError error) {
                resumeScheduler();
                main.post(() -> listener.onDone(false, "Lỗi tải từ drone: "
                        + (error != null ? error.getDescription() : "Thất bại")));
            }

            @Override public void onRealtimeDataUpdate(byte[] bytes, long l, boolean b) {}
        }));
    }

    private static void uploadLocalFile(Context context, File file, MediaFile mediaFile, FirebaseUploadListener listener) {
        String meta = buildMetaInfo(file, mediaFile);
        FirebaseHelper.uploadFileToFirebase(context, file, mediaFile, meta, new FirebaseHelper.UploadCallback() {
            @Override
            public void onProgress(int percent, long done, long total) {
                listener.onStatus(String.format(Locale.US, "Đẩy lên Cloud %s: %d%% (%.1f/%.1f MB)",
                        file.getName(), percent, done / 1048576.0, total / 1048576.0));
            }

            @Override
            public void onSuccess(String downloadUrl) {
                listener.onDone(true, "Đã đẩy " + file.getName() + " lên Cloud!");
            }

            @Override
            public void onFailure(String message) {
                listener.onDone(false, "Lỗi Cloud (" + file.getName() + "): " + message);
            }
        });
    }
}