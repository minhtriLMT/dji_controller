package com.tri.djicontrol;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.ExifInterface;
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

import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import dji.common.error.DJIError;
import dji.sdk.media.DownloadListener;
import dji.sdk.media.MediaFile;
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
            if (mediaFile.getMediaType() == MediaFile.MediaType.JPEG
                    || mediaFile.getMediaType() == MediaFile.MediaType.TIFF) {
                Bitmap clickedThumbnail = mediaFile.getThumbnail();
                showImageDialog(context, clickedThumbnail, mediaFile);
            }
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

        File destDir = new File(context.getExternalFilesDir(null), "DJI_Downloads");
        if (!destDir.exists()) destDir.mkdirs();
        File downloadedFile = new File(destDir, mediaFile.getFileName());

        btnDownload.setOnClickListener(v -> {
            Toast.makeText(context, "Đang kết nối tải ảnh...", Toast.LENGTH_SHORT).show();

            mediaFile.fetchFileData(destDir, mediaFile.getFileName(), new DownloadListener<String>() {
                @Override public void onStart() {}
                @Override public void onRateUpdate(long total, long current, long persize) {}
                @Override public void onProgress(long total, long current) {}

                @Override
                public void onSuccess(String filePath) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        Toast.makeText(context, "Tải xong! Đang lưu vào thư viện...", Toast.LENGTH_SHORT).show();
                        // Sử dụng đường dẫn tuyệt đối của downloadedFile thay vì filePath từ DJI để tránh rủi ro
                        processAndShowMetadata(context, downloadedFile.getAbsolutePath(), false);
                    });
                }

                @Override
                public void onFailure(DJIError djiError) {
                    new Handler(Looper.getMainLooper()).post(() ->
                            Toast.makeText(context, "Lỗi kết nối thẻ nhớ: " + (djiError != null ? djiError.getDescription() : "Timeout"), Toast.LENGTH_LONG).show()
                    );
                }

                @Override public void onRealtimeDataUpdate(byte[] bytes, long l, boolean b) {}
            });
        });

        btnFirebase.setOnClickListener(v -> {
            if (downloadedFile.exists() && downloadedFile.length() > 0) {
                processAndShowMetadata(context, downloadedFile.getAbsolutePath(), true);
            } else {
                Toast.makeText(context, "Vui lòng ấn 'TẢI VỀ MÁY' trước khi đưa lên Firebase!", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(context, "Lỗi: File ảnh bị hỏng hoặc chưa tải xong!", Toast.LENGTH_SHORT).show();
            return;
        }

        String displayTime = "Không xác định";
        String gpsInfo = "Chưa lưu GPS";

        // TÁCH RIÊNG PHẦN ĐỌC EXIF (Nếu lỗi đọc thông tin thì BỎ QUA, VẪN TIẾP TỤC LƯU ẢNH)
        try {
            ExifInterface exif = new ExifInterface(absolutePath);
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
        } catch (Exception ignored) {
            // Không làm ứng dụng sụp đổ nếu ảnh không có dữ liệu định vị
        }

        String metaInfo = "Thời gian chụp: " + displayTime + "\n" + gpsInfo;

        if (!forFirebase) {
            // PHẦN LƯU VÀO THƯ VIỆN ĐIỆN THOẠI (GALLERY)
            try {
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, sourceFile.getName());
                values.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DJI_Captured_Photos");
                    values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 1);
                }

                android.content.ContentResolver resolver = context.getContentResolver();
                android.net.Uri collection = android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY);
                android.net.Uri imageUri = resolver.insert(collection, values);

                if (imageUri != null) {
                    try (java.io.OutputStream out = resolver.openOutputStream(imageUri);
                         InputStream in = new java.io.FileInputStream(sourceFile)) {
                        byte[] buffer = new byte[4096];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                    }

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        values.clear();
                        values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0);
                        resolver.update(imageUri, values, null, null);
                    }
                }

                android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(context);
                builder.setTitle("Đã lưu vào Thư Viện Ảnh!");
                builder.setMessage("Ảnh đã xuất hiện trong bộ sưu tập (Thư mục: DJI_Captured_Photos).\n\n--- THÔNG TIN ẢNH ---\n" + metaInfo);
                builder.setPositiveButton("ĐÓNG", null);
                builder.show();

            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(context, "Lỗi khi chép file vào thư viện ảnh: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else {
            // TẢI LÊN FIREBASE
            FirebaseHelper.uploadImageToFirebase(sourceFile, new FirebaseHelper.UploadCallback() {
                @Override public void onProgress(int progress, double speedKBps) {}
                @Override
                public void onSuccess(String downloadUrl) {
                    new Handler(Looper.getMainLooper()).post(() ->
                            Toast.makeText(context, "Đã đẩy ảnh lên Firebase thành công!", Toast.LENGTH_SHORT).show()
                    );
                }
                @Override
                public void onFailure(String error) {
                    new Handler(Looper.getMainLooper()).post(() ->
                            Toast.makeText(context, "Lỗi upload Firebase: " + error, Toast.LENGTH_LONG).show()
                    );
                }
            });
        }
    }
}