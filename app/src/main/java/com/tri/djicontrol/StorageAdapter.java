package com.tri.djicontrol;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.ExifInterface;
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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import dji.common.error.DJIError;
import dji.sdk.media.DownloadListener;
import dji.sdk.media.MediaFile;

// Gọi thư viện FirebaseHelper từ package bạn đã tạo
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

        View view = inflater.inflate(R.layout.item_storage, parent, false);

        ImageView imgMedia = view.findViewById(R.id.imgMedia);
        TextView tvMediaType = view.findViewById(R.id.tvMediaType);
        TextView tvMediaName = view.findViewById(R.id.tvMediaName);
        TextView tvMediaDate = view.findViewById(R.id.tvMediaDate);

        MediaFile mediaFile = mediaFiles.get(position);

        // Tên file
        tvMediaName.setText(mediaFile.getFileName());

        // Loại media
        if (mediaFile.getMediaType() == MediaFile.MediaType.MOV
                || mediaFile.getMediaType() == MediaFile.MediaType.MP4) {
            tvMediaType.setText("VIDEO");
        } else {
            tvMediaType.setText("ẢNH");
        }

        // Thời gian tạo thật của media trên DJI
        String dateCreated = mediaFile.getDateCreated();
        if (dateCreated != null && !dateCreated.isEmpty()) {
            try {
                SimpleDateFormat inputFormat = new SimpleDateFormat("yyyy-MM-dd kk:mm:ss", Locale.US);
                SimpleDateFormat outputFormat = new SimpleDateFormat("dd/MM/yyyy - HH:mm:ss", Locale.US);
                tvMediaDate.setText(outputFormat.format(inputFormat.parse(dateCreated)));
            } catch (Exception e) {
                tvMediaDate.setText(dateCreated);
            }
        } else {
            tvMediaDate.setText("Không có thời gian");
        }

        // Lấy thumbnail
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
                        imgMedia.post(() -> imgMedia.setImageBitmap(bitmap));
                    }
                }
            });
        }

        // SỰ KIỆN CLICK VÀO ẢNH TRONG DANH SÁCH
        view.setOnClickListener(v -> {
            if (mediaFile.getMediaType() == MediaFile.MediaType.JPEG
                    || mediaFile.getMediaType() == MediaFile.MediaType.TIFF) {

                Bitmap clickedThumbnail = mediaFile.getThumbnail();
                if (clickedThumbnail != null) {
                    // Truyền toàn bộ object mediaFile thay vì chỉ truyền tên file
                    showImageDialog(context, clickedThumbnail, mediaFile);
                }
            }
        });

        return view;
    }

    // =========================================================
    // HÀM HIỂN THỊ GIAO DIỆN ẢNH VÀ NÚT TẢI
    // =========================================================
    private void showImageDialog(Context context, Bitmap bitmap, MediaFile mediaFile) {
        android.app.Dialog dialog = new android.app.Dialog(context);

        // Layout cha chứa Image và các Nút
        LinearLayout rootLayout = new LinearLayout(context);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(android.graphics.Color.BLACK);

        // Image View hiển thị ảnh
        android.widget.ImageView imageView = new android.widget.ImageView(context);
        imageView.setImageBitmap(bitmap);
        imageView.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams imgParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
        imageView.setLayoutParams(imgParams);
        imageView.setOnClickListener(v -> dialog.dismiss());

        // Layout chứa Nút
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

        // Thư mục lưu ảnh gốc trên máy
        File destDir = new File(context.getExternalFilesDir(null), "DJI_Downloads");
        if (!destDir.exists()) destDir.mkdirs();
        File downloadedFile = new File(destDir, mediaFile.getFileName());

        // Sự kiện: Nút TẢI VỀ MÁY
        btnDownload.setOnClickListener(v -> {
            Toast.makeText(context, "Đang tải ảnh gốc, vui lòng đợi...", Toast.LENGTH_SHORT).show();

            mediaFile.fetchFileData(destDir, mediaFile.getFileName(), new DownloadListener<String>() {
                @Override
                public void onStart() { }
                @Override
                public void onRateUpdate(long total, long current, long persize) {}
                @Override
                public void onProgress(long total, long current) {}

                @Override
                public void onSuccess(String filePath) {
                    ((MainActivity) context).runOnUiThread(() -> {
                        // Tải xong -> Trích xuất dữ liệu Ngày giờ, GPS và hiển thị cho người dùng xem
                        processAndShowMetadata(context, filePath, false);
                    });
                }

                @Override
                public void onFailure(DJIError djiError) {
                    ((MainActivity) context).runOnUiThread(() ->
                            Toast.makeText(context, "Lỗi tải ảnh: " + djiError.getDescription(), Toast.LENGTH_SHORT).show()
                    );
                }

                @Override
                public void onRealtimeDataUpdate(byte[] bytes, long l, boolean b) {}
            });
        });

        // Sự kiện: Nút TẢI LÊN FIREBASE
        btnFirebase.setOnClickListener(v -> {
            if (downloadedFile.exists()) {
                // Đọc metadata từ file đã tải về trong máy và đẩy lên Firebase
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

    // =========================================================
    // HÀM ĐỌC EXIF (NGÀY GIỜ CHỤP, GPS) TỪ FILE ẢNH GỐC
    // =========================================================
    private void processAndShowMetadata(Context context, String filePath, boolean forFirebase) {
        try {
            ExifInterface exif = new ExifInterface(filePath);

            // 1. Lấy Ngày - Tháng - Năm - Giờ
            String dateTime = exif.getAttribute(ExifInterface.TAG_DATETIME);
            String displayTime = "Không xác định";
            if (dateTime != null) {
                try {
                    // Định dạng gốc của EXIF: "yyyy:MM:dd HH:mm:ss"
                    SimpleDateFormat exifFormat = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US);
                    // Định dạng đích: "Thứ, ngày dd/MM/yyyy lúc HH:mm:ss"
                    SimpleDateFormat newFormat = new SimpleDateFormat("EEEE, 'ngày' dd/MM/yyyy 'lúc' HH:mm:ss", new Locale("vi", "VN"));
                    Date d = exifFormat.parse(dateTime);
                    if (d != null) {
                        displayTime = newFormat.format(d);
                    }
                } catch (Exception ignored) {}
            }

            // 2. Lấy Tọa độ GPS (Vĩ độ, Kinh độ)
            float[] latLong = new float[2];
            boolean hasGps = exif.getLatLong(latLong);
            String gpsInfo = hasGps ? String.format(Locale.US, "Vĩ độ: %.6f\nKinh độ: %.6f", latLong[0], latLong[1]) : "Chưa lưu GPS";

            // Tổng hợp thông tin
            String metaInfo = "Thời gian chụp: " + displayTime + "\n" + gpsInfo;

            if (!forFirebase) {
                // Xử lý khi nhấn Tải về máy: Hiển thị Dialog thông báo chi tiết
                android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(context);
                builder.setTitle("Tải ảnh thành công!");
                builder.setMessage("Đã lưu tại: " + filePath + "\n\n--- THÔNG TIN ẢNH ---\n" + metaInfo);
                builder.setPositiveButton("ĐÓNG", null);
                builder.show();
            } else {
                // Xử lý khi nhấn Tải lên Firebase: Đẩy file cùng với đoạn chữ metaInfo lên cloud
                FirebaseHelper.uploadFileToFirebase(context, new File(filePath), metaInfo);
            }

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(context, "Có lỗi khi đọc thông tin ảnh!", Toast.LENGTH_SHORT).show();
        }
    }
}