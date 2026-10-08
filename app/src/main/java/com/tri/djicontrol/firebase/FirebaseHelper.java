package com.tri.djicontrol.firebase;

import android.net.Uri;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;
import java.io.File;

public class FirebaseHelper {

    // Tạo interface để giao tiếp với MainActivity
    public interface UploadCallback {
        void onProgress(int progress, double speedKBps);
        void onSuccess(String downloadUrl);
        void onFailure(String error);
    }

    public static void uploadImageToFirebase(File file, UploadCallback callback) {
        if (file == null || !file.exists()) {
            callback.onFailure("File ảnh không tồn tại!");
            return;
        }

        // Tạo tham chiếu đến Firebase Storage (Lưu vào thư mục "dji_photos")
        FirebaseStorage storage = FirebaseStorage.getInstance();
        StorageReference storageRef = storage.getReference().child("dji_photos/" + file.getName());

        Uri fileUri = Uri.fromFile(file);
        UploadTask uploadTask = storageRef.putFile(fileUri);

        // Các biến dùng để tính tốc độ Upload
        final long[] lastTime = {System.currentTimeMillis()};
        final long[] lastUploaded = {0};

        uploadTask.addOnProgressListener(taskSnapshot -> {
            long uploaded = taskSnapshot.getBytesTransferred();
            long total = taskSnapshot.getTotalByteCount();
            int progress = (int) (100.0 * uploaded / total);

            long currentTime = System.currentTimeMillis();
            long timeDiff = currentTime - lastTime[0];

            // Cập nhật tốc độ mỗi 400ms để tránh giật UI
            if (timeDiff >= 400) {
                double speedKBps = ((uploaded - lastUploaded[0]) / 1024.0) / (timeDiff / 1000.0);
                callback.onProgress(progress, speedKBps);

                lastTime[0] = currentTime;
                lastUploaded[0] = uploaded;
            }
        }).addOnSuccessListener(taskSnapshot -> {
            // Lấy link tải ảnh về sau khi upload thành công
            storageRef.getDownloadUrl().addOnSuccessListener(uri -> {
                callback.onSuccess(uri.toString());
            });
        }).addOnFailureListener(e -> {
            callback.onFailure(e.getMessage());
        });
    }
}