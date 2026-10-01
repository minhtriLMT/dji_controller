package com.tri.djicontrol;

import android.graphics.PointF;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.maplibre.android.MapLibre;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.Style;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.annotations.Marker;
import org.maplibre.android.annotations.MarkerOptions;
import org.maplibre.android.style.layers.FillLayer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Polygon;

import java.util.ArrayList;
import java.util.List;

import org.maplibre.geojson.Point;


import static org.maplibre.android.style.layers.PropertyFactory.fillColor;
import static org.maplibre.android.style.layers.PropertyFactory.fillOpacity;
import static org.maplibre.android.style.layers.PropertyFactory.lineColor;
import static org.maplibre.android.style.layers.PropertyFactory.lineWidth;

public class MapActivity extends AppCompatActivity {

    private MapView mapView;
    private MapLibreMap mapLibreMap;
    private Button btnConfirmTarget;

    private final List<Marker> fieldMarkers = new ArrayList<>();

    private GeoJsonSource polygonSource;
    private GeoJsonSource lineSource;

    private static final String POLYGON_SOURCE_ID = "field-polygon-source";
    private static final String POLYGON_LAYER_ID = "field-polygon-layer";

    private static final String LINE_SOURCE_ID = "field-line-source";
    private static final String LINE_LAYER_ID = "field-line-layer";

    private int draggingMarkerIndex = -1;

    private double currentAreaHa = 0.0;
    private boolean isSatelliteStyle = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        MapLibre.getInstance(this);

        setContentView(R.layout.activity_map);
        Button btnToggleSatellite = findViewById(R.id.btnToggleSatellite);
        btnToggleSatellite.setOnClickListener(v -> {
            if (mapLibreMap != null) {
                isSatelliteStyle = !isSatelliteStyle;
                String styleUrl = isSatelliteStyle ? "asset://satellite_style.json" : "asset://osm_style.json";

                // Load lại style
                mapLibreMap.setStyle(new Style.Builder().fromUri(styleUrl), style -> {
                    // Giữ lại các layer polygon và line nếu cần thiết
                });
            }
        });

        mapView = findViewById(R.id.mapView);
        btnConfirmTarget = findViewById(R.id.btnConfirmTarget);
        boolean editField = getIntent().getBooleanExtra(
                "EDIT_FIELD",
                false
        );

        double[] oldLatitudes =
                getIntent().getDoubleArrayExtra(
                        "FIELD_LATITUDES"
                );

        double[] oldLongitudes =
                getIntent().getDoubleArrayExtra(
                        "FIELD_LONGITUDES"
                );
        mapView.onCreate(savedInstanceState);

        mapView.getMapAsync(mapboxMap -> {

            mapLibreMap = mapboxMap;

            mapLibreMap.setStyle(
                    new Style.Builder().fromUri("asset://osm_style.json"),
                    style -> {

                        mapLibreMap.setCameraPosition(
                                new org.maplibre.android.camera.CameraPosition.Builder()
                                        .target(new LatLng(10.82310, 106.62970))
                                        .zoom(14.0)
                                        .build()
                        );

                        // =========================
                        // SOURCE VẼ POLYGON
                        // =========================

                        polygonSource = new GeoJsonSource(
                                POLYGON_SOURCE_ID
                        );

                        style.addSource(polygonSource);

                        FillLayer polygonLayer = new FillLayer(
                                POLYGON_LAYER_ID,
                                POLYGON_SOURCE_ID
                        );

                        polygonLayer.setProperties(
                                fillColor("#2196F3"),
                                fillOpacity(0.30f)
                        );

                        style.addLayer(polygonLayer);

                        // =========================
                        // SOURCE VẼ ĐƯỜNG VIỀN
                        // =========================

                        lineSource = new GeoJsonSource(
                                LINE_SOURCE_ID
                        );

                        style.addSource(lineSource);

                        LineLayer lineLayer = new LineLayer(
                                LINE_LAYER_ID,
                                LINE_SOURCE_ID
                        );

                        lineLayer.setProperties(
                                lineColor("#1565C0"),
                                lineWidth(3.0f)
                        );

                        style.addLayer(lineLayer);

                         // =========================
                         // KHÔI PHỤC KHU VỰC CŨ
                        // =========================

                        if (editField
                                && oldLatitudes != null
                                && oldLongitudes != null
                                && oldLatitudes.length == 4
                                && oldLongitudes.length == 4) {

                            for (int i = 0; i < 4; i++) {

                                LatLng point = new LatLng(
                                        oldLatitudes[i],
                                        oldLongitudes[i]
                                );

                                addFieldPoint(point);
                            }

                            updateFieldPolygon();
                        }
                    }
            );

            // =========================
            // CHẠM BẢN ĐỒ
            // =========================

            mapLibreMap.addOnMapClickListener(point -> {

                if (fieldMarkers.size() >= 4) {

                    Toast.makeText(
                            MapActivity.this,
                            "Đã đủ 4 góc. Kéo các điểm để chỉnh ruộng.",
                            Toast.LENGTH_SHORT
                    ).show();

                    return true;
                }

                addFieldPoint(point);

                return true;
            });

            // =========================
            // KÉO 4 ĐIỂM
            // =========================

            mapView.setOnTouchListener((v, event) -> {

                switch (event.getAction()) {

                    case MotionEvent.ACTION_DOWN:

                        draggingMarkerIndex =
                                findMarkerAtScreenPosition(
                                        event.getX(),
                                        event.getY()
                                );

                        if (draggingMarkerIndex >= 0) {
                            return true;
                        }

                        return false;

                    case MotionEvent.ACTION_MOVE:

                        if (draggingMarkerIndex >= 0) {

                            LatLng newPosition =
                                    mapLibreMap.getProjection()
                                            .fromScreenLocation(
                                                    new PointF(
                                                            event.getX(),
                                                            event.getY()
                                                    )
                                            );

                            fieldMarkers
                                    .get(draggingMarkerIndex)
                                    .setPosition(newPosition);

                            updateFieldPolygon();

                            return true;
                        }

                        return false;

                    case MotionEvent.ACTION_UP:

                        if (draggingMarkerIndex >= 0) {

                            LatLng newPosition =
                                    mapLibreMap.getProjection()
                                            .fromScreenLocation(
                                                    new PointF(
                                                            event.getX(),
                                                            event.getY()
                                                    )
                                            );

                            fieldMarkers
                                    .get(draggingMarkerIndex)
                                    .setPosition(newPosition);

                            updateFieldPolygon();

                            draggingMarkerIndex = -1;

                            return true;
                        }

                        return false;
                }

                return false;
            });
        });

