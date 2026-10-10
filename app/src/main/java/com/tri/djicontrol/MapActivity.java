package com.tri.djicontrol;

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
import android.widget.Button;
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
import org.maplibre.android.style.layers.RasterLayer;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.android.style.sources.RasterSource;
import org.maplibre.android.style.sources.TileSet;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Polygon;
import org.maplibre.geojson.Point;

import java.util.ArrayList;
import java.util.List;

import dji.common.model.LocationCoordinate2D;

import dji.sdk.products.Aircraft;
import dji.sdk.sdkmanager.DJISDKManager;
import com.tri.djicontrol.firebase.FirebaseHelper;

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
    private Button btnMyLocation;
    private Button btnMapType;

    private final List<Marker> fieldMarkers = new ArrayList<>();

    private GeoJsonSource polygonSource;
    private GeoJsonSource lineSource;
    private GeoJsonSource droneSource;

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
                    Toast.makeText(MapActivity.this, "Đã đủ 4 góc. Kéo các điểm để chỉnh ruộng.", Toast.LENGTH_SHORT).show();
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
                            return true;
                        }
                        return false;
                    case MotionEvent.ACTION_UP:
                        if (draggingMarkerIndex >= 0) {
                            LatLng newPosition = mapLibreMap.getProjection().fromScreenLocation(new PointF(event.getX(), event.getY()));
                            fieldMarkers.get(draggingMarkerIndex).setPosition(newPosition);
                            updateFieldPolygon();
                            draggingMarkerIndex = -1;
                            return true;
                        }
                        return false;
                }
                return false;
            });

            startDroneTracking();
        });

        btnMapType.setOnClickListener(v -> {
            isSatellite = !isSatellite;
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
                Toast.makeText(MapActivity.this, "Vui lòng chọn đủ 4 góc ruộng!", Toast.LENGTH_SHORT).show();
                return;
            }
            updateFieldPolygon();
            if (currentAreaHa <= 0) {
                Toast.makeText(MapActivity.this, "Không thể tính diện tích khu vực!", Toast.LENGTH_SHORT).show();
                return;
            }

            double[] latitudes = new double[fieldMarkers.size()];
            double[] longitudes = new double[fieldMarkers.size()];
            for (int i = 0; i < fieldMarkers.size(); i++) {
                LatLng position = fieldMarkers.get(i).getPosition();
                latitudes[i] = position.getLatitude();
                longitudes[i] = position.getLongitude();
            }

            android.widget.EditText input = new android.widget.EditText(MapActivity.this);
            input.setHint("Nhập tên mảnh ruộng (vd: Mảnh lúa số 1)");
            input.setTextColor(android.graphics.Color.BLACK);
            input.setHintTextColor(android.graphics.Color.GRAY);
            input.setPadding(40, 20, 40, 20);

            new android.app.AlertDialog.Builder(MapActivity.this)
                    .setTitle("LƯU MẢNH RUỘNG LÊN FIREBASE")
                    .setMessage("Bạn có muốn lưu mảng ruộng này lên Firebase để sau này bay lại không?")
                    .setView(input)
                    .setPositiveButton("LƯU & XÁC NHẬN", (dialog, which) -> {
                        String fieldName = input.getText().toString().trim();
                        FirebaseHelper.saveFieldToFirestore(
                                fieldName, currentAreaHa, latitudes, longitudes, "",
                                new FirebaseHelper.FirestoreCallback() {
                                    @Override
                                    public void onSuccess(String documentId) {
                                        runOnUiThread(() -> Toast.makeText(MapActivity.this, "Đã lưu mảnh ruộng lên Firebase thành công!", Toast.LENGTH_SHORT).show());
                                    }
                                    @Override
                                    public void onFailure(String error) {
                                        runOnUiThread(() -> Toast.makeText(MapActivity.this, "Lỗi lưu Firebase: " + error, Toast.LENGTH_SHORT).show());
                                    }
                                }
                        );

                        returnResultAndFinish(latitudes, longitudes);
                    })
                    .setNegativeButton("CHỈ XÁC NHẬN", (dialog, which) -> {
                        returnResultAndFinish(latitudes, longitudes);
                    })
                    .show();
        });
    }

    private void returnResultAndFinish(double[] latitudes, double[] longitudes) {
        android.content.Intent resultIntent = new android.content.Intent();
        resultIntent.putExtra("FIELD_AREA_HA", currentAreaHa);
        resultIntent.putExtra("FIELD_LATITUDES", latitudes);
        resultIntent.putExtra("FIELD_LONGITUDES", longitudes);
        setResult(RESULT_OK, resultIntent);
        finish();
    }

    private void reloadMapStyle() {
        if (mapLibreMap == null) return;

        Style.Builder styleBuilder;
        if (isSatellite) {
            String SATELLITE_URL = "https://mt1.google.com/vt/lyrs=s&x={x}&y={y}&z={z}";
            styleBuilder = new Style.Builder()
                    .withSource(new RasterSource("sat-source", new TileSet("tileset", SATELLITE_URL), 256))
                    .withLayer(new RasterLayer("sat-layer", "sat-source"));
        } else {
            styleBuilder = new Style.Builder().fromUri("asset://osm_style.json");
        }

        mapLibreMap.setStyle(styleBuilder, style -> {
            enableLocationComponent(style);

            // 1. Polygon Vẽ Khung (Blue)
            polygonSource = new GeoJsonSource(POLYGON_SOURCE_ID);
            style.addSource(polygonSource);
            FillLayer polygonLayer = new FillLayer(POLYGON_LAYER_ID, POLYGON_SOURCE_ID);
            polygonLayer.setProperties(fillColor("#2196F3"), fillOpacity(0.30f));
            style.addLayer(polygonLayer);

            lineSource = new GeoJsonSource(LINE_SOURCE_ID);
            style.addSource(lineSource);
            LineLayer lineLayer = new LineLayer(LINE_LAYER_ID, LINE_SOURCE_ID);
            lineLayer.setProperties(lineColor("#1565C0"), lineWidth(3.0f));
            style.addLayer(lineLayer);

            // 2. Lưới đường bay quét đồng bộ từ MainActivity (Yellow)
            style.addSource(new GeoJsonSource("grid-source"));
            LineLayer gridLayer = new LineLayer("grid-layer", "grid-source");
            gridLayer.setProperties(lineColor("#FFEB3B"), lineWidth(2.0f));
            style.addLayer(gridLayer);

            // 3. Đường thực tế đã bay đồng bộ từ MainActivity (Red)
            style.addSource(new GeoJsonSource("flight-path-source"));
            LineLayer flightPathLayer = new LineLayer("flight-path-layer", "flight-path-source");
            flightPathLayer.setProperties(lineColor("#F44336"), lineWidth(4.0f));
            style.addLayer(flightPathLayer);

            // 4. Khởi tạo Drone Icon
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
                    iconSize(1.0f),
                    iconAllowOverlap(true),
                    iconIgnorePlacement(true),
                    iconRotate(Expression.get("bearing"))
            );
            style.addLayer(droneLayer);

            // 5. Home Icon
            Drawable homeDrawable = getResources().getDrawable(android.R.drawable.ic_menu_myplaces);
            Bitmap homeBitmap = Bitmap.createBitmap(
                    homeDrawable.getIntrinsicWidth() > 0 ? homeDrawable.getIntrinsicWidth() : 64,
                    homeDrawable.getIntrinsicHeight() > 0 ? homeDrawable.getIntrinsicHeight() : 64,
                    Bitmap.Config.ARGB_8888
            );
            Canvas homeCanvas = new Canvas(homeBitmap);
            homeDrawable.setBounds(0, 0, homeCanvas.getWidth(), homeCanvas.getHeight());
            homeDrawable.draw(homeCanvas);
            style.addImage("home-icon", homeBitmap);

            GeoJsonSource homeSource = new GeoJsonSource("home-source");
            style.addSource(homeSource);
            SymbolLayer homeLayer = new SymbolLayer("home-layer", "home-source");
            homeLayer.setProperties(
                    iconImage("home-icon"),
                    iconSize(0.8f),
                    iconAllowOverlap(true),
                    iconIgnorePlacement(true)
            );
            style.addLayer(homeLayer);

            // Phục hồi khung đa giác từ MainActivity.sharedFieldPoints hoặc Intent nếu có
            if (MainActivity.sharedFieldPoints != null && MainActivity.sharedFieldPoints.size() == 4 && fieldMarkers.isEmpty()) {
                for (int i = 0; i < 4; i++) {
                    addFieldPoint(MainActivity.sharedFieldPoints.get(i));
                }
            } else if (editField && oldLatitudes != null && oldLongitudes != null
                    && oldLatitudes.length == 4 && oldLongitudes.length == 4 && fieldMarkers.isEmpty()) {
                for (int i = 0; i < 4; i++) addFieldPoint(new LatLng(oldLatitudes[i], oldLongitudes[i]));
            }
            updateFieldPolygon();

            // Vẽ các đường được dùng chung từ MainActivity ngay khi Map load xong
            if (MainActivity.sharedGridPoints != null && MainActivity.sharedGridPoints.size() >= 2) {
                GeoJsonSource gs = style.getSourceAs("grid-source");
                if (gs != null) gs.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(MainActivity.sharedGridPoints)));
            }
        });
    }

    private void startDroneTracking() {
        droneRunnable = new Runnable() {
            @Override
            public void run() {
                updateDroneLocationOnMap();
                droneHandler.postDelayed(this, 200);
            }
        };
        droneHandler.post(droneRunnable);
    }

    private void updateDroneLocationOnMap() {
        // CẬP NHẬT ĐƯỜNG ĐỎ THỰC TẾ (Chia sẻ từ MainActivity)
        if (MainActivity.sharedFlightPath != null && MainActivity.sharedFlightPath.size() >= 2) {
            if (mapLibreMap != null && mapLibreMap.getStyle() != null) {
                GeoJsonSource fpSource = mapLibreMap.getStyle().getSourceAs("flight-path-source");
                if (fpSource != null) {
                    fpSource.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(MainActivity.sharedFlightPath)));
                }
            }
        }

        // CẬP NHẬT VỊ TRÍ ICON DRONE VÀ HOME POINT
        if (DJISDKManager.getInstance().getProduct() instanceof Aircraft) {
            Aircraft aircraft = (Aircraft) DJISDKManager.getInstance().getProduct();
            if (aircraft != null && aircraft.getFlightController() != null) {
                dji.common.flightcontroller.FlightControllerState state = aircraft.getFlightController().getState();
                if (state != null) {
                    LocationCoordinate2D homeLoc = state.getHomeLocation();
                    if (homeLoc != null && !Double.isNaN(homeLoc.getLatitude()) && homeLoc.getLatitude() != 0) {
                        if (mapLibreMap != null && mapLibreMap.getStyle() != null) {
                            GeoJsonSource homeSrc = mapLibreMap.getStyle().getSourceAs("home-source");
                            if (homeSrc != null) {
                                homeSrc.setGeoJson(Feature.fromGeometry(Point.fromLngLat(homeLoc.getLongitude(), homeLoc.getLatitude())));
                            }
                        }
                    }

                    if (state.getAircraftLocation() != null) {
                        double lat = state.getAircraftLocation().getLatitude();
                        double lng = state.getAircraftLocation().getLongitude();

                        if (!Double.isNaN(lat) && !Double.isNaN(lng)) {
                            double yaw = state.getAttitude().yaw;
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
        Marker marker = mapLibreMap.addMarker(new MarkerOptions().position(point).title("Góc ruộng " + (fieldMarkers.size() + 1)));
        fieldMarkers.add(marker);
        if (fieldMarkers.size() == 4) {
            updateFieldPolygon();
        }
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
            if (distance <= 35f) return i;
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
            droneHandler.removeCallbacks(droneRunnable);
        }
    }
}