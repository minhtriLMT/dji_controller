package com.tri.djicontrol;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.Point;

import static org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap;
import static org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement;
import static org.maplibre.android.style.layers.PropertyFactory.iconImage;
import static org.maplibre.android.style.layers.PropertyFactory.iconSize;

import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.TextureView;
import android.view.MotionEvent;
import android.widget.Button;
import android.graphics.Color;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.view.WindowManager;
import android.app.Dialog;
import android.widget.FrameLayout;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import org.maplibre.android.MapLibre;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.geometry.LatLng;

import org.maplibre.geojson.LineString;
import org.maplibre.android.style.layers.LineLayer;
import static org.maplibre.android.style.layers.PropertyFactory.lineColor;
import static org.maplibre.android.style.layers.PropertyFactory.lineWidth;

//import map

import android.graphics.BitmapFactory;



import static org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap;
import static org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement;
import static org.maplibre.android.style.layers.PropertyFactory.iconImage;
import static org.maplibre.android.style.layers.PropertyFactory.iconSize;

import com.tri.djicontrol.connection.DJIConnectionManager;
import com.tri.djicontrol.flight.FlightMissionManager;

import java.io.File;
import java.util.List;
import java.util.ArrayList;

import dji.common.camera.SettingsDefinitions;
import dji.common.error.DJIError;
import dji.sdk.airlink.AirLink;
import dji.sdk.battery.Battery;
import dji.sdk.camera.Camera;
import dji.sdk.camera.VideoFeeder;
import dji.sdk.codec.DJICodecManager;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.media.MediaFile;
import dji.sdk.media.MediaManager;
import dji.sdk.media.DownloadListener;
import dji.sdk.products.Aircraft;
import dji.sdk.sdkmanager.DJISDKManager;

import java.util.Locale;

public class MainActivity extends AppCompatActivity implements TextureView.SurfaceTextureListener {
    private TextView tvStatus, tvGpsAndSdStatus, tvTelemetry, tvPhotoCount;

    private Button btnCaptureManual, btnViewPhotos, btnLockRoute , btnVideoPhoto;

    private FrameLayout cameraModePicker;
    private TextView tvCameraVideo;
    private TextView tvCameraPhoto,tvVideoTimer;
    private float cameraSwipeStartY;

    private LinearLayout cameraControlPanel;
    private SeekBar seekBarGimbal;

    private FlightMissionManager missionManager;
    private TextureView videoSurface;
    private DJICodecManager codecManager;

    private String sdStatusStr = "Thẻ: Sẵn sàng";
    private int batteryPercent = 0;
    private int rcSignalPercent = 0;
    private int photoCount = 0;
    private boolean isHomePointSet = false;

    private boolean isCurrentModePhoto = true;
    private boolean isRecordingVideo = false;
    private boolean isVideoPaused = false;

    private long videoStartTime = 0;
    private long videoPausedTime = 0;
    private long videoPauseStartTime = 0;
    private Handler videoTimerHandler = new Handler(Looper.getMainLooper());

    private Runnable videoTimerRunnable;
    private View miniMap;
    private MapView miniMapView;
    private MapLibreMap miniMapLibreMap;
    private GeoJsonSource droneSource;
    private LatLng lastDroneLocation;

    private GeoJsonSource flightPathSource;
    private final List<Point> flightPathPoints = new ArrayList<>();
    private static final String FLIGHT_PATH_SOURCE_ID = "flight-path-source";
    private static final String FLIGHT_PATH_LAYER_ID = "flight-path-layer";

    private static final int REQUEST_FIELD_MAP = 1001;
    private TextView tvFieldArea;
    private double fieldAreaHa = 0.0;
    private final List<LatLng> fieldPoints = new ArrayList<>();
    private Button btnReturnStop;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //full màn hình
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
        // Khởi tạo MapLibre
        MapLibre.getInstance(this);

        setContentView(R.layout.activity_main);
        // =========================
        // RETURN TO HOME
        // =========================
        btnReturnStop = findViewById(R.id.btnReturnStop);
        btnReturnStop.setOnClickListener(v -> {

            if (missionManager != null) {

                missionManager.startReturnToHome();

                Toast.makeText(
                        MainActivity.this,
                        "ĐANG THỰC HIỆN RTH!",
                        Toast.LENGTH_SHORT
                ).show();
            }
        });
        // =========================
        // MAP NHỎ TRÊN MÀN HÌNH CHÍNH
        // =========================
        miniMap = findViewById(R.id.miniMap);
        miniMapView = findViewById(R.id.miniMapView);

        // Khởi tạo MapView
        miniMapView.onCreate(savedInstanceState);

