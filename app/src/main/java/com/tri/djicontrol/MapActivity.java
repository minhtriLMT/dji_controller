package com.tri.djicontrol;

import com.tri.djicontrol.ui.AppToast;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PointF;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.gson.JsonObject;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.location.LocationComponent;
import org.maplibre.android.location.LocationComponentActivationOptions;
import org.maplibre.android.location.modes.CameraMode;
import org.maplibre.android.location.modes.RenderMode;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.Style;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.annotations.Marker;
import org.maplibre.android.annotations.MarkerOptions;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.FillLayer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Polygon;
import org.maplibre.geojson.Point;

import java.util.ArrayList;
import java.util.List;

import dji.sdk.products.Aircraft;
import dji.sdk.sdkmanager.DJISDKManager;

import static org.maplibre.android.style.layers.PropertyFactory.fillColor;
import static org.maplibre.android.style.layers.PropertyFactory.fillOpacity;
import static org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap;
import static org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement;
import static org.maplibre.android.style.layers.PropertyFactory.iconImage;
import static org.maplibre.android.style.layers.PropertyFactory.iconRotate;
import static org.maplibre.android.style.layers.PropertyFactory.iconSize;
import static org.maplibre.android.style.layers.PropertyFactory.lineColor;
import static org.maplibre.android.style.layers.PropertyFactory.lineWidth;

public class MapActivity extends AppCompatActivity {

    private MapView mapView;
    private MapLibreMap mapLibreMap;
    private Button btnConfirmTarget;
    private ImageButton btnMyLocation;
    private Button btnMapType;
    private ImageButton btnUndoPoint;
    private TextView tvMapHint, tvMapArea, tvMapCorners;
    private final View[] cornerDots = new View[4];
    private static final int ACCENT = 0xFF69F0AE;

    private final List<Marker> fieldMarkers = new ArrayList<>();

    private GeoJsonSource polygonSource;
    private GeoJsonSource lineSource;
    private GeoJsonSource droneSource; // Nguồn vị trí drone

    private static final String POLYGON_SOURCE_ID = "field-polygon-source";
    private static final String POLYGON_LAYER_ID = "field-polygon-layer";
    private static final String LINE_SOURCE_ID = "field-line-source";
    private static final String LINE_LAYER_ID = "field-line-layer";
    private static final String DRONE_SOURCE_ID = "full-drone-source";
    private static final String DRONE_LAYER_ID = "full-drone-layer";

    private static final int PERMISSIONS_REQUEST_LOCATION = 1002;

    private int draggingMarkerIndex = -1;
    private double currentAreaHa = 0.0;

    private boolean isSatellite = false;
    private boolean editField;
    private double[] oldLatitudes, oldLongitudes;

    // Vòng lặp lấy vị trí drone
    private Handler droneHandler = new Handler(Looper.getMainLooper());
    private Runnable droneRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MapLibre.getInstance(this);
        setContentView(R.layout.activity_map);

        mapView = findViewById(R.id.mapView);
        btnConfirmTarget = findViewById(R.id.btnConfirmTarget);
        btnMyLocation = findViewById(R.id.btnMyLocation);
        btnMapType = findViewById(R.id.btnMapType);
        btnUndoPoint = findViewById(R.id.btnUndoPoint);
        tvMapHint = findViewById(R.id.tvMapHint);
        tvMapArea = findViewById(R.id.tvMapArea);
        tvMapCorners = findViewById(R.id.tvMapCorners);
        buildCornerDots();
        findViewById(R.id.btnMapBack).setOnClickListener(v -> finish());
        btnUndoPoint.setOnClickListener(v -> undoLastPoint());
        updateFieldUi();

        editField = getIntent().getBooleanExtra("EDIT_FIELD", false);
        oldLatitudes = getIntent().getDoubleArrayExtra("FIELD_LATITUDES");
        oldLongitudes = getIntent().getDoubleArrayExtra("FIELD_LONGITUDES");

