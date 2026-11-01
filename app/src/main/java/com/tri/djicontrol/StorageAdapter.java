package com.tri.djicontrol;

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

import dji.common.error.DJIError;
import dji.sdk.media.DownloadListener;
import dji.sdk.media.MediaFile;
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

    public StorageAdapter(Context context, List<MediaFile> mediaFiles) {
        this.context = context;
        this.mediaFiles = mediaFiles;
        this.inflater = LayoutInflater.from(context);
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

        String dateCreated = mediaFile.getDateCreated();
        if (dateCreated != null && !dateCreated.isEmpty()) {
            try {
                SimpleDateFormat inputFormat = new SimpleDateFormat("yyyy-MM-dd kk:mm:ss", Locale.US);
                SimpleDateFormat outputFormat = new SimpleDateFormat("dd/MM/yyyy - HH:mm:ss", Locale.US);
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
            if (itemRoot != null) {
                itemRoot.setBackgroundColor(isSelected ? Color.parseColor("#4A148C") : Color.parseColor("#30343B"));
            }
        } else {
            if (cbSelect != null) {
                cbSelect.setVisibility(View.GONE);
            }
            if (itemRoot != null) {
                itemRoot.setBackgroundColor(Color.parseColor("#30343B"));
            }
        }

        imgMedia.setImageResource(android.R.color.darker_gray);
        imgMedia.setTag(mediaFile);

        // Nạp Thumbnail cực nhanh (1. RAM -> 2. Disk Cache cục bộ -> 3. Tải từ Drone)
        Bitmap thumbnail = mediaFile.getThumbnail();
        if (thumbnail != null) {
            imgMedia.setImageBitmap(thumbnail);
        } else {
            File thumbCacheFile = new File(context.getExternalCacheDir(), "thumb_" + mediaFile.getFileName() + ".jpg");
            if (thumbCacheFile.exists() && thumbCacheFile.length() > 0) {
                Bitmap cachedBitmap = BitmapFactory.decodeFile(thumbCacheFile.getAbsolutePath());
                if (cachedBitmap != null) {
                    imgMedia.setImageBitmap(cachedBitmap);
                } else {
                    fetchThumbnailFromDrone(mediaFile, thumbCacheFile, imgMedia);
                }
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
        mediaFile.fetchThumbnail(error -> {
            if (error == null) {
                Bitmap bitmap = mediaFile.getThumbnail();
                if (bitmap != null) {
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(thumbCacheFile)) {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out);
                    } catch (Exception ignored) {}

                    imgMedia.post(() -> {
                        if (imgMedia.getTag() == mediaFile) {
                            imgMedia.setImageBitmap(bitmap);
                        }
                    });
                }
            }
        });
    }

    // =========================================================
    // 1. HÀM XEM ẢNH TỐC ĐỘ CAO (DÙNG PREVIEW IMAGE / THUMBNAIL FALLBACK)
    // =========================================================
    private void showImageDialog(Context context, MediaFile mediaFile) {
        Dialog dialog = new Dialog(context);

        LinearLayout rootLayout = new LinearLayout(context);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(Color.BLACK);
        rootLayout.setPadding(10, 10, 10, 10);

        ProgressBar progressBar = new ProgressBar(context);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        progressParams.gravity = android.view.Gravity.CENTER;
        progressBar.setLayoutParams(progressParams);

        ImageView imageView = new ImageView(context);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams imgParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
        imageView.setLayoutParams(imgParams);
        imageView.setOnClickListener(v -> dialog.dismiss());

        // Hiện Thumbnail tạm thời trong khi chờ
        if (mediaFile.getThumbnail() != null) {
            imageView.setImageBitmap(mediaFile.getThumbnail());
        }

        // Tải ảnh Preview sắc nét nhưng dung lượng nhẹ (Load cực nhanh)
        mediaFile.fetchPreview(error -> {
            if (error == null && mediaFile.getPreview() != null) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    progressBar.setVisibility(View.GONE);
                    imageView.setImageBitmap(mediaFile.getPreview());
                });
            } else {
                // Nếu camera không hỗ trợ Preview (như dòng Mini/Air/Phantom cũ), sử dụng Thumbnail
                mediaFile.fetchThumbnail(thumbError -> {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        progressBar.setVisibility(View.GONE);
                        if (mediaFile.getThumbnail() != null) {
                            imageView.setImageBitmap(mediaFile.getThumbnail());
                        } else {
                            Toast.makeText(context, "Ảnh lỗi hoặc không thể xem trước!", Toast.LENGTH_SHORT).show();
                        }
                    });
                });
            }
        });

        LinearLayout btnLayout = new LinearLayout(context);
        btnLayout.setOrientation(LinearLayout.HORIZONTAL);
        btnLayout.setPadding(20, 20, 20, 20);

        Button btnDownload = new Button(context);
        btnDownload.setText("TẢI VỀ MÁY");
        btnDownload.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button btnFirebase = new Button(context);
        btnFirebase.setText("TẢI LÊN FIREBASE");
        btnFirebase.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        btnLayout.addView(btnDownload);
        btnLayout.addView(btnFirebase);

        rootLayout.addView(progressBar);
        rootLayout.addView(imageView);
        rootLayout.addView(btnLayout);

        dialog.setContentView(rootLayout);

        File destDir = new File(context.getExternalFilesDir(null), "DJI_Downloads");
        if (!destDir.exists()) destDir.mkdirs();
        File downloadedFile = new File(destDir, mediaFile.getFileName());

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

            Toast.makeText(context, "Đang tải ảnh gốc về máy...", Toast.LENGTH_SHORT).show();

            // 2. Tiến hành tải file gốc từ Drone về thư mục
            mediaFile.fetchFileData(
                    finalDownloadDir,
                    fileName,
                    new DownloadListener<String>() {
                        @Override public void onStart() {}
                        @Override public void onRateUpdate(long total, long current, long persize) {}
                        @Override public void onProgress(long total, long current) {}

                        @Override
                        public void onSuccess(String filePath) {
                            new Handler(Looper.getMainLooper()).post(() -> {
                                Toast.makeText(context, "Đã tải xong! Đang lưu vào Bộ sưu tập...", Toast.LENGTH_SHORT).show();

                                if (filePath != null) {
                                    // Kích hoạt MediaScanner để Thư viện ảnh / Gallery điện thoại nhận diện ngay
                                    MediaScannerConnection.scanFile(
                                            context,
                                            new String[]{filePath},
                                            null,
                                            null
                                    );
                                    // Đọc thông tin EXIF và hiển thị thông báo
                                    processAndShowMetadata(context, filePath, false);
                                }
                            });
                        }

                        @Override
                        public void onFailure(DJIError djiError) {
                            new Handler(Looper.getMainLooper()).post(() ->
                                    Toast.makeText(context, "Lỗi tải file: " + djiError.getDescription(), Toast.LENGTH_SHORT).show()
                            );
                        }

                        @Override public void onRealtimeDataUpdate(byte[] bytes, long l, boolean b) {}
                    }
            );
        });

        btnFirebase.setOnClickListener(v -> {
            if (downloadedFile.exists()) {
                processAndShowMetadata(context, downloadedFile.getAbsolutePath(), true);
            } else {
                Toast.makeText(context, "Ấn 'TẢI VỀ MÁY' trước khi đưa lên Firebase!", Toast.LENGTH_SHORT).show();
            }
        });

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.black);
            dialog.getWindow().setLayout(
                    (int) (context.getResources().getDisplayMetrics().widthPixels * 0.90),
                    (int) (context.getResources().getDisplayMetrics().heightPixels * 0.85)
            );
        }
        dialog.show();
    }

    // =========================================================
    // HÀM PHÁT VIDEO TRỰC TIẾP VỚI BỘ ĐẾM TIẾN TRÌNH & PROGRESS BAR UI
    // =========================================================
    private void showVideoDialog(Context context, MediaFile mediaFile) {
        Dialog dialog = new Dialog(context);

        LinearLayout rootLayout = new LinearLayout(context);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(android.graphics.Color.BLACK);
        rootLayout.setPadding(20, 20, 20, 20);

        // Khung FrameLayout chứa VideoView ở dưới và Loading Overlay ở trên
        FrameLayout videoContainer = new FrameLayout(context);
        LinearLayout.LayoutParams containerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
        videoContainer.setLayoutParams(containerParams);

        // 1. VideoView (Hiển thị ngay từ đầu để SurfaceHolder khởi tạo sẵn)
        VideoView videoView = new VideoView(context);
        FrameLayout.LayoutParams videoParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        videoView.setLayoutParams(videoParams);

        MediaController mediaController = new MediaController(context);
        mediaController.setAnchorView(videoView);
        videoView.setMediaController(mediaController);

        // 2. Loading Overlay đè lên trên VideoView trong khi tải
        LinearLayout loadingLayout = new LinearLayout(context);
        loadingLayout.setOrientation(LinearLayout.VERTICAL);
        loadingLayout.setGravity(android.view.Gravity.CENTER);
        loadingLayout.setBackgroundColor(android.graphics.Color.BLACK);
        FrameLayout.LayoutParams loadingParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        loadingLayout.setLayoutParams(loadingParams);

        TextView tvTitle = new TextView(context);
        tvTitle.setText("Đang tải video từ máy bay: " + mediaFile.getFileName());
        tvTitle.setTextColor(android.graphics.Color.WHITE);
        tvTitle.setTextSize(15);
        tvTitle.setGravity(android.view.Gravity.CENTER);
        tvTitle.setPadding(0, 0, 0, 15);

        ProgressBar progressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        LinearLayout.LayoutParams pbParams = new LinearLayout.LayoutParams(
                (int) (context.getResources().getDisplayMetrics().widthPixels * 0.70),
                LinearLayout.LayoutParams.WRAP_CONTENT);
        progressBar.setLayoutParams(pbParams);

        TextView tvProgressText = new TextView(context);
        tvProgressText.setText("0.0 MB / 0.0 MB (0%)");
        tvProgressText.setTextColor(android.graphics.Color.LTGRAY);
        tvProgressText.setTextSize(13);
        tvProgressText.setGravity(android.view.Gravity.CENTER);
        tvProgressText.setPadding(0, 10, 0, 0);

        Button btnRetry = new Button(context);
        btnRetry.setText("THỬ NẠP LẠI VIDEO");
        btnRetry.setVisibility(View.GONE);
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        retryParams.gravity = android.view.Gravity.CENTER;
        retryParams.topMargin = 20;
        btnRetry.setLayoutParams(retryParams);

        loadingLayout.addView(tvTitle);
        loadingLayout.addView(progressBar);
        loadingLayout.addView(tvProgressText);
        loadingLayout.addView(btnRetry);

        videoContainer.addView(videoView);
        videoContainer.addView(loadingLayout);

        // 3. Thanh nút bấm ở dưới cùng
        LinearLayout btnLayout = new LinearLayout(context);
        btnLayout.setOrientation(LinearLayout.HORIZONTAL);
        btnLayout.setPadding(0, 15, 0, 0);

        Button btnDownload = new Button(context);
        btnDownload.setText("TẢI VỀ MÁY");
        btnDownload.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button btnExternal = new Button(context);
        btnExternal.setText("MỞ BẰNG TRÌNH PHÁT NGOÀI");
        btnExternal.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        btnLayout.addView(btnDownload);
        btnLayout.addView(btnExternal);

        rootLayout.addView(videoContainer);
        rootLayout.addView(btnLayout);
        dialog.setContentView(rootLayout);

        File destDir = new File(context.getExternalFilesDir(null), "DJI_Video_Cache");
        if (!destDir.exists()) destDir.mkdirs();

        File publicDir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                "DJI_Videos"
        );
        if (!publicDir.exists()) publicDir.mkdirs();

        File publicVideoFile = new File(publicDir, mediaFile.getFileName());
        File localVideoFile = new File(destDir, mediaFile.getFileName());

        long expectedSize = mediaFile.getFileSize();

        // Kiểm tra xem file đã có trong bộ sưu tập công khai DCIM hoặc bộ nhớ cache chưa
        boolean existsInPublic = publicVideoFile.exists() && publicVideoFile.length() > 0
                && (expectedSize > 0 ? publicVideoFile.length() >= expectedSize : publicVideoFile.length() > 2 * 1024 * 1024);

        boolean existsInCache = localVideoFile.exists() && localVideoFile.length() > 0
                && (expectedSize > 0 ? localVideoFile.length() >= expectedSize : localVideoFile.length() > 2 * 1024 * 1024);

        File readyFile = null;
        if (existsInPublic) {
            readyFile = publicVideoFile;
        } else if (existsInCache) {
            readyFile = localVideoFile;
        } else {
            // Xóa file dở dang nếu có
            if (localVideoFile.exists()) localVideoFile.delete();
            if (publicVideoFile.exists()) publicVideoFile.delete();
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
                            Toast.makeText(context, "Chuyển sang Trình phát Video điện thoại...", Toast.LENGTH_SHORT).show();
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
                    Toast.makeText(context, "Video đã có sẵn trong DCIM/DJI_Videos!", Toast.LENGTH_SHORT).show();
                    return;
                }
                Toast.makeText(context, "Đang lưu video vào DCIM/DJI_Videos...", Toast.LENGTH_SHORT).show();
                try (InputStream in = new FileInputStream(currentFile);
                     OutputStream out = new java.io.FileOutputStream(downloadDest)) {
                    byte[] buffer = new byte[16384];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    out.flush();
                    MediaScannerConnection.scanFile(context, new String[]{downloadDest.getAbsolutePath()}, new String[]{"video/*"}, null);
                    Toast.makeText(context, "Đã lưu video vào DCIM/DJI_Videos!", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(context, "Lỗi lưu video: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(context, "File video chưa nạp xong, vui lòng đợi!", Toast.LENGTH_SHORT).show();
            }
        };

        btnDownload.setOnClickListener(v -> copyToGalleryAction.run());

        btnExternal.setOnClickListener(v -> {
            File currentFile = new File(playFilePath.get());
            if (currentFile.exists() && currentFile.length() > 0) {
                openSystemVideoPlayer(context, currentFile);
            } else {
                Toast.makeText(context, "Vui lòng đợi nạp xong video từ drone!", Toast.LENGTH_SHORT).show();
            }
        });

        Runnable startDownloadProcess = new Runnable() {
            @Override
            public void run() {
                btnRetry.setVisibility(View.GONE);
                tvProgressText.setText("Bắt đầu nạp video từ drone...");
                isDownloading[0] = true;

                // Tải vào destDir (app-internal external storage) để đảm bảo 100% không bị chặn bởi Scoped Storage Android 13
                mediaFile.fetchFileData(destDir, mediaFile.getFileName(), new DownloadListener<String>() {
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
                        if (filePath != null && !filePath.isEmpty()) {
                            playFilePath.set(filePath);
                        }
                        playVideoAction.run();
                    }

                    @Override
                    public void onFailure(DJIError error) {
                        isDownloading[0] = false;
                        new Handler(Looper.getMainLooper()).post(() -> {
                            tvProgressText.setText("Lỗi nạp video: " + (error != null ? error.getDescription() : "Thất bại"));
                            btnRetry.setVisibility(View.VISIBLE);
                        });
                    }

                    @Override public void onRealtimeDataUpdate(byte[] bytes, long l, boolean b) {}
                });
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
            if (isDownloading[0]) {
                try {
                    mediaFile.stopFetchingFileData(null);
                } catch (Exception ignored) {}
            }
        });

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.black);
            dialog.getWindow().setLayout(
                    (int) (context.getResources().getDisplayMetrics().widthPixels * 0.90),
                    (int) (context.getResources().getDisplayMetrics().heightPixels * 0.85)
            );
        }
        dialog.show();
    }

    // Mở bằng Trình phát Video ngoài của hệ điều hành điện thoại
    private void openSystemVideoPlayer(Context context, File videoFile) {
        if (videoFile == null || !videoFile.exists()) {
            Toast.makeText(context, "File video chưa sẵn sàng!", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(context, "Lỗi mở trình phát video: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
                boolean isVideo = filePath.endsWith(".mp4") || filePath.endsWith(".mov");
                MediaScannerConnection.scanFile(
                        context,
                        new String[]{sourceFile.getAbsolutePath()},
                        new String[]{isVideo ? "video/*" : "image/*"},
                        null
                );

                Toast.makeText(context, "Đã lưu thành công vào Bộ sưu tập điện thoại!", Toast.LENGTH_LONG).show();
            } else {
                FirebaseHelper.uploadFileToFirebase(context, sourceFile, metaInfo);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}