        miniMapView.getMapAsync(mapLibreMap -> {

            miniMapLibreMap = mapLibreMap;

            // Load OpenStreetMap
            miniMapLibreMap.setStyle(
                    new Style.Builder().fromUri("asset://osm_style.json"),
                    style -> {

                        // Vị trí mặc định ban đầu
                        LatLng droneLocation = new LatLng(
                                10.82310,
                                106.62970
                        );

                        // Đưa camera tới vị trí drone
                        miniMapLibreMap.setCameraPosition(
                                new org.maplibre.android.camera.CameraPosition.Builder()
                                        .target(droneLocation)
                                        .zoom(13.0)
                                        .build()
                        );
// =========================
// ICON DRONE TRÊN MINI MAP
// =========================

                        Drawable drawable = getResources().getDrawable(
                                R.drawable.ic_drone_arrow
                        );

                        Bitmap droneBitmap = Bitmap.createBitmap(
                                drawable.getIntrinsicWidth(),
                                drawable.getIntrinsicHeight(),
                                Bitmap.Config.ARGB_8888
                        );

                        Canvas canvas = new Canvas(droneBitmap);

                        drawable.setBounds(
                                0,
                                0,
                                canvas.getWidth(),
                                canvas.getHeight()
                        );

                        drawable.draw(canvas);

                        style.addImage(
                                "drone-icon",
                                droneBitmap
                        );

                        droneSource = new GeoJsonSource(
                                "drone-source",
                                Feature.fromGeometry(
                                        Point.fromLngLat(
                                                droneLocation.getLongitude(),
                                                droneLocation.getLatitude()
                                        )
                                )
                        );

                        style.addSource(droneSource);

                        SymbolLayer droneLayer = new SymbolLayer(
                                "drone-layer",
                                "drone-source"
                        );

                        droneLayer.setProperties(
                                iconImage("drone-icon"),
                                iconSize(0.7f),
                                iconAllowOverlap(true),
                                iconIgnorePlacement(true)
                        );

                        style.addLayer(droneLayer);

                        flightPathSource = new GeoJsonSource(
                                FLIGHT_PATH_SOURCE_ID
                        );

                        style.addSource(flightPathSource);

                        LineLayer flightPathLayer = new LineLayer(
                                FLIGHT_PATH_LAYER_ID,
                                FLIGHT_PATH_SOURCE_ID
                        );

                        flightPathLayer.setProperties(
                                lineColor("#FFEB3B"),
                                lineWidth(3.0f)
                        );

                        style.addLayer(flightPathLayer);
                    }
            );

            // =========================
            // BẤM MAP NHỎ -> MAP LỚN
            // =========================
            miniMapLibreMap.addOnMapClickListener(point -> {

                Intent intent = new Intent(
                        MainActivity.this,
                        MapActivity.class
                );

                startActivityForResult(intent, REQUEST_FIELD_MAP);

                return true;
            });
        });

        // =========================
        // CÁC VIEW CŨ
        // =========================
        tvStatus = findViewById(R.id.tvStatus);

        tvGpsAndSdStatus = findViewById(R.id.tvGpsAndSdStatus);
        tvTelemetry = findViewById(R.id.tvTelemetry);
        tvPhotoCount = findViewById(R.id.tvPhotoCount);
        tvVideoTimer = findViewById(R.id.tvVideoTimer);
        tvFieldArea = findViewById(R.id.tvFieldArea);




        btnLockRoute = findViewById(R.id.btnLockRoute);

        btnCaptureManual = findViewById(R.id.btnCaptureManual);
        btnCaptureManual.setBackgroundTintList(null);
        btnCaptureManual.setBackgroundResource(R.drawable.bg_camera_shutter);
        btnViewPhotos = findViewById(R.id.btnViewPhotos);
        btnVideoPhoto = findViewById(R.id.btnVideoPhoto);
        btnVideoPhoto.setBackgroundTintList(null);
        btnVideoPhoto.setBackgroundResource(R.drawable.bg_video_photo);
        seekBarGimbal = findViewById(R.id.seekBarGimbal);

        videoSurface = findViewById(R.id.video_preview_texture_view);


        btnLockRoute.setOnClickListener(v -> {
            openWPDialog();
        });
        // Ánh xạ View cho nút bấm và panel camera

        cameraControlPanel = findViewById(R.id.cameraControlPanel);

        cameraModePicker = findViewById(R.id.cameraModePicker);
        tvCameraVideo = findViewById(R.id.tvCameraVideo);
        tvCameraPhoto = findViewById(R.id.tvCameraPhoto);
        setupCameraModeSwipe();
        if (videoSurface != null) {
            videoSurface.setSurfaceTextureListener(this);
        }



// =========================
// NÚT LƯU TRỮ
// =========================

        btnViewPhotos.setOnClickListener(v -> {

            if (isRecordingVideo) {

                // ĐANG QUAY → TẠM DỪNG
                if (!isVideoPaused) {

                    isVideoPaused = true;
                    videoPauseStartTime = System.currentTimeMillis();
                    btnViewPhotos.setText("▶");

                    btnViewPhotos.setContentDescription(
                            "Tiếp tục quay"
                    );

                    Toast.makeText(
                            MainActivity.this,
                            "Đã tạm dừng quay",
                            Toast.LENGTH_SHORT
                    ).show();

                }

                // ĐANG TẠM DỪNG → TIẾP TỤC
                else {

                    isVideoPaused = false;
                    videoPausedTime +=
                            System.currentTimeMillis()
                                    - videoPauseStartTime;
                    btnViewPhotos.setText("Ⅱ");

                    btnViewPhotos.setContentDescription(
                            "Tạm dừng quay"
                    );

                    Toast.makeText(
                            MainActivity.this,
                            "Tiếp tục quay",
                            Toast.LENGTH_SHORT
                    ).show();

                    videoTimerHandler.post(
                            videoTimerRunnable
                    );
                }

            } else {

                // Không quay → mở kho lưu trữ
                showStorageDialog();
            }
        });

// =========================
// NÚT CHỤP / QUAY
// =========================
        btnCaptureManual.setOnClickListener(
                v -> triggerManualCapture()
        );
        btnVideoPhoto.setOnClickListener(v -> {

            if (DJISDKManager.getInstance().getProduct() == null) {

                Toast.makeText(
                        this,
                        "Chưa kết nối Drone!",
                        Toast.LENGTH_SHORT
                ).show();

                return;
            }

            Camera camera =
                    ((Aircraft) DJISDKManager
                            .getInstance()
                            .getProduct())
                            .getCamera();

            if (camera == null) {
                return;
            }

            camera.startShootPhoto(djiError -> {

                runOnUiThread(() -> {

                    if (djiError == null) {

                        photoCount++;

                        tvPhotoCount.setText(
                                "Ảnh đã chụp: " + photoCount + " tấm"
                        );

                        Toast.makeText(
                                MainActivity.this,
                                "Đã chụp ảnh",
                                Toast.LENGTH_SHORT
                        ).show();

                    } else {

                        Toast.makeText(
                                MainActivity.this,
                                "Chụp ảnh thất bại: "
                                        + djiError.getDescription(),
                                Toast.LENGTH_SHORT
                        ).show();
                    }

                });

            });
        });
        btnCaptureManual.setOnClickListener(
                v -> triggerManualCapture()
        );