        // =========================
        // XÁC NHẬN
        // =========================

        btnConfirmTarget.setOnClickListener(v -> {

            if (fieldMarkers.size() < 4) {

                Toast.makeText(
                        MapActivity.this,
                        "Vui lòng chọn đủ 4 góc ruộng!",
                        Toast.LENGTH_SHORT
                ).show();

                return;
            }

            updateFieldPolygon();

            if (currentAreaHa <= 0) {

                Toast.makeText(
                        MapActivity.this,
                        "Không thể tính diện tích khu vực!",
                        Toast.LENGTH_SHORT
                ).show();

                return;
            }

            // =========================
            // TRẢ DIỆN TÍCH VỀ MAINACTIVITY
            // =========================

            android.content.Intent resultIntent =
                    new android.content.Intent();

            resultIntent.putExtra(
                    "FIELD_AREA_HA",
                    currentAreaHa
            );
// =========================
// TRẢ 4 TỌA ĐỘ VỀ MAINACTIVITY
// =========================

            double[] latitudes = new double[fieldMarkers.size()];
            double[] longitudes = new double[fieldMarkers.size()];

            for (int i = 0; i < fieldMarkers.size(); i++) {

                LatLng position = fieldMarkers.get(i).getPosition();

                latitudes[i] = position.getLatitude();
                longitudes[i] = position.getLongitude();
            }

            resultIntent.putExtra(
                    "FIELD_LATITUDES",
                    latitudes
            );

            resultIntent.putExtra(
                    "FIELD_LONGITUDES",
                    longitudes
            );
            setResult(
                    RESULT_OK,
                    resultIntent
            );

            Toast.makeText(
                    MapActivity.this,
                    String.format(
                            java.util.Locale.US,
                            "Đã xác nhận %.2f ha",
                            currentAreaHa
                    ),
                    Toast.LENGTH_SHORT
            ).show();

            finish();
        });
    }

    // =====================================================
    // THÊM 1 GÓC RUỘNG
    // =====================================================

    private void addFieldPoint(LatLng point) {

        Marker marker = mapLibreMap.addMarker(
                new MarkerOptions()
                        .position(point)
                        .title("Góc ruộng " + (fieldMarkers.size() + 1))
        );

        fieldMarkers.add(marker);

        if (fieldMarkers.size() == 1) {

            Toast.makeText(
                    this,
                    "Đã chọn góc 1/4",
                    Toast.LENGTH_SHORT
            ).show();

        } else if (fieldMarkers.size() == 2) {

            Toast.makeText(
                    this,
                    "Đã chọn góc 2/4",
                    Toast.LENGTH_SHORT
            ).show();

        } else if (fieldMarkers.size() == 3) {

            Toast.makeText(
                    this,
                    "Đã chọn góc 3/4",
                    Toast.LENGTH_SHORT
            ).show();

        } else if (fieldMarkers.size() == 4) {

            updateFieldPolygon();

            Toast.makeText(
                    this,
                    "Đã đủ 4 góc. Có thể kéo các điểm để chỉnh.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    // =====================================================
    // CẬP NHẬT POLYGON
    // =====================================================

    private void updateFieldPolygon() {

        if (fieldMarkers.size() < 4) {
            return;
        }

        List<LatLng> points = new ArrayList<>();

        for (Marker marker : fieldMarkers) {
            points.add(marker.getPosition());
        }

        // Sắp xếp các điểm theo thứ tự quanh tâm
        LatLng center = calculateCenter(points);

        points.sort((p1, p2) -> {

            double angle1 = Math.atan2(
                    p1.getLatitude() - center.getLatitude(),
                    p1.getLongitude() - center.getLongitude()
            );

            double angle2 = Math.atan2(
                    p2.getLatitude() - center.getLatitude(),
                    p2.getLongitude() - center.getLongitude()
            );

            return Double.compare(angle1, angle2);
        });

        List<org.maplibre.geojson.Point> polygonPoints =
                new ArrayList<>();

        for (LatLng point : points) {

            polygonPoints.add(
                    org.maplibre.geojson.Point.fromLngLat(
                            point.getLongitude(),
                            point.getLatitude()
                    )
            );
        }

        polygonPoints.add(polygonPoints.get(0));

        List<List<Point>> rings = new ArrayList<>();
        rings.add(polygonPoints);

        Polygon polygon = Polygon.fromLngLats(rings);

        polygonSource.setGeoJson(
                Feature.fromGeometry(polygon)
        );

        // =========================
        // ĐƯỜNG VIỀN
        // =========================

        LineString lineString =
                LineString.fromLngLats(polygonPoints);

        lineSource.setGeoJson(
                Feature.fromGeometry(lineString)
        );

        // =========================
        // TÍNH DIỆN TÍCH
        // =========================

        currentAreaHa =
                calculatePolygonAreaHa(points);
    }

    // =====================================================
    // TÌM MARKER GẦN VỊ TRÍ NGÓN TAY
    // =====================================================

    private int findMarkerAtScreenPosition(
            float x,
            float y
    ) {

        if (mapLibreMap == null) {
            return -1;
        }

        for (int i = 0; i < fieldMarkers.size(); i++) {

            Marker marker = fieldMarkers.get(i);

            PointF screenPoint =
                    mapLibreMap.getProjection()
                            .toScreenLocation(
                                    marker.getPosition()
                            );

            float dx = x - screenPoint.x;
            float dy = y - screenPoint.y;

            float distance =
                    (float) Math.sqrt(
                            dx * dx + dy * dy
                    );

            // Vùng bắt điểm khoảng 35 pixel
            if (distance <= 35f) {
                return i;
            }
        }

        return -1;
    }

    // =====================================================
    // TÍNH TÂM 4 ĐIỂM
    // =====================================================

    private LatLng calculateCenter(
            List<LatLng> points
    ) {

        double lat = 0;
        double lng = 0;

        for (LatLng point : points) {

            lat += point.getLatitude();
            lng += point.getLongitude();
        }

        return new LatLng(
                lat / points.size(),
                lng / points.size()
        );
    }

    // =====================================================
    // TÍNH DIỆN TÍCH POLYGON
    // ĐƠN VỊ: HECTARE
    // =====================================================

    private double calculatePolygonAreaHa(
            List<LatLng> points
    ) {

        final double EARTH_RADIUS = 6371000.0;

        double area = 0.0;

        for (int i = 0; i < points.size(); i++) {

            LatLng p1 = points.get(i);

            LatLng p2 =
                    points.get(
                            (i + 1) % points.size()
                    );

            double lat1 =
                    Math.toRadians(
                            p1.getLatitude()
                    );

            double lat2 =
                    Math.toRadians(
                            p2.getLatitude()
                    );

            double lon1 =
                    Math.toRadians(
                            p1.getLongitude()
                    );

            double lon2 =
                    Math.toRadians(
                            p2.getLongitude()
                    );

            area +=
                    (lon2 - lon1)
                            * (2
                            + Math.sin(lat1)
                            + Math.sin(lat2));
        }

        area =
                Math.abs(
                        area
                                * EARTH_RADIUS
                                * EARTH_RADIUS
                                / 2.0
                );

        // m² -> ha
        return area / 10000.0;
    }

    // =====================================================
    // LIFECYCLE MAPLIBRE
    // =====================================================

    @Override
    protected void onStart() {

        super.onStart();

        if (mapView != null) {
            mapView.onStart();
        }
    }

    @Override
    protected void onResume() {

        super.onResume();

        if (mapView != null) {
            mapView.onResume();
        }
    }

    @Override
    protected void onPause() {

        super.onPause();

        if (mapView != null) {
            mapView.onPause();
        }
    }

    @Override
    protected void onStop() {

        super.onStop();

        if (mapView != null) {
            mapView.onStop();
        }
    }

    @Override
    protected void onSaveInstanceState(
            Bundle outState
    ) {

        super.onSaveInstanceState(outState);

        if (mapView != null) {
            mapView.onSaveInstanceState(outState);
        }
    }

    @Override
    public void onLowMemory() {

        super.onLowMemory();

        if (mapView != null) {
            mapView.onLowMemory();
        }
    }

    @Override
    protected void onDestroy() {

        super.onDestroy();

        if (mapView != null) {
            mapView.onDestroy();
        }
    }
}