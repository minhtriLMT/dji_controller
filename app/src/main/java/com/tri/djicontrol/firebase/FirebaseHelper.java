package com.tri.djicontrol.firebase;

import android.content.Context;
import android.widget.Toast;
import java.io.File;

public class FirebaseHelper {

    // Đã thêm biến String metaInfo vào tham số
    public static void uploadFileToFirebase(Context context, File localFile, String metaInfo) {
        if (localFile == null || !localFile.exists()) {
            Toast.makeText(context, "File không tồn tại trên máy!", Toast.LENGTH_SHORT).show();
            return;
        }

        // Hiển thị tạm thông báo để bạn thấy code đã truyền dữ liệu qua thành công
        Toast.makeText(context, "Bắt đầu upload:\n" + localFile.getName() + "\n\n" + metaInfo, Toast.LENGTH_LONG).show();

        /* BẠN SẼ BỎ COMMENT ĐOẠN NÀY KHI CÀI FIREBASE CHÍNH THỨC:

        FirebaseStorage storage = FirebaseStorage.getInstance();
        StorageReference storageRef = storage.getReference().child("dji_media/" + localFile.getName());

        // Gói các thông tin GPS, Thời gian vào Metadata của Firebase Storage
        StorageMetadata metadata = new StorageMetadata.Builder()
                .setCustomMetadata("photo_details", metaInfo)
                .build();

        Uri fileUri = Uri.fromFile(localFile);

        // Đính kèm metadata lúc putFile
        storageRef.putFile(fileUri, metadata)
            .addOnSuccessListener(taskSnapshot -> {
                Toast.makeText(context, "Đã đẩy lên Firebase thành công!", Toast.LENGTH_SHORT).show();

                // Gợi ý: Nếu bạn dùng Firestore/Realtime DB, đây là lúc bạn đẩy "metaInfo" lên database

            })
            .addOnFailureListener(e -> {
                Toast.makeText(context, "Lỗi Firebase: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            });
        */
    }
}