        DJIConnectionManager.getInstance().setConnectionListener(status -> {
            runOnUiThread(() -> {
                tvStatus.setText("Trạng thái: " + status);
                if (status.contains("Đã kết nối")) {


                    missionManager = new FlightMissionManager();

                    if (DJISDKManager.getInstance().getProduct() != null) {
                        Aircraft aircraft = (Aircraft) DJISDKManager.getInstance().getProduct();

                        AirLink airLink = aircraft.getAirLink();
                        if (airLink != null) {
                            airLink.setUplinkSignalQualityCallback(percent -> rcSignalPercent = percent);
                        }

                        Battery battery = aircraft.getBattery();
                        if (battery != null) {
                            battery.setStateCallback(batteryState -> {
                                if (batteryState != null) {
                                    batteryPercent = batteryState.getChargeRemainingInPercent();
                                }
                            });
                        }

                        Camera camera = aircraft.getCamera();
                        if (camera != null) {
                            if (VideoFeeder.getInstance() != null) {
                                VideoFeeder.VideoFeed feed = VideoFeeder.getInstance().getPrimaryVideoFeed();
                                if (feed != null) {
                                    feed.addVideoDataListener(receivedVideoDataListener);
                                }
                            }

                            camera.setStorageStateCallBack(storageState -> {
                                if (storageState.isInserted()) {
                                    if (storageState.isFull()) sdStatusStr = "Thẻ: ĐẦY";
                                    else if (storageState.hasError()) sdStatusStr = "Thẻ: LỖI";
                                    else sdStatusStr = "Thẻ: OK";
                                } else {
                                    sdStatusStr = "Thẻ: CHƯA CẮM";
                                }
                            });
                        }

                        FlightController fc = aircraft.getFlightController();
                        if (fc != null) {
                            fc.setStateCallback(state -> {
                                if (state != null) {
                                    int satellites = state.getSatelliteCount();
                                    float currentAlt = state.getAircraftLocation() != null ? state.getAircraftLocation().getAltitude() : 0.0f;

                                    // =========================
                                    // CẬP NHẬT VỊ TRÍ DRONE TRÊN MAP
                                    // =========================
                                    if (state.getAircraftLocation() != null) {

                                        double droneLat = state.getAircraftLocation().getLatitude();
                                        double droneLng = state.getAircraftLocation().getLongitude();

                                        if (!Double.isNaN(droneLat) && !Double.isNaN(droneLng)) {

                                            lastDroneLocation = new LatLng(droneLat, droneLng);
                                            Point currentPoint = Point.fromLngLat(
                                                    droneLng,
                                                    droneLat
                                            );

                                            if (flightPathPoints.isEmpty()) {

                                                flightPathPoints.add(currentPoint);

                                            } else {

                                                Point lastPoint =
                                                        flightPathPoints.get(
                                                                flightPathPoints.size() - 1
                                                        );

                                                double distance =
                                                        calculateHaversineDistance(
                                                                lastPoint.latitude(),
                                                                lastPoint.longitude(),
                                                                droneLat,
                                                                droneLng
                                                        );

                                                // Chỉ lưu điểm mới khi drone di chuyển ít nhất 2 mét
                                                if (distance >= 2.0) {
                                                    flightPathPoints.add(currentPoint);
                                                }
                                            }
                                            runOnUiThread(() -> {

                                                if (miniMapLibreMap != null) {

                                                    if (miniMapLibreMap.getStyle() != null) {

                                                        GeoJsonSource source =
                                                                miniMapLibreMap.getStyle()
                                                                        .getSourceAs("drone-source");

                                                        if (source != null) {

                                                            source.setGeoJson(
                                                                    Feature.fromGeometry(
                                                                            Point.fromLngLat(
                                                                                    lastDroneLocation.getLongitude(),
                                                                                    lastDroneLocation.getLatitude()
                                                                            )
                                                                    )
                                                            );
                                                        }
                                                        if (flightPathSource != null
                                                                && flightPathPoints.size() >= 2) {

                                                            LineString lineString =
                                                                    LineString.fromLngLats(
                                                                            flightPathPoints
                                                                    );

                                                            flightPathSource.setGeoJson(
                                                                    Feature.fromGeometry(lineString)
                                                            );
                                                        }
                                                    }

                                                    miniMapLibreMap.setCameraPosition(
                                                            new org.maplibre.android.camera.CameraPosition.Builder()
                                                                    .target(lastDroneLocation)
                                                                    .zoom(13.0)
                                                                    .build()
                                                    );
                                                }
                                            });
                                        }
                                    }
                                    float velocityX = state.getVelocityX();
                                    float velocityY = state.getVelocityY();
                                    float speed = (float) Math.sqrt(velocityX * velocityX + velocityY * velocityY);

                                    if (satellites >= 10 && !isHomePointSet) {
                                        fc.setHomeLocationUsingAircraftCurrentLocation(djiError -> {
                                            if (djiError == null) {
                                                isHomePointSet = true;
                                                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Đã lưu Home Point!", Toast.LENGTH_LONG).show());
                                            }
                                        });
                                    }

                                    runOnUiThread(() -> {
                                        double targetLat = 10.82310;
                                        double targetLng = 106.62970;


                                        double distance = 0.0;
                                        if (state.getAircraftLocation() != null && !Double.isNaN(state.getAircraftLocation().getLatitude())) {
                                            distance = calculateHaversineDistance(
                                                    state.getAircraftLocation().getLatitude(),
                                                    state.getAircraftLocation().getLongitude(),
                                                    targetLat, targetLng
                                            );
                                        }

                                        String homeStatus = isHomePointSet ? "[HOME: OK]" : "[HOME: ĐANG TÌM]";
                                        tvGpsAndSdStatus.setText(String.format("GPS: %d | Pin: %d%% | RC: %d%% | %s %s", satellites, batteryPercent, rcSignalPercent, sdStatusStr, homeStatus));
                                        tvTelemetry.setText(String.format("Cao: %.1fm | Tốc độ: %.1fm/s\nCách đích: %.1fm", currentAlt, speed, distance));
                                    });
                                }
                            });
                        }
                    }
                }
            });
        });

