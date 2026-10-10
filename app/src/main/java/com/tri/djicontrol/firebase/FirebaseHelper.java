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

    public interface FirestoreCallback {
        void onSuccess(String documentId);
        void onFailure(String error);
    }

    // Đưa dữ liệu lộ trình và hình ảnh lên Firebase dưới dạng NoSQL (Cloud Firestore)
    public static void saveMissionDataToFirestore(String missionId, double areaHa, float altitude, int waypointCount, String imageUrl, FirestoreCallback callback) {
        com.google.firebase.firestore.FirebaseFirestore db = com.google.firebase.firestore.FirebaseFirestore.getInstance();
        java.util.Map<String, Object> missionData = new java.util.HashMap<>();
        missionData.put("missionId", missionId);
        missionData.put("areaHa", areaHa);
        missionData.put("altitude", altitude);
        missionData.put("waypointCount", waypointCount);
        missionData.put("imageUrl", imageUrl != null ? imageUrl : "");
        missionData.put("timestamp", System.currentTimeMillis());

        db.collection("dji_missions")
                .document(missionId)
                .set(missionData)
                .addOnSuccessListener(aVoid -> {
                    if (callback != null) callback.onSuccess(missionId);
                })
                .addOnFailureListener(e -> {
                    if (callback != null) callback.onFailure(e.getMessage());
                });
    }

    public static class SavedFieldItem {
        public String fieldId;
        public String fieldName;
        public double areaHa;
        public java.util.List<Double> latitudes;
        public java.util.List<Double> longitudes;
        public String imageUrl;
        public long timestamp;
    }

    public interface FieldsCallback {
        void onSuccess(java.util.List<SavedFieldItem> fields);
        void onFailure(String error);
    }

    // Lưu mảng ruộng đã khoanh vùng lên Firebase Firestore (NoSQL)
    public static void saveFieldToFirestore(String fieldName, double areaHa, double[] latitudes, double[] longitudes, String imageUrl, FirestoreCallback callback) {
        com.google.firebase.firestore.FirebaseFirestore db = com.google.firebase.firestore.FirebaseFirestore.getInstance();
        String fieldId = "field_" + System.currentTimeMillis();
        java.util.Map<String, Object> fieldData = new java.util.HashMap<>();
        fieldData.put("fieldId", fieldId);
        fieldData.put("fieldName", fieldName != null && !fieldName.isEmpty() ? fieldName : ("Mảnh ruộng " + System.currentTimeMillis()));
        fieldData.put("areaHa", areaHa);
        fieldData.put("imageUrl", imageUrl != null ? imageUrl : "");

        java.util.List<Double> lats = new java.util.ArrayList<>();
        java.util.List<Double> lngs = new java.util.ArrayList<>();
        for (double lat : latitudes) lats.add(lat);
        for (double lng : longitudes) lngs.add(lng);

        fieldData.put("latitudes", lats);
        fieldData.put("longitudes", lngs);
        fieldData.put("timestamp", System.currentTimeMillis());

        db.collection("dji_saved_fields")
                .document(fieldId)
                .set(fieldData)
                .addOnSuccessListener(aVoid -> {
                    if (callback != null) callback.onSuccess(fieldId);
                })
                .addOnFailureListener(e -> {
                    if (callback != null) callback.onFailure(e.getMessage());
                });
    }

    // Lấy danh sách các mảng ruộng đã lưu từ Firebase Firestore (NoSQL)
    public static void getSavedFieldsFromFirestore(FieldsCallback callback) {
        com.google.firebase.firestore.FirebaseFirestore db = com.google.firebase.firestore.FirebaseFirestore.getInstance();
        db.collection("dji_saved_fields")
                .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    java.util.List<SavedFieldItem> list = new java.util.ArrayList<>();
                    for (com.google.firebase.firestore.DocumentSnapshot doc : queryDocumentSnapshots) {
                        SavedFieldItem item = new SavedFieldItem();
                        item.fieldId = doc.getString("fieldId");
                        item.fieldName = doc.getString("fieldName");
                        Double area = doc.getDouble("areaHa");
                        item.areaHa = area != null ? area : 0.0;
                        item.latitudes = (java.util.List<Double>) doc.get("latitudes");
                        item.longitudes = (java.util.List<Double>) doc.get("longitudes");
                        item.imageUrl = doc.getString("imageUrl");
                        Long ts = doc.getLong("timestamp");
                        item.timestamp = ts != null ? ts : 0L;
                        list.add(item);
                    }
                    callback.onSuccess(list);
                })
                .addOnFailureListener(e -> callback.onFailure(e.getMessage()));
    }

    // Xóa mảnh ruộng đã lưu trên Firebase Firestore (NoSQL)
    public static void deleteFieldFromFirestore(String fieldId, FirestoreCallback callback) {
        com.google.firebase.firestore.FirebaseFirestore db = com.google.firebase.firestore.FirebaseFirestore.getInstance();
        db.collection("dji_saved_fields")
                .document(fieldId)
                .delete()
                .addOnSuccessListener(aVoid -> {
                    if (callback != null) callback.onSuccess(fieldId);
                })
                .addOnFailureListener(e -> {
                    if (callback != null) callback.onFailure(e.getMessage());
                });
    }
}