package com.tri.djicontrol;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.ExifInterface;
import android.media.MediaScannerConnection;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.app.AlertDialog;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import dji.common.camera.SettingsDefinitions;
import dji.common.error.DJIError;
import dji.sdk.media.DownloadListener;
import dji.sdk.media.MediaFile;
import dji.sdk.products.Aircraft;
import dji.sdk.sdkmanager.DJISDKManager;
import com.tri.djicontrol.firebase.FirebaseHelper;

public class StorageAdapter extends BaseAdapter {

    private final Context context;
    private final List<MediaFile> mediaFiles;
    private final LayoutInflater inflater;

    public StorageAdapter(Context context, List<MediaFile> mediaFiles) {
        this.context = context;
        this.mediaFiles = mediaFiles;
        this.inflater = LayoutInflater.from(context);
    }

    @Override
    public int getCount() {
        return mediaFiles.size();
    }

    @Override
    public Object getItem(int position) {
        return mediaFiles.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView != null ? convertView : inflater.inflate(R.layout.item_storage, parent, false);

        ImageView imgMedia = view.findViewById(R.id.imgMedia);
        TextView tvMediaType = view.findViewById(R.id.tvMediaType);
        TextView tvMediaName = view.findViewById(R.id.tvMediaName);
        TextView tvMediaDate = view.findViewById(R.id.tvMediaDate);

        MediaFile mediaFile = mediaFiles.get(position);

        tvMediaName.setText(mediaFile.getFileName() != null ? mediaFile.getFileName() : "Unknown_File");

        if (mediaFile.getMediaType() == MediaFile.MediaType.MOV || mediaFile.getMediaType() == MediaFile.MediaType.MP4) {
            tvMediaType.setText("VIDEO");
        } else {
            tvMediaType.setText("ẢNH");
        }

        String dateCreated = mediaFile.getDateCreated();
        if (dateCreated != null && !dateCreated.isEmpty()) {
            try {
                SimpleDateFormat inputFormat = new SimpleDateFormat("yyyy-MM-dd kk:mm:ss", Locale.US);
                SimpleDateFormat outputFormat = new SimpleDateFormat("dd/MM/yyyy - HH:mm:ss", Locale.US);
                Date parsedDate = inputFormat.parse(dateCreated);
                tvMediaDate.setText(parsedDate != null ? outputFormat.format(parsedDate) : dateCreated);
            } catch (Exception e) {
                tvMediaDate.setText(dateCreated);
            }
        } else {
            tvMediaDate.setText("Không có thời gian");
        }

        imgMedia.setImageResource(android.R.color.darker_gray);
        Bitmap thumbnail = mediaFile.getThumbnail();

        if (thumbnail != null) {
            imgMedia.setImageBitmap(thumbnail);
        } else {
            imgMedia.setTag(mediaFile);
            mediaFile.fetchThumbnail(error -> {
                if (error == null) {
                    Bitmap bitmap = mediaFile.getThumbnail();
                    if (bitmap != null && mediaFile.equals(imgMedia.getTag())) {
                        new Handler(Looper.getMainLooper()).post(() -> imgMedia.setImageBitmap(bitmap));
                    }
                }
            });
        }

        view.setOnClickListener(v -> {
            Bitmap clickedThumbnail = mediaFile.getThumbnail();
            showImageDialog(context, clickedThumbnail, mediaFile);
        });

        return view;
    }