        // Gọi thẳng đăng ký SDK (đã bỏ phần cấp quyền theo yêu cầu)
        DJIConnectionManager.getInstance().startConnection(this.getApplicationContext());



        seekBarGimbal.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (missionManager != null && fromUser) {
                    float angle = progress - 90f;
                    missionManager.setGimbalAngle(angle);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }


    private void setupCameraModeSwipe() {

        updateCameraModeUI(true, false);

        cameraModePicker.setOnTouchListener((v, event) -> {

            switch (event.getActionMasked()) {

                case MotionEvent.ACTION_DOWN:

                    cameraSwipeStartY = event.getY();

                    v.getParent().requestDisallowInterceptTouchEvent(true);

                    return true;

                case MotionEvent.ACTION_UP:

                    float endY = event.getY();

                    float diffY = endY - cameraSwipeStartY;


                    v.getParent().requestDisallowInterceptTouchEvent(false);

                    // Vuốt lên
                    if (diffY < -20) {

                        selectCameraMode(true);

                    }

                    // Vuốt xuống
                    else if (diffY > 20) {

                        selectCameraMode(false);

                    }

                    return true;

                case MotionEvent.ACTION_CANCEL:

                    v.getParent().requestDisallowInterceptTouchEvent(false);

                    return true;
            }

            return true;
        });
    }

    private void selectCameraMode(boolean photo) {

        if (photo) {

            // Chuyển sang chế độ Ảnh
            updateCameraModeUI(true, true);

            setCameraPhotoMode();

            // Ẩn nút chụp phụ
            btnVideoPhoto.setVisibility(View.GONE);

            // Nút chính trở lại nút chụp ảnh
            btnCaptureManual.setBackgroundResource(
                    R.drawable.bg_camera_shutter
            );

            btnCaptureManual.setSelected(false);

            btnCaptureManual.setContentDescription("Chụp ảnh");

        } else {

            // Chuyển sang chế độ Video
            updateCameraModeUI(false, true);

            setCameraVideoMode();

            // Hiện nút chụp ảnh phụ
            btnVideoPhoto.setVisibility(View.VISIBLE);

            // Nút chính trở thành nút quay
            btnCaptureManual.setBackgroundResource(
                    R.drawable.bg_video_record
            );

            btnCaptureManual.setSelected(false);

            btnCaptureManual.setContentDescription("Quay video");
        }
    }