        mapView.onCreate(savedInstanceState);
        mapView.getMapAsync(mapboxMap -> {
            mapLibreMap = mapboxMap;
            reloadMapStyle();

            mapLibreMap.setCameraPosition(
                    new org.maplibre.android.camera.CameraPosition.Builder()
                            .target(new LatLng(10.82310, 106.62970))
                            .zoom(14.0)
                            .build()
            );

            mapLibreMap.addOnMapClickListener(point -> {
                if (fieldMarkers.size() >= 4) {
                    AppToast.makeText(MapActivity.this, "Đã đủ 4 góc. Kéo các điểm để chỉnh ruộng.", Toast.LENGTH_SHORT).show();
                    return true;
                }
                addFieldPoint(point);
                return true;
            });

            mapView.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        draggingMarkerIndex = findMarkerAtScreenPosition(event.getX(), event.getY());
                        return draggingMarkerIndex >= 0;
                    case MotionEvent.ACTION_MOVE:
                        if (draggingMarkerIndex >= 0) {
                            LatLng newPosition = mapLibreMap.getProjection().fromScreenLocation(new PointF(event.getX(), event.getY()));
                            fieldMarkers.get(draggingMarkerIndex).setPosition(newPosition);
                            updateFieldPolygon();
                            updateFieldUi(); // diện tích cập nhật theo tay kéo
                            return true;
                        }
                        return false;
                    case MotionEvent.ACTION_UP:
                        if (draggingMarkerIndex >= 0) {
                            LatLng newPosition = mapLibreMap.getProjection().fromScreenLocation(new PointF(event.getX(), event.getY()));
                            fieldMarkers.get(draggingMarkerIndex).setPosition(newPosition);
                            updateFieldPolygon();
                            updateFieldUi();
                            draggingMarkerIndex = -1;
                            return true;
                        }
                        return false;
                }
                return false;
            });

            // Bắt đầu vòng lặp cập nhật icon drone
            startDroneTracking();
        });

        btnMapType.setOnClickListener(v -> {
            isSatellite = !isSatellite;
            // Nút ghi kiểu bản đồ sẽ chuyển sang khi bấm
            btnMapType.setText(isSatellite ? "Bản đồ" : "Vệ tinh");
            reloadMapStyle();
        });

        btnMyLocation.setOnClickListener(v -> {
            if (mapLibreMap != null && mapLibreMap.getStyle() != null) {
                LocationComponent locationComponent = mapLibreMap.getLocationComponent();
                if (locationComponent.isLocationComponentActivated()) {
                    Location lastKnownLocation = locationComponent.getLastKnownLocation();
                    if (lastKnownLocation != null) {
                        mapLibreMap.animateCamera(CameraUpdateFactory.newLatLngZoom(
                                new LatLng(lastKnownLocation.getLatitude(), lastKnownLocation.getLongitude()), 16.0));
                    }
                } else {
                    enableLocationComponent(mapLibreMap.getStyle());
                }
            }
        });

        btnConfirmTarget.setOnClickListener(v -> {
            if (fieldMarkers.size() < 4) {
                AppToast.makeText(MapActivity.this, "Vui lòng chọn đủ 4 góc ruộng!", Toast.LENGTH_SHORT).show();
                return;
            }
            updateFieldPolygon();
            if (currentAreaHa <= 0) {
                AppToast.makeText(MapActivity.this, "Không thể tính diện tích khu vực!", Toast.LENGTH_SHORT).show();
                return;
            }
            android.content.Intent resultIntent = new android.content.Intent();
            resultIntent.putExtra("FIELD_AREA_HA", currentAreaHa);

            double[] latitudes = new double[fieldMarkers.size()];
            double[] longitudes = new double[fieldMarkers.size()];
            for (int i = 0; i < fieldMarkers.size(); i++) {
                LatLng position = fieldMarkers.get(i).getPosition();
                latitudes[i] = position.getLatitude();
                longitudes[i] = position.getLongitude();
            }
            resultIntent.putExtra("FIELD_LATITUDES", latitudes);
            resultIntent.putExtra("FIELD_LONGITUDES", longitudes);
            setResult(RESULT_OK, resultIntent);

            finish();
        });
    }

    private void reloadMapStyle() {
        if (mapLibreMap == null) return;

        // Dùng style JSON trong assets cho cả 2 chế độ (style dựng bằng code với
        // TileSet("tileset", ...) bị đen màn hình khi bật vệ tinh). Có nền tối khi tile đang tải.
        Style.Builder styleBuilder = new Style.Builder().fromUri(
                isSatellite ? "asset://satellite_style.json" : "asset://osm_style.json");

        mapLibreMap.setStyle(styleBuilder, style -> {
            enableLocationComponent(style);

            // Các mảnh ruộng đã lưu (vẽ dưới ruộng đang chọn)
            addSavedFieldsLayer(style);

            polygonSource = new GeoJsonSource(POLYGON_SOURCE_ID);
            style.addSource(polygonSource);
            FillLayer polygonLayer = new FillLayer(POLYGON_LAYER_ID, POLYGON_SOURCE_ID);
            polygonLayer.setProperties(fillColor("#69F0AE"), fillOpacity(0.22f));
            style.addLayer(polygonLayer);

            lineSource = new GeoJsonSource(LINE_SOURCE_ID);
            style.addSource(lineSource);
            LineLayer lineLayer = new LineLayer(LINE_LAYER_ID, LINE_SOURCE_ID);
            lineLayer.setProperties(lineColor("#69F0AE"), lineWidth(3.0f),
                    org.maplibre.android.style.layers.PropertyFactory.lineJoin("round"));
            style.addLayer(lineLayer);

            // --- THÊM ICON VÀ LAYER CHO DRONE ---
            Drawable drawable = getResources().getDrawable(R.drawable.ic_drone_arrow);
            Bitmap droneBitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(droneBitmap);
            drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            drawable.draw(canvas);
            style.addImage("full-drone-icon", droneBitmap);

            droneSource = new GeoJsonSource(DRONE_SOURCE_ID);
            style.addSource(droneSource);

            SymbolLayer droneLayer = new SymbolLayer(DRONE_LAYER_ID, DRONE_SOURCE_ID);
            droneLayer.setProperties(
                    iconImage("full-drone-icon"),
                    iconSize(1.0f), // To hơn minimap 1 chút để dễ nhìn
                    iconAllowOverlap(true),
                    iconIgnorePlacement(true),
                    iconRotate(Expression.get("bearing")) // ĐỌC GÓC XOAY TỪ THUỘC TÍNH
            );
            style.addLayer(droneLayer);

            if (editField && oldLatitudes != null && oldLongitudes != null
                    && oldLatitudes.length == 4 && oldLongitudes.length == 4 && fieldMarkers.isEmpty()) {
                for (int i = 0; i < 4; i++) addFieldPoint(new LatLng(oldLatitudes[i], oldLongitudes[i]));
            }
            updateFieldPolygon();
        });
    }

    // =========================================================
    // RUỘNG ĐÃ LƯU: xanh = đã bay xong, vàng = bay dở, xám = chưa bay
    // =========================================================
    private void addSavedFieldsLayer(Style style) {
        com.tri.djicontrol.field.SavedFieldsLayer.update(this, style,
                getIntent().getStringExtra("CURRENT_FIELD_ID"), POLYGON_LAYER_ID);
    }

    private void sortAroundCenter(List<LatLng> points) {
        LatLng center = calculateCenter(points);
        points.sort((p1, p2) -> Double.compare(
                Math.atan2(p1.getLatitude() - center.getLatitude(), p1.getLongitude() - center.getLongitude()),
                Math.atan2(p2.getLatitude() - center.getLatitude(), p2.getLongitude() - center.getLongitude())));
    }

    // Hàm liên tục đọc tọa độ và góc xoay từ Drone đưa lên bản đồ Lớn
    private void startDroneTracking() {
        droneRunnable = new Runnable() {
            @Override
            public void run() {
                updateDroneLocationOnMap();
                droneHandler.postDelayed(this, 200); // Cập nhật 5 lần / giây
            }
        };
        droneHandler.post(droneRunnable);
    }

    private void updateDroneLocationOnMap() {
        if (DJISDKManager.getInstance().getProduct() instanceof Aircraft) {
            Aircraft aircraft = (Aircraft) DJISDKManager.getInstance().getProduct();
            if (aircraft != null && aircraft.getFlightController() != null) {
                dji.common.flightcontroller.FlightControllerState state = aircraft.getFlightController().getState();
                if (state != null && state.getAircraftLocation() != null) {
                    double lat = state.getAircraftLocation().getLatitude();
                    double lng = state.getAircraftLocation().getLongitude();

                    if (!Double.isNaN(lat) && !Double.isNaN(lng)) {
                        double yaw = state.getAttitude().yaw; // Góc mũi drone

                        JsonObject properties = new JsonObject();
                        properties.addProperty("bearing", yaw);

                        Feature feature = Feature.fromGeometry(Point.fromLngLat(lng, lat), properties);

                        if (mapLibreMap != null && mapLibreMap.getStyle() != null) {
                            GeoJsonSource source = mapLibreMap.getStyle().getSourceAs(DRONE_SOURCE_ID);
                            if (source != null) {
                                source.setGeoJson(feature);
                            }
                        }
                    }
                }
            }
        }
    }

    @SuppressWarnings({"MissingPermission"})
    private void enableLocationComponent(@NonNull Style loadedMapStyle) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            LocationComponent locationComponent = mapLibreMap.getLocationComponent();
            locationComponent.activateLocationComponent(LocationComponentActivationOptions.builder(this, loadedMapStyle).build());
            locationComponent.setLocationComponentEnabled(true);
            locationComponent.setCameraMode(CameraMode.TRACKING);
            locationComponent.setRenderMode(RenderMode.COMPASS);
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSIONS_REQUEST_LOCATION);
        }
    }

    private void addFieldPoint(LatLng point) {
        int no = fieldMarkers.size() + 1;
        Marker marker = mapLibreMap.addMarker(new MarkerOptions()
                .position(point)
                .title("Góc ruộng " + no)
                .icon(cornerIcon(no)));
        fieldMarkers.add(marker);
        if (fieldMarkers.size() == 4) {
            updateFieldPolygon();
        }
        updateFieldUi();
    }

    /** Xóa góc vừa đặt gần nhất. */
    private void undoLastPoint() {
        if (fieldMarkers.isEmpty() || mapLibreMap == null) return;
        Marker last = fieldMarkers.remove(fieldMarkers.size() - 1);
        mapLibreMap.removeMarker(last);
        // Chưa đủ 4 góc -> xóa vùng ruộng đang vẽ
        org.maplibre.geojson.FeatureCollection empty =
                org.maplibre.geojson.FeatureCollection.fromFeatures(new ArrayList<>());
        if (polygonSource != null) polygonSource.setGeoJson(empty);
        if (lineSource != null) lineSource.setGeoJson(empty);
        currentAreaHa = 0;
        updateFieldUi();
    }

    /** Ghim góc ruộng: vòng tròn xanh viền trắng có số thứ tự. */
    private org.maplibre.android.annotations.Icon cornerIcon(int number) {
        float d = getResources().getDisplayMetrics().density;
        int size = (int) (30 * d);
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        float r = size / 2f;
        p.setColor(0x66000000);
        c.drawCircle(r, r + d, r - d, p);              // bóng
        p.setColor(0xFFFFFFFF);
        c.drawCircle(r, r, r - 2 * d, p);              // viền trắng
        p.setColor(ACCENT);
        c.drawCircle(r, r, r - 4.5f * d, p);           // lõi xanh
        p.setColor(0xFF0F2A1C);
        p.setTextSize(13 * d);
        p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        p.setTextAlign(android.graphics.Paint.Align.CENTER);
        android.graphics.Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(String.valueOf(number), r, r - (fm.ascent + fm.descent) / 2f, p);
        return org.maplibre.android.annotations.IconFactory.getInstance(this).fromBitmap(bmp);
    }

    private void buildCornerDots() {
        android.widget.LinearLayout row = findViewById(R.id.mapCornerDots);
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < 4; i++) {
            View dot = new View(this);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams((int) (18 * d), (int) (6 * d));
            if (i > 0) lp.setMarginStart((int) (4 * d));
            dot.setLayoutParams(lp);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setCornerRadius(3 * d);
            dot.setBackground(g);
            row.addView(dot);
            cornerDots[i] = dot;
        }
    }

    /** Cập nhật hướng dẫn, số góc, diện tích và trạng thái các nút. */
    private void updateFieldUi() {
        int n = fieldMarkers.size();
        for (int i = 0; i < 4; i++) {
            if (cornerDots[i] != null) {
                ((android.graphics.drawable.GradientDrawable) cornerDots[i].getBackground())
                        .setColor(i < n ? ACCENT : 0x33FFFFFF);
            }
        }
        tvMapCorners.setText(n + " / 4 góc");
        if (n < 4) {
            tvMapHint.setText("Chạm lên bản đồ để đặt góc " + (n + 1) + " / 4");
            tvMapArea.setText("— ha");
        } else {
            tvMapHint.setText("Kéo các ghim để chỉnh lại góc ruộng");
            tvMapArea.setText(String.format(java.util.Locale.US, "%.2f ha", currentAreaHa));
        }
        btnUndoPoint.setEnabled(n > 0);
        btnUndoPoint.setAlpha(n > 0 ? 1f : 0.45f);
        btnConfirmTarget.setEnabled(n == 4 && currentAreaHa > 0);
    }

    private void updateFieldPolygon() {
        if (fieldMarkers.size() < 4 || polygonSource == null || lineSource == null) return;
        List<LatLng> points = new ArrayList<>();
        for (Marker marker : fieldMarkers) points.add(marker.getPosition());

        LatLng center = calculateCenter(points);
        points.sort((p1, p2) -> {
            double angle1 = Math.atan2(p1.getLatitude() - center.getLatitude(), p1.getLongitude() - center.getLongitude());
            double angle2 = Math.atan2(p2.getLatitude() - center.getLatitude(), p2.getLongitude() - center.getLongitude());
            return Double.compare(angle1, angle2);
        });

        List<org.maplibre.geojson.Point> polygonPoints = new ArrayList<>();
        for (LatLng point : points) polygonPoints.add(org.maplibre.geojson.Point.fromLngLat(point.getLongitude(), point.getLatitude()));
        polygonPoints.add(polygonPoints.get(0));

        List<List<Point>> rings = new ArrayList<>(); rings.add(polygonPoints);
        polygonSource.setGeoJson(Feature.fromGeometry(Polygon.fromLngLats(rings)));
        lineSource.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(polygonPoints)));
        currentAreaHa = calculatePolygonAreaHa(points);
    }

    private int findMarkerAtScreenPosition(float x, float y) {
        if (mapLibreMap == null) return -1;
        for (int i = 0; i < fieldMarkers.size(); i++) {
            Marker marker = fieldMarkers.get(i);
            PointF screenPoint = mapLibreMap.getProjection().toScreenLocation(marker.getPosition());
            float dx = x - screenPoint.x;
            float dy = y - screenPoint.y;
            float distance = (float) Math.sqrt(dx * dx + dy * dy);
            // Vùng chạm ~28dp quanh ghim cho dễ kéo bằng ngón tay
            if (distance <= 28f * getResources().getDisplayMetrics().density) return i;
        }
        return -1;
    }

    private LatLng calculateCenter(List<LatLng> points) {
        double lat = 0, lng = 0;
        for (LatLng point : points) { lat += point.getLatitude(); lng += point.getLongitude(); }
        return new LatLng(lat / points.size(), lng / points.size());
    }

    private double calculatePolygonAreaHa(List<LatLng> points) {
        final double EARTH_RADIUS = 6371000.0;
        double area = 0.0;
        for (int i = 0; i < points.size(); i++) {
            LatLng p1 = points.get(i);
            LatLng p2 = points.get((i + 1) % points.size());
            double lat1 = Math.toRadians(p1.getLatitude());
            double lat2 = Math.toRadians(p2.getLatitude());
            double lon1 = Math.toRadians(p1.getLongitude());
            double lon2 = Math.toRadians(p2.getLongitude());
            area += (lon2 - lon1) * (2 + Math.sin(lat1) + Math.sin(lat2));
        }
        return Math.abs(area * EARTH_RADIUS * EARTH_RADIUS / 2.0) / 10000.0;
    }

    @Override
    protected void onStart() { super.onStart(); if (mapView != null) mapView.onStart(); }
    @Override
    protected void onResume() { super.onResume(); if (mapView != null) mapView.onResume(); }
    @Override
    protected void onPause() { super.onPause(); if (mapView != null) mapView.onPause(); }
    @Override
    protected void onStop() { super.onStop(); if (mapView != null) mapView.onStop(); }
    @Override
    protected void onSaveInstanceState(Bundle outState) { super.onSaveInstanceState(outState); if (mapView != null) mapView.onSaveInstanceState(outState); }
    @Override
    public void onLowMemory() { super.onLowMemory(); if (mapView != null) mapView.onLowMemory(); }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mapView != null) mapView.onDestroy();
        if (droneHandler != null && droneRunnable != null) {
            droneHandler.removeCallbacks(droneRunnable); // Dừng vòng lặp khi thoát Map
        }
    }
}