package com.tri.djicontrol;

import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import org.maplibre.android.MapLibre;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.Style;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.annotations.MarkerOptions;

public class MapActivity extends AppCompatActivity {
    private MapView mapView;
    private MapLibreMap mapLibreMap;
    private Button btnConfirmTarget;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Bắt buộc khởi tạo MapLibre trước khi hiển thị layout
        MapLibre.getInstance(this);

        setContentView(R.layout.activity_map);

        mapView = findViewById(R.id.mapView);
        btnConfirmTarget = findViewById(R.id.btnConfirmTarget);

        mapView.onCreate(savedInstanceState);

        mapView.getMapAsync(mapboxMap -> {
            mapLibreMap = mapboxMap;

            // 2. Nạp style OpenStreetMap từ thư mục assets (osm_style.json)
            mapLibreMap.setStyle(new Style.Builder().fromUri("asset://osm_style.json"), style -> {
                // Bản đồ đã tải thành công, bạn có thể thiết lập vị trí mặc định (ví dụ: TP.HCM)
                mapLibreMap.setCameraPosition(
                        new org.maplibre.android.camera.CameraPosition.Builder()
                                .target(new LatLng(10.82310, 106.62970))
                                .zoom(14.0)
                                .build()
                );
            });

            // 3. Sự kiện bấm vào bản đồ để chọn điểm mục tiêu bay
            mapLibreMap.addOnMapClickListener(point -> {
                // Xóa các marker cũ nếu có và thêm marker mới tại vị trí bấm
                mapLibreMap.clear();
                mapLibreMap.addMarker(new MarkerOptions().position(point).title("Điểm mục tiêu"));

                Toast.makeText(MapActivity.this, "Đã chọn điểm: " + point.getLatitude() + ", " + point.getLongitude(), Toast.LENGTH_SHORT).show();
                return true;
            });
        });

        // 4. Nút xác nhận trả dữ liệu về màn hình chính hoặc kết thúc activity
        btnConfirmTarget.setOnClickListener(v -> {
            if (mapLibreMap.getMarkers().size() > 0) {
                LatLng target = mapLibreMap.getMarkers().get(0).getPosition();
                // Bạn có thể truyền ngược tọa độ target về MainActivity nếu muốn
                Toast.makeText(this, "Đã xác nhận điểm bay!", Toast.LENGTH_SHORT).show();
                finish();
            } else {
                Toast.makeText(this, "Vui lòng chạm lên bản đồ để chọn điểm đến trước!", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // --- CÁC HÀM VÒNG ĐỜI (LIFECYCLE) BẮT BUỘC CHO MAPLIBRE MAPVIEW ---
    @Override
    protected void onStart() {
        super.onStart();
        if (mapView != null) mapView.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mapView != null) mapView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mapView != null) mapView.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mapView != null) mapView.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mapView != null) mapView.onSaveInstanceState(outState);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (mapView != null) mapView.onLowMemory();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mapView != null) mapView.onDestroy();
    }
}