    private void updateCameraModeUI(
            boolean photo,
            boolean animate
    ) {

        TextView selected;
        TextView unselected;

        if (photo) {

            selected = tvCameraPhoto;
            unselected = tvCameraVideo;

        } else {

            selected = tvCameraVideo;
            unselected = tvCameraPhoto;
        }

        // =========================
        // CHẾ ĐỘ ĐANG CHỌN
        // =========================

        selected.setTextColor(Color.YELLOW);
        selected.setTextSize(20);
        selected.setTypeface(null, Typeface.BOLD);
        selected.setAlpha(1.0f);

        // =========================
        // CHẾ ĐỘ CHƯA CHỌN
        // =========================

        unselected.setTextColor(Color.WHITE);
        unselected.setTextSize(16);
        unselected.setTypeface(null, Typeface.NORMAL);
        unselected.setAlpha(0.6f);

        // =========================
        // ANIMATION
        // =========================

        if (animate) {

            if (photo) {

                // Ảnh chạy vào giữa
                tvCameraPhoto.animate()
                        .translationY(0)
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(180)
                        .start();

                // Video chạy lên trên
                tvCameraVideo.animate()
                        .translationY(-42)
                        .scaleX(0.9f)
                        .scaleY(0.9f)
                        .setDuration(180)
                        .start();

            } else {

                // Video chạy vào giữa
                tvCameraVideo.animate()
                        .translationY(0)
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(180)
                        .start();

                // Ảnh chạy xuống dưới
                tvCameraPhoto.animate()
                        .translationY(42)
                        .scaleX(0.9f)
                        .scaleY(0.9f)
                        .setDuration(180)
                        .start();
            }

        } else {

            if (photo) {

                tvCameraPhoto.setTranslationY(0);
                tvCameraPhoto.setScaleX(1.0f);
                tvCameraPhoto.setScaleY(1.0f);

                tvCameraVideo.setTranslationY(-42);
                tvCameraVideo.setScaleX(0.9f);
                tvCameraVideo.setScaleY(0.9f);

            } else {

                tvCameraVideo.setTranslationY(0);
                tvCameraVideo.setScaleX(1.0f);
                tvCameraVideo.setScaleY(1.0f);

                tvCameraPhoto.setTranslationY(42);
                tvCameraPhoto.setScaleX(0.9f);
                tvCameraPhoto.setScaleY(0.9f);
            }
        }
    }
    private void fetchAndDownloadLatestPhoto() {
        if (DJISDKManager.getInstance().getProduct() == null) {
            Toast.makeText(this, "Chưa kết nối Drone!", Toast.LENGTH_SHORT).show();
            return;
        }

        Camera camera = ((Aircraft) DJISDKManager.getInstance().getProduct()).getCamera();
        if (camera == null) {
            Toast.makeText(this, "Không tìm thấy Camera!", Toast.LENGTH_SHORT).show();
            return;
        }

        camera.setMode(SettingsDefinitions.CameraMode.MEDIA_DOWNLOAD, djiError -> {
            if (djiError != null && !djiError.getDescription().toLowerCase().contains("not supported")) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Lỗi chuyển chế độ tải ảnh: " + djiError.getDescription(), Toast.LENGTH_SHORT).show());
                return;
            }

            MediaManager mediaManager = camera.getMediaManager();
            if (mediaManager == null) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "MediaManager chưa sẵn sàng!", Toast.LENGTH_SHORT).show());
                return;
            }

            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Đang làm mới danh sách thẻ nhớ...", Toast.LENGTH_SHORT).show());

            mediaManager.refreshFileListOfStorageLocation(SettingsDefinitions.StorageLocation.SDCARD, refreshError -> {
                if (refreshError == null) {
                    List<MediaFile> fileList = mediaManager.getSDCardFileListSnapshot();
                    if (fileList != null && !fileList.isEmpty()) {
                        MediaFile latestMedia = null;
                        for (int i = fileList.size() - 1; i >= 0; i--) {
                            if (fileList.get(i).getMediaType() == MediaFile.MediaType.JPEG ||
                                    fileList.get(i).getMediaType() == MediaFile.MediaType.TIFF) {
                                latestMedia = fileList.get(i);
                                break;
                            }
                        }

                        if (latestMedia != null) {
                            final MediaFile targetMedia = latestMedia;
                            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Đang tải ảnh: " + targetMedia.getFileName(), Toast.LENGTH_SHORT).show());

                            File destDir = new File(getExternalFilesDir(null), "DJI_Captured_Photos");
                            if (!destDir.exists()) destDir.mkdirs();

                            targetMedia.fetchFileData(destDir, targetMedia.getFileName(), new DownloadListener<String>() {
                                @Override
                                public void onStart() {}

                                @Override
                                public void onRateUpdate(long total, long current, long persize) {}

                                @Override
                                public void onProgress(long total, long current) {}

                                @Override
                                public void onSuccess(String filePath) {
                                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Đã lưu ảnh vào: " + filePath, Toast.LENGTH_LONG).show());
                                    camera.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO, null);
                                }

                                @Override
                                public void onFailure(DJIError error) {
                                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Lỗi tải ảnh: " + error.getDescription(), Toast.LENGTH_SHORT).show());
                                }

                                @Override
                                public void onRealtimeDataUpdate(byte[] bytes, long l, boolean b) {}
                            });
                        } else {
                            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Không tìm thấy file ảnh nào trên thẻ nhớ!", Toast.LENGTH_SHORT).show());
                        }
                    } else {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "Thẻ nhớ trống!", Toast.LENGTH_SHORT).show());
                    }
                } else {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Không thể làm mới thẻ nhớ: " + refreshError.getDescription(), Toast.LENGTH_SHORT).show());
                }
            });
        });
    }


    private void setCameraPhotoMode() {

        if (DJISDKManager.getInstance().getProduct() == null) {
            Toast.makeText(
                    this,
                    "Chưa kết nối Drone!",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        Camera camera =
                ((Aircraft) DJISDKManager
                        .getInstance()
                        .getProduct())
                        .getCamera();

        if (camera == null) {
            return;
        }

        camera.setMode(
                SettingsDefinitions.CameraMode.SHOOT_PHOTO,
                djiError -> {

                    runOnUiThread(() -> {

                        if (djiError == null) {

                            isCurrentModePhoto = true;
                            isRecordingVideo = false;

                            updateCameraModeUI(true, true);

                        } else {

                            Toast.makeText(
                                    MainActivity.this,
                                    "Không chuyển được sang chế độ ảnh: "
                                            + djiError.getDescription(),
                                    Toast.LENGTH_SHORT
                            ).show();
                        }
                    });
                }
        );
    }

    private void setCameraVideoMode() {

        if (DJISDKManager.getInstance().getProduct() == null) {
            Toast.makeText(
                    this,
                    "Chưa kết nối Drone!",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        Camera camera =
                ((Aircraft) DJISDKManager
                        .getInstance()
                        .getProduct())
                        .getCamera();

        if (camera == null) {
            return;
        }

        camera.setMode(
                SettingsDefinitions.CameraMode.RECORD_VIDEO,
                djiError -> {

                    runOnUiThread(() -> {

                        if (djiError == null) {

                            isCurrentModePhoto = false;
                            isRecordingVideo = false;
                            updateCameraModeUI(false, true);
                        } else {

                            Toast.makeText(
                                    MainActivity.this,
                                    "Không chuyển được sang chế độ quay: "
                                            + djiError.getDescription(),
                                    Toast.LENGTH_SHORT
                            ).show();
                        }
                    });
                }
        );
    }
    private void formatSDCard() {
        if (DJISDKManager.getInstance().getProduct() == null) {
            Toast.makeText(this, "Chưa kết nối Drone!", Toast.LENGTH_SHORT).show();
            return;
        }
        Camera camera = ((Aircraft) DJISDKManager.getInstance().getProduct()).getCamera();
        if (camera != null) {
            Toast.makeText(this, "Đang tiến hành Format thẻ...", Toast.LENGTH_SHORT).show();

            camera.formatStorage(SettingsDefinitions.StorageLocation.SDCARD, djiError -> {
                runOnUiThread(() -> {
                    if (djiError == null) {
                        Toast.makeText(MainActivity.this, "Format thẻ nhớ THÀNH CÔNG!", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(MainActivity.this, "Lỗi format: " + djiError.getDescription(), Toast.LENGTH_LONG).show();
                    }
                });
            });
        } else {
            Toast.makeText(this, "Không tìm thấy Camera/Thẻ nhớ!", Toast.LENGTH_SHORT).show();
        }
    }

    // Hàm triggerManualCapture đã được bổ sung đầy đủ thông báo lỗi cho Quay và Dừng quay
    private void triggerManualCapture() {

        if (DJISDKManager.getInstance().getProduct() == null) {

            Toast.makeText(
                    this,
                    "Chưa kết nối Drone!",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }

        Camera camera =
                ((Aircraft) DJISDKManager
                        .getInstance()
                        .getProduct())
                        .getCamera();

        if (camera == null) {
            return;
        }


        // =========================
        // CHẾ ĐỘ ẢNH
        // =========================

        if (isCurrentModePhoto) {

            camera.startShootPhoto(djiError -> {

                if (djiError == null) {

                    photoCount++;

                    runOnUiThread(() -> {

                        Toast.makeText(
                                MainActivity.this,
                                "Đã chụp 1 tấm!",
                                Toast.LENGTH_SHORT
                        ).show();

                        tvPhotoCount.setText(
                                "Ảnh đã chụp: "
                                        + photoCount
                                        + " tấm"
                        );

                    });

                } else {

                    runOnUiThread(() -> {

                        Toast.makeText(
                                MainActivity.this,
                                "Lỗi chụp: "
                                        + djiError.getDescription(),
                                Toast.LENGTH_LONG
                        ).show();

                    });

                }

            });

            return;
        }


        // =========================
        // CHẾ ĐỘ VIDEO
        // =========================

        if (!isRecordingVideo) {

            // BẮT ĐẦU QUAY

            camera.startRecordVideo(djiError -> {

                runOnUiThread(() -> {

                    if (djiError == null) {

                        isRecordingVideo = true;
                        isVideoPaused = false;

                        startVideoTimer();

                        btnViewPhotos.setText("Ⅱ");

                        btnViewPhotos.setContentDescription(
                                "Tạm dừng quay"
                        );

                        // Đổi nút thành trạng thái ĐANG QUAY
                        btnCaptureManual.setSelected(true);

                        btnCaptureManual.setContentDescription(
                                "Dừng quay"
                        );

                        Toast.makeText(
                                MainActivity.this,
                                "Bắt đầu quay Video!",
                                Toast.LENGTH_SHORT
                        ).show();

                    } else {

                        Toast.makeText(
                                MainActivity.this,
                                "Lỗi quay: "
                                        + djiError.getDescription(),
                                Toast.LENGTH_LONG
                        ).show();

                    }

                });

            });

        } else {

            // DỪNG QUAY

            camera.stopRecordVideo(djiError -> {

                runOnUiThread(() -> {

                    if (djiError == null) {

                        isRecordingVideo = false;

                        isVideoPaused = false;

                        stopVideoTimer();

                        btnCaptureManual.setSelected(false);

                        btnCaptureManual.setContentDescription(
                                "Quay video"
                        );

                        btnViewPhotos.setText("□");

                        btnViewPhotos.setContentDescription(
                                "Mở kho lưu trữ"
                        );

                        Toast.makeText(
                                MainActivity.this,
                                "Đã lưu Video vào thẻ nhớ!",
                                Toast.LENGTH_SHORT
                        ).show();

                    } else {

                        Toast.makeText(
                                MainActivity.this,
                                "Lỗi dừng quay: "
                                        + djiError.getDescription(),
                                Toast.LENGTH_LONG
                        ).show();

                    }

                });

            });

        }
    }
    private void startVideoTimer() {

        videoStartTime = System.currentTimeMillis();
        videoPausedTime = 0;
        videoPauseStartTime = 0;

        tvVideoTimer.setText("00:00");
        tvVideoTimer.setVisibility(View.VISIBLE);

        videoTimerRunnable = new Runnable() {

            @Override
            public void run() {

                if (isRecordingVideo && !isVideoPaused) {

                    long elapsed =
                            System.currentTimeMillis()
                                    - videoStartTime
                                    - videoPausedTime;

                    long totalSeconds = elapsed / 1000;

                    long minutes = totalSeconds / 60;
                    long seconds = totalSeconds % 60;

                    String time = String.format(
                            Locale.getDefault(),
                            "%02d:%02d",
                            minutes,
                            seconds
                    );

                    tvVideoTimer.setText(time);

                    videoTimerHandler.postDelayed(
                            this,
                            1000
                    );
                }
            }
        };

        videoTimerHandler.post(videoTimerRunnable);
    }
    private void stopVideoTimer() {

        if (videoTimerRunnable != null) {

            videoTimerHandler.removeCallbacks(
                    videoTimerRunnable
            );
        }

        tvVideoTimer.setText("00:00");
        tvVideoTimer.setVisibility(View.GONE);

        videoStartTime = 0;
        videoPausedTime = 0;
        videoPauseStartTime = 0;
    }
    private double calculateHaversineDistance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371;
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance/2) * Math.sin(latDistance/2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(lonDistance/2) * Math.sin(lonDistance/2);
        return R * (2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))) * 1000;
    }
    private void openWPDialog() {

        Dialog dialog = new Dialog(MainActivity.this);

        dialog.setContentView(R.layout.dialog_wp);

        Button btnCloseWP =
                dialog.findViewById(R.id.btnCloseWP);

        Button btnEditWPField =
                dialog.findViewById(R.id.btnEditWPField);

        TextView tvWPFieldStatus =
                dialog.findViewById(R.id.tvWPFieldStatus);

        TextView tvWPFieldArea =
                dialog.findViewById(R.id.tvWPFieldArea);

        if (fieldPoints.size() == 4) {

            tvWPFieldStatus.setText(
                    "Đã xác định khu vực"
            );

            tvWPFieldArea.setText(
                    String.format(
                            Locale.US,
                            "Diện tích: %.2f ha",
                            fieldAreaHa
                    )
            );

        } else {

            tvWPFieldStatus.setText(
                    "Chưa xác định khu vực"
            );

            tvWPFieldArea.setText(
                    "Diện tích: 0.00 ha"
            );
        }

        btnCloseWP.setOnClickListener(view -> {
            dialog.dismiss();
        });

        btnEditWPField.setOnClickListener(view -> {

            Intent intent = new Intent(
                    MainActivity.this,
                    MapActivity.class
            );

            intent.putExtra(
                    "EDIT_FIELD",
                    true
            );

            if (fieldPoints.size() == 4) {

                double[] latitudes =
                        new double[fieldPoints.size()];

                double[] longitudes =
                        new double[fieldPoints.size()];

                for (int i = 0; i < fieldPoints.size(); i++) {

                    latitudes[i] =
                            fieldPoints.get(i).getLatitude();

                    longitudes[i] =
                            fieldPoints.get(i).getLongitude();
                }

                intent.putExtra(
                        "FIELD_LATITUDES",
                        latitudes
                );

                intent.putExtra(
                        "FIELD_LONGITUDES",
                        longitudes
                );
            }

            dialog.dismiss();

            startActivityForResult(
                    intent,
                    REQUEST_FIELD_MAP
            );
        });

        dialog.show();

        if (dialog.getWindow() != null) {

            dialog.getWindow()
                    .setBackgroundDrawableResource(
                            android.R.color.transparent
                    );

            dialog.getWindow().setLayout(
                    (int) (
                            getResources()
                                    .getDisplayMetrics()
                                    .widthPixels * 0.80
                    ),
                    (int) (
                            getResources()
                                    .getDisplayMetrics()
                                    .heightPixels * 0.85
                    )
            );
        }
    }
    @Override
    protected void onStart() {
        super.onStart();

        if (miniMapView != null) {
            miniMapView.onStart();
        }
    }
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_FIELD_MAP
                && resultCode == RESULT_OK
                && data != null) {

            double areaHa = data.getDoubleExtra(
                    "FIELD_AREA_HA",
                    0.0
            );

            double[] latitudes =
                    data.getDoubleArrayExtra("FIELD_LATITUDES");

            double[] longitudes =
                    data.getDoubleArrayExtra("FIELD_LONGITUDES");

            fieldPoints.clear();

            if (latitudes != null
                    && longitudes != null
                    && latitudes.length == longitudes.length) {

                for (int i = 0; i < latitudes.length; i++) {
                    fieldPoints.add(
                            new LatLng(
                                    latitudes[i],
                                    longitudes[i]
                            )
                    );
                }
            }

            fieldAreaHa = areaHa;

            if (tvFieldArea != null) {
                tvFieldArea.setText(
                        String.format(
                                Locale.US,
                                "%.2f ha",
                                fieldAreaHa
                        )
                );
            }

            // MỞ WP NGAY SAU KHI XÁC NHẬN 4 ĐIỂM
            openWPDialog();
        }
    }
    @Override
    protected void onPause() {

        if (miniMapView != null) {
            miniMapView.onPause();
        }

        super.onPause();
    }

    @Override
    protected void onStop() {

        if (miniMapView != null) {
            miniMapView.onStop();
        }

        super.onStop();
    }
    @Override
    protected void onResume() {
        super.onResume();

        if (miniMapView != null) {
            miniMapView.onResume();
        }

        if (videoSurface != null && videoSurface.isAvailable()) {
            if (codecManager == null) {
                codecManager = new DJICodecManager(
                        this,
                        videoSurface.getSurfaceTexture(),
                        videoSurface.getWidth(),
                        videoSurface.getHeight()
                );
            }
        }
    }
    @Override
    protected void onDestroy() {

        if (miniMapView != null) {
            miniMapView.onDestroy();
        }

        if (missionManager != null) {
            missionManager.cancelMission();
        }

        if (codecManager != null) {
            codecManager.cleanSurface();
            codecManager = null;
        }

        super.onDestroy();
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        if (codecManager == null) {
            codecManager = new DJICodecManager(this, surface, width, height);
        }
        if (VideoFeeder.getInstance() != null) {
            VideoFeeder.VideoFeed feed = VideoFeeder.getInstance().getPrimaryVideoFeed();
            if (feed != null) {
                feed.addVideoDataListener(receivedVideoDataListener);
            }
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        if (codecManager != null) {
            codecManager.cleanSurface();
            codecManager = new DJICodecManager(this, surface, width, height);
        }
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        if (VideoFeeder.getInstance() != null) {
            VideoFeeder.VideoFeed feed = VideoFeeder.getInstance().getPrimaryVideoFeed();
            if (feed != null) {
                feed.removeVideoDataListener(receivedVideoDataListener);
            }
        }
        if (codecManager != null) {
            codecManager.cleanSurface();
            codecManager = null;
        }
        return false;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {}

    private final VideoFeeder.VideoDataListener receivedVideoDataListener = (videoBuffer, size) -> {
        if (codecManager != null) {
            codecManager.sendDataToDecoder(videoBuffer, size);
        }
    };
    private void showStorageDialog() {

        Dialog dialog = new Dialog(this);

        dialog.setContentView(R.layout.dialog_storage);

        if (dialog.getWindow() != null) {

            dialog.getWindow().setBackgroundDrawableResource(
                    android.R.color.transparent
            );
        }

        GridView gridStorage =
                dialog.findViewById(R.id.gridStorage);

        Button btnCloseStorage =
                dialog.findViewById(R.id.btnCloseStorage);

        List<MediaFile> storageFiles =
                new ArrayList<>();

        StorageAdapter adapter =
                new StorageAdapter(
                        MainActivity.this,
                        storageFiles
                );

        gridStorage.setAdapter(adapter);

        btnCloseStorage.setOnClickListener(v -> {

            if (DJISDKManager.getInstance().getProduct() != null) {

                Camera camera =
                        ((Aircraft) DJISDKManager
                                .getInstance()
                                .getProduct())
                                .getCamera();

                if (camera != null) {

                    SettingsDefinitions.CameraMode mode;

                    if (isCurrentModePhoto) {

                        mode = SettingsDefinitions.CameraMode.SHOOT_PHOTO;

                    } else {

                        mode = SettingsDefinitions.CameraMode.RECORD_VIDEO;
                    }

                    camera.setMode(mode, null);
                }
            }

            dialog.dismiss();
        });

        dialog.show();

        if (dialog.getWindow() != null) {

            dialog.getWindow().setLayout(
                    (int) (
                            getResources()
                                    .getDisplayMetrics()
                                    .widthPixels * 0.70
                    ),
                    (int) (
                            getResources()
                                    .getDisplayMetrics()
                                    .heightPixels * 0.80
                    )
            );
        }

        // =========================
        // KIỂM TRA DRONE
        // =========================

        if (DJISDKManager.getInstance().getProduct() == null) {

            Toast.makeText(
                    MainActivity.this,
                    "Chưa kết nối Drone!",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }

        Camera camera =
                ((Aircraft) DJISDKManager
                        .getInstance()
                        .getProduct())
                        .getCamera();

        if (camera == null) {

            Toast.makeText(
                    MainActivity.this,
                    "Không tìm thấy Camera!",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }

        // =========================
        // CHUYỂN CAMERA SANG MEDIA DOWNLOAD
        // =========================

        camera.setMode(
                SettingsDefinitions.CameraMode.MEDIA_DOWNLOAD,
                djiError -> {

                    if (djiError != null
                            && !djiError
                            .getDescription()
                            .toLowerCase()
                            .contains("not supported")) {

                        runOnUiThread(() ->
                                Toast.makeText(
                                        MainActivity.this,
                                        "Lỗi mở bộ nhớ: "
                                                + djiError.getDescription(),
                                        Toast.LENGTH_SHORT
                                ).show()
                        );

                        return;
                    }

                    MediaManager mediaManager =
                            camera.getMediaManager();

                    if (mediaManager == null) {

                        runOnUiThread(() ->
                                Toast.makeText(
                                        MainActivity.this,
                                        "MediaManager chưa sẵn sàng!",
                                        Toast.LENGTH_SHORT
                                ).show()
                        );

                        return;
                    }

                    runOnUiThread(() ->
                            Toast.makeText(
                                    MainActivity.this,
                                    "Đang tải danh sách ảnh/video...",
                                    Toast.LENGTH_SHORT
                            ).show()
                    );

                    // =========================
                    // LẤY DANH SÁCH FILE TRÊN SD
                    // =========================

                    mediaManager
                            .refreshFileListOfStorageLocation(
                                    SettingsDefinitions.StorageLocation.SDCARD,
                                    refreshError -> {

                                        if (refreshError != null) {

                                            runOnUiThread(() ->
                                                    Toast.makeText(
                                                            MainActivity.this,
                                                            "Lỗi đọc thẻ nhớ: "
                                                                    + refreshError
                                                                    .getDescription(),
                                                            Toast.LENGTH_SHORT
                                                    ).show()
                                            );

                                            return;
                                        }

                                        List<MediaFile> fileList =
                                                mediaManager
                                                        .getSDCardFileListSnapshot();

                                        if (fileList == null
                                                || fileList.isEmpty()) {

                                            runOnUiThread(() ->
                                                    Toast.makeText(
                                                            MainActivity.this,
                                                            "Thẻ nhớ chưa có ảnh/video!",
                                                            Toast.LENGTH_SHORT
                                                    ).show()
                                            );

                                            return;
                                        }

                                        storageFiles.clear();

                                        storageFiles.addAll(
                                                fileList
                                        );

                                        runOnUiThread(() -> {

                                            adapter.notifyDataSetChanged();

                                            Toast.makeText(
                                                    MainActivity.this,
                                                    "Đã tìm thấy "
                                                            + storageFiles.size()
                                                            + " file",
                                                    Toast.LENGTH_SHORT
                                            ).show();
                                        });
                                    }
                            );
                }
        );
    }
}