    private void showImageDialog(Context context, Bitmap bitmap, MediaFile mediaFile) {
        android.app.Dialog dialog = new android.app.Dialog(context);

        LinearLayout rootLayout = new LinearLayout(context);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(android.graphics.Color.BLACK);

        ImageView imageView = new ImageView(context);
        if (bitmap != null) {
            imageView.setImageBitmap(bitmap);
        } else {
            imageView.setImageResource(android.R.color.darker_gray);
        }
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams imgParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
        imageView.setLayoutParams(imgParams);
        imageView.setOnClickListener(v -> dialog.dismiss());

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

        rootLayout.addView(imageView);
        rootLayout.addView(btnLayout);

        dialog.setContentView(rootLayout);

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

        btnDownload.setOnClickListener(v -> {

            if (mediaFile == null) {
                Toast.makeText(
                        context,
                        "mediaFile = NULL!",
                        Toast.LENGTH_LONG
                ).show();
                return;
            }

            String fileName = mediaFile.getFileName();

            if (fileName == null || fileName.isEmpty()) {
                Toast.makeText(
                        context,
                        "Không lấy được tên file!",
                        Toast.LENGTH_LONG
                ).show();
                return;
            }

            Toast.makeText(
                    context,
                    "Bắt đầu tải: " + fileName,
                    Toast.LENGTH_SHORT
            ).show();

            // Đảm bảo Camera ở chế độ MEDIA_DOWNLOAD trước khi tải
            if (DJISDKManager.getInstance().getProduct() != null &&
                    DJISDKManager.getInstance().getProduct() instanceof Aircraft) {
                Aircraft aircraft = (Aircraft) DJISDKManager.getInstance().getProduct();
                if (aircraft.getCamera() != null) {
                    aircraft.getCamera().setMode(SettingsDefinitions.CameraMode.MEDIA_DOWNLOAD, null);
                }
            }

            mediaFile.fetchFileData(
                    finalDownloadDir,
                    fileName,
                    new DownloadListener<String>() {

                        @Override
                        public void onStart() {
                            new Handler(Looper.getMainLooper()).post(() ->
                                    Toast.makeText(
                                            context,
                                            "DJI bắt đầu tải...",
                                            Toast.LENGTH_SHORT
                                    ).show()
                            );
                        }

                        @Override
                        public void onRateUpdate(
                                long total,
                                long current,
                                long persize
                        ) {
                        }

                        @Override
                        public void onProgress(
                                long total,
                                long current
                        ) {
                        }

                        @Override
                        public void onSuccess(String filePath) {

                            new Handler(Looper.getMainLooper()).post(() -> {

                                Toast.makeText(
                                        context,
                                        "DJI tải xong! Đang lưu vào bộ sưu tập...",
                                        Toast.LENGTH_SHORT
                                ).show();

                                if (filePath != null) {
                                    MediaScannerConnection.scanFile(
                                            context,
                                            new String[]{filePath},
                                            null,
                                            null
                                    );
                                    processAndShowMetadata(context, filePath, false);
                                } else {
                                    Toast.makeText(
                                            context,
                                            "Lỗi: filePath từ DJI trả về NULL!",
                                            Toast.LENGTH_LONG
                                    ).show();
                                }
                            });
                        }

                        @Override
                        public void onFailure(DJIError djiError) {

                            new Handler(Looper.getMainLooper()).post(() -> {

                                String error = djiError != null
                                        ? djiError.getDescription()
                                        : "Không xác định";

                                Toast.makeText(
                                        context,
                                        "DJI lỗi tải file:\n" + error,
                                        Toast.LENGTH_LONG
                                ).show();
                            });
                        }

                        @Override
                        public void onRealtimeDataUpdate(
                                byte[] bytes,
                                long l,
                                boolean b
                        ) {
                        }
                    }
            );
        });

        btnFirebase.setOnClickListener(v -> {

            File downloadedFile = new File(
                    finalDownloadDir,
                    mediaFile.getFileName()
            );

            if (!downloadedFile.exists() || downloadedFile.length() == 0) {
                // Thử tìm ở thư mục fallback
                File fallbackFile = new File(
                        new File(context.getExternalFilesDir(null), "DJI_Downloads"),
                        mediaFile.getFileName()
                );
                if (fallbackFile.exists() && fallbackFile.length() > 0) {
                    downloadedFile = fallbackFile;
                }
            }

            if (downloadedFile.exists() && downloadedFile.length() > 0) {

                processAndShowMetadata(
                        context,
                        downloadedFile.getAbsolutePath(),
                        true
                );

            } else {

                Toast.makeText(
                        context,
                        "Vui lòng tải ảnh về máy trước!",
                        Toast.LENGTH_SHORT
                ).show();
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

    private void processAndShowMetadata(Context context, String absolutePath, boolean forFirebase) {
        File sourceFile = new File(absolutePath);

        if (!sourceFile.exists() || sourceFile.length() == 0) {
            Toast.makeText(
                    context,
                    "Lỗi: File ảnh/video bị hỏng hoặc chưa tải xong!",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        String displayTime = "Không xác định";
        String gpsInfo = "Chưa lưu GPS";

        // Đọc thông tin EXIF
        try {
            ExifInterface exif = new ExifInterface(absolutePath);

            String dateTime = exif.getAttribute(ExifInterface.TAG_DATETIME);

            if (dateTime != null) {
                SimpleDateFormat exifFormat =
                        new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US);

                SimpleDateFormat newFormat =
                        new SimpleDateFormat(
                                "EEEE, 'ngày' dd/MM/yyyy 'lúc' HH:mm:ss",
                                new Locale("vi", "VN")
                        );

                Date d = exifFormat.parse(dateTime);

                if (d != null) {
                    displayTime = newFormat.format(d);
                }
            }

            float[] latLong = new float[2];

            if (exif.getLatLong(latLong)) {
                gpsInfo = String.format(
                        Locale.US,
                        "Vĩ độ: %.6f\nKinh độ: %.6f",
                        latLong[0],
                        latLong[1]
                );
            }

        } catch (Exception ignored) {
            // Không có EXIF vẫn tiếp tục lưu
        }

        String metaInfo =
                "Thời gian chụp: " + displayTime +
                        "\n" + gpsInfo;

        // ==============================
        // LƯU VÀO GALLERY ĐIỆN THOẠI
        // ==============================
        if (!forFirebase) {

            try {
                MediaScannerConnection.scanFile(
                        context,
                        new String[]{sourceFile.getAbsolutePath()},
                        null,
                        null
                );

                boolean isInPublicFolder = absolutePath.contains("/DCIM/")
                        || absolutePath.contains("/Pictures/")
                        || absolutePath.contains("/Movies/");

                if (!isInPublicFolder) {
                    boolean isVideo = absolutePath.toLowerCase().endsWith(".mp4")
                            || absolutePath.toLowerCase().endsWith(".mov");

                    ContentResolver resolver = context.getContentResolver();
                    ContentValues values = new ContentValues();

                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, sourceFile.getName());
                    values.put(MediaStore.MediaColumns.MIME_TYPE, isVideo ? "video/mp4" : "image/jpeg");

                    Uri collection;

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.put(
                                MediaStore.MediaColumns.RELATIVE_PATH,
                                isVideo ? (Environment.DIRECTORY_MOVIES + "/DJI_Captured_Videos")
                                        : (Environment.DIRECTORY_PICTURES + "/DJI_Captured_Photos")
                        );
                        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                        collection = isVideo
                                ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                                : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                    } else {
                        File targetDir = new File(
                                Environment.getExternalStoragePublicDirectory(
                                        isVideo ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES
                                ),
                                isVideo ? "DJI_Captured_Videos" : "DJI_Captured_Photos"
                        );
                        if (!targetDir.exists()) targetDir.mkdirs();
                        File targetFile = new File(targetDir, sourceFile.getName());
                        values.put(MediaStore.MediaColumns.DATA, targetFile.getAbsolutePath());
                        collection = isVideo
                                ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                    }

                    Uri mediaUri = resolver.insert(collection, values);

                    if (mediaUri != null) {
                        try (InputStream in = new FileInputStream(sourceFile);
                             OutputStream out = resolver.openOutputStream(mediaUri)) {

                            if (out != null) {
                                byte[] buffer = new byte[8192];
                                int read;
                                while ((read = in.read(buffer)) != -1) {
                                    out.write(buffer, 0, read);
                                }
                                out.flush();
                            }
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            ContentValues updateValues = new ContentValues();
                            updateValues.put(MediaStore.MediaColumns.IS_PENDING, 0);
                            resolver.update(mediaUri, updateValues, null, null);
                        }

                        MediaScannerConnection.scanFile(
                                context,
                                new String[]{sourceFile.getAbsolutePath()},
                                null,
                                null
                        );
                    }
                }

                Toast.makeText(
                        context,
                        "Đã lưu vào bộ sưu tập điện thoại!",
                        Toast.LENGTH_SHORT
                ).show();

                new AlertDialog.Builder(context)
                        .setTitle("Thông tin File")
                        .setMessage(metaInfo + "\n\nĐường dẫn:\n" + absolutePath)
                        .setPositiveButton("OK", null)
                        .show();

            } catch (Exception e) {

                String error = e.getClass().getSimpleName()
                        + ": "
                        + e.getMessage();

                Toast.makeText(
                        context,
                        "LỖI PROCESS:\n" + error,
                        Toast.LENGTH_LONG
                ).show();
            }

        } else {

            // ==============================
            // TẢI LÊN FIREBASE
            // ==============================

            FirebaseHelper.uploadImageToFirebase(
                    sourceFile,
                    new FirebaseHelper.UploadCallback() {

                        @Override
                        public void onProgress(
                                int progress,
                                double speedKBps
                        ) {
                        }

                        @Override
                        public void onSuccess(String downloadUrl) {

                            new Handler(
                                    Looper.getMainLooper()
                            ).post(() ->
                                    Toast.makeText(
                                            context,
                                            "Đã đẩy ảnh lên Firebase thành công!",
                                            Toast.LENGTH_SHORT
                                    ).show()
                            );
                        }

                        @Override
                        public void onFailure(String error) {

                            new Handler(
                                    Looper.getMainLooper()
                            ).post(() ->
                                    Toast.makeText(
                                            context,
                                            "Lỗi upload Firebase: " + error,
                                            Toast.LENGTH_LONG
                                    ).show()
                            );
                        }
                    }
            );
        }
    }
}