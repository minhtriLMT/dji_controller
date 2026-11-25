package com.tri.djicontrol;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

import com.google.gson.JsonObject;
import static org.maplibre.android.style.layers.PropertyFactory.iconRotate;
import org.maplibre.android.style.expressions.Expression;

import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.Point;


import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.provider.MediaStore;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Environment;
import android.media.MediaScannerConnection;
import android.view.View;
import android.view.LayoutInflater;
import android.widget.CheckBox;
import android.view.TextureView;
import android.view.MotionEvent;
import android.widget.Button;
import android.graphics.Color;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.view.WindowManager;
import android.app.Dialog;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import androidx.appcompat.app.AppCompatActivity;
import org.maplibre.android.MapLibre;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.location.LocationComponent;
import org.maplibre.android.location.LocationComponentActivationOptions;
import org.maplibre.android.location.modes.CameraMode;
import org.maplibre.android.location.modes.RenderMode;

import org.maplibre.geojson.LineString;
import org.maplibre.android.style.layers.LineLayer;
import static org.maplibre.android.style.layers.Property.NONE;
import static org.maplibre.android.style.layers.Property.VISIBLE;
import static org.maplibre.android.style.layers.PropertyFactory.lineColor;
import static org.maplibre.android.style.layers.PropertyFactory.lineWidth;
import static org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap;
import static org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement;
import static org.maplibre.android.style.layers.PropertyFactory.iconImage;
import static org.maplibre.android.style.layers.PropertyFactory.iconSize;
import static org.maplibre.android.style.layers.PropertyFactory.circleColor;
import static org.maplibre.android.style.layers.PropertyFactory.circleRadius;
import static org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor;
import static org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth;
import static org.maplibre.android.style.layers.PropertyFactory.visibility;
import org.maplibre.android.location.engine.LocationEngineRequest;
import com.tri.djicontrol.connection.DJIConnectionManager;
import com.tri.djicontrol.flight.FlightMissionManager;
import com.tri.djicontrol.flight.GridPlanner;
import android.widget.EditText;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.HashSet;
import java.util.Locale;
import java.util.Date;
import java.text.SimpleDateFormat;

import dji.common.camera.SettingsDefinitions;
import dji.common.error.DJICameraError;
import dji.common.error.DJIError;
import dji.common.model.LocationCoordinate2D;
import dji.common.util.CommonCallbacks;
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

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

public class MainActivity extends AppCompatActivity implements TextureView.SurfaceTextureListener {
    private TextView tvStatus, tvGpsAndSdStatus, tvTelemetry, tvPhotoCount, tvCaptureLabel, tvSpeedStats;
    private Button btnCaptureManual, btnLockRoute , btnVideoPhoto;
    private android.widget.ImageButton btnViewPhotos;
    private FrameLayout cameraModePicker;
    private TextView tvCameraVideo;
    private View cameraModeThumb;
    private TextView tvCameraPhoto,tvVideoTimer;
    private float cameraSwipeStartY;

    private LinearLayout cameraControlPanel;
    private SeekBar seekBarGimbal;

    private FlightMissionManager missionManager;
    private TextureView videoSurface;
    private DJICodecManager codecManager;

    private String sdStatusStr = "Thẻ: Sẵn sàng";
    // Trạng thái thẻ nhớ (cập nhật từ StorageStateCallback) cho hộp thoại Cài đặt
    private volatile boolean sdInserted = false, sdFull = false, sdHasError = false;
    private volatile int sdTotalMB = 0, sdRemainMB = 0;
    private int batteryPercent = 0;
    // Thời gian bay còn lại (giây) do flight controller ước tính; 0 = chưa có (vd. đang ở dưới đất)
    private volatile int remainingFlightSec = 0;
    private int rcSignalPercent = 0;
    private int photoCount = 0;
    private boolean isHomePointSet = false;

    // Biến theo dõi tốc độ mạng Tải về (Drone) / Đẩy lên (Server)
    private String downloadSpeedText = "DL: 0 KB/s";
    private String uploadSpeedText = "UL: 0 KB/s";

    private boolean isCurrentModePhoto = true;
    private boolean isRecordingVideo = false;
    private boolean isVideoPaused = false;

    private long videoStartTime = 0;
    private long videoPausedTime = 0;
    private long videoPauseStartTime = 0;
    private Handler videoTimerHandler = new Handler(Looper.getMainLooper());
    private Runnable videoTimerRunnable;

    private View miniMap;
    private View ivCrosshair;
    private boolean miniMapSatellite = false;
    // true = bản đồ toàn màn hình + camera thu nhỏ ở góc; false = camera toàn màn hình + mini map ở góc
    private boolean mapExpanded = false;
    private MapView miniMapView;
    private MapLibreMap miniMapLibreMap;
    private GeoJsonSource droneSource;
    private SymbolLayer droneLayer;
    private LatLng lastDroneLocation;
    private GeoJsonSource phoneLocationSource;
    private LocationManager locationManager;

    private static final String PHONE_LOCATION_SOURCE_ID = "phone-location-source";
    private static final String PHONE_LOCATION_LAYER_ID = "phone-location-layer";

    private static final int MINI_MAP_LOCATION_PERMISSION = 2001;
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

        MapLibre.getInstance(this);
        setContentView(R.layout.activity_main);

        btnReturnStop = findViewById(R.id.btnReturnStop);
        btnReturnStop.setOnClickListener(v -> {
            if (missionManager != null) {
                missionManager.startReturnToHome();
                Toast.makeText(MainActivity.this, "ĐANG THỰC HIỆN RTH!", Toast.LENGTH_SHORT).show();
            }
        });

        miniMap = findViewById(R.id.miniMap);
        miniMapView = findViewById(R.id.miniMapView);
        miniMapView.onCreate(savedInstanceState);

        miniMapView.getMapAsync(mapLibreMap -> {

            miniMapLibreMap = mapLibreMap;

            miniMapLibreMap.setStyle(
                    new Style.Builder()
                            .fromUri("asset://minimap_style.json"),

                    style -> {

                        // VỊ TRÍ ĐIỆN THOẠI THẬT
                        setupPhoneLocationMarker(style);


                        // ICON DRONE
                        Drawable drawable =
                                getResources().getDrawable(
                                        R.drawable.ic_drone_arrow
                                );

                        Bitmap droneBitmap =
                                Bitmap.createBitmap(
                                        drawable.getIntrinsicWidth(),
                                        drawable.getIntrinsicHeight(),
                                        Bitmap.Config.ARGB_8888
                                );

                        Canvas canvas =
                                new Canvas(droneBitmap);

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


                        droneSource =
                                new GeoJsonSource("drone-source");

                        style.addSource(droneSource);


                        droneLayer =
                                new SymbolLayer(
                                        "drone-layer",
                                        "drone-source"
                                );

                        droneLayer.setProperties(
                                iconImage("drone-icon"),
                                iconSize(0.7f),
                                iconAllowOverlap(true),
                                iconIgnorePlacement(true),
                                iconRotate(
                                        Expression.get("bearing")
                                ),
                                visibility(NONE)
                        );

                        style.addLayer(droneLayer);


                        // ĐƯỜNG BAY DRONE
                        flightPathSource =
                                new GeoJsonSource(
                                        FLIGHT_PATH_SOURCE_ID
                                );

                        style.addSource(flightPathSource);

                        LineLayer flightPathLayer =
                                new LineLayer(
                                        FLIGHT_PATH_LAYER_ID,
                                        FLIGHT_PATH_SOURCE_ID
                                );

                        flightPathLayer.setProperties(
                                lineColor("#FFEB3B"),
                                lineWidth(3.0f)
                        );

                        style.addLayer(flightPathLayer);

                        // RUỘNG + PHẦN ĐÃ BAY + ĐIỂM BẮT ĐẦU (bay quét WP)
                        setupWpMapLayers(style);

                        // TẤT CẢ RUỘNG ĐÃ LƯU (dưới lớp ruộng đang chọn / lộ trình / drone)
                        refreshSavedFieldsOnMiniMap();
                    }
            );

            // Chạm mini map: phóng bản đồ ra toàn màn hình, camera thu về góc
            miniMapLibreMap.addOnMapClickListener(point -> {
                if (!mapExpanded) {
                    setMapExpanded(true);
                    return true;
                }
                // Bản đồ to: chạm vào 1 ruộng đã lưu -> xem ảnh đã chụp của ruộng đó
                String fid = savedFieldAt(point);
                if (fid != null) {
                    showFieldPhotosDialog(fid);
                    return true;
                }
                return false;
            });
            applyMiniMapUiMode();
        });

        findViewById(R.id.btnMiniMapType).setOnClickListener(v -> toggleMiniMapSatellite());
        findViewById(R.id.btnMiniMapMyLocation).setOnClickListener(v -> centerMiniMapOnMe());
        // Lưu / sửa / xóa ruộng ở bất kỳ đâu -> vẽ lại trên mini map
        com.tri.djicontrol.field.SavedFieldStore.setOnChangeListener(this::refreshSavedFieldsOnMiniMap);
        ivCrosshair = findViewById(R.id.ivCrosshair);

        tvStatus = findViewById(R.id.tvStatus);
        tvGpsAndSdStatus = findViewById(R.id.tvGpsAndSdStatus);
        tvTelemetry = findViewById(R.id.tvTelemetry);
        statusDot = findViewById(R.id.statusDot);
        tvChipGps = findViewById(R.id.tvChipGps);
        tvChipBattery = findViewById(R.id.tvChipBattery);
        // Chip PIN: vòng tròn % pin + thời gian bay còn lại
        batteryRing = new BatteryRingDrawable(getResources().getDisplayMetrics().density,
                getResources().getDisplayMetrics().scaledDensity);
        tvChipBattery.setCompoundDrawableTintList(null);
        tvChipBattery.setCompoundDrawablesRelative(batteryRing, null, null, null);
        tvChipBattery.setPaddingRelative(Math.round(3 * getResources().getDisplayMetrics().density),
                0, tvChipBattery.getPaddingEnd(), 0);
        setBatteryChip(-1);
        tvChipRc = findViewById(R.id.tvChipRc);
        // Chip RC: cột sóng thay cho chấm tròn + %
        rcBars = new SignalBarsDrawable(getResources().getDisplayMetrics().density);
        tvChipRc.setCompoundDrawableTintList(null);
        tvChipRc.setCompoundDrawablesRelative(rcBars, null, null, null);
        tvChipRc.setText("RC");
        setRcSignal(-1);
        tvChipSd = findViewById(R.id.tvChipSd);
        tvChipHome = findViewById(R.id.tvChipHome);
        tvTeleAlt = findViewById(R.id.tvTeleAlt);
        tvTeleHSpeed = findViewById(R.id.tvTeleHSpeed);
        tvTeleVSpeed = findViewById(R.id.tvTeleVSpeed);
        tvTeleDist = findViewById(R.id.tvTeleDist);
        tvTeleDistLabel = findViewById(R.id.tvTeleDistLabel);
        tvTeleNet = findViewById(R.id.tvTeleNet);
        tvPhotoCount = findViewById(R.id.tvPhotoCount);
        tvCaptureLabel = findViewById(R.id.tvCaptureLabel);
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
        btnLockRoute.setOnClickListener(v -> openWPDialog());
        findViewById(R.id.btnCameraMenu).setOnClickListener(v -> showSettingsMenu());

        cameraControlPanel = findViewById(R.id.cameraControlPanel);
        cameraModePicker = findViewById(R.id.cameraModePicker);
        tvCameraVideo = findViewById(R.id.tvCameraVideo);
        tvCameraPhoto = findViewById(R.id.tvCameraPhoto);
        cameraModeThumb = findViewById(R.id.cameraModeThumb);
        setupCameraModeSwipe();

        if (videoSurface != null) {
            videoSurface.setSurfaceTextureListener(this);
        }

        btnViewPhotos.setOnClickListener(v -> {
            if (isRecordingVideo) {
                if (!isVideoPaused) {
                    isVideoPaused = true;
                    videoPauseStartTime = System.currentTimeMillis();
                    btnViewPhotos.setImageResource(R.drawable.ic_storage_play);
                    btnViewPhotos.setContentDescription("Tiếp tục quay");
                    setRecTimerDot(0xFFFFB74D, 255); // cam = đang tạm dừng
                    Toast.makeText(MainActivity.this, "Đã tạm dừng quay", Toast.LENGTH_SHORT).show();
                } else {
                    isVideoPaused = false;
                    videoPausedTime += System.currentTimeMillis() - videoPauseStartTime;
                    btnViewPhotos.setImageResource(R.drawable.ic_storage_pause);
                    btnViewPhotos.setContentDescription("Tạm dừng quay");
                    Toast.makeText(MainActivity.this, "Tiếp tục quay", Toast.LENGTH_SHORT).show();
                    videoTimerHandler.post(videoTimerRunnable);
                }
            } else {
                showStorageDialog();
            }
        });

        btnCaptureManual.setOnClickListener(v -> triggerManualCapture());

        btnVideoPhoto.setOnClickListener(v -> {
            if (DJISDKManager.getInstance().getProduct() == null) {
                Toast.makeText(this, "Chưa kết nối Drone!", Toast.LENGTH_SHORT).show();
                return;
            }
            Camera camera = ((Aircraft) DJISDKManager.getInstance().getProduct()).getCamera();
            if (camera == null) return;

            if (isRecordingVideo) {
                // Đang quay: chụp bằng phần cứng camera (không đổi mode, không dừng quay)
                // để ảnh được ghi thẳng vào thẻ nhớ drone -> hiện trong Kho lưu trữ.
                camera.startShootPhoto(djiError -> {
                    if (djiError == null) {
                        photoCount++;
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, "Đã chụp 1 tấm vào thẻ nhớ drone!", Toast.LENGTH_SHORT).show();
                        });
                    } else {
                        // Camera không hỗ trợ chụp khi đang quay -> lấy khung hình từ luồng video
                        runOnUiThread(() -> captureFrameFromLiveView(djiError.getDescription()));
                    }
                });
            } else {
                // Nếu chưa quay video nhưng đang ở chế độ Video UI
                // Bước 1: Thử chụp trực tiếp bằng phần cứng trước
                camera.startShootPhoto(djiError -> {
                    if (djiError == null) {
                        photoCount++;
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, "Đã chụp 1 tấm!", Toast.LENGTH_SHORT).show();
                        });
                    } else {
                        // Nếu camera báo lỗi do đang ở mode Video, tạm thời chuyển sang mode SHOOT_PHOTO để chụp
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "Đang chụp ảnh...", Toast.LENGTH_SHORT).show());
                        camera.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO, setModeErr -> {
                            if (setModeErr != null) {
                                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Lỗi chuyển chế độ chụp: " + setModeErr.getDescription(), Toast.LENGTH_SHORT).show());
                                return;
                            }
                            // Đợi 500ms cho phần cứng camera sẵn sàng ở chế độ SHOOT_PHOTO
                            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                camera.setShootPhotoMode(SettingsDefinitions.ShootPhotoMode.SINGLE, shootModeErr -> {
                                    camera.startShootPhoto(shootErr -> {
                                        if (shootErr == null) {
                                            photoCount++;
                                            runOnUiThread(() -> {
                                                Toast.makeText(MainActivity.this, "Đã chụp 1 tấm!", Toast.LENGTH_SHORT).show();
                                            });
                                        } else {
                                            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Lỗi chụp ảnh: " + shootErr.getDescription(), Toast.LENGTH_SHORT).show());
                                        }
                                        // Chờ 1200ms cho máy bay ghi xong file lên thẻ SD rồi chuyển lại mode RECORD_VIDEO
                                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                            camera.setMode(SettingsDefinitions.CameraMode.RECORD_VIDEO, null);
                                        }, 1200);
                                    });
                                });
                            }, 500);
                        });
                    }
                });
            }
        });

        DJIConnectionManager.getInstance().setConnectionListener(status -> {
            runOnUiThread(() -> {
                tvStatus.setText(status);
                setConnectionDot(status.contains("Đã kết nối") ? Boolean.TRUE
                        : status.contains("mất") || status.contains("Lỗi") ? Boolean.FALSE : null);
                if (status.contains("Đã kết nối")) {
                    if (miniMapLibreMap != null && miniMapLibreMap.getStyle() != null) {
                        SymbolLayer layer =
                                miniMapLibreMap.getStyle().getLayerAs("drone-layer");

                        if (layer != null) {
                            layer.setProperties(
                                    visibility(VISIBLE)
                            );
                        }
                    }
                    missionManager = new FlightMissionManager(MainActivity.this);
                    missionManager.setTransitAltitude(wpTransitAlt);
                    missionManager.setReturnAltitude(wpReturnAlt);
                    com.tri.djicontrol.firebase.UploadMeta.refreshDroneInfo();
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
                                    // Nhiệm vụ bay quét theo dõi pin để tự về nhà khi pin yếu
                                    if (missionManager != null) missionManager.onBatteryUpdate(batteryPercent);
                                    runOnUiThread(MainActivity.this::maybePromptResume);
                                }
                            });
                        }

                        // Góc gimbal thật -> thanh chỉnh góc camera chạy theo (chỉ cập nhật khi đổi ≥ 1°)
                        dji.sdk.gimbal.Gimbal gimbalComp = aircraft.getGimbal();
                        if (gimbalComp != null) {
                            gimbalComp.setStateCallback(gs -> {
                                if (gs == null || gs.getAttitudeInDegrees() == null) return;
                                float pitch = gs.getAttitudeInDegrees().getPitch();
                                if (Math.abs(pitch - lastGimbalPitch) < 1f) return;
                                lastGimbalPitch = pitch;
                                runOnUiThread(() -> syncGimbalSlider(pitch));
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
                                sdInserted = storageState.isInserted();
                                sdFull = storageState.isFull();
                                sdHasError = storageState.hasError();
                                sdTotalMB = storageState.getTotalSpaceInMB();
                                sdRemainMB = storageState.getRemainingSpaceInMB();
                                if (storageState.isInserted()) {
                                    if (storageState.isFull()) sdStatusStr = "Thẻ: ĐẦY";
                                    else if (storageState.hasError()) sdStatusStr = "Thẻ: LỖI";
                                    else sdStatusStr = "Thẻ: OK";
                                } else {
                                    sdStatusStr = "Thẻ: CHƯA CẮM";
                                }
                            });
                            // --- THÊM ĐOẠN NÀY: Lắng nghe nút bấm chuyển chế độ trên tay cầm tay ---
                            camera.setSystemStateCallback(systemState -> {
                                if (systemState == null) return;

                                SettingsDefinitions.CameraMode currentMode = systemState.getMode();
                                boolean recording = systemState.isRecording();

                                runOnUiThread(() -> {
                                    if (currentMode == SettingsDefinitions.CameraMode.SHOOT_PHOTO) {
                                        isCurrentModePhoto = true;

                                        updateCameraModeUI(true, false);
                                        btnVideoPhoto.setVisibility(View.GONE);
                                        btnCaptureManual.setBackgroundResource(
                                                R.drawable.bg_camera_shutter
                                        );
                                        btnCaptureManual.setContentDescription("Chụp ảnh");

                                    } else if (currentMode == SettingsDefinitions.CameraMode.RECORD_VIDEO) {
                                        isCurrentModePhoto = false;

                                        updateCameraModeUI(false, false);
                                        btnVideoPhoto.setVisibility(View.VISIBLE);
                                        btnCaptureManual.setBackgroundResource(
                                                R.drawable.bg_video_record
                                        );
                                        btnCaptureManual.setContentDescription(
                                                recording ? "Dừng quay" : "Quay video"
                                        );
                                    }

                                    if (recording != isRecordingVideo) {
                                        isRecordingVideo = recording;

                                        if (recording) {
                                            startVideoTimer();
                                            btnCaptureManual.setSelected(true);
                                        } else {
                                            stopVideoTimer();
                                            btnCaptureManual.setSelected(false);
                                        }
                                    }
                                });
                            });
                        }

                        FlightController fc = aircraft.getFlightController();
                        if (fc != null) {
                            fc.setStateCallback(state -> {
                                if (state != null) {
                                    int satellites = state.getSatelliteCount();
                                    remainingFlightSec = state.getGoHomeAssessment() != null
                                            ? state.getGoHomeAssessment().getRemainingFlightTime() : 0;
                                    float currentAlt = state.getAircraftLocation() != null ? state.getAircraftLocation().getAltitude() : 0.0f;

                                    if (state.getAircraftLocation() != null) {
                                        double droneLat = state.getAircraftLocation().getLatitude();
                                        double droneLng = state.getAircraftLocation().getLongitude();

                                        if (!Double.isNaN(droneLat) && !Double.isNaN(droneLng)) {

                                            lastDroneLocation =
                                                    new LatLng(droneLat, droneLng);

                                            Point currentPoint =
                                                    Point.fromLngLat(droneLng, droneLat);

                                            runOnUiThread(() -> {

                                                if (miniMapLibreMap != null
                                                        && miniMapLibreMap.getStyle() != null) {

                                                    SymbolLayer layer =
                                                            miniMapLibreMap
                                                                    .getStyle()
                                                                    .getLayerAs("drone-layer");

                                                    if (layer != null) {
                                                        layer.setProperties(
                                                                visibility(VISIBLE)
                                                        );
                                                    }

                                                    GeoJsonSource source =
                                                            miniMapLibreMap
                                                                    .getStyle()
                                                                    .getSourceAs("drone-source");

                                                    if (source != null) {

                                                        double yaw =
                                                                state.getAttitude().yaw;
                                                        if (yaw < 0) {
                                                            yaw += 360;
                                                        }
                                                        JsonObject properties =
                                                                new JsonObject();

                                                        properties.addProperty(
                                                                "bearing",
                                                                yaw
                                                        );

                                                        source.setGeoJson(
                                                                Feature.fromGeometry(
                                                                        Point.fromLngLat(
                                                                                lastDroneLocation.getLongitude(),
                                                                                lastDroneLocation.getLatitude()
                                                                        ),
                                                                        properties
                                                                )
                                                        );
                                                    }
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
                                        updateStatusChips(satellites);

                                        // Khoảng cách tới Home (nếu đã có Home Point), không thì tới đích cũ
                                        double homeDist = -1;
                                        try {
                                            LocationCoordinate2D home = state.getHomeLocation();
                                            if (isHomePointSet && home != null && !Double.isNaN(home.getLatitude())
                                                    && state.getAircraftLocation() != null
                                                    && !Double.isNaN(state.getAircraftLocation().getLatitude())) {
                                                homeDist = calculateHaversineDistance(
                                                        state.getAircraftLocation().getLatitude(),
                                                        state.getAircraftLocation().getLongitude(),
                                                        home.getLatitude(), home.getLongitude());
                                            }
                                        } catch (Exception ignored) {}
                                        float vSpeed = -state.getVelocityZ(); // DJI: Z dương = đi xuống

                                        String speedInfo = String.format("%s | %s", downloadSpeedText, uploadSpeedText);
                                        String net;
                                        if (tvSpeedStats != null) {
                                            String fb = firebaseBatchStatus;
                                            tvSpeedStats.setText(fb != null ? fb + "\n" + speedInfo : speedInfo);
                                            tvTelemetry.setText(String.format("Cao: %.1fm | Tốc độ: %.1fm/s\nCách đích: %.1fm", currentAlt, speed, distance));
                                            net = null; // đang mở Lưu trữ -> tốc độ mạng hiện ở đó
                                        } else {
                                            tvTelemetry.setText(String.format("Cao: %.1fm | Tốc độ: %.1fm/s\nCách đích: %.1fm | %s", currentAlt, speed, distance, speedInfo));
                                            net = speedInfo.replace(" | ", "\n");
                                        }
                                        updateTelemetryCard(currentAlt, speed, vSpeed,
                                                homeDist >= 0 ? homeDist : distance, homeDist >= 0, net);
                                    });
                                }
                            });
                        }
                    }
                } else {

                    // MẤT KẾT NỐI → ẨN ICON DRONE

                    if (miniMapLibreMap != null
                            && miniMapLibreMap.getStyle() != null) {

                        SymbolLayer layer =
                                miniMapLibreMap
                                        .getStyle()
                                        .getLayerAs("drone-layer");

                        if (layer != null) {
                            layer.setProperties(
                                    visibility(NONE)
                            );
                        }
                    }

                    lastDroneLocation = null;
                    runOnUiThread(() -> setConnectionDot(false));
                }
            });
        });

        DJIConnectionManager.getInstance().startConnection(this.getApplicationContext());

        // Thanh góc camera: progress 0..110 <-> góc -90°..+20° (giới hạn gimbal Mini 2)
        tvGimbalAngle = findViewById(R.id.tvGimbalAngle);
        seekBarGimbal.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float angle = progress - GIMBAL_OFFSET;
                showGimbalAngle(angle);
                if (missionManager != null && fromUser) missionManager.setGimbalAngle(angle);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { gimbalDragging = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { gimbalDragging = false; }
        });
        // Chạm số độ: chuyển nhanh nhìn thẳng (0°) <-> nhìn thẳng xuống (-90°)
        if (tvGimbalAngle != null) {
            tvGimbalAngle.setOnClickListener(v -> {
                float target = seekBarGimbal.getProgress() - GIMBAL_OFFSET > -45 ? -90f : 0f;
                seekBarGimbal.setProgress(Math.round(target + GIMBAL_OFFSET));
                if (missionManager != null) missionManager.setGimbalAngle(target);
            });
        }
        showGimbalAngle(seekBarGimbal.getProgress() - GIMBAL_OFFSET);
    }

    private static final int GIMBAL_OFFSET = 90;
    private TextView tvGimbalAngle;
    private volatile boolean gimbalDragging = false;
    private volatile float lastGimbalPitch = Float.NaN;

    private void showGimbalAngle(float angle) {
        if (tvGimbalAngle == null) return;
        int a = Math.round(angle);
        tvGimbalAngle.setText(a + "°");
        // Xanh khi camera nhìn thẳng xuống (góc chụp bản đồ ruộng)
        tvGimbalAngle.setTextColor(a <= -85 ? 0xFF69F0AE : 0xFFFFFFFF);
    }

    /** Gọi từ callback trạng thái gimbal: thanh chạy theo góc thật (vd khi bay WP tự hạ camera). */
    private void syncGimbalSlider(float pitch) {
        if (seekBarGimbal == null || gimbalDragging) return;
        int p = Math.max(0, Math.min(seekBarGimbal.getMax(), Math.round(pitch + GIMBAL_OFFSET)));
        if (Math.abs(p - seekBarGimbal.getProgress()) >= 1) seekBarGimbal.setProgress(p);
    }

    private void updateNetworkSpeedUI() {
        runOnUiThread(() -> {
            String speedInfo = String.format("%s | %s", downloadSpeedText, uploadSpeedText);
            if (tvSpeedStats != null) {
                if(!tvSpeedStats.getText().toString().contains("%")) {
                    tvSpeedStats.setText(speedInfo);
                }
            }
        });
    }

    // ==========================================================
    // HÀM TẢI ẢNH TỪ DRONE VÀ LƯU VÀO ALBUM ĐIỆN THOẠI (DCIM)
    // ==========================================================

    private void fetchAndDownloadAllPhotos() {
        if (DJISDKManager.getInstance().getProduct() == null) {
            Toast.makeText(this, "Chưa kết nối Drone!", Toast.LENGTH_SHORT).show();
            return;
        }

        Camera camera = ((Aircraft) DJISDKManager.getInstance().getProduct()).getCamera();
        if (camera == null) {
            Toast.makeText(this, "Không tìm thấy Camera!", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "Đang quét danh sách ảnh từ máy bay...", Toast.LENGTH_SHORT).show();

        Runnable startDownloadScan = () -> {
            MediaManager mediaManager = camera.getMediaManager();
            if (mediaManager == null) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "MediaManager chưa sẵn sàng!", Toast.LENGTH_SHORT).show());
                return;
            }

            List<MediaFile> allMediaFiles = new ArrayList<>();
            StorageAdapter dummyAdapter = new StorageAdapter(MainActivity.this, allMediaFiles);

            loadMediaFilesForActiveStorage(camera, mediaManager, allMediaFiles, dummyAdapter, () -> {
                List<MediaFile> photoList = new ArrayList<>();
                for (MediaFile file : allMediaFiles) {
                    if (file != null) {
                        MediaFile.MediaType type = file.getMediaType();
                        if (type != MediaFile.MediaType.MOV && type != MediaFile.MediaType.MP4) {
                            photoList.add(file);
                        }
                    }
                }

                if (!photoList.isEmpty()) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Tìm thấy " + photoList.size() + " tấm ảnh!", Toast.LENGTH_LONG).show());
                    downloadPhotoListRecursive(camera, photoList, 0);
                } else {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Không tìm thấy file ảnh nào!", Toast.LENGTH_LONG).show());
                }
            });
        };

        switchCameraToMediaDownloadMode(camera, startDownloadScan);
    }


    // Hàm tải tuần tự từng ảnh trong danh sách về Album điện thoại
    private void downloadPhotoListRecursive(Camera camera, List<MediaFile> photoList, int index) {
        if (index >= photoList.size()) {
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, "ĐÃ TẢI XONG TOÀN BỘ " + photoList.size() + " ẢNH VỀ ALBUM!", Toast.LENGTH_LONG).show();
                camera.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO, null);
            });
            StorageAdapter.resumeScheduler();
            return;
        }

        MediaFile targetMedia = photoList.get(index);
        File destDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "DJI_Photos");
        if (!destDir.exists()) destDir.mkdirs();

        runOnUiThread(() -> Toast.makeText(MainActivity.this, "Đang tải ảnh (" + (index + 1) + "/" + photoList.size() + "): " + targetMedia.getFileName(), Toast.LENGTH_SHORT).show());

        StorageAdapter.suspendScheduler(() -> targetMedia.fetchFileData(destDir, StorageAdapter.baseName(targetMedia.getFileName()), new DownloadListener<String>() {
            @Override
            public void onStart() {}
            @Override
            public void onRateUpdate(long total, long current, long persize) {}
            @Override
            public void onProgress(long total, long current) {}

            @Override
            public void onSuccess(String filePath) {
                File realFile = StorageAdapter.resolveDownloadedFile(destDir, targetMedia.getFileName(), filePath);
                if (realFile != null) {
                    MediaScannerConnection.scanFile(MainActivity.this, new String[]{realFile.getAbsolutePath()}, null, null);
                }
                // Tải tiếp tấm tiếp theo
                downloadPhotoListRecursive(camera, photoList, index + 1);
            }

            @Override
            public void onFailure(DJIError error) {
                // Bỏ qua tấm lỗi và tiếp tục tải tấm sau
                downloadPhotoListRecursive(camera, photoList, index + 1);
            }

            @Override
            public void onRealtimeDataUpdate(byte[] bytes, long l, boolean b) {}
        }));
    }

    // Đẩy lần lượt từng file đã chọn lên Firebase (tự tải từ drone nếu chưa có trên máy).
    // Tiến trình hiện ở dòng tvSpeedStats của dialog Lưu trữ.
    private volatile String firebaseBatchStatus = null;

    private void uploadBatchToFirebaseRecursive(List<MediaFile> fileList, int index, int successCount) {
        if (index >= fileList.size()) {
            String done = "CLOUD: XONG " + successCount + "/" + fileList.size() + " FILE";
            firebaseBatchStatus = done;
            runOnUiThread(() -> {
                if (tvSpeedStats != null) tvSpeedStats.setText(done);
                Toast.makeText(MainActivity.this, done, Toast.LENGTH_LONG).show();
            });
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (done.equals(firebaseBatchStatus)) firebaseBatchStatus = null;
            }, 6000);
            return;
        }

        MediaFile mediaFile = fileList.get(index);
        String prefix = "[" + (index + 1) + "/" + fileList.size() + "] ";
        StorageAdapter.uploadMediaToFirebase(MainActivity.this, mediaFile, new StorageAdapter.FirebaseUploadListener() {
            @Override
            public void onStatus(String status) {
                firebaseBatchStatus = prefix + status;
                if (tvSpeedStats != null) tvSpeedStats.setText(firebaseBatchStatus);
            }

            @Override
            public void onDone(boolean success, String message) {
                if (!success) {
                    Toast.makeText(MainActivity.this, prefix + message, Toast.LENGTH_SHORT).show();
                }
                uploadBatchToFirebaseRecursive(fileList, index + 1, successCount + (success ? 1 : 0));
            }
        });
    }

    // Hàm tải hàng loạt các file (ảnh & video) đã chọn từ drone về Bộ sưu tập điện thoại
    private void downloadBatchSelectedFilesRecursive(List<MediaFile> fileList, int index, int successCount) {
        if (fileList == null || index >= fileList.size()) {
            runOnUiThread(() -> {
                int total = (fileList != null) ? fileList.size() : 0;
                Toast.makeText(
                        MainActivity.this,
                        "ĐÃ TẢI XONG TOÀN BỘ " + successCount + "/" + total + " FILE VÀO BỘ SỰ TẬP!",
                        Toast.LENGTH_LONG
                ).show();
            });
            StorageAdapter.resumeScheduler();
            return;
        }

        MediaFile mediaFile = fileList.get(index);
        if (mediaFile == null || mediaFile.getFileName() == null) {
            downloadBatchSelectedFilesRecursive(fileList, index + 1, successCount);
            return;
        }

        MediaFile.MediaType type = mediaFile.getMediaType();
        boolean isVideo = (type == MediaFile.MediaType.MOV || type == MediaFile.MediaType.MP4);

        File destDir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                isVideo ? "DJI_Videos" : "DJI_Photos"
        );
        if (!destDir.exists()) {
            boolean created = destDir.mkdirs();
            if (!created && !destDir.exists()) {
                destDir = new File(getExternalFilesDir(null), "DJI_Downloads");
                if (!destDir.exists()) destDir.mkdirs();
            }
        }

        final File finalDestDir = destDir;
        final String fileName = mediaFile.getFileName();

        runOnUiThread(() -> Toast.makeText(
                MainActivity.this,
                "Đang tải (" + (index + 1) + "/" + fileList.size() + "): " + fileName,
                Toast.LENGTH_SHORT
        ).show());

        StorageAdapter.suspendScheduler(() -> mediaFile.fetchFileData(finalDestDir, StorageAdapter.baseName(fileName), new DownloadListener<String>() {
            @Override public void onStart() {}
            @Override public void onRateUpdate(long total, long current, long persize) {}
            @Override public void onProgress(long total, long current) {}

            @Override
            public void onSuccess(String filePath) {
                File realFile = StorageAdapter.resolveDownloadedFile(finalDestDir, fileName, filePath);
                if (realFile != null) {
                    MediaScannerConnection.scanFile(
                            MainActivity.this,
                            new String[]{realFile.getAbsolutePath()},
                            new String[]{isVideo ? "video/*" : "image/*"},
                            null
                    );
                }
                downloadBatchSelectedFilesRecursive(fileList, index + 1, successCount + 1);
            }

            @Override
            public void onFailure(DJIError error) {
                runOnUiThread(() -> Toast.makeText(
                        MainActivity.this,
                        "Lỗi tải " + fileName + ": " + (error != null ? error.getDescription() : "Thất bại"),
                        Toast.LENGTH_SHORT
                ).show());
                downloadBatchSelectedFilesRecursive(fileList, index + 1, successCount);
            }

            @Override public void onRealtimeDataUpdate(byte[] bytes, long l, boolean b) {}
        }));
    }

    // ==========================================================
    // HÀM ĐẨY ẢNH LÊN SERVER VÀ THEO DÕI TỐC ĐỘ (UPLOAD SPEED)
    // ==========================================================
    public void uploadPhotoToServer(File file, String serverUrl) {
        if (file == null || !file.exists()) return;

        OkHttpClient client = new OkHttpClient();

        ProgressRequestBody fileBody = new ProgressRequestBody(file, "image/jpeg", (bytesWritten, contentLength, speedKBps) -> {
            if (speedKBps >= 1024.0) {
                uploadSpeedText = String.format(Locale.US, "UL: %.2f MB/s", speedKBps / 1024.0);
            } else {
                uploadSpeedText = String.format(Locale.US, "UL: %.1f KB/s", speedKBps);
            }
            updateNetworkSpeedUI();
        });

        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.getName(), fileBody)
                .build();

        Request request = new Request.Builder()
                .url(serverUrl)
                .post(requestBody)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                uploadSpeedText = "UL: 0 KB/s";
                updateNetworkSpeedUI();
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Upload thất bại: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                uploadSpeedText = "UL: 0 KB/s";
                updateNetworkSpeedUI();
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Đã upload ảnh lên Server thành công!", Toast.LENGTH_SHORT).show());
            }
        });
    }

    public static class ProgressRequestBody extends RequestBody {
        private final File file;
        private final String contentType;
        private final UploadProgressListener listener;

        public interface UploadProgressListener {
            void onProgress(long bytesWritten, long contentLength, double speedKBps);
        }

        public ProgressRequestBody(File file, String contentType, UploadProgressListener listener) {
            this.file = file;
            this.contentType = contentType;
            this.listener = listener;
        }

        @Override public MediaType contentType() { return MediaType.parse(contentType); }
        @Override public long contentLength() { return file.length(); }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {
            long fileLength = contentLength();
            byte[] buffer = new byte[4096];
            long uploaded = 0;
            long lastTime = System.currentTimeMillis();
            long lastUploaded = 0;

            try (FileInputStream in = new FileInputStream(file)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    uploaded += read;
                    sink.write(buffer, 0, read);

                    long currentTime = System.currentTimeMillis();
                    long timeDiff = currentTime - lastTime;
                    if (timeDiff >= 400) {
                        double speedKBps = ((uploaded - lastUploaded) / 1024.0) / (timeDiff / 1000.0);
                        if (listener != null) {
                            listener.onProgress(uploaded, fileLength, speedKBps);
                        }
                        lastTime = currentTime;
                        lastUploaded = uploaded;
                    }
                }
            }
        }
    }

    private void setupCameraModeSwipe() {
        updateCameraModeUI(true, false);
        // THÊM 2 DÒNG NÀY: Cho phép lấy tay chạm trực tiếp vào chữ Video / Ảnh
        tvCameraVideo.setOnClickListener(v -> selectCameraMode(false));
        tvCameraPhoto.setOnClickListener(v -> selectCameraMode(true));

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
                    if (diffY < -20) {
                        selectCameraMode(true);
                    } else if (diffY > 20) {
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
            updateCameraModeUI(true, true);
            setCameraPhotoMode();
            btnVideoPhoto.setVisibility(View.GONE);
            btnCaptureManual.setBackgroundResource(R.drawable.bg_camera_shutter);
            btnCaptureManual.setSelected(false);
            btnCaptureManual.setContentDescription("Chụp ảnh");
        } else {
            updateCameraModeUI(false, true);
            setCameraVideoMode();
            btnVideoPhoto.setVisibility(View.VISIBLE);
            btnCaptureManual.setBackgroundResource(R.drawable.bg_video_record);
            btnCaptureManual.setSelected(false);
            btnCaptureManual.setContentDescription("Quay video");
        }
    }

    private void updateCameraModeUI(boolean photo, boolean animate) {
        TextView selected;
        TextView unselected;

        if (photo) {
            selected = tvCameraPhoto;
            unselected = tvCameraVideo;
        } else {
            selected = tvCameraVideo;
            unselected = tvCameraPhoto;
        }

        // Chữ + biểu tượng: tối trên khối vàng (đang chọn), trắng mờ khi không chọn
        android.content.res.ColorStateList on = android.content.res.ColorStateList.valueOf(0xFF1A1A1A);
        android.content.res.ColorStateList off = android.content.res.ColorStateList.valueOf(0xD9FFFFFF);
        selected.setTextColor(on);
        selected.setCompoundDrawableTintList(on);
        unselected.setTextColor(off);
        unselected.setCompoundDrawableTintList(off);

        // Khối vàng trượt tới chế độ đang chọn (VIDEO ở trên, ẢNH ở dưới)
        if (cameraModeThumb != null) {
            float target = photo ? 52 * getResources().getDisplayMetrics().density : 0f;
            cameraModeThumb.animate().cancel();
            if (animate) {
                cameraModeThumb.animate().translationY(target).setDuration(200)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            } else {
                cameraModeThumb.setTranslationY(target);
            }
        }
    }

    private void setCameraPhotoMode() {
        if (DJISDKManager.getInstance().getProduct() == null) {
            Toast.makeText(this, "Chưa kết nối Drone!", Toast.LENGTH_SHORT).show();
            return;
        }
        Camera camera = ((Aircraft) DJISDKManager.getInstance().getProduct()).getCamera();
        if (camera == null) return;
        camera.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO, djiError -> {
            runOnUiThread(() -> {
                if (djiError == null) {
                    isCurrentModePhoto = true;
                    isRecordingVideo = false;
                    updateCameraModeUI(true, true);
                } else {
                    Toast.makeText(MainActivity.this, "Không chuyển được sang chế độ ảnh: " + djiError.getDescription(), Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void setCameraVideoMode() {
        if (DJISDKManager.getInstance().getProduct() == null) {
            Toast.makeText(this, "Chưa kết nối Drone!", Toast.LENGTH_SHORT).show();
            return;
        }
        Camera camera = ((Aircraft) DJISDKManager.getInstance().getProduct()).getCamera();
        if (camera == null) return;
        camera.setMode(SettingsDefinitions.CameraMode.RECORD_VIDEO, djiError -> {
            runOnUiThread(() -> {
                if (djiError == null) {
                    isCurrentModePhoto = false;
                    isRecordingVideo = false;
                    updateCameraModeUI(false, true);
                } else {
                    Toast.makeText(MainActivity.this, "Không chuyển được sang chế độ quay: " + djiError.getDescription(), Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    // =========================================================
    // HỘP THOẠI CÀI ĐẶT (nút ⚙): Thẻ nhớ + độ cao Return To Home
    // =========================================================
    private static final int GO_HOME_MIN_M = 20;
    private static final int GO_HOME_MAX_M = 500;

    private void showSettingsMenu() {
        android.app.Dialog d = new android.app.Dialog(this, R.style.CfDialog);
        d.setContentView(R.layout.dialog_settings);
        d.setCancelable(true);

        Aircraft aircraft = DJISDKManager.getInstance().getProduct() instanceof Aircraft
                ? (Aircraft) DJISDKManager.getInstance().getProduct() : null;
        boolean connected = aircraft != null && aircraft.isConnected();

        TextView tvConn = d.findViewById(R.id.tvStConn);
        tvConn.setText(connected ? "● ĐÃ KẾT NỐI" : "● CHƯA KẾT NỐI");
        tvConn.setTextColor(connected ? 0xFF69F0AE : 0xFFFF6E66);
        tvConn.setBackgroundTintList(connected ? null
                : android.content.res.ColorStateList.valueOf(0x40F44336));
        d.findViewById(R.id.btnStClose).setOnClickListener(v -> d.dismiss());

        // ---------- THẺ NHỚ ----------
        Button btnFormat = d.findViewById(R.id.btnStFormat);
        Runnable refreshSd = () -> bindSdInfo(d, connected);
        refreshSd.run();
        btnFormat.setOnClickListener(v -> showFormatConfirmDialog(() -> {
            btnFormat.setEnabled(false);
            btnFormat.setText("ĐANG FORMAT…");
            formatSDCard(ok -> {
                btnFormat.setText("🗑  FORMAT THẺ NHỚ");
                // Chờ StorageStateCallback cập nhật dung lượng mới rồi vẽ lại
                new Handler(Looper.getMainLooper()).postDelayed(refreshSd, 800);
            });
        }));

        // ---------- RETURN TO HOME ----------
        bindGoHomeHeight(d, connected ? aircraft.getFlightController() : null);

        // ---------- TAB Chung / Camera ----------
        View tabGeneral = d.findViewById(R.id.stTabGeneral);
        View tabCamera = d.findViewById(R.id.stTabCamera);
        ((android.widget.RadioGroup) d.findViewById(R.id.rgStTabs)).setOnCheckedChangeListener((g, id) -> {
            boolean cam = id == R.id.rbStTabCamera;
            tabGeneral.setVisibility(cam ? View.GONE : View.VISIBLE);
            tabCamera.setVisibility(cam ? View.VISIBLE : View.GONE);
        });
        Camera expCamera = connected ? aircraft.getCamera() : null;
        bindCameraExposure(d, expCamera);
        d.setOnDismissListener(x -> {
            if (expCamera != null) expCamera.setExposureSettingsCallback(null);
        });

        d.show();
        if (d.getWindow() != null) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int width = (int) Math.min(dm.widthPixels * 0.9f, 660 * dm.density);
            d.getWindow().setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        }
    }

    // =========================================================
    // MINI MAP: Bản đồ / Vệ tinh + đổi chỗ với khung camera
    // =========================================================
    private void toggleMiniMapSatellite() {
        if (miniMapLibreMap == null || miniMapLibreMap.getStyle() == null) return;
        miniMapSatellite = !miniMapSatellite;
        Style style = miniMapLibreMap.getStyle();
        org.maplibre.android.style.layers.Layer sat = style.getLayer("sat-tiles-layer");
        org.maplibre.android.style.layers.Layer osm = style.getLayer("osm-tiles-layer");
        if (sat != null) sat.setProperties(visibility(miniMapSatellite ? VISIBLE : NONE));
        if (osm != null) osm.setProperties(visibility(miniMapSatellite ? NONE : VISIBLE));
        ImageButton btn = findViewById(R.id.btnMiniMapType);
        btn.setImageTintList(android.content.res.ColorStateList.valueOf(miniMapSatellite ? C_GOOD : 0xFFFFFFFF));
        Toast.makeText(this, miniMapSatellite ? "Bản đồ vệ tinh" : "Bản đồ thường", Toast.LENGTH_SHORT).show();
    }

    /** Đưa bản đồ về vị trí điện thoại và tiếp tục bám theo khi di chuyển. */
    private void centerMiniMapOnMe() {
        if (miniMapLibreMap == null || miniMapLibreMap.getStyle() == null) return;
        LocationComponent lc = miniMapLibreMap.getLocationComponent();
        if (!lc.isLocationComponentActivated()) {
            // Chưa có quyền vị trí -> xin lại (setupPhoneLocationMarker tự hỏi quyền)
            setupPhoneLocationMarker(miniMapLibreMap.getStyle());
            return;
        }
        Location loc = lc.getLastKnownLocation();
        if (loc == null) {
            Toast.makeText(this, "Đang lấy vị trí GPS của điện thoại…", Toast.LENGTH_SHORT).show();
            lc.setCameraMode(CameraMode.TRACKING);
            return;
        }
        double zoom = Math.max(miniMapLibreMap.getCameraPosition().zoom, 17.0);
        miniMapLibreMap.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(
                new LatLng(loc.getLatitude(), loc.getLongitude()), zoom), 600,
                new MapLibreMap.CancelableCallback() {
                    @Override public void onCancel() { }
                    @Override public void onFinish() { lc.setCameraMode(CameraMode.TRACKING); }
                });
    }

    /** Đổi chỗ bản đồ và camera. Bản đồ to thì camera thu về góc dưới trái (chạm vào để đổi lại). */
    private void setMapExpanded(boolean expand) {
        if (miniMap == null || videoSurface == null) return;
        mapExpanded = expand;
        float d = getResources().getDisplayMetrics().density;
        int margin = Math.round(8 * d);

        android.widget.RelativeLayout.LayoutParams mapLp =
                (android.widget.RelativeLayout.LayoutParams) miniMap.getLayoutParams();
        android.widget.RelativeLayout.LayoutParams vidLp =
                (android.widget.RelativeLayout.LayoutParams) videoSurface.getLayoutParams();
        if (expand) {
            mapLp.width = mapLp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
            mapLp.setMargins(0, 0, 0, 0);
            // Z âm: bản đồ nằm dưới mọi nút/thanh trạng thái
            miniMap.setElevation(0f);
            miniMap.setTranslationZ(-4 * d);

            vidLp.width = Math.round(178 * d);   // 16:9
            vidLp.height = Math.round(100 * d);
            vidLp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_START);
            vidLp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM);
            vidLp.setMargins(margin, margin, margin, margin);
            videoSurface.setElevation(10 * d);
            videoSurface.setOnClickListener(v -> setMapExpanded(false));
        } else {
            mapLp.width = Math.round(210 * d);
            mapLp.height = Math.round(100 * d);
            mapLp.setMargins(margin, margin, margin, margin);
            miniMap.setTranslationZ(0f);
            miniMap.setElevation(10 * d);

            vidLp.width = vidLp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
            vidLp.removeRule(android.widget.RelativeLayout.ALIGN_PARENT_START);
            vidLp.removeRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM);
            vidLp.setMargins(0, 0, 0, 0);
            videoSurface.setElevation(0f);
            videoSurface.setOnClickListener(null);
            videoSurface.setClickable(false);
        }
        miniMap.setLayoutParams(mapLp);
        videoSurface.setLayoutParams(vidLp);
        // Viền + nhãn CAMERA quanh khung camera thu nhỏ (nằm trên video, nhận chạm để đổi lại)
        View pipFrame = findViewById(R.id.pipFrame);
        if (pipFrame != null) {
            pipFrame.setVisibility(expand ? View.VISIBLE : View.GONE);
            pipFrame.setOnClickListener(expand ? v -> setMapExpanded(false) : null);
        }
        applyMiniMapUiMode();
        refreshMiniMapTilesAfterResize();
    }

    /**
     * Sau khi đổi kích thước khung bản đồ mà camera đứng yên, MapLibre không tính lại các tile cần tải
     * -> chỉ ô cũ có ảnh. Nhích camera một chút (sau khi layout xong) để nó tải đủ tile cho khung mới.
     */
    private void refreshMiniMapTilesAfterResize() {
        if (miniMapView == null) return;
        Runnable nudge = () -> {
            if (miniMapLibreMap == null) return;
            org.maplibre.android.camera.CameraPosition cp = miniMapLibreMap.getCameraPosition();
            miniMapLibreMap.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.zoomTo(cp.zoom + 0.001));
            miniMapLibreMap.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.zoomTo(cp.zoom));
            miniMapLibreMap.triggerRepaint();
        };
        miniMapView.post(nudge);
        miniMapView.postDelayed(nudge, 350); // lần 2: sau khi GL surface đã đổi kích thước
    }

    /** Cử chỉ bản đồ + vị trí nút đổi kiểu theo chế độ thu nhỏ / toàn màn hình. */
    private void applyMiniMapUiMode() {
        float d = getResources().getDisplayMetrics().density;
        if (ivCrosshair != null) ivCrosshair.setVisibility(mapExpanded ? View.GONE : View.VISIBLE);
        View btnMe = findViewById(R.id.btnMiniMapMyLocation);
        if (btnMe != null) btnMe.setVisibility(mapExpanded ? View.VISIBLE : View.GONE);
        if (miniMapLibreMap != null) {
            // Nhãn tên ruộng chỉ hiện khi bản đồ to (mini map nhỏ quá sẽ rối)
            com.tri.djicontrol.field.SavedFieldsLayer.setLabelsVisible(miniMapLibreMap.getStyle(), mapExpanded);
        }

        View btn = findViewById(R.id.btnMiniMapType);
        if (btn != null) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) btn.getLayoutParams();
            int size = Math.round((mapExpanded ? 40 : 28) * d);
            lp.width = lp.height = size;
            int m = Math.round(4 * d);
            // Toàn màn hình: tránh thanh trạng thái (48dp) ở trên
            lp.setMargins(m, mapExpanded ? Math.round(56 * d) : m, mapExpanded ? Math.round(12 * d) : m, m);
            btn.setLayoutParams(lp);
            int pad = Math.round((mapExpanded ? 9 : 5) * d);
            btn.setPadding(pad, pad, pad, pad);
        }

        if (miniMapLibreMap != null) {
            org.maplibre.android.maps.UiSettings ui = miniMapLibreMap.getUiSettings();
            ui.setScrollGesturesEnabled(mapExpanded);
            ui.setZoomGesturesEnabled(mapExpanded);
            ui.setRotateGesturesEnabled(false);
            ui.setTiltGesturesEnabled(false);
            ui.setDoubleTapGesturesEnabled(mapExpanded);
            // Bản đồ to: góc dưới trái là khung camera, giữa dưới là thẻ thông số
            // -> logo + nút (i) dời sang góc dưới phải, chừa chỗ cho nút thư viện ảnh
            int bottom = Math.round(4 * d);
            if (mapExpanded) {
                int gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
                ui.setLogoGravity(gravity);
                ui.setAttributionGravity(gravity);
                ui.setAttributionMargins(0, 0, Math.round(112 * d), bottom);
                ui.setLogoMargins(0, 0, Math.round(136 * d), bottom);
            } else {
                int gravity = android.view.Gravity.BOTTOM | android.view.Gravity.START;
                ui.setLogoGravity(gravity);
                ui.setAttributionGravity(gravity);
                ui.setLogoMargins(Math.round(4 * d), 0, 0, bottom);
                ui.setAttributionMargins(Math.round(96 * d), 0, 0, bottom);
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (mapExpanded) { setMapExpanded(false); return; }
        super.onBackPressed();
    }

    // =========================================================
    // TAB CAMERA: chế độ phơi sáng, màn trập, ISO, EV
    // =========================================================
    /** Màn trập từ chậm -> nhanh (bấm + = nhanh hơn = ít nhòe hơn). */
    private static final SettingsDefinitions.ShutterSpeed[] SHUTTERS = {
            SettingsDefinitions.ShutterSpeed.SHUTTER_SPEED_1_30, SettingsDefinitions.ShutterSpeed.SHUTTER_SPEED_1_60,
            SettingsDefinitions.ShutterSpeed.SHUTTER_SPEED_1_100, SettingsDefinitions.ShutterSpeed.SHUTTER_SPEED_1_120,
            SettingsDefinitions.ShutterSpeed.SHUTTER_SPEED_1_160, SettingsDefinitions.ShutterSpeed.SHUTTER_SPEED_1_200,
            SettingsDefinitions.ShutterSpeed.SHUTTER_SPEED_1_240, SettingsDefinitions.ShutterSpeed.SHUTTER_SPEED_1_320,
            SettingsDefinitions.ShutterSpeed.SHUTTER_SPEED_1_400, SettingsDefinitions.ShutterSpeed.SHUTTER_SPEED_1_500,
            SettingsDefinitions.ShutterSpeed.SHUTTER_SPEED_1_640, SettingsDefinitions.ShutterSpeed.SHUTTER_SPEED_1_800,
            SettingsDefinitions.ShutterSpeed.SHUTTER_SPEED_1_1000, SettingsDefinitions.ShutterSpeed.SHUTTER_SPEED_1_1250,
            SettingsDefinitions.ShutterSpeed.SHUTTER_SPEED_1_1600, SettingsDefinitions.ShutterSpeed.SHUTTER_SPEED_1_2000,
            SettingsDefinitions.ShutterSpeed.SHUTTER_SPEED_1_2500, SettingsDefinitions.ShutterSpeed.SHUTTER_SPEED_1_3200,
            SettingsDefinitions.ShutterSpeed.SHUTTER_SPEED_1_4000, SettingsDefinitions.ShutterSpeed.SHUTTER_SPEED_1_5000,
            SettingsDefinitions.ShutterSpeed.SHUTTER_SPEED_1_6400, SettingsDefinitions.ShutterSpeed.SHUTTER_SPEED_1_8000};
    private static final SettingsDefinitions.ISO[] ISOS = {
            SettingsDefinitions.ISO.ISO_100, SettingsDefinitions.ISO.ISO_200, SettingsDefinitions.ISO.ISO_400,
            SettingsDefinitions.ISO.ISO_800, SettingsDefinitions.ISO.ISO_1600, SettingsDefinitions.ISO.ISO_3200};
    private static final SettingsDefinitions.ExposureCompensation[] EVS = {
            SettingsDefinitions.ExposureCompensation.N_3_0, SettingsDefinitions.ExposureCompensation.N_2_7,
            SettingsDefinitions.ExposureCompensation.N_2_3, SettingsDefinitions.ExposureCompensation.N_2_0,
            SettingsDefinitions.ExposureCompensation.N_1_7, SettingsDefinitions.ExposureCompensation.N_1_3,
            SettingsDefinitions.ExposureCompensation.N_1_0, SettingsDefinitions.ExposureCompensation.N_0_7,
            SettingsDefinitions.ExposureCompensation.N_0_3, SettingsDefinitions.ExposureCompensation.N_0_0,
            SettingsDefinitions.ExposureCompensation.P_0_3, SettingsDefinitions.ExposureCompensation.P_0_7,
            SettingsDefinitions.ExposureCompensation.P_1_0, SettingsDefinitions.ExposureCompensation.P_1_3,
            SettingsDefinitions.ExposureCompensation.P_1_7, SettingsDefinitions.ExposureCompensation.P_2_0,
            SettingsDefinitions.ExposureCompensation.P_2_3, SettingsDefinitions.ExposureCompensation.P_2_7,
            SettingsDefinitions.ExposureCompensation.P_3_0};
    private static final int SHUTTER_ANTI_BLUR_INDEX = 12; // 1/1000

    /** "SHUTTER_SPEED_1_1000" -> "1/1000"; "SHUTTER_SPEED_1" -> "1\"" */
    private static String shutterLabel(SettingsDefinitions.ShutterSpeed s) {
        if (s == null) return "—";
        String n = s.name().replace("SHUTTER_SPEED_", "");
        if (n.startsWith("1_")) return "1/" + n.substring(2).replace("_", ".");
        return n.replace("_", ".") + "\"";
    }

    private static String isoLabel(SettingsDefinitions.ISO iso) {
        if (iso == null) return "—";
        return iso == SettingsDefinitions.ISO.AUTO ? "Tự động" : iso.name().replace("ISO_", "");
    }

    /** "N_1_3" -> "−1.3", "P_0_7" -> "+0.7", "N_0_0" -> "0" */
    private static String evLabel(SettingsDefinitions.ExposureCompensation ev) {
        if (ev == null || !ev.name().matches("[NP]_\\d_\\d")) return "—";
        String n = ev.name();
        String v = n.charAt(2) + "." + n.charAt(4);
        if (v.equals("0.0")) return "0";
        return (n.charAt(0) == 'N' ? "−" : "+") + v;
    }

    private static <T> int indexOf(T[] arr, T v) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return -1;
    }

    private void bindCameraExposure(android.app.Dialog d, Camera camera) {
        android.widget.RadioGroup rgMode = d.findViewById(R.id.rgStExpMode);
        TextView tvLive = d.findViewById(R.id.tvStExpLive);
        TextView tvShutter = d.findViewById(R.id.tvStShutter);
        TextView tvIso = d.findViewById(R.id.tvStIso);
        TextView tvEv = d.findViewById(R.id.tvStEv);
        Button btnPreset = d.findViewById(R.id.btnStExpPreset);
        int[] rows = {R.id.stRowShutter, R.id.stRowIso, R.id.stRowEv};

        // Trạng thái đang hiển thị (index trong mảng; -1 = chưa biết)
        final int[] shutterIdx = {-1}, isoIdx = {-1}, evIdx = {-1};
        final boolean[] manual = {false};
        final boolean[] binding = {false}; // đang cập nhật UI từ camera -> bỏ qua listener

        Runnable render = () -> {
            tvShutter.setText(shutterIdx[0] >= 0 ? shutterLabel(SHUTTERS[shutterIdx[0]]) : "—");
            tvIso.setText(isoIdx[0] >= 0 ? isoLabel(ISOS[isoIdx[0]]) : "—");
            tvEv.setText(evIdx[0] >= 0 ? evLabel(EVS[evIdx[0]]) : "—");
            boolean on = camera != null;
            setRowEnabled(d.findViewById(rows[0]), on && manual[0]);
            setRowEnabled(d.findViewById(rows[1]), on && manual[0]);
            setRowEnabled(d.findViewById(rows[2]), on && !manual[0]);
            binding[0] = true;
            rgMode.check(manual[0] ? R.id.rbStExpManual : R.id.rbStExpAuto);
            binding[0] = false;
            // Cảnh báo nếu màn trập thủ công chậm hơn 1/1000 (dễ nhòe khi bay)
            tvShutter.setTextColor(manual[0] && shutterIdx[0] >= 0 && shutterIdx[0] < SHUTTER_ANTI_BLUR_INDEX
                    ? C_WARN : 0xFFFFFFFF);
        };

        if (camera == null) {
            tvLive.setText("Kết nối drone để chỉnh camera");
            for (int i = 0; i < rgMode.getChildCount(); i++) rgMode.getChildAt(i).setEnabled(false);
            btnPreset.setEnabled(false);
            render.run();
            return;
        }

        // Đọc thông số hiện tại từ camera
        Runnable readAll = () -> {
            camera.getExposureMode(new CommonCallbacks.CompletionCallbackWith<SettingsDefinitions.ExposureMode>() {
                @Override public void onSuccess(SettingsDefinitions.ExposureMode m) {
                    runOnUiThread(() -> { manual[0] = m == SettingsDefinitions.ExposureMode.MANUAL; render.run(); });
                }
                @Override public void onFailure(DJIError e) { }
            });
            camera.getShutterSpeed(new CommonCallbacks.CompletionCallbackWith<SettingsDefinitions.ShutterSpeed>() {
                @Override public void onSuccess(SettingsDefinitions.ShutterSpeed v) {
                    runOnUiThread(() -> { shutterIdx[0] = indexOf(SHUTTERS, v); render.run(); });
                }
                @Override public void onFailure(DJIError e) { }
            });
            camera.getISO(new CommonCallbacks.CompletionCallbackWith<SettingsDefinitions.ISO>() {
                @Override public void onSuccess(SettingsDefinitions.ISO v) {
                    runOnUiThread(() -> { isoIdx[0] = indexOf(ISOS, v); render.run(); });
                }
                @Override public void onFailure(DJIError e) { }
            });
            camera.getExposureCompensation(new CommonCallbacks.CompletionCallbackWith<SettingsDefinitions.ExposureCompensation>() {
                @Override public void onSuccess(SettingsDefinitions.ExposureCompensation v) {
                    runOnUiThread(() -> { evIdx[0] = indexOf(EVS, v); render.run(); });
                }
                @Override public void onFailure(DJIError e) { }
            });
        };
        readAll.run();

        // Thông số camera đang thực sự dùng (cả khi tự động)
        camera.setExposureSettingsCallback(es -> {
            if (es == null) return;
            String text = shutterLabel(es.getShutterSpeed()) + " s  ·  ISO " + es.getISO()
                    + "  ·  EV " + evLabel(es.getExposureCompensation());
            boolean slow = indexOf(SHUTTERS, es.getShutterSpeed()) >= 0
                    && indexOf(SHUTTERS, es.getShutterSpeed()) < SHUTTER_ANTI_BLUR_INDEX;
            runOnUiThread(() -> {
                tvLive.setText(text);
                tvLive.setTextColor(slow ? C_WARN : 0xFFFFFFFF);
            });
        });

        java.util.function.Consumer<DJIError> onResult = err -> runOnUiThread(() -> {
            if (err != null) {
                Toast.makeText(MainActivity.this, "Camera không nhận: " + err.getDescription(), Toast.LENGTH_LONG).show();
            }
            readAll.run(); // luôn hiển thị đúng giá trị camera đang giữ
        });

        rgMode.setOnCheckedChangeListener((g, id) -> {
            if (binding[0]) return;
            boolean wantManual = id == R.id.rbStExpManual;
            manual[0] = wantManual;
            render.run();
            camera.setExposureMode(wantManual ? SettingsDefinitions.ExposureMode.MANUAL
                    : SettingsDefinitions.ExposureMode.PROGRAM, onResult::accept);
        });

        d.findViewById(R.id.btnStShutterMinus).setOnClickListener(v -> stepShutter(camera, shutterIdx, -1, render, onResult));
        d.findViewById(R.id.btnStShutterPlus).setOnClickListener(v -> stepShutter(camera, shutterIdx, +1, render, onResult));
        d.findViewById(R.id.btnStIsoMinus).setOnClickListener(v -> stepIso(camera, isoIdx, -1, render, onResult));
        d.findViewById(R.id.btnStIsoPlus).setOnClickListener(v -> stepIso(camera, isoIdx, +1, render, onResult));
        d.findViewById(R.id.btnStEvMinus).setOnClickListener(v -> stepEv(camera, evIdx, -1, render, onResult));
        d.findViewById(R.id.btnStEvPlus).setOnClickListener(v -> stepEv(camera, evIdx, +1, render, onResult));

        // Chống nhòe: thủ công + 1/1000 s (giữ ISO đang dùng, mặc định 200 nếu chưa biết)
        btnPreset.setOnClickListener(v -> {
            btnPreset.setEnabled(false);
            camera.setExposureMode(SettingsDefinitions.ExposureMode.MANUAL, e1 -> {
                if (e1 != null) { btnPreset.post(() -> btnPreset.setEnabled(true)); onResult.accept(e1); return; }
                camera.setShutterSpeed(SHUTTERS[SHUTTER_ANTI_BLUR_INDEX], e2 -> {
                    if (e2 != null || isoIdx[0] >= 0) {
                        btnPreset.post(() -> btnPreset.setEnabled(true));
                        onResult.accept(e2);
                        if (e2 == null) runOnUiThread(() -> Toast.makeText(this,
                                "Đã đặt 1/1000 s. Ảnh tối thì tăng ISO.", Toast.LENGTH_LONG).show());
                        return;
                    }
                    camera.setISO(SettingsDefinitions.ISO.ISO_200, e3 -> {
                        btnPreset.post(() -> btnPreset.setEnabled(true));
                        onResult.accept(e3);
                        if (e3 == null) runOnUiThread(() -> Toast.makeText(this,
                                "Đã đặt 1/1000 s · ISO 200. Ảnh tối thì tăng ISO.", Toast.LENGTH_LONG).show());
                    });
                });
            });
        });
        render.run();
    }

    private static void setRowEnabled(View row, boolean enabled) {
        if (!(row instanceof android.view.ViewGroup)) return;
        row.setAlpha(enabled ? 1f : 0.4f);
        android.view.ViewGroup g = (android.view.ViewGroup) row;
        for (int i = 0; i < g.getChildCount(); i++) g.getChildAt(i).setEnabled(enabled);
    }

    private void stepShutter(Camera camera, int[] idx, int dir, Runnable render, java.util.function.Consumer<DJIError> onResult) {
        int n = idx[0] < 0 ? SHUTTER_ANTI_BLUR_INDEX : Math.max(0, Math.min(SHUTTERS.length - 1, idx[0] + dir));
        idx[0] = n;
        render.run();
        camera.setShutterSpeed(SHUTTERS[n], onResult::accept);
    }

    private void stepIso(Camera camera, int[] idx, int dir, Runnable render, java.util.function.Consumer<DJIError> onResult) {
        int n = idx[0] < 0 ? 1 : Math.max(0, Math.min(ISOS.length - 1, idx[0] + dir));
        idx[0] = n;
        render.run();
        camera.setISO(ISOS[n], onResult::accept);
    }

    private void stepEv(Camera camera, int[] idx, int dir, Runnable render, java.util.function.Consumer<DJIError> onResult) {
        int n = idx[0] < 0 ? indexOf(EVS, SettingsDefinitions.ExposureCompensation.N_0_0)
                : Math.max(0, Math.min(EVS.length - 1, idx[0] + dir));
        idx[0] = n;
        render.run();
        camera.setExposureCompensation(EVS[n], onResult::accept);
    }

    private static String formatMB(int mb) {
        return mb >= 1024 ? String.format(Locale.US, "%.1f GB", mb / 1024f) : mb + " MB";
    }

    /** Vẽ khối Thẻ nhớ theo trạng thái hiện tại. */
    private void bindSdInfo(android.app.Dialog d, boolean connected) {
        TextView tvUsed = d.findViewById(R.id.tvStSdUsed);
        TextView tvState = d.findViewById(R.id.tvStSdState);
        TextView tvFree = d.findViewById(R.id.tvStSdFree);
        ProgressBar pb = d.findViewById(R.id.pbStSd);
        Button btnFormat = d.findViewById(R.id.btnStFormat);

        String state;
        int color;
        if (!connected) { state = "CHƯA KẾT NỐI"; color = 0xFF9AA0A6; }
        else if (!sdInserted) { state = "CHƯA CẮM"; color = 0xFFFF6E66; }
        else if (sdHasError) { state = "LỖI"; color = 0xFFFF6E66; }
        else if (sdFull) { state = "ĐẦY"; color = 0xFFFFD54F; }
        else { state = "OK"; color = 0xFF69F0AE; }
        tvState.setText(state);
        tvState.setTextColor(color);
        tvState.setBackgroundTintList(android.content.res.ColorStateList.valueOf((color & 0x00FFFFFF) | 0x26000000));

        if (connected && sdInserted && sdTotalMB > 0) {
            int used = Math.max(0, sdTotalMB - sdRemainMB);
            tvUsed.setText(formatMB(used) + " / " + formatMB(sdTotalMB));
            tvFree.setText("Còn trống: " + formatMB(sdRemainMB));
            int prog = (int) (used * 1000L / sdTotalMB);
            pb.setProgress(prog);
            int bar = prog >= 900 ? 0xFFFF6E66 : prog >= 750 ? 0xFFFFD54F : 0xFF69F0AE;
            pb.setProgressTintList(android.content.res.ColorStateList.valueOf(bar));
        } else {
            tvUsed.setText("—");
            tvFree.setText(connected ? "Không đọc được dung lượng thẻ" : "Kết nối drone để xem thẻ nhớ");
            pb.setProgress(0);
        }
        btnFormat.setEnabled(connected && sdInserted);
    }

    /** Khối Độ cao RTH: đọc giá trị từ drone, chỉnh bằng −/+, thanh kéo hoặc gõ số. */
    private void bindGoHomeHeight(android.app.Dialog d, FlightController fc) {
        EditText et = d.findViewById(R.id.etStRth);
        SeekBar sb = d.findViewById(R.id.sbStRth);
        TextView tvCurrent = d.findViewById(R.id.tvStRthCurrent);
        Button btnMinus = d.findViewById(R.id.btnStRthMinus);
        Button btnPlus = d.findViewById(R.id.btnStRthPlus);
        Button btnSave = d.findViewById(R.id.btnStRthSave);
        ((TextView) d.findViewById(R.id.tvStRthMin)).setText(GO_HOME_MIN_M + " m");
        ((TextView) d.findViewById(R.id.tvStRthMax)).setText(GO_HOME_MAX_M + " m");

        sb.setMax(GO_HOME_MAX_M - GO_HOME_MIN_M);
        final int[] droneValue = {-1};       // giá trị đang lưu trên drone
        final boolean[] syncing = {false};   // tránh vòng lặp EditText <-> SeekBar

        Runnable updateSave = () -> {
            int v = parseIntOr(et.getText().toString(), -1);
            boolean valid = v >= GO_HOME_MIN_M && v <= GO_HOME_MAX_M;
            btnSave.setEnabled(fc != null && valid && v != droneValue[0]);
            et.setTextColor(valid || et.getText().length() == 0 ? 0xFFFFFFFF : 0xFFFF6E66);
        };
        java.util.function.IntConsumer setValue = v -> {
            v = Math.max(GO_HOME_MIN_M, Math.min(GO_HOME_MAX_M, v));
            syncing[0] = true;
            et.setText(String.valueOf(v));
            et.setSelection(et.getText().length());
            sb.setProgress(v - GO_HOME_MIN_M);
            syncing[0] = false;
            updateSave.run();
        };

        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && !syncing[0]) {
                    // bước 5 m khi kéo cho dễ chọn số tròn
                    int v = GO_HOME_MIN_M + Math.round(p / 5f) * 5;
                    setValue.accept(v);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) { et.clearFocus(); }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        et.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int e) { }
            @Override public void onTextChanged(CharSequence c, int a, int b, int e) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                if (syncing[0]) return;
                int v = parseIntOr(e.toString(), -1);
                if (v >= GO_HOME_MIN_M && v <= GO_HOME_MAX_M) {
                    syncing[0] = true;
                    sb.setProgress(v - GO_HOME_MIN_M);
                    syncing[0] = false;
                }
                updateSave.run();
            }
        });
        btnMinus.setOnClickListener(v -> setValue.accept(parseIntOr(et.getText().toString(), GO_HOME_MIN_M) - 5));
        btnPlus.setOnClickListener(v -> setValue.accept(parseIntOr(et.getText().toString(), GO_HOME_MIN_M) + 5));

        if (fc == null) {
            tvCurrent.setText("Kết nối drone để cài độ cao RTH");
            et.setEnabled(false);
            sb.setEnabled(false);
            btnMinus.setEnabled(false);
            btnPlus.setEnabled(false);
            btnSave.setEnabled(false);
            return;
        }

        fc.getGoHomeHeightInMeters(new CommonCallbacks.CompletionCallbackWith<Integer>() {
            @Override
            public void onSuccess(Integer h) {
                runOnUiThread(() -> {
                    droneValue[0] = h;
                    tvCurrent.setText("Đang lưu trên drone: " + h + " m");
                    setValue.accept(h);
                });
            }

            @Override
            public void onFailure(DJIError djiError) {
                runOnUiThread(() -> {
                    tvCurrent.setText("Không đọc được độ cao hiện tại");
                    setValue.accept(30);
                });
            }
        });

        btnSave.setOnClickListener(v -> {
            int h = parseIntOr(et.getText().toString(), -1);
            if (h < GO_HOME_MIN_M || h > GO_HOME_MAX_M) return;
            btnSave.setEnabled(false);
            btnSave.setText("ĐANG LƯU…");
            fc.setGoHomeHeightInMeters(h, djiError -> runOnUiThread(() -> {
                btnSave.setText("✓  LƯU ĐỘ CAO");
                if (djiError == null) {
                    droneValue[0] = h;
                    tvCurrent.setText("Đang lưu trên drone: " + h + " m");
                    Toast.makeText(MainActivity.this, "Đã đặt độ cao RTH: " + h + " m", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(MainActivity.this, "Lỗi đặt độ cao RTH: " + djiError.getDescription(), Toast.LENGTH_LONG).show();
                }
                updateSave.run();
            }));
        });
    }

    private static int parseIntOr(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    /** Xác nhận format: phải gõ FORMAT mới bật nút. */
    private void showFormatConfirmDialog(Runnable onConfirm) {
        android.app.Dialog d = new android.app.Dialog(this, R.style.CfDialog);
        d.setContentView(R.layout.dialog_format_confirm);
        d.setCancelable(true);

        TextView info = d.findViewById(R.id.tvFcInfo);
        if (sdInserted && sdTotalMB > 0) {
            info.setText("Sẽ xóa " + formatMB(Math.max(0, sdTotalMB - sdRemainMB)) + " dữ liệu · không thể hoàn tác");
        }
        EditText et = d.findViewById(R.id.etFcConfirm);
        Button btnOk = d.findViewById(R.id.btnFcFormat);
        et.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int e) { }
            @Override public void onTextChanged(CharSequence c, int a, int b, int e) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                btnOk.setEnabled("FORMAT".equalsIgnoreCase(e.toString().trim()));
            }
        });
        d.findViewById(R.id.btnFcCancel).setOnClickListener(v -> d.dismiss());
        btnOk.setOnClickListener(v -> {
            d.dismiss();
            onConfirm.run();
        });

        d.show();
        if (d.getWindow() != null) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            d.getWindow().setLayout((int) Math.min(dm.widthPixels * 0.9f, 420 * dm.density),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        }
    }

    private void formatSDCard(java.util.function.Consumer<Boolean> onDone) {
        if (!(DJISDKManager.getInstance().getProduct() instanceof Aircraft)) {
            Toast.makeText(this, "Chưa kết nối Drone!", Toast.LENGTH_SHORT).show();
            if (onDone != null) onDone.accept(false);
            return;
        }
        Camera camera = ((Aircraft) DJISDKManager.getInstance().getProduct()).getCamera();
        if (camera == null) {
            Toast.makeText(this, "Không tìm thấy Camera/Thẻ nhớ!", Toast.LENGTH_SHORT).show();
            if (onDone != null) onDone.accept(false);
            return;
        }
        camera.formatStorage(SettingsDefinitions.StorageLocation.SDCARD, djiError -> runOnUiThread(() -> {
            if (djiError == null) {
                Toast.makeText(MainActivity.this, "Format thẻ nhớ THÀNH CÔNG!", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(MainActivity.this, "Lỗi format: " + djiError.getDescription(), Toast.LENGTH_LONG).show();
            }
            if (onDone != null) onDone.accept(djiError == null);
        }));
    }

    private void triggerManualCapture() {
        if (DJISDKManager.getInstance().getProduct() == null) {
            Toast.makeText(this, "Chưa kết nối Drone!", Toast.LENGTH_SHORT).show();
            return;
        }
        Camera camera = ((Aircraft) DJISDKManager.getInstance().getProduct()).getCamera();
        if (camera == null) return;

        if (isCurrentModePhoto) {
            camera.startShootPhoto(djiError -> {
                if (djiError == null) {
                    photoCount++;
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "Đã chụp 1 tấm!", Toast.LENGTH_SHORT).show();
                    });
                } else {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Lỗi chụp: " + djiError.getDescription(), Toast.LENGTH_LONG).show());
                }
            });
            return;
        }

        if (!isRecordingVideo) {
            camera.startRecordVideo(djiError -> {
                runOnUiThread(() -> {
                    if (djiError == null) {
                        isRecordingVideo = true;
                        isVideoPaused = false;
                        startVideoTimer();
                        btnViewPhotos.setImageResource(R.drawable.ic_storage_pause);
                        btnViewPhotos.setContentDescription("Tạm dừng quay");
                        btnCaptureManual.setSelected(true);
                        btnCaptureManual.setContentDescription("Dừng quay");
                        Toast.makeText(MainActivity.this, "Bắt đầu quay Video!", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(MainActivity.this, "Lỗi quay: " + djiError.getDescription(), Toast.LENGTH_LONG).show();
                    }
                });
            });
        } else {
            camera.stopRecordVideo(djiError -> {
                runOnUiThread(() -> {
                    if (djiError == null) {
                        isRecordingVideo = false;
                        isVideoPaused = false;
                        stopVideoTimer();
                        btnCaptureManual.setSelected(false);
                        btnCaptureManual.setContentDescription("Quay video");
                        btnViewPhotos.setImageResource(R.drawable.ic_storage_gallery);
                        btnViewPhotos.setContentDescription("Mở kho lưu trữ");
                        Toast.makeText(MainActivity.this, "Đã lưu Video vào thẻ nhớ!", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(MainActivity.this, "Lỗi dừng quay: " + djiError.getDescription(), Toast.LENGTH_LONG).show();
                    }
                });
            });
        }
    }

    // =========================================================
    // THANH TRẠNG THÁI TRÊN + THẺ THÔNG SỐ BAY DƯỚI
    // =========================================================
    private static final int C_GOOD = 0xFF69F0AE, C_WARN = 0xFFFFD54F, C_BAD = 0xFFFF5252, C_IDLE = 0xFF9AA0A6;
    private View statusDot;
    private TextView tvChipGps, tvChipBattery, tvChipRc, tvChipSd, tvChipHome;
    private TextView tvTeleAlt, tvTeleHSpeed, tvTeleVSpeed, tvTeleDist, tvTeleDistLabel, tvTeleNet;

    /** true = xanh (đã kết nối), false = đỏ (mất kết nối), null = vàng (đang kết nối). */
    private void setConnectionDot(Boolean connected) {
        if (statusDot == null) return;
        int c = connected == null ? C_WARN : connected ? C_GOOD : C_BAD;
        statusDot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(c));
        if (Boolean.FALSE.equals(connected)) {
            setChip(tvChipGps, "GPS --", C_IDLE);
            remainingFlightSec = 0;
            setBatteryChip(-1);
            rcSignalPercent = 0;
            setRcSignal(0); // mất kết nối = mất sóng (đỏ)
            setChip(tvChipSd, "THẺ --", C_IDLE);
            setChip(tvChipHome, "HOME", C_IDLE);
        }
    }

    private SignalBarsDrawable rcBars;
    private BatteryRingDrawable batteryRing;

    /** Thời gian bay ước tính của Mini 2 với pin đầy (thực tế, đã trừ dự phòng) — dùng khi FC chưa báo. */
    private static final int FULL_BATTERY_FLIGHT_MIN = 25;

    /** Chip PIN: vòng tròn % (màu theo ngưỡng) + "⏱ 18 phút" thời gian còn bay được. percent < 0 = chưa có. */
    private void setBatteryChip(int percent) {
        if (batteryRing == null || tvChipBattery == null) return;
        if (percent < 0) {
            batteryRing.setBattery(-1, C_IDLE);
            tvChipBattery.setText("-- phút");
            tvChipBattery.setContentDescription("Pin: chưa có dữ liệu");
            return;
        }
        int color = percent <= Math.max(25, wpLowBattery) ? C_BAD : percent <= 50 ? C_WARN : C_GOOD;
        batteryRing.setBattery(percent, color);

        int sec = remainingFlightSec;
        boolean estimated = sec <= 0;
        if (estimated) sec = FULL_BATTERY_FLIGHT_MIN * 60 * percent / 100;
        int min = sec / 60;
        String time = min < 1 ? "<1 phút" : (estimated ? "≈" : "") + min + " phút";
        tvChipBattery.setText(time);
        tvChipBattery.setTextColor(color == C_BAD ? C_BAD : 0xFFE8EAED);
        tvChipBattery.setContentDescription("Pin " + percent + "%, bay được khoảng " + min + " phút");
    }

    /**
     * Cột sóng RC. percent < 0: chưa có dữ liệu (xám).
     * 0 = mất sóng (đỏ) · 1–59 = sóng yếu (vàng) · ≥ 60 = đủ sóng (xanh lá).
     */
    private void setRcSignal(int percent) {
        if (rcBars == null) return;
        int bars, color;
        if (percent < 0) { bars = 0; color = C_IDLE; }
        else if (percent == 0) { bars = 1; color = C_BAD; }
        else {
            bars = percent >= 80 ? 4 : percent >= 60 ? 3 : percent >= 30 ? 2 : 1;
            color = percent >= 60 ? C_GOOD : C_WARN;
        }
        rcBars.setSignal(bars, color);
        if (tvChipRc != null) {
            tvChipRc.setContentDescription(percent < 0 ? "Sóng RC: chưa có dữ liệu"
                    : percent == 0 ? "Sóng RC: mất sóng" : "Sóng RC: " + percent + "%");
        }
    }

    private static void setChip(TextView chip, String text, int color) {
        if (chip == null) return;
        chip.setText(text);
        chip.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(color));
    }

    /** Mỗi ô một màu theo ngưỡng: xanh = tốt, vàng = cần chú ý, đỏ = nguy hiểm. */
    private void updateStatusChips(int satellites) {
        setConnectionDot(true);
        setChip(tvChipGps, "GPS " + satellites, satellites >= 10 ? C_GOOD : satellites >= 6 ? C_WARN : C_BAD);
        setBatteryChip(batteryPercent > 0 ? batteryPercent : -1);
        setRcSignal(rcSignalPercent);
        String sd = sdStatusStr != null ? sdStatusStr.replace("Thẻ: ", "") : "--";
        boolean sdOk = sd.equals("OK") || sd.equals("Sẵn sàng");
        setChip(tvChipSd, "THẺ " + (sd.equals("Sẵn sàng") ? "OK" : sd.toUpperCase(Locale.ROOT)), sdOk ? C_GOOD : C_BAD);
        setChip(tvChipHome, isHomePointSet ? "HOME" : "HOME ...", isHomePointSet ? C_GOOD : C_WARN);
    }

    private void updateTelemetryCard(float alt, float hSpeed, float vSpeed, double dist, boolean toHome, String net) {
        if (tvTeleAlt == null) return;
        tvTeleAlt.setText(teleValue(String.format(Locale.US, "%.1f", alt), "m"));
        tvTeleAlt.setTextColor(alt > 120 ? C_WARN : 0xFFFFFFFF); // > 120 m: vượt giới hạn độ cao thường gặp
        tvTeleHSpeed.setText(teleValue(String.format(Locale.US, "%.1f", hSpeed), "m/s"));
        String arrow = vSpeed > 0.2f ? "↑ " : vSpeed < -0.2f ? "↓ " : "";
        tvTeleVSpeed.setText(teleValue(arrow + String.format(Locale.US, "%.1f", Math.abs(vSpeed)), "m/s"));
        tvTeleDist.setText(dist >= 1000
                ? teleValue(String.format(Locale.US, "%.2f", dist / 1000.0), "km")
                : teleValue(String.format(Locale.US, "%.0f", dist), "m"));
        tvTeleDistLabel.setText(toHome ? "CÁCH HOME" : "CÁCH ĐÍCH");
        // Chỉ hiện tốc độ mạng khi đang tải/đẩy (cả DL và UL đều 0 thì ẩn cho gọn)
        boolean showNet = net != null && !net.trim().isEmpty()
                && !(net.contains("DL: 0 KB/s") && net.contains("UL: 0 KB/s"));
        tvTeleNet.setVisibility(showNet ? View.VISIBLE : View.GONE);
        if (showNet) tvTeleNet.setText(net);
    }

    /** "12.3" to + " m" nhỏ, xám. */
    private static CharSequence teleValue(String value, String unit) {
        android.text.SpannableString s = new android.text.SpannableString(value + " " + unit);
        int start = value.length() + 1;
        s.setSpan(new android.text.style.RelativeSizeSpan(0.7f), start, s.length(), 0);
        s.setSpan(new android.text.style.ForegroundColorSpan(0xFF9AA0A6), start, s.length(), 0);
        return s;
    }

    /** Đổi màu / độ mờ chấm tròn bên trái đồng hồ quay (đỏ = đang quay, cam = tạm dừng). */
    private void setRecTimerDot(int color, int alpha) {
        if (tvVideoTimer == null) return;
        android.graphics.drawable.Drawable[] ds = tvVideoTimer.getCompoundDrawablesRelative();
        android.graphics.drawable.Drawable dot = ds[0];
        if (dot == null) return;
        dot = dot.mutate();
        dot.setTint(color);
        dot.setAlpha(alpha);
        tvVideoTimer.setCompoundDrawablesRelative(dot, ds[1], ds[2], ds[3]);
    }

    private void startVideoTimer() {
        videoStartTime = System.currentTimeMillis();
        videoPausedTime = 0;
        videoPauseStartTime = 0;
        tvVideoTimer.setText("00:00");
        tvVideoTimer.setVisibility(View.VISIBLE);
        setRecTimerDot(0xFFFF3B30, 255);
        startLiveRecording(); // ghi bản xem nhanh song song với file gốc trên thẻ nhớ
        videoTimerRunnable = new Runnable() {
            @Override
            public void run() {
                if (isRecordingVideo && !isVideoPaused) {
                    long elapsed = System.currentTimeMillis() - videoStartTime - videoPausedTime;
                    long totalSeconds = elapsed / 1000;
                    long minutes = totalSeconds / 60;
                    long seconds = totalSeconds % 60;
                    String time = totalSeconds >= 3600
                            ? String.format(Locale.US, "%d:%02d:%02d", totalSeconds / 3600, minutes % 60, seconds)
                            : String.format(Locale.US, "%02d:%02d", minutes, seconds);
                    tvVideoTimer.setText(time);
                    // Chấm đỏ nhấp nháy mỗi giây khi đang quay
                    setRecTimerDot(0xFFFF3B30, totalSeconds % 2 == 0 ? 255 : 70);
                    videoTimerHandler.postDelayed(this, 1000);
                }
            }
        };
        videoTimerHandler.post(videoTimerRunnable);
    }

    private void stopVideoTimer() {
        if (videoTimerRunnable != null) {
            videoTimerHandler.removeCallbacks(videoTimerRunnable);
        }
        tvVideoTimer.setText("00:00");
        tvVideoTimer.setVisibility(View.GONE);
        videoStartTime = 0;
        videoPausedTime = 0;
        videoPauseStartTime = 0;
        stopLiveRecording();
    }

    // =========================================================
    // BẢN XEM NHANH: ghi luồng camera trực tiếp thành MP4 trên điện thoại
    // trong lúc drone quay -> xem ngay ở Lưu trữ > Trên điện thoại, không chờ tải file gốc.
    // =========================================================
    private volatile com.tri.djicontrol.media.LiveStreamRecorder liveRecorder;

    public static File liveRecordDir(android.content.Context ctx) {
        return new File(ctx.getExternalFilesDir(null), "DJI_LiveRec");
    }

    private void startLiveRecording() {
        if (liveRecorder != null) return;
        String name = "LIVE_REC_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new java.util.Date()) + ".mp4";
        File out = new File(liveRecordDir(this), name);
        double lat = Double.NaN, lon = Double.NaN;
        try {
            FlightController fc = ((Aircraft) DJISDKManager.getInstance().getProduct()).getFlightController();
            if (fc != null && fc.getState() != null && fc.getState().getAircraftLocation() != null) {
                lat = fc.getState().getAircraftLocation().getLatitude();
                lon = fc.getState().getAircraftLocation().getLongitude();
            }
        } catch (Exception ignored) {}
        int w = videoSurface != null ? videoSurface.getWidth() : 0;
        int h = videoSurface != null ? videoSurface.getHeight() : 0;
        liveRecorder = new com.tri.djicontrol.media.LiveStreamRecorder(out, w, h, lat, lon);
    }

    private void stopLiveRecording() {
        com.tri.djicontrol.media.LiveStreamRecorder r = liveRecorder;
        if (r == null) return;
        liveRecorder = null;
        r.stop((file, durationMs, error) -> {
            if (isFinishing() || isDestroyed()) return;
            if (file != null) {
                Toast.makeText(MainActivity.this, String.format(Locale.US,
                        "Đã lưu bản xem nhanh (%s) • Xem ở Lưu trữ > Trên điện thoại",
                        formatDuration(durationMs)), Toast.LENGTH_LONG).show();
            } else {
                android.util.Log.w("LiveRec", "Không ghi được bản xem nhanh: " + error);
                Toast.makeText(MainActivity.this, "Không ghi được bản xem nhanh: " + error,
                        Toast.LENGTH_LONG).show();
            }
        });
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

    // =========================================================
    // BAY QUÉT TỰ ĐỘNG (WP)
    // =========================================================
    private float wpAltitude = 10f;
    private float wpTransitAlt = 20f;   // độ cao bay ra (tới ruộng)
    private int wpReturnAlt = 30;       // độ cao bay về Home
    private double wpSpacing = 5.0;
    private float wpSpeed = 3f;
    private boolean wpVideo = false;
    /** Chụp ảnh WP: true = vừa bay vừa đẩy ảnh lên Cloud, false = chỉ lưu thẻ nhớ. */
    private boolean wpCloudUpload = false;
    private List<LocationCoordinate2D> plannedWaypoints = new ArrayList<>();
    private GeoJsonSource fieldOutlineSource, wpDoneSource, wpStartSource;
    private String wpStatusText = null;
    private TextView activeWpEstimateView;   // dòng trạng thái trong hộp thoại WP đang mở (nếu có)
    private Button activeWpStopButton;

    /** Thêm các lớp bản đồ cho WP vào mini map (gọi trong lúc dựng style). */
    private void setupWpMapLayers(Style style) {
        fieldOutlineSource = new GeoJsonSource("wp-field-source");
        style.addSource(fieldOutlineSource);
        org.maplibre.android.style.layers.FillLayer fill =
                new org.maplibre.android.style.layers.FillLayer("wp-field-fill", "wp-field-source");
        fill.setProperties(org.maplibre.android.style.layers.PropertyFactory.fillColor("#2196F3"),
                org.maplibre.android.style.layers.PropertyFactory.fillOpacity(0.20f));
        style.addLayerBelow(fill, FLIGHT_PATH_LAYER_ID);

        wpDoneSource = new GeoJsonSource("wp-done-source");
        style.addSource(wpDoneSource);
        LineLayer done = new LineLayer("wp-done-layer", "wp-done-source");
        done.setProperties(lineColor("#4CAF50"), lineWidth(4.0f));
        style.addLayer(done);

        wpStartSource = new GeoJsonSource("wp-start-source");
        style.addSource(wpStartSource);
        org.maplibre.android.style.layers.CircleLayer start =
                new org.maplibre.android.style.layers.CircleLayer("wp-start-layer", "wp-start-source");
        start.setProperties(org.maplibre.android.style.layers.PropertyFactory.circleColor("#4CAF50"),
                org.maplibre.android.style.layers.PropertyFactory.circleRadius(6f),
                org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor("#FFFFFF"),
                org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth(2f));
        style.addLayer(start);

        drawFieldOnMap();
        drawPlanOnMap(false);
    }

    private void drawFieldOnMap() {
        if (fieldOutlineSource == null) return;
        if (fieldPoints.size() < 3) {
            fieldOutlineSource.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(new Feature[0]));
            return;
        }
        // Sắp các góc theo vòng để vẽ đúng hình ruộng
        double cLat = 0, cLng = 0;
        for (LatLng p : fieldPoints) { cLat += p.getLatitude(); cLng += p.getLongitude(); }
        final double fLat = cLat / fieldPoints.size(), fLng = cLng / fieldPoints.size();
        List<LatLng> sorted = new ArrayList<>(fieldPoints);
        sorted.sort((a, b) -> Double.compare(
                Math.atan2(a.getLatitude() - fLat, a.getLongitude() - fLng),
                Math.atan2(b.getLatitude() - fLat, b.getLongitude() - fLng)));
        List<Point> ring = new ArrayList<>();
        for (LatLng p : sorted) ring.add(Point.fromLngLat(p.getLongitude(), p.getLatitude()));
        ring.add(ring.get(0));
        List<List<Point>> rings = new ArrayList<>();
        rings.add(ring);
        fieldOutlineSource.setGeoJson(Feature.fromGeometry(org.maplibre.geojson.Polygon.fromLngLats(rings)));
    }

    /** Vẽ lộ trình (vàng), điểm bắt đầu (xanh); fit camera nếu cần. */
    private void drawPlanOnMap(boolean fitCamera) {
        if (flightPathSource == null) return;
        org.maplibre.geojson.FeatureCollection empty =
                org.maplibre.geojson.FeatureCollection.fromFeatures(new Feature[0]);
        if (wpDoneSource != null) wpDoneSource.setGeoJson(empty);
        if (plannedWaypoints.size() < 2) {
            flightPathSource.setGeoJson(empty);
            if (wpStartSource != null) wpStartSource.setGeoJson(empty);
            return;
        }
        List<Point> pts = new ArrayList<>();
        for (LocationCoordinate2D wp : plannedWaypoints) pts.add(Point.fromLngLat(wp.getLongitude(), wp.getLatitude()));
        flightPathSource.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(pts)));
        if (wpStartSource != null) wpStartSource.setGeoJson(Feature.fromGeometry(pts.get(0)));

        if (fitCamera && miniMapLibreMap != null) {
            try {
                if (miniMapLibreMap.getLocationComponent().isLocationComponentActivated()) {
                    // Tắt bám theo vị trí điện thoại để bản đồ đứng yên ở ruộng
                    miniMapLibreMap.getLocationComponent().setCameraMode(CameraMode.NONE);
                }
            } catch (Exception ignored) {}
            org.maplibre.android.geometry.LatLngBounds.Builder b = new org.maplibre.android.geometry.LatLngBounds.Builder();
            for (LocationCoordinate2D wp : plannedWaypoints) b.include(new LatLng(wp.getLatitude(), wp.getLongitude()));
            for (LatLng p : fieldPoints) b.include(p);
            try {
                miniMapLibreMap.easeCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngBounds(b.build(), 30));
            } catch (Exception ignored) {}
        }
    }

    /** Vẽ phần đường đã bay (xanh lá) theo tiến độ nhiệm vụ. */
    private void drawProgressOnMap(int reachedIndex) {
        if (wpDoneSource == null || plannedWaypoints.isEmpty()) return;
        List<Point> pts = new ArrayList<>();
        for (int i = 0; i < Math.min(reachedIndex, plannedWaypoints.size()); i++) {
            LocationCoordinate2D wp = plannedWaypoints.get(i);
            pts.add(Point.fromLngLat(wp.getLongitude(), wp.getLatitude()));
        }
        try {
            Aircraft a = (Aircraft) DJISDKManager.getInstance().getProduct();
            dji.common.flightcontroller.LocationCoordinate3D loc = a.getFlightController().getState().getAircraftLocation();
            if (loc != null && !Double.isNaN(loc.getLatitude())) pts.add(Point.fromLngLat(loc.getLongitude(), loc.getLatitude()));
        } catch (Exception ignored) {}
        if (pts.size() >= 2) wpDoneSource.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(pts)));
    }

    private static float parseFloat(EditText e, float def) {
        try {
            float v = Float.parseFloat(e.getText().toString().trim().replace(',', '.'));
            return Float.isNaN(v) ? def : v;
        } catch (Exception ex) {
            return def;
        }
    }

    /** Khoảng cách giữa 2 lần chụp = khoảng cách đường bay (lưới ảnh vuông, chồng ảnh đều 2 chiều). */
    private double wpPhotoDistance() {
        return wpSpacing;
    }

    /**
     * Tốc độ bay giữa các điểm. Chế độ chụp ảnh: drone DỪNG HẲN tại mỗi điểm chụp rồi mới bấm
     * (FlightMissionManager) nên không cần bay chậm nữa -> dùng đúng tốc độ người dùng nhập.
     */
    private float wpEffectiveSpeed() {
        return wpSpeed;
    }

    /** Số ảnh dự kiến (1 ảnh mỗi wpPhotoDistance mét). */
    private int wpEstimatedShots(GridPlanner.Plan plan) {
        return (int) Math.ceil(plan.lengthMeters / wpPhotoDistance()) + 1;
    }

    /** Thời gian quét (phút) = bay + dừng chụp ở mỗi điểm (chế độ ảnh). */
    private double wpEstimatedMinutes(GridPlanner.Plan plan) {
        double sec = plan.lengthMeters / wpEffectiveSpeed();
        if (!wpVideo) sec += wpEstimatedShots(plan) * FlightMissionManager.photoStopSeconds(wpCloudUpload);
        return sec / 60.0;
    }

    private GridPlanner.Plan lastWpPlan = null;

    /** Hộp thoại xác nhận trước khi bay quét: thông số, quy trình và checklist an toàn. */
    private void showWpConfirmDialog(GridPlanner.Plan plan, Runnable onFly) {
        android.app.Dialog cf = new android.app.Dialog(this, R.style.CfDialog);
        cf.setContentView(R.layout.dialog_wp_confirm);
        cf.setCancelable(true);

        float speed = wpEffectiveSpeed();
        double minutes = wpEstimatedMinutes(plan);
        float transitAlt = Math.max(wpAltitude, wpTransitAlt);
        int shots = wpEstimatedShots(plan);

        TextView tvMode = cf.findViewById(R.id.tvCfMode);
        tvMode.setText(wpVideo ? "🎥  QUAY VIDEO"
                : wpCloudUpload ? "📷  CHỤP ẢNH • THẺ + CLOUD" : "📷  CHỤP ẢNH • LƯU THẺ");

        // --- Thông số ---
        LinearLayout row1 = cf.findViewById(R.id.cfStatsRow1);
        LinearLayout row2 = cf.findViewById(R.id.cfStatsRow2);
        addCfStat(row1, String.format(Locale.US, "%.2f ha", fieldAreaHa), "Diện tích ruộng");
        addCfStat(row1, plan.lengthMeters >= 1000
                ? String.format(Locale.US, "%.2f km", plan.lengthMeters / 1000.0)
                : String.format(Locale.US, "%.0f m", plan.lengthMeters), "Quãng đường quét");
        addCfStat(row1, String.format(Locale.US, "≈ %.0f phút", Math.max(1, Math.ceil(minutes))), "Thời gian quét");
        addCfStat(row2, String.format(Locale.US, "%.0f m", wpAltitude), "Độ cao quét");
        addCfStat(row2, String.format(Locale.US, "%.1f m/s", speed), "Tốc độ bay");
        if (wpVideo) {
            addCfStat(row2, plan.lineCount + " đường", "Số đường bay");
        } else {
            addCfStat(row2, "≈ " + shots + " ảnh", String.format(Locale.US, "Mỗi %.1f m 1 ảnh", wpPhotoDistance()));
        }

        if (!wpVideo) {
            TextView note = cf.findViewById(R.id.tvCfSpeedNote);
            note.setText(String.format(Locale.US,
                    wpCloudUpload
                            ? "ⓘ Drone dừng ~%.0f s ở mỗi điểm: chụp ảnh nét + tải ảnh đẩy lên Cloud (đã tính vào thời gian)"
                            : "ⓘ Drone dừng ~%.0f s ở mỗi điểm để chụp ảnh nét, ảnh lưu vào thẻ nhớ (đã tính vào thời gian)",
                    FlightMissionManager.photoStopSeconds(wpCloudUpload)));
            note.setVisibility(View.VISIBLE);
        }
        StringBuilder warnText = new StringBuilder();
        if (wpAltitude < 5) {
            warnText.append(String.format(Locale.US, "⚠  Độ cao quét %.0f m rất thấp: drone bay sát mặt đất, "
                    + "dễ va cây/cột điện và có thể không giữ được độ cao này.", wpAltitude));
        }
        if (minutes > 20) {
            if (warnText.length() > 0) warnText.append("\n");
            warnText.append("⚠  Thời gian quét lâu hơn 1 pin Mini 2 (~25 phút bay). "
                    + "Nên tăng khoảng cách đường bay hoặc chia nhỏ ruộng.");
        }
        if (warnText.length() > 0) {
            TextView warn = cf.findViewById(R.id.tvCfWarning);
            warn.setText(warnText);
            warn.setVisibility(View.VISIBLE);
        }

        // --- Quy trình ---
        LinearLayout steps = cf.findViewById(R.id.cfStepsContainer);
        java.util.List<String> stepTexts = new ArrayList<>();
        stepTexts.add("Tự cất cánh (nếu đang ở mặt đất)");
        stepTexts.add(String.format(Locale.US, "Bay ra ở độ cao %.0f m", transitAlt));
        stepTexts.add("Bay tới góc bắt đầu của ruộng");
        stepTexts.add(String.format(Locale.US, "%sQuét zigzag %d đường, %s",
                transitAlt > wpAltitude ? String.format(Locale.US, "Hạ về %.0f m • ", wpAltitude) : "",
                plan.lineCount, wpVideo ? "quay liên tục" : "dừng lại chụp mỗi điểm"));
        stepTexts.add(String.format(Locale.US, "Dừng camera, bay về Home ở độ cao %d m", wpReturnAlt));
        LayoutInflater inf = LayoutInflater.from(this);
        for (int i = 0; i < stepTexts.size(); i++) {
            View item = inf.inflate(R.layout.item_cf_step, steps, false);
            ((TextView) item.findViewById(R.id.tvStepNo)).setText(String.valueOf(i + 1));
            ((TextView) item.findViewById(R.id.tvStepText)).setText(stepTexts.get(i));
            steps.addView(item);
        }

        // --- Checklist: chỉ cho bay khi đã tick đủ ---
        CheckBox cb1 = cf.findViewById(R.id.cbCfObstacle);
        CheckBox cb2 = cf.findViewById(R.id.cbCfBattery);
        CheckBox cb3 = cf.findViewById(R.id.cbCfHome);
        Button btnFly = cf.findViewById(R.id.btnCfFly);
        TextView hint = cf.findViewById(R.id.tvCfHint);
        android.widget.CompoundButton.OnCheckedChangeListener check = (b, c) -> {
            boolean ok = cb1.isChecked() && cb2.isChecked() && cb3.isChecked();
            btnFly.setEnabled(ok);
            hint.setText(ok ? "Có thể bấm DỪNG hoặc RTH bất cứ lúc nào"
                            : "Tick đủ 3 mục kiểm tra để bắt đầu bay");
        };
        cb1.setOnCheckedChangeListener(check);
        cb2.setOnCheckedChangeListener(check);
        cb3.setOnCheckedChangeListener(check);
        check.onCheckedChanged(null, false);

        cf.findViewById(R.id.btnCfCancel).setOnClickListener(v -> cf.dismiss());
        btnFly.setOnClickListener(v -> {
            cf.dismiss();
            onFly.run();
        });

        cf.show();
        if (cf.getWindow() != null) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int width = (int) Math.min(dm.widthPixels * 0.9f, 660 * dm.density);
            cf.getWindow().setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void addCfStat(LinearLayout row, String value, String label) {
        View cell = LayoutInflater.from(this).inflate(R.layout.item_cf_stat, row, false);
        ((TextView) cell.findViewById(R.id.tvStatValue)).setText(value);
        ((TextView) cell.findViewById(R.id.tvStatLabel)).setText(label);
        if (row.getChildCount() > 0) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) cell.getLayoutParams();
            lp.setMarginStart((int) (6 * getResources().getDisplayMetrics().density));
            cell.setLayoutParams(lp);
        }
        row.addView(cell);
    }

    private String buildPlanSummary(GridPlanner.Plan plan) {
        float speed = wpEffectiveSpeed();
        double minutes = wpEstimatedMinutes(plan);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "Lộ trình: %d đường bay, %d điểm, dài %.0f m\n",
                plan.lineCount, plan.waypoints.size(), plan.lengthMeters));
        sb.append(String.format(Locale.US, "Độ cao %.0f m • cách %.1f m • tốc độ %.1f m/s\n",
                wpAltitude, wpSpacing, speed));
        sb.append(String.format(Locale.US, "Thời gian quét ≈ %.1f phút", minutes));
        if (!wpVideo) {
            sb.append(String.format(Locale.US, " • ≈ %d ảnh (mỗi %.1f m, dừng ~%.0f s/ảnh)",
                    wpEstimatedShots(plan), wpPhotoDistance(), FlightMissionManager.photoStopSeconds(wpCloudUpload)));
        } else {
            sb.append(" • quay video liên tục");
        }
        if (minutes > 20) sb.append("\n⚠ Lâu hơn 1 pin Mini 2 (~25 phút bay). Nên tăng khoảng cách hoặc chia ruộng.");
        return sb.toString();
    }

    /** Tạo lưới từ 4 góc ruộng + thông số (kết quả lưu vào plannedWaypoints). */
    private GridPlanner.Plan createPlan() {
        double[] lats = new double[fieldPoints.size()];
        double[] lngs = new double[fieldPoints.size()];
        for (int i = 0; i < fieldPoints.size(); i++) {
            lats[i] = fieldPoints.get(i).getLatitude();
            lngs[i] = fieldPoints.get(i).getLongitude();
        }
        GridPlanner.Plan plan = GridPlanner.plan(lats, lngs, wpSpacing);
        List<LocationCoordinate2D> wps = new ArrayList<>(plan.waypoints);

        // Bắt đầu từ đầu lộ trình gần drone hơn để đỡ bay vòng
        try {
            Aircraft a = (Aircraft) DJISDKManager.getInstance().getProduct();
            dji.common.flightcontroller.LocationCoordinate3D loc = a.getFlightController().getState().getAircraftLocation();
            if (loc != null && !Double.isNaN(loc.getLatitude()) && wps.size() >= 2) {
                LocationCoordinate2D s = wps.get(0), e = wps.get(wps.size() - 1);
                if (GridPlanner.distance(loc.getLatitude(), loc.getLongitude(), e.getLatitude(), e.getLongitude())
                        < GridPlanner.distance(loc.getLatitude(), loc.getLongitude(), s.getLatitude(), s.getLongitude())) {
                    Collections.reverse(wps);
                }
            }
        } catch (Exception ignored) {}

        plannedWaypoints = wps;
        return plan;
    }

    private void openWPDialog() {
        Dialog dialog = new Dialog(MainActivity.this, R.style.CfDialog);
        dialog.setContentView(R.layout.dialog_wp);

        Button btnCloseWP = dialog.findViewById(R.id.btnCloseWP);
        Button btnEditWPField = dialog.findViewById(R.id.btnEditWPField);
        TextView tvWPFieldStatus = dialog.findViewById(R.id.tvWPFieldStatus);
        TextView tvWPFieldArea = dialog.findViewById(R.id.tvWPFieldArea);
        EditText edtAlt = dialog.findViewById(R.id.edtWPAltitude);
        EditText edtSpacing = dialog.findViewById(R.id.edtWPLineSpacing);
        EditText edtSpeed = dialog.findViewById(R.id.edtWPSpeed);
        android.widget.RadioButton rbPhoto = dialog.findViewById(R.id.rbWPPhoto);
        android.widget.RadioButton rbVideo = dialog.findViewById(R.id.rbWPVideo);
        android.widget.RadioButton rbSaveCard = dialog.findViewById(R.id.rbWPSaveCard);
        android.widget.RadioButton rbSaveCloud = dialog.findViewById(R.id.rbWPSaveCloud);
        View rgSave = dialog.findViewById(R.id.rgWPSave);
        View tvSaveTitle = dialog.findViewById(R.id.tvWPSaveTitle);
        TextView tvEstimate = dialog.findViewById(R.id.tvWPEstimate);
        Button btnClear = dialog.findViewById(R.id.btnClearWP);
        Button btnCreate = dialog.findViewById(R.id.btnCreateWP);
        Button btnStart = dialog.findViewById(R.id.btnStartMapping);
        Button btnStop = dialog.findViewById(R.id.btnConfirmWP);

        boolean hasField = fieldPoints.size() == 4;
        com.tri.djicontrol.field.SavedFieldStore.SavedField curField =
                com.tri.djicontrol.field.SavedFieldStore.get(this, currentFieldId);
        tvWPFieldStatus.setText(!hasField ? "Chưa chọn khu vực — bấm nút bên dưới"
                : curField != null ? curField.name + " • " + curField.statusLabel()
                : "Đã chọn khu vực (4 góc ruộng) • chưa lưu");

        // Lưu ruộng / mở ruộng đã lưu
        Button btnSaveField = dialog.findViewById(R.id.btnSaveField);
        Button btnSavedFields = dialog.findViewById(R.id.btnSavedFields);
        int savedCount = com.tri.djicontrol.field.SavedFieldStore.list(this).size();
        btnSavedFields.setText(savedCount > 0 ? "Ruộng đã lưu (" + savedCount + ")" : "Ruộng đã lưu");
        btnSaveField.setEnabled(hasField);
        btnSaveField.setOnClickListener(v -> showSaveFieldDialog(dialog));
        btnSavedFields.setOnClickListener(v -> showSavedFieldsDialog(dialog));
        tvWPFieldArea.setText(String.format(Locale.US, "%.2f ha", hasField ? fieldAreaHa : 0.0));
        View fieldDot = dialog.findViewById(R.id.wpFieldDot);
        if (fieldDot != null) {
            fieldDot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    hasField ? 0xFF69F0AE : 0xFFFFB74D));
        }

        // Nhớ thông số lần trước
        edtAlt.setText(String.format(Locale.US, "%.0f", wpAltitude));
        EditText edtTransitAlt = dialog.findViewById(R.id.edtWPTransitAlt);
        EditText edtReturnAlt = dialog.findViewById(R.id.edtWPReturnAlt);
        edtTransitAlt.setText(String.format(Locale.US, "%.0f", wpTransitAlt));
        edtReturnAlt.setText(String.valueOf(wpReturnAlt));
        edtSpacing.setText(String.format(Locale.US, "%.1f", wpSpacing));
        edtSpeed.setText(String.format(Locale.US, "%.1f", wpSpeed));
        if (wpVideo) rbVideo.setChecked(true); else rbPhoto.setChecked(true);
        if (wpCloudUpload) rbSaveCloud.setChecked(true); else rbSaveCard.setChecked(true);
        // Lựa chọn lưu ảnh chỉ có nghĩa khi chụp ảnh (video luôn lưu thẻ)
        Runnable syncSaveVisibility = () -> {
            int vis = rbVideo.isChecked() ? View.GONE : View.VISIBLE;
            rgSave.setVisibility(vis);
            tvSaveTitle.setVisibility(vis);
        };
        syncSaveVisibility.run();
        rbPhoto.setOnCheckedChangeListener((b, c) -> syncSaveVisibility.run());
        rbVideo.setOnCheckedChangeListener((b, c) -> syncSaveVisibility.run());
        EditText edtBattery = dialog.findViewById(R.id.edtWPBattery);
        edtBattery.setText(String.valueOf(wpLowBattery));

        boolean running = missionManager != null && missionManager.isRunning();
        btnStop.setVisibility(running ? View.VISIBLE : View.GONE);
        btnStart.setVisibility(View.VISIBLE);
        btnStart.setEnabled(!running);
        btnCreate.setEnabled(!running);
        btnClear.setEnabled(!running);
        btnEditWPField.setEnabled(!running);
        if (running && wpStatusText != null) tvEstimate.setText(wpStatusText);
        activeWpEstimateView = tvEstimate;
        activeWpStopButton = btnStop;
        dialog.setOnDismissListener(d -> { activeWpEstimateView = null; activeWpStopButton = null; });

        // Đọc + kiểm tra thông số. Trả false (kèm báo lỗi trên ô nhập) nếu sai.
        java.util.function.BooleanSupplier readParams = () -> {
            float alt = parseFloat(edtAlt, -1);
            float spacing = parseFloat(edtSpacing, -1);
            float speed = parseFloat(edtSpeed, -1);
            if (alt < 0 || alt > 120) { edtAlt.setError("0 – 120 m"); return false; }
            if (spacing < 1 || spacing > 100) { edtSpacing.setError("1 – 100 m"); return false; }
            if (speed < 0.5f || speed > 10) { edtSpeed.setError("0.5 – 10 m/s"); return false; }
            float transit = parseFloat(edtTransitAlt, -1);
            float ret = parseFloat(edtReturnAlt, -1);
            if (transit < 0 || transit > 120) { edtTransitAlt.setError("0 – 120 m"); return false; }
            if (ret < FlightMissionManager.RETURN_ALT_MIN || ret > FlightMissionManager.RETURN_ALT_MAX) {
                edtReturnAlt.setError(FlightMissionManager.RETURN_ALT_MIN + " – " + FlightMissionManager.RETURN_ALT_MAX + " m");
                return false;
            }
            float bat = parseFloat(edtBattery, -1);
            if (bat < 20 || bat > 60) { edtBattery.setError("20 – 60 %"); return false; }
            wpAltitude = alt;
            wpTransitAlt = transit;
            wpReturnAlt = Math.round(ret);
            if (missionManager != null) {
                missionManager.setTransitAltitude(wpTransitAlt);
                missionManager.setReturnAltitude(wpReturnAlt);
            }
            wpSpacing = spacing;
            wpSpeed = speed;
            wpVideo = rbVideo.isChecked();
            wpCloudUpload = rbSaveCloud.isChecked();
            wpLowBattery = Math.round(bat);
            if (missionManager != null) missionManager.setLowBatteryThreshold(wpLowBattery);
            return true;
        };

        // Dải thông báo nhiệm vụ đang dở -> bấm để bay tiếp
        View resumeBanner = dialog.findViewById(R.id.wpResumeBanner);
        com.tri.djicontrol.flight.MissionCheckpoint pendingCp =
                running ? null : com.tri.djicontrol.flight.MissionCheckpoint.load(this);
        if (pendingCp != null) {
            resumeBanner.setVisibility(View.VISIBLE);
            ((TextView) dialog.findViewById(R.id.tvWPResumeInfo)).setText(String.format(Locale.US,
                    "Nhiệm vụ đang dở: đã quét %d%%%s • dừng do %s",
                    pendingCp.progressPercent(),
                    pendingCp.video ? "" : ", " + pendingCp.photoCount + " ảnh",
                    pendingCp.reason.toLowerCase(Locale.ROOT)));
            dialog.findViewById(R.id.btnWPResume).setOnClickListener(v -> {
                dialog.dismiss();
                showResumeDialog(pendingCp);
            });
        }

        Runnable buildPlan = () -> {
            if (fieldPoints.size() != 4) {
                Toast.makeText(MainActivity.this, "Vui lòng chọn đủ 4 góc ruộng trước!", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!readParams.getAsBoolean()) return;
            GridPlanner.Plan plan = createPlan();
            lastWpPlan = plan;
            if (plannedWaypoints.size() < 2) {
                tvEstimate.setText("Không tạo được lộ trình. Khoảng cách đường bay quá lớn so với ruộng?");
                drawPlanOnMap(false);
                return;
            }
            drawPlanOnMap(true);
            tvEstimate.setText(buildPlanSummary(plan));
        };

        btnCreate.setOnClickListener(v -> buildPlan.run());

        btnClear.setOnClickListener(v -> {
            plannedWaypoints = new ArrayList<>();
            drawPlanOnMap(false);
            tvEstimate.setText("Đã xóa lộ trình");
        });

        btnStart.setOnClickListener(v -> {
            if (missionManager == null || DJISDKManager.getInstance().getProduct() == null) {
                Toast.makeText(MainActivity.this, "Chưa kết nối Drone!", Toast.LENGTH_SHORT).show();
                return;
            }
            buildPlan.run(); // luôn tạo lại theo thông số mới nhất
            if (plannedWaypoints.size() < 2 || lastWpPlan == null) return;

            showWpConfirmDialog(lastWpPlan, () -> {
                startWpMission();
                btnStop.setVisibility(View.VISIBLE);
                btnStart.setEnabled(false);
                btnCreate.setEnabled(false);
                btnClear.setEnabled(false);
                btnEditWPField.setEnabled(false);
            });
        });

        btnStop.setOnClickListener(v -> {
            if (missionManager != null) missionManager.stopAndReturnHome();
            Toast.makeText(MainActivity.this, "Đã dừng nhiệm vụ, drone bay về nhà", Toast.LENGTH_SHORT).show();
        });

        btnCloseWP.setOnClickListener(view -> dialog.dismiss());

        btnEditWPField.setOnClickListener(view -> {
            Intent intent = new Intent(MainActivity.this, MapActivity.class);
            intent.putExtra("EDIT_FIELD", true);
            // Ruộng đang chỉnh không vẽ lặp trong lớp "ruộng đã lưu" trên bản đồ
            if (currentFieldId != null) intent.putExtra("CURRENT_FIELD_ID", currentFieldId);
            if (fieldPoints.size() == 4) {
                double[] latitudes = new double[fieldPoints.size()];
                double[] longitudes = new double[fieldPoints.size()];
                for (int i = 0; i < fieldPoints.size(); i++) {
                    latitudes[i] = fieldPoints.get(i).getLatitude();
                    longitudes[i] = fieldPoints.get(i).getLongitude();
                }
                intent.putExtra("FIELD_LATITUDES", latitudes);
                intent.putExtra("FIELD_LONGITUDES", longitudes);
            }
            dialog.dismiss();
            startActivityForResult(intent, REQUEST_FIELD_MAP);
        });

        // Có ruộng mà chưa bay -> tạo lộ trình luôn để thấy ngay trên map
        // (trừ khi đang có nhiệm vụ dở: giữ lộ trình cũ trên map để còn bay tiếp)
        if (hasField && !running && pendingCp == null) buildPlan.run();

        dialog.show();
        if (dialog.getWindow() != null) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(
                    (int) Math.min(dm.widthPixels * 0.86f, 780 * dm.density),
                    (int) (dm.heightPixels * 0.9f)
            );
        }
    }

    private void startWpMission() {
        if (missionManager == null) return;
        final boolean video = wpVideo;
        resetCapturePanel(video);
        com.tri.djicontrol.flight.MissionCheckpoint.clear(this); // nhiệm vụ mới thay cho nhiệm vụ dở cũ
        missionManager.onBatteryUpdate(batteryPercent);
        missionManager.setLowBatteryThreshold(wpLowBattery);
        missionManager.setCloudUpload(wpCloudUpload);
        missionManager.startMappingMission(new ArrayList<>(plannedWaypoints), wpAltitude, wpEffectiveSpeed(),
                video, wpPhotoDistance(), createWpListener(video));
        // Gắn chuyến bay vào mảnh ruộng đang chọn (nếu đã lưu) để đánh dấu đã bay
        if (missionManager.isRunning() && currentFieldId != null) {
            com.tri.djicontrol.field.SavedFieldStore.SavedField f =
                    com.tri.djicontrol.field.SavedFieldStore.get(this, currentFieldId);
            if (f != null) {
                String mid = missionManager.getMissionId();
                if (mid != null && !f.missionIds.contains(mid)) f.missionIds.add(mid);
                f.altitude = wpAltitude;
                f.spacing = wpSpacing;
                f.speed = wpSpeed;
                f.video = wpVideo;
                f.lastFlownAt = System.currentTimeMillis();
                if (!com.tri.djicontrol.field.SavedFieldStore.STATUS_DONE.equals(f.status)) {
                    f.status = com.tri.djicontrol.field.SavedFieldStore.STATUS_PARTIAL;
                }
                f.updatedAt = System.currentTimeMillis();
                com.tri.djicontrol.field.SavedFieldStore.put(this, f);
            }
        }
    }

    // =========================================================
    // MẢNH RUỘNG ĐÃ LƯU
    // =========================================================
    /** Ruộng đang chọn có trong danh sách đã lưu (null = ruộng mới chưa lưu). */
    private String currentFieldId = null;

    /** Vẽ lại các ruộng đã lưu trên mini map (bỏ qua ruộng đang chọn vì đã được vẽ riêng). */
    private void refreshSavedFieldsOnMiniMap() {
        runOnUiThread(() -> {
            if (miniMapLibreMap == null || miniMapLibreMap.getStyle() == null) return;
            Style style = miniMapLibreMap.getStyle();
            // Chèn dưới lớp ruộng đang chọn (nếu có) để ruộng đang bay luôn nổi lên trên
            String below = style.getLayer("wp-field-fill") != null ? "wp-field-fill" : FLIGHT_PATH_LAYER_ID;
            com.tri.djicontrol.field.SavedFieldsLayer.update(this, style,
                    fieldPoints.size() >= 3 ? currentFieldId : null, below);
            com.tri.djicontrol.field.SavedFieldsLayer.setLabelsVisible(style, mapExpanded);
        });
    }

    private void showSaveFieldDialog(Dialog wpDialog) {
        if (fieldPoints.size() != 4) {
            Toast.makeText(this, "Chọn đủ 4 góc ruộng trước!", Toast.LENGTH_SHORT).show();
            return;
        }
        com.tri.djicontrol.field.SavedFieldStore.SavedField existing =
                com.tri.djicontrol.field.SavedFieldStore.get(this, currentFieldId);

        android.app.Dialog d = new android.app.Dialog(this, R.style.CfDialog);
        d.setContentView(R.layout.dialog_field_name);
        ((TextView) d.findViewById(R.id.tvFieldNameTitle))
                .setText(existing != null ? "Cập nhật mảnh ruộng" : "Lưu mảnh ruộng");
        ((TextView) d.findViewById(R.id.tvFieldNameInfo)).setText(String.format(Locale.US,
                "%.2f ha • 4 góc • lưu kèm thông số bay hiện tại", fieldAreaHa));
        EditText edt = d.findViewById(R.id.edtFieldName);
        edt.setText(existing != null ? existing.name
                : "Ruộng " + (com.tri.djicontrol.field.SavedFieldStore.list(this).size() + 1));
        edt.selectAll();

        // asNew = true: luôn tạo ruộng mới; false: cập nhật ruộng đang chọn (nếu có)
        java.util.function.Consumer<Boolean> doSave = asNew -> {
            String name = edt.getText().toString().trim();
            if (name.isEmpty()) { edt.setError("Nhập tên ruộng"); return; }
            com.tri.djicontrol.field.SavedFieldStore.SavedField f =
                    (!asNew && existing != null) ? existing : new com.tri.djicontrol.field.SavedFieldStore.SavedField();
            f.name = name;
            f.lats = new double[4];
            f.lngs = new double[4];
            for (int i = 0; i < 4; i++) {
                f.lats[i] = fieldPoints.get(i).getLatitude();
                f.lngs[i] = fieldPoints.get(i).getLongitude();
            }
            f.areaHa = fieldAreaHa;
            f.altitude = wpAltitude;
            f.spacing = wpSpacing;
            f.speed = wpSpeed;
            f.video = wpVideo;
            f.updatedAt = System.currentTimeMillis();
            com.tri.djicontrol.field.SavedFieldStore.put(this, f);
            currentFieldId = f.id;
            refreshSavedFieldsOnMiniMap();
            d.dismiss();
            Toast.makeText(this, "Đã lưu \"" + name + "\"", Toast.LENGTH_SHORT).show();
            // Mở lại hộp thoại WP để cập nhật tên ruộng đang chọn
            if (wpDialog != null) wpDialog.dismiss();
            openWPDialog();
        };
        Button btnSaveNew = d.findViewById(R.id.btnFieldNameSaveNew);
        if (existing != null) {
            btnSaveNew.setVisibility(View.VISIBLE);
            btnSaveNew.setOnClickListener(v -> doSave.accept(true));
            ((Button) d.findViewById(R.id.btnFieldNameSave)).setText("Cập nhật");
        }
        d.findViewById(R.id.btnFieldNameSave).setOnClickListener(v -> doSave.accept(false));
        d.findViewById(R.id.btnFieldNameCancel).setOnClickListener(v -> d.dismiss());
        d.show();
        if (d.getWindow() != null) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            d.getWindow().setLayout((int) Math.min(dm.widthPixels * 0.9f, 460 * dm.density),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
    }

    private void showSavedFieldsDialog(Dialog wpDialog) {
        android.app.Dialog d = new android.app.Dialog(this, R.style.CfDialog);
        d.setContentView(R.layout.dialog_field_list);
        LinearLayout container = d.findViewById(R.id.fieldListContainer);
        TextView info = d.findViewById(R.id.tvFieldListInfo);
        View empty = d.findViewById(R.id.tvFieldListEmpty);
        View scroll = d.findViewById(R.id.fieldListScroll);
        Button btnSyncAll = d.findViewById(R.id.btnFieldSyncAll);

        Runnable[] render = new Runnable[1];
        render[0] = () -> {
            container.removeAllViews();
            List<com.tri.djicontrol.field.SavedFieldStore.SavedField> all =
                    com.tri.djicontrol.field.SavedFieldStore.list(this);
            int done = 0, pending = 0;
            for (com.tri.djicontrol.field.SavedFieldStore.SavedField f : all) {
                if (com.tri.djicontrol.field.SavedFieldStore.STATUS_DONE.equals(f.status)) done++;
                if (f.needsSync()) pending++;
                container.addView(buildSavedFieldRow(container, f, d, wpDialog, render[0]));
            }
            info.setText(String.format(Locale.US, "%d ruộng • %d đã bay xong%s", all.size(), done,
                    pending > 0 ? " • " + pending + " chưa đẩy lên Cloud" : ""));
            empty.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
            scroll.setVisibility(all.isEmpty() ? View.GONE : View.VISIBLE);
            btnSyncAll.setEnabled(pending > 0);
            btnSyncAll.setAlpha(pending > 0 ? 1f : 0.45f);
        };
        render[0].run();

        btnSyncAll.setOnClickListener(v -> {
            List<com.tri.djicontrol.field.SavedFieldStore.SavedField> todo = new ArrayList<>();
            for (com.tri.djicontrol.field.SavedFieldStore.SavedField f
                    : com.tri.djicontrol.field.SavedFieldStore.list(this)) {
                if (f.needsSync()) todo.add(f);
            }
            btnSyncAll.setEnabled(false);
            uploadFieldsSequential(todo, 0, 0, btnSyncAll, render[0]);
        });
        d.findViewById(R.id.btnFieldListClose).setOnClickListener(v -> d.dismiss());
        d.show();
        if (d.getWindow() != null) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            d.getWindow().setLayout((int) Math.min(dm.widthPixels * 0.86f, 760 * dm.density),
                    (int) (dm.heightPixels * 0.85f));
        }
    }

    private View buildSavedFieldRow(LinearLayout parent, com.tri.djicontrol.field.SavedFieldStore.SavedField f,
                                    android.app.Dialog listDialog, Dialog wpDialog, Runnable rerender) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_saved_field, parent, false);
        int fieldNo = com.tri.djicontrol.field.SavedFieldsLayer.fieldNumber(this, f.id);
        TextView tvName = row.findViewById(R.id.tvFieldName);
        android.text.SpannableString nameSp = new android.text.SpannableString(fieldNo + "  " + f.name);
        nameSp.setSpan(new android.text.style.ForegroundColorSpan(
                        com.tri.djicontrol.field.SavedFieldsLayer.fieldColor(fieldNo)), 0, String.valueOf(fieldNo).length(),
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        tvName.setText(nameSp);
        row.findViewById(R.id.tvFieldCurrent).setVisibility(f.id.equals(currentFieldId) ? View.VISIBLE : View.GONE);

        String when = f.lastFlownAt > 0
                ? "bay gần nhất " + new java.text.SimpleDateFormat("dd/MM HH:mm", Locale.US).format(new java.util.Date(f.lastFlownAt))
                : "lưu " + new java.text.SimpleDateFormat("dd/MM HH:mm", Locale.US).format(new java.util.Date(f.createdAt));
        ((TextView) row.findViewById(R.id.tvFieldInfo)).setText(String.format(Locale.US,
                "%.2f ha • %s • %s%s", f.areaHa, f.statusLabel(), when, f.needsSync() ? "" : " • đã lên Cloud"));
        row.findViewById(R.id.fieldStatusDot).setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(fieldStatusColor(f.status)));

        Button btnSelect = row.findViewById(R.id.btnFieldSelect);
        boolean running = missionManager != null && missionManager.isRunning();
        btnSelect.setEnabled(!running);
        btnSelect.setOnClickListener(v -> {
            loadSavedField(f);
            listDialog.dismiss();
            if (wpDialog != null) wpDialog.dismiss();
            openWPDialog();
        });

        Button btnCloud = row.findViewById(R.id.btnFieldCloud);
        btnCloud.setText(f.needsSync() ? "Cloud" : "Đã lên ✓");
        btnCloud.setOnClickListener(v -> {
            btnCloud.setEnabled(false);
            btnCloud.setText("Đang đẩy...");
            uploadField(f, ok -> rerender.run());
        });

        row.findViewById(R.id.btnFieldDelete).setOnClickListener(v ->
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Xóa \"" + f.name + "\"?")
                        .setMessage("Mảnh ruộng sẽ bị xóa khỏi danh sách trên điện thoại "
                                + "(dữ liệu đã đẩy lên Cloud vẫn còn).")
                        .setPositiveButton("XÓA", (x, i) -> {
                            com.tri.djicontrol.field.SavedFieldStore.delete(this, f.id);
                            if (f.id.equals(currentFieldId)) currentFieldId = null;
                            refreshSavedFieldsOnMiniMap();
                            rerender.run();
                        })
                        .setNegativeButton("HỦY", null)
                        .show());
        return row;
    }

    // =========================================================
    // CHẠM RUỘNG TRÊN BẢN ĐỒ -> ẢNH ĐÃ CHỤP + BAY GIÁM SÁT 1 ẢNH
    // =========================================================
    /** Id ruộng đã lưu tại điểm chạm trên mini map (null nếu không trúng ruộng nào). */
    private String savedFieldAt(org.maplibre.android.geometry.LatLng point) {
        if (miniMapLibreMap == null || miniMapLibreMap.getStyle() == null) return null;
        android.graphics.PointF sp = miniMapLibreMap.getProjection().toScreenLocation(point);
        List<Feature> hit = miniMapLibreMap.queryRenderedFeatures(sp,
                com.tri.djicontrol.field.SavedFieldsLayer.FILL_LAYER_ID);
        for (Feature f : hit) {
            if (f.hasProperty(com.tri.djicontrol.field.SavedFieldsLayer.PROP_FIELD_ID)) {
                return f.getStringProperty(com.tri.djicontrol.field.SavedFieldsLayer.PROP_FIELD_ID);
            }
        }
        // Ruộng đang chọn được vẽ ở lớp riêng (không nằm trong lớp ruộng đã lưu)
        if (currentFieldId != null && fieldPoints.size() >= 3
                && !miniMapLibreMap.queryRenderedFeatures(sp, "wp-field-fill").isEmpty()) {
            return currentFieldId;
        }
        return null;
    }

    private final java.util.concurrent.ExecutorService fieldPhotoWorker =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /** Giải mã ảnh thu nhỏ (cạnh dài ~maxPx) để không tốn RAM với ảnh gốc 12MP. */
    private static android.graphics.Bitmap decodeSampled(File f, int maxPx) {
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        int s = 1;
        while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= maxPx) s *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = s;
        return android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o);
    }

    private void showFieldPhotosDialog(String fieldId) {
        com.tri.djicontrol.field.SavedFieldStore.SavedField field =
                com.tri.djicontrol.field.SavedFieldStore.get(this, fieldId);
        if (field == null) return;
        int number = com.tri.djicontrol.field.SavedFieldsLayer.fieldNumber(this, fieldId);
        int color = com.tri.djicontrol.field.SavedFieldsLayer.fieldColor(number);
        float dens = getResources().getDisplayMetrics().density;

        android.app.Dialog d = new android.app.Dialog(this, R.style.CfDialog);
        d.setContentView(R.layout.dialog_field_photos);
        TextView tvNum = d.findViewById(R.id.tvFieldPhotosNumber);
        tvNum.setText(String.valueOf(number));
        tvNum.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
        ((TextView) d.findViewById(R.id.tvFieldPhotosTitle)).setText(field.name);
        TextView tvInfo = d.findViewById(R.id.tvFieldPhotosInfo);
        tvInfo.setText(String.format(Locale.US, "%.2f ha • %s • đang tìm ảnh...", field.areaHa, field.statusLabel()));
        GridView grid = d.findViewById(R.id.gridFieldPhotos);
        TextView tvEmpty = d.findViewById(R.id.tvFieldPhotosEmpty);
        android.widget.ImageView ivPreview = d.findViewById(R.id.ivFieldPhotoPreview);
        TextView tvMeta = d.findViewById(R.id.tvFieldPhotoMeta);
        Button btnInspect = d.findViewById(R.id.btnFieldPhotoInspect);
        btnInspect.setAlpha(0.45f);
        d.findViewById(R.id.btnFieldPhotosClose).setOnClickListener(v -> d.dismiss());

        final List<com.tri.djicontrol.field.FieldPhotoIndex.Photo> photos = new ArrayList<>();
        final android.util.LruCache<String, android.graphics.Bitmap> thumbs = new android.util.LruCache<>(60);
        final com.tri.djicontrol.field.FieldPhotoIndex.Photo[] selected = new com.tri.djicontrol.field.FieldPhotoIndex.Photo[1];
        final Handler ui = new Handler(Looper.getMainLooper());
        final boolean[] open = {true};
        d.setOnDismissListener(x -> open[0] = false);

        android.widget.BaseAdapter adapter = new android.widget.BaseAdapter() {
            @Override public int getCount() { return photos.size(); }
            @Override public Object getItem(int i) { return photos.get(i); }
            @Override public long getItemId(int i) { return i; }

            @Override
            public View getView(int i, View convert, android.view.ViewGroup parent) {
                android.widget.ImageView iv = convert instanceof android.widget.ImageView
                        ? (android.widget.ImageView) convert : new android.widget.ImageView(MainActivity.this);
                int size = grid.getColumnWidth() > 0 ? grid.getColumnWidth() : Math.round(96 * dens);
                iv.setLayoutParams(new android.widget.AbsListView.LayoutParams(size, size));
                iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                int pad = Math.round(2 * dens);
                iv.setPadding(pad, pad, pad, pad);
                com.tri.djicontrol.field.FieldPhotoIndex.Photo p = photos.get(i);
                iv.setBackgroundColor(p == selected[0] ? color : 0x22FFFFFF);
                String key = p.file.getAbsolutePath();
                iv.setTag(key);
                android.graphics.Bitmap b = thumbs.get(key);
                iv.setImageBitmap(b);
                if (b == null) {
                    fieldPhotoWorker.execute(() -> {
                        if (!open[0]) return;
                        android.graphics.Bitmap bmp = decodeSampled(p.file, 200);
                        ui.post(() -> {
                            if (bmp != null) thumbs.put(key, bmp);
                            if (key.equals(iv.getTag())) iv.setImageBitmap(bmp);
                        });
                    });
                }
                return iv;
            }
        };
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, pos, id) -> {
            com.tri.djicontrol.field.FieldPhotoIndex.Photo p = photos.get(pos);
            selected[0] = p;
            adapter.notifyDataSetChanged();
            ivPreview.setImageBitmap(thumbs.get(p.file.getAbsolutePath()));
            fieldPhotoWorker.execute(() -> {
                android.graphics.Bitmap big = decodeSampled(p.file, 1280);
                ui.post(() -> { if (selected[0] == p && big != null) ivPreview.setImageBitmap(big); });
            });
            float alt = Float.isNaN(p.relAlt) ? field.altitude : p.relAlt;
            tvMeta.setText(String.format(Locale.US, "%s\n%s\n%.6f, %.6f • cao %.1f m%s%s",
                    p.file.getName(),
                    new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US).format(new java.util.Date(p.takenAt)),
                    p.lat, p.lng, alt, Float.isNaN(p.relAlt) ? " (theo ruộng)" : "",
                    Float.isNaN(p.yaw) ? "" : String.format(Locale.US, " • mũi %.0f°", p.yaw)));
            boolean busy = missionManager == null || missionManager.isRunning();
            btnInspect.setEnabled(!busy);
            btnInspect.setAlpha(busy ? 0.45f : 1f);
            if (busy) tvMeta.append(missionManager == null ? "\n⚠ Chưa kết nối drone" : "\n⚠ Đang có nhiệm vụ bay khác");
        });
        btnInspect.setOnClickListener(v -> {
            if (selected[0] == null) return;
            confirmInspectPhoto(field, selected[0], d);
        });

        Runnable load = () -> {
            List<com.tri.djicontrol.field.FieldPhotoIndex.Photo> found =
                    com.tri.djicontrol.field.FieldPhotoIndex.findInField(this, field);
            ui.post(() -> {
                if (!open[0]) return;
                boolean changed = found.size() != photos.size();
                photos.clear();
                photos.addAll(found);
                // Giữ ảnh đang chọn khi danh sách được làm mới trong lúc bay
                if (selected[0] != null) {
                    String sel = selected[0].file.getAbsolutePath();
                    for (com.tri.djicontrol.field.FieldPhotoIndex.Photo p : found) {
                        if (p.file.getAbsolutePath().equals(sel)) { selected[0] = p; break; }
                    }
                }
                if (changed) adapter.notifyDataSetChanged();
                boolean flying = missionManager != null && missionManager.isRunning();
                tvInfo.setText(String.format(Locale.US, "%.2f ha • %s • %d ảnh trên điện thoại%s",
                        field.areaHa, field.statusLabel(), found.size(),
                        flying ? " • đang bay, tự cập nhật ảnh mới" : ""));
                tvEmpty.setText("Chưa có ảnh nào của ruộng này trên điện thoại.\n"
                        + "Bay quét chế độ \"Thẻ + Cloud ngay\", hoặc tải ảnh từ Lưu trữ > THẺ NHỚ DRONE.");
                tvEmpty.setVisibility(found.isEmpty() ? View.VISIBLE : View.GONE);
            });
        };
        fieldPhotoWorker.execute(load);
        // Đang bay (quét / giám sát): cứ 4 s quét lại thư mục để ảnh vừa tải về hiện ngay trong tab
        Runnable[] poll = new Runnable[1];
        poll[0] = () -> {
            if (!open[0]) return;
            if (missionManager != null && missionManager.isRunning()) fieldPhotoWorker.execute(load);
            ui.postDelayed(poll[0], 4000);
        };
        ui.postDelayed(poll[0], 4000);

        d.show();
        if (d.getWindow() != null) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            d.getWindow().setLayout((int) Math.min(dm.widthPixels * 0.92f, 1000 * dm.density),
                    (int) (dm.heightPixels * 0.88f));
        }
    }

    /** Hỏi lại rồi bay tới đúng vị trí/độ cao/hướng của ảnh cũ, chụp 1 tấm mới và về Home. */
    private void confirmInspectPhoto(com.tri.djicontrol.field.SavedFieldStore.SavedField field,
                                     com.tri.djicontrol.field.FieldPhotoIndex.Photo p, android.app.Dialog photosDialog) {
        if (missionManager == null) {
            Toast.makeText(this, "Chưa kết nối drone!", Toast.LENGTH_SHORT).show();
            return;
        }
        if (missionManager.isRunning()) {
            Toast.makeText(this, "Đang có nhiệm vụ bay khác!", Toast.LENGTH_SHORT).show();
            return;
        }
        float alt = Float.isNaN(p.relAlt) ? field.altitude : p.relAlt;
        float transit = Math.max(alt, wpTransitAlt);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Bay giám sát ảnh này?")
                .setMessage(String.format(Locale.US,
                        "Drone sẽ:\n• Cất cánh (nếu đang ở đất) và lên %.0f m\n• Bay tới %.6f, %.6f\n"
                                + "• Hạ xuống %.1f m, quay đúng hướng ảnh cũ và chụp 1 ảnh mới\n"
                                + "• Tải ảnh về điện thoại (đẩy Cloud nếu đã cấu hình) rồi tự bay về Home\n\n"
                                + "Kiểm tra xung quanh không có vật cản trước khi bay.",
                        transit, p.lat, p.lng, alt))
                .setPositiveButton("BAY", (x, i) -> {
                    photosDialog.dismiss();
                    missionManager.onBatteryUpdate(batteryPercent);
                    missionManager.setLowBatteryThreshold(wpLowBattery);
                    missionManager.setTransitAltitude(wpTransitAlt);
                    missionManager.setReturnAltitude(wpReturnAlt);
                    missionManager.startInspection(p.lat, p.lng, alt, p.yaw, p.gimbalPitch,
                            Math.max(1f, Math.min(field.speed, 5f)), createInspectListener(field));
                })
                .setNegativeButton("HỦY", null)
                .show();
    }

    private FlightMissionManager.MissionListener createInspectListener(
            com.tri.djicontrol.field.SavedFieldStore.SavedField field) {
        return new FlightMissionManager.MissionListener() {
            @Override public void onStatus(String status) { setWpStatus("Giám sát: " + status); }

            @Override public void onProgress(int reachedIndex, int total, int photoCount) { }

            @Override
            public void onFinished(boolean completed, String message) {
                setWpStatus("Giám sát: " + message);
                if (activeWpStopButton != null) activeWpStopButton.setVisibility(View.GONE);
                new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle(completed ? "Đã chụp lại ảnh giám sát" : "Giám sát đã dừng")
                        .setMessage(message + (completed
                                ? "\n\nẢnh mới đã lưu vào thẻ nhớ và có trong tab ảnh của ruộng \"" + field.name + "\"."
                                : ""))
                        .setPositiveButton("XEM ẢNH RUỘNG", (x, i) -> showFieldPhotosDialog(field.id))
                        .setNegativeButton("OK", null)
                        .show();
            }
        };
    }

    /** Xanh = đã bay xong, vàng = bay dở, xám = chưa bay (dùng chung với bản đồ). */
    static int fieldStatusColor(String status) {
        return com.tri.djicontrol.field.SavedFieldsLayer.statusColor(status);
    }

    /** Nạp 4 góc + thông số bay của ruộng đã lưu để bay lại. */
    private void loadSavedField(com.tri.djicontrol.field.SavedFieldStore.SavedField f) {
        fieldPoints.clear();
        for (int i = 0; i < f.lats.length && i < f.lngs.length; i++) {
            fieldPoints.add(new LatLng(f.lats[i], f.lngs[i]));
        }
        fieldAreaHa = f.areaHa;
        wpAltitude = f.altitude;
        wpSpacing = f.spacing;
        wpSpeed = f.speed;
        wpVideo = f.video;
        currentFieldId = f.id;
        refreshSavedFieldsOnMiniMap();
        if (tvFieldArea != null) tvFieldArea.setText(String.format(Locale.US, "%.2f ha", fieldAreaHa));
        plannedWaypoints = new ArrayList<>();
        drawFieldOnMap();
        drawPlanOnMap(false);
        Toast.makeText(this, "Đã chọn \"" + f.name + "\"", Toast.LENGTH_SHORT).show();
    }

    /** Đẩy GeoJSON của 1 ruộng lên Cloudinary. */
    private void uploadField(com.tri.djicontrol.field.SavedFieldStore.SavedField f,
                             java.util.function.Consumer<Boolean> done) {
        com.tri.djicontrol.firebase.FirebaseHelper.uploadJson(this, "field_" + f.id,
                com.tri.djicontrol.field.SavedFieldStore.toGeoJson(f),
                com.tri.djicontrol.field.SavedFieldStore.cloudContext(f),
                "dji_field," + f.id,
                new com.tri.djicontrol.firebase.FirebaseHelper.UploadCallback() {
                    @Override public void onProgress(int percent, long a, long b) {}

                    @Override
                    public void onSuccess(String url) {
                        // Đọc lại bản mới nhất (có thể vừa được cập nhật trong lúc đẩy)
                        com.tri.djicontrol.field.SavedFieldStore.SavedField cur =
                                com.tri.djicontrol.field.SavedFieldStore.get(MainActivity.this, f.id);
                        if (cur != null) {
                            cur.cloudUrl = url;
                            cur.cloudSyncedAt = Math.max(System.currentTimeMillis(), cur.updatedAt);
                            com.tri.djicontrol.field.SavedFieldStore.put(MainActivity.this, cur);
                        }
                        Toast.makeText(MainActivity.this, "Đã đẩy \"" + f.name + "\" lên Cloud", Toast.LENGTH_SHORT).show();
                        done.accept(true);
                    }

                    @Override
                    public void onFailure(String message) {
                        Toast.makeText(MainActivity.this, "Lỗi Cloud (" + f.name + "): " + message, Toast.LENGTH_LONG).show();
                        done.accept(false);
                    }
                });
    }

    private void uploadFieldsSequential(List<com.tri.djicontrol.field.SavedFieldStore.SavedField> list,
                                        int i, int ok, Button btn, Runnable rerender) {
        if (i >= list.size()) {
            Toast.makeText(this, "Đã đẩy " + ok + "/" + list.size() + " ruộng lên Cloud", Toast.LENGTH_SHORT).show();
            rerender.run();
            return;
        }
        btn.setText(String.format(Locale.US, "Đang đẩy %d/%d...", i + 1, list.size()));
        uploadField(list.get(i), success -> uploadFieldsSequential(list, i + 1, ok + (success ? 1 : 0), btn, rerender));
    }

    /** Nhiệm vụ kết thúc -> cập nhật trạng thái mảnh ruộng gắn với chuyến bay đó. */
    private void markFieldAfterMission(boolean completed) {
        if (missionManager == null) return;
        com.tri.djicontrol.field.SavedFieldStore.SavedField f =
                com.tri.djicontrol.field.SavedFieldStore.findByMission(this, missionManager.getMissionId());
        if (f == null) return;
        if (completed) {
            f.status = com.tri.djicontrol.field.SavedFieldStore.STATUS_DONE;
            f.flightCount++;
        } else if (!com.tri.djicontrol.field.SavedFieldStore.STATUS_DONE.equals(f.status)) {
            f.status = com.tri.djicontrol.field.SavedFieldStore.STATUS_PARTIAL;
        }
        f.lastFlownAt = System.currentTimeMillis();
        f.updatedAt = System.currentTimeMillis();
        com.tri.djicontrol.field.SavedFieldStore.put(this, f);
    }

    /** Bay tiếp nhiệm vụ đang dở (sau khi thay pin). */
    private void resumeWpMission(com.tri.djicontrol.flight.MissionCheckpoint cp) {
        if (missionManager == null || DJISDKManager.getInstance().getProduct() == null) {
            Toast.makeText(this, "Chưa kết nối Drone!", Toast.LENGTH_SHORT).show();
            return;
        }
        if (missionManager.isRunning()) {
            Toast.makeText(this, "Nhiệm vụ đang chạy!", Toast.LENGTH_SHORT).show();
            return;
        }
        // Hiện lại lộ trình gốc + phần đã quét trên bản đồ
        plannedWaypoints = new ArrayList<>(cp.waypoints);
        wpAltitude = cp.scanAltitude;
        wpSpeed = cp.speed;
        wpVideo = cp.video;
        drawPlanOnMap(true);
        if (cp.scanStarted) drawProgressOnMap(cp.nextIndex);
        resetCapturePanel(cp.video);
        wpCapturedPhotos = cp.photoCount;
        updateCapturePanel();

        missionManager.onBatteryUpdate(batteryPercent);
        missionManager.setLowBatteryThreshold(wpLowBattery);
        missionManager.setCloudUpload(wpCloudUpload);
        missionManager.resumeMission(cp, createWpListener(cp.video));
        Toast.makeText(this, "Bay tiếp nhiệm vụ từ " + cp.progressPercent() + "%", Toast.LENGTH_SHORT).show();
    }

    private FlightMissionManager.MissionListener createWpListener(final boolean video) {
        return new FlightMissionManager.MissionListener() {
                    @Override
                    public void onStatus(String status) {
                        setWpStatus("WP: " + status);
                    }

                    @Override
                    public void onProgress(int reachedIndex, int total, int photoCount) {
                        drawProgressOnMap(reachedIndex);
                        setWpStatus(String.format(Locale.US, "WP: đã qua điểm %d/%d",
                                Math.min(reachedIndex, total), total));
                        // Số ảnh hiện ở khung "Thông tin nhiệm vụ", không ở dòng trạng thái
                        wpCapturedPhotos = photoCount;
                        updateCapturePanel();
                    }

                    @Override
                    public void onRecordingChanged(boolean recording) {
                        if (recording) {
                            wpRecordStartMs = System.currentTimeMillis();
                            wpRecording = true;
                            capturePanelHandler.removeCallbacks(capturePanelTicker);
                            capturePanelHandler.post(capturePanelTicker);
                        } else if (wpRecording) {
                            wpRecording = false;
                            wpRecordedMs = System.currentTimeMillis() - wpRecordStartMs;
                            capturePanelHandler.removeCallbacks(capturePanelTicker);
                            updateCapturePanel();
                        }
                    }

                    @Override
                    public void onCloudUpload(int uploaded, int failed, int pending, String message) {
                        wpCloudUploaded = uploaded;
                        wpCloudFailed = failed;
                        wpCloudPending = pending;
                        updateCapturePanel();
                    }

                    @Override
                    public void onCheckpointSaved(com.tri.djicontrol.flight.MissionCheckpoint checkpoint) {
                        // Lần kết nối/thay pin tới sẽ được hỏi bay tiếp
                        resumePromptShown = false;
                    }

                    @Override
                    public void onFinished(boolean completed, String message) {
                        if (wpRecording) onRecordingChanged(false);
                        markFieldAfterMission(completed); // đánh dấu ruộng đã bay / bay dở
                        setWpStatus("WP: " + message);
                        if (activeWpStopButton != null) activeWpStopButton.setVisibility(View.GONE);
                        boolean paused = !completed
                                && com.tri.djicontrol.flight.MissionCheckpoint.load(MainActivity.this) != null;
                        new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this)
                                .setTitle(completed ? "Hoàn thành bay quét"
                                        : paused ? "Tạm dừng — đã lưu tiến độ" : "Nhiệm vụ đã dừng")
                                .setMessage(message + (completed
                                        ? "\n\nMở Lưu trữ > THẺ NHỚ DRONE để xem " + (video ? "video." : "ảnh.")
                                        : paused ? "\n\nKhi drone hạ cánh: thay pin đầy và bật lại drone, "
                                                + "app sẽ tự hỏi để bay tiếp." : ""))
                                .setPositiveButton("OK", null)
                                .show();
                    }
                };
    }

    // =========================================================
    // BAY TIẾP NHIỆM VỤ DỞ KHI PIN ĐÃ ĐẦY
    // =========================================================
    /** Ngưỡng pin (%) để dừng quét và về nhà — chỉnh trong hộp thoại Thiết lập lộ trình. */
    private int wpLowBattery = FlightMissionManager.DEFAULT_LOW_BATTERY_PERCENT;
    /** Pin tối thiểu (%) để cho phép bay tiếp. */
    private static final int RESUME_MIN_BATTERY = 90;
    private boolean resumePromptShown = false;

    /** Gọi mỗi lần pin cập nhật: drone đang ở đất + pin đầy + có nhiệm vụ dở -> hỏi bay tiếp (1 lần). */
    private void maybePromptResume() {
        if (resumePromptShown || isFinishing() || batteryPercent < RESUME_MIN_BATTERY) return;
        if (missionManager == null || missionManager.isRunning()) return;
        try {
            FlightController fc = ((Aircraft) DJISDKManager.getInstance().getProduct()).getFlightController();
            if (fc == null || fc.getState() == null || fc.getState().isFlying()) return;
        } catch (Exception e) {
            return;
        }
        com.tri.djicontrol.flight.MissionCheckpoint cp = com.tri.djicontrol.flight.MissionCheckpoint.load(this);
        // Chỉ kiểm tra 1 lần; khi có tiến độ mới được lưu (onCheckpointSaved) sẽ kiểm tra lại
        resumePromptShown = true;
        if (cp != null) showResumeDialog(cp);
    }

    private void showResumeDialog(com.tri.djicontrol.flight.MissionCheckpoint cp) {
        android.app.Dialog d = new android.app.Dialog(this, R.style.CfDialog);
        d.setContentView(R.layout.dialog_wp_resume);
        d.setCancelable(true);

        int pct = cp.progressPercent();
        ((TextView) d.findViewById(R.id.tvResumePercent)).setText(pct + "%");
        ((android.widget.ProgressBar) d.findViewById(R.id.pbResume)).setProgress(pct);
        ((TextView) d.findViewById(R.id.tvResumeMission)).setText(
                (cp.missionId != null ? cp.missionId : "Nhiệm vụ") + " • " + (cp.video ? "Quay video" : "Chụp ảnh"));
        ((TextView) d.findViewById(R.id.tvResumeReason)).setText(String.format(Locale.US,
                "Dừng lúc %s • %s (pin còn %d%%)",
                new java.text.SimpleDateFormat("HH:mm dd/MM", Locale.US).format(new java.util.Date(cp.savedAtMs)),
                cp.reason, cp.batteryAtPause));

        LinearLayout stats = d.findViewById(R.id.resumeStats);
        int remaining = Math.max(0, cp.waypoints.size() - Math.max(cp.nextIndex, 0));
        addCfStat(stats, cp.video ? "Video" : String.valueOf(cp.photoCount), cp.video ? "Chế độ" : "Ảnh đã chụp");
        addCfStat(stats, remaining + " điểm", "Còn lại");
        addCfStat(stats, batteryPercent + "%", "Pin hiện tại");

        Button btnGo = d.findViewById(R.id.btnResumeGo);
        TextView hint = d.findViewById(R.id.tvResumeHint);
        boolean enough = batteryPercent >= RESUME_MIN_BATTERY;
        btnGo.setEnabled(enough);
        hint.setText(enough
                ? "Drone sẽ tự cất cánh, bay thẳng tới chỗ đã dừng rồi quét tiếp phần còn lại."
                : "Pin cần ≥ " + RESUME_MIN_BATTERY + "% để bay tiếp. Hãy thay pin đầy.");

        btnGo.setOnClickListener(v -> {
            d.dismiss();
            resumeWpMission(cp);
        });
        d.findViewById(R.id.btnResumeLater).setOnClickListener(v -> d.dismiss());
        d.findViewById(R.id.btnResumeDiscard).setOnClickListener(v ->
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Hủy nhiệm vụ đang dở?")
                        .setMessage("Tiến độ " + pct + "% sẽ bị xóa, không thể bay tiếp được nữa.")
                        .setPositiveButton("HỦY NHIỆM VỤ", (x, i) -> {
                            com.tri.djicontrol.flight.MissionCheckpoint.clear(this);
                            d.dismiss();
                        })
                        .setNegativeButton("KHÔNG", null)
                        .show());

        d.show();
        if (d.getWindow() != null) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            d.getWindow().setLayout((int) Math.min(dm.widthPixels * 0.9f, 520 * dm.density),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    // =========================================================
    // KHUNG "THÔNG TIN NHIỆM VỤ": dữ liệu thu thập của chuyến bay quét (WP)
    // =========================================================
    private boolean wpCaptureVideo = false;
    private int wpCapturedPhotos = 0;
    /** Ảnh WP đã đẩy lên Cloudinary / lỗi / đang đẩy (-1 = chưa bật upload). */
    private int wpCloudUploaded = -1, wpCloudFailed = 0, wpCloudPending = 0;
    private boolean wpRecording = false;
    private long wpRecordStartMs = 0, wpRecordedMs = 0;
    private final Handler capturePanelHandler = new Handler(Looper.getMainLooper());
    private final Runnable capturePanelTicker = new Runnable() {
        @Override
        public void run() {
            updateCapturePanel();
            if (wpRecording) capturePanelHandler.postDelayed(this, 1000);
        }
    };

    private void resetCapturePanel(boolean video) {
        capturePanelHandler.removeCallbacks(capturePanelTicker);
        wpCaptureVideo = video;
        wpCapturedPhotos = 0;
        wpCloudUploaded = -1;
        wpCloudFailed = 0;
        wpCloudPending = 0;
        wpRecording = false;
        wpRecordStartMs = 0;
        wpRecordedMs = 0;
        updateCapturePanel();
    }

    private void updateCapturePanel() {
        if (tvPhotoCount == null) return;
        if (tvCaptureLabel != null) tvCaptureLabel.setText(wpCaptureVideo ? "Video bay quét" : "Ảnh bay quét");
        String value;
        int color = 0xFF69F0AE;
        if (!wpCaptureVideo) {
            value = wpCapturedPhotos + " ảnh";
            if (wpCloudUploaded >= 0) {
                value += " • ☁ " + wpCloudUploaded;
                if (wpCloudPending > 0) value += " (đang đẩy " + wpCloudPending + ")";
                if (wpCloudFailed > 0) value += " • lỗi " + wpCloudFailed;
            }
        } else if (wpRecording) {
            value = "● REC " + formatDuration(System.currentTimeMillis() - wpRecordStartMs);
            color = 0xFFFF5252;
        } else if (wpRecordedMs > 0) {
            value = "1 video • " + formatDuration(wpRecordedMs);
        } else {
            value = "Chờ quay";
        }
        tvPhotoCount.setText(value);
        tvPhotoCount.setTextColor(color);
    }

    private static String formatDuration(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    private void setWpStatus(String text) {
        wpStatusText = text;
        if (tvStatus != null) tvStatus.setText(text);
        if (activeWpEstimateView != null) activeWpEstimateView.setText(text);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (miniMapView != null) miniMapView.onStart();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_FIELD_MAP && resultCode == RESULT_OK && data != null) {
            double areaHa = data.getDoubleExtra("FIELD_AREA_HA", 0.0);
            double[] latitudes = data.getDoubleArrayExtra("FIELD_LATITUDES");
            double[] longitudes = data.getDoubleArrayExtra("FIELD_LONGITUDES");

            fieldPoints.clear();
            if (latitudes != null && longitudes != null && latitudes.length == longitudes.length) {
                for (int i = 0; i < latitudes.length; i++) {
                    fieldPoints.add(new LatLng(latitudes[i], longitudes[i]));
                }
            }

            fieldAreaHa = areaHa;
            if (tvFieldArea != null) {
                tvFieldArea.setText(String.format(Locale.US, "%.2f ha", fieldAreaHa));
            }
            // Ruộng mới -> bỏ lộ trình cũ, vẽ lại ruộng (lộ trình mới được tạo khi mở hộp thoại WP)
            plannedWaypoints = new ArrayList<>();
            drawFieldOnMap();
            drawPlanOnMap(false);
            openWPDialog();
        }
    }

    @Override
    protected void onPause() {
        if (miniMapView != null) miniMapView.onPause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (miniMapView != null) miniMapView.onStop();
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (miniMapView != null) miniMapView.onResume();
        refreshSavedFieldsOnMiniMap(); // có thể vừa sửa ruộng ở màn hình bản đồ
        if (videoSurface != null && videoSurface.isAvailable()) {
            if (codecManager == null) {
                codecManager = new DJICodecManager(this, videoSurface.getSurfaceTexture(), videoSurface.getWidth(), videoSurface.getHeight());
            }
        }
    }

    @Override
    protected void onDestroy() {
        com.tri.djicontrol.field.SavedFieldStore.setOnChangeListener(null);
        capturePanelHandler.removeCallbacks(capturePanelTicker);
        stopLiveRecording(); // đóng file MP4 đang ghi để không bị hỏng
        if (miniMapView != null) miniMapView.onDestroy();
        if (missionManager != null) missionManager.cancelMission();
        if (codecManager != null) {
            codecManager.cleanSurface();
            codecManager = null;
        }
        super.onDestroy();
    }


    private void setupPhoneLocationMarker(Style style) {

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    MINI_MAP_LOCATION_PERMISSION
            );

            return;
        }

        LocationComponent locationComponent =
                miniMapLibreMap.getLocationComponent();

        LocationEngineRequest locationRequest =
                new LocationEngineRequest.Builder(1000)
                        .setFastestInterval(500)
                        .setPriority(
                                LocationEngineRequest.PRIORITY_HIGH_ACCURACY
                        )
                        .build();

        locationComponent.activateLocationComponent(
                LocationComponentActivationOptions
                        .builder(this, style)
                        .useDefaultLocationEngine(true)
                        .locationEngineRequest(locationRequest)
                        .build()
        );


        locationComponent.setLocationComponentEnabled(true);

        locationComponent.setRenderMode(
                RenderMode.COMPASS
        );

// Tự theo dõi vị trí điện thoại và zoom gần
        locationComponent.setCameraMode(
                CameraMode.TRACKING
        );

// Zoom mặc định của bản đồ nhỏ
        miniMapLibreMap.setCameraPosition(
                new org.maplibre.android.camera.CameraPosition.Builder()
                        .zoom(18.0)
                        .build()
        );

// Lấy vị trí điện thoại gần nhất nếu đã có
        Location lastLocation =
                locationComponent.getLastKnownLocation();

        if (lastLocation != null
                && lastLocation.getLatitude() != 0
                && lastLocation.getLongitude() != 0) {

            miniMapLibreMap.setCameraPosition(
                    new org.maplibre.android.camera.CameraPosition.Builder()
                            .target(
                                    new LatLng(
                                            lastLocation.getLatitude(),
                                            lastLocation.getLongitude()
                                    )
                            )
                            .zoom(18.0)
                            .build()
            );
        }

    }



    private void updatePhoneLocationOnMiniMap(
            double latitude,
            double longitude
    ) {

        if (miniMapLibreMap == null) {
            return;
        }

        runOnUiThread(() -> {

            if (miniMapLibreMap.getStyle() == null) {
                return;
            }

            GeoJsonSource source =
                    miniMapLibreMap
                            .getStyle()
                            .getSourceAs(PHONE_LOCATION_SOURCE_ID);

            if (source != null) {

                source.setGeoJson(
                        Feature.fromGeometry(
                                Point.fromLngLat(
                                        longitude,
                                        latitude
                                )
                        )
                );
            }
        });
    }



    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );

        if (requestCode == MINI_MAP_LOCATION_PERMISSION) {

            boolean locationGranted = false;

            for (int result : grantResults) {
                if (result == PackageManager.PERMISSION_GRANTED) {
                    locationGranted = true;
                    break;
                }
            }

            if (locationGranted) {

                if (miniMapLibreMap != null
                        && miniMapLibreMap.getStyle() != null) {

                    setupPhoneLocationMarker(
                            miniMapLibreMap.getStyle()
                    );
                }

            } else {

                Toast.makeText(
                        MainActivity.this,
                        "Ứng dụng chưa được cấp quyền vị trí",
                        Toast.LENGTH_SHORT
                ).show();
            }
        }
    }


    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        if (codecManager == null) codecManager = new DJICodecManager(this, surface, width, height);
        if (VideoFeeder.getInstance() != null) {
            VideoFeeder.VideoFeed feed = VideoFeeder.getInstance().getPrimaryVideoFeed();
            if (feed != null) feed.addVideoDataListener(receivedVideoDataListener);
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
            if (feed != null) feed.removeVideoDataListener(receivedVideoDataListener);
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
        if (codecManager != null) codecManager.sendDataToDecoder(videoBuffer, size);
        com.tri.djicontrol.media.LiveStreamRecorder rec = liveRecorder;
        if (rec != null) rec.feed(videoBuffer, size);
    };



    private void showStorageDialog() {
        Dialog dialog = new Dialog(this, R.style.CfDialog);
        dialog.setContentView(R.layout.dialog_storage);

        if (dialog.getWindow() != null) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(
                    (int) Math.min(dm.widthPixels * 0.82f, 860 * dm.density),
                    (int) (dm.heightPixels * 0.9f)
            );
        }

        GridView gridStorage = dialog.findViewById(R.id.gridStorage);
        Button btnCloseStorage = dialog.findViewById(R.id.btnCloseStorage);
        View layoutHeaderNormal = dialog.findViewById(R.id.layoutHeaderNormal);
        View layoutHeaderSelection = dialog.findViewById(R.id.layoutHeaderSelection);
        Button btnCancelSelection = dialog.findViewById(R.id.btnCancelSelection);
        TextView tvSelectionCount = dialog.findViewById(R.id.tvSelectionCount);
        Button btnDownloadSelected = dialog.findViewById(R.id.btnDownloadSelected);
        Button btnDeleteSelected = dialog.findViewById(R.id.btnDeleteSelected);
        Button btnFirebaseSelected = dialog.findViewById(R.id.btnFirebaseSelected);

        tvSpeedStats = dialog.findViewById(R.id.tvSpeedStats);

        List<MediaFile> storageFiles = new ArrayList<>();

        StorageAdapter adapter = new StorageAdapter(
                MainActivity.this,
                storageFiles
        );

        gridStorage.setAdapter(adapter);

        // --- Tab "Ảnh trên điện thoại": ảnh chụp khi quay (drone không hỗ trợ chụp lúc quay) ---
        GridView gridLocal = dialog.findViewById(R.id.gridLocal);
        TextView tvLocalEmpty = dialog.findViewById(R.id.tvLocalEmpty);
        TextView tabDrone = dialog.findViewById(R.id.tabDrone);
        TextView tabPhone = dialog.findViewById(R.id.tabPhone);
        View tvSpeedView = dialog.findViewById(R.id.tvSpeedStats);
        LocalPhotoAdapter localAdapter = new LocalPhotoAdapter(MainActivity.this);
        gridLocal.setAdapter(localAdapter);
        // true khi đang ở tab "Ảnh trên điện thoại" -> các nút TẢI VỀ / CLOUD / XÓA thao tác trên ảnh điện thoại
        final boolean[] phoneTab = {false};

        Runnable showDroneTab = () -> {
            localAdapter.setSelectionMode(false);
            phoneTab[0] = false;
            tabDrone.setSelected(true);
            tabPhone.setSelected(false);
            gridStorage.setVisibility(View.VISIBLE);
            tvSpeedView.setVisibility(View.VISIBLE);
            gridLocal.setVisibility(View.GONE);
            tvLocalEmpty.setVisibility(View.GONE);
        };
        Runnable showPhoneTab = () -> {
            adapter.setSelectionMode(false);
            phoneTab[0] = true;
            tabPhone.setSelected(true);
            tabDrone.setSelected(false);
            gridStorage.setVisibility(View.GONE);
            tvSpeedView.setVisibility(View.GONE);
            boolean empty = localAdapter.reload() == 0;
            gridLocal.setVisibility(empty ? View.GONE : View.VISIBLE);
            tvLocalEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        };
        tabDrone.setSelected(true); // mặc định mở tab thẻ nhớ drone
        tabDrone.setOnClickListener(v -> showDroneTab.run());
        tabPhone.setOnClickListener(v -> showPhoneTab.run());
        localAdapter.registerDataSetObserver(new android.database.DataSetObserver() {
            @Override
            public void onChanged() {
                if (gridStorage.getVisibility() == View.VISIBLE) return; // đang ở tab drone
                boolean empty = localAdapter.getCount() == 0;
                gridLocal.setVisibility(empty ? View.GONE : View.VISIBLE);
                tvLocalEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
            }
        });

        StorageAdapter.OnSelectionListener selectionListener = new StorageAdapter.OnSelectionListener() {
            @Override
            public void onSelectionChanged(int selectedCount) {
                if (tvSelectionCount != null) {
                    tvSelectionCount.setText("Đã chọn: " + selectedCount);
                }
                if (btnDownloadSelected != null) {
                    btnDownloadSelected.setText("TẢI VỀ (" + selectedCount + ")");
                }
                if (btnDeleteSelected != null) {
                    btnDeleteSelected.setText("XÓA (" + selectedCount + ")");
                }
                if (btnFirebaseSelected != null) {
                    btnFirebaseSelected.setText("CLOUD (" + selectedCount + ")");
                }
            }

            @Override
            public void onSelectionModeChanged(boolean isSelectionMode) {
                if (layoutHeaderNormal != null && layoutHeaderSelection != null) {
                    layoutHeaderNormal.setVisibility(isSelectionMode ? View.GONE : View.VISIBLE);
                    layoutHeaderSelection.setVisibility(isSelectionMode ? View.VISIBLE : View.GONE);
                }
            }
        };
        adapter.setOnSelectionListener(selectionListener);
        localAdapter.setOnSelectionListener(selectionListener);

        if (btnCancelSelection != null) {
            btnCancelSelection.setOnClickListener(v -> {
                if (phoneTab[0]) localAdapter.setSelectionMode(false);
                else adapter.setSelectionMode(false);
            });
        }

        if (btnDownloadSelected != null) {
            btnDownloadSelected.setOnClickListener(v -> {
                if (phoneTab[0]) {
                    localAdapter.saveSelectedToGallery();
                    return;
                }
                Set<MediaFile> selected = adapter.getSelectedFiles();
                if (selected.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Vui lòng chọn ít nhất 1 file để tải về!", Toast.LENGTH_SHORT).show();
                    return;
                }

                List<MediaFile> toDownload = new ArrayList<>(selected);
                adapter.clearSelection();
                adapter.setSelectionMode(false);

                Toast.makeText(MainActivity.this, "Bắt đầu tải " + toDownload.size() + " file về Bộ sưu tập...", Toast.LENGTH_SHORT).show();
                downloadBatchSelectedFilesRecursive(toDownload, 0, 0);
            });
        }

        if (btnFirebaseSelected != null) {
            btnFirebaseSelected.setOnClickListener(v -> {
                if (phoneTab[0]) {
                    localAdapter.uploadSelectedToCloud();
                    return;
                }
                Set<MediaFile> selected = adapter.getSelectedFiles();
                if (selected.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Vui lòng chọn ít nhất 1 file để đẩy lên Cloud!", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!com.tri.djicontrol.firebase.FirebaseHelper.isConfigured(MainActivity.this)) {
                    Toast.makeText(MainActivity.this, "Chưa cấu hình Cloud (CloudConfig.java)!", Toast.LENGTH_LONG).show();
                    return;
                }

                List<MediaFile> toUpload = new ArrayList<>(selected);
                adapter.clearSelection();
                adapter.setSelectionMode(false);

                Toast.makeText(MainActivity.this, "Bắt đầu đẩy " + toUpload.size() + " file lên Cloud...", Toast.LENGTH_SHORT).show();
                uploadBatchToFirebaseRecursive(toUpload, 0, 0);
            });
        }

        if (btnDeleteSelected != null) {
            btnDeleteSelected.setOnClickListener(v -> {
                if (phoneTab[0]) {
                    localAdapter.deleteSelected();
                    return;
                }
                Set<MediaFile> selected = adapter.getSelectedFiles();
                if (selected.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Vui lòng chọn ít nhất 1 file để xóa!", Toast.LENGTH_SHORT).show();
                    return;
                }

                new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle("Xác nhận xóa")
                        .setMessage("Bạn có chắc chắn muốn xóa " + selected.size() + " file đã chọn khỏi máy bay?")
                        .setPositiveButton("XÓA", (dialogInterface, i) -> {
                            Aircraft currentAircraft = (Aircraft) DJISDKManager.getInstance().getProduct();
                            if (currentAircraft == null || currentAircraft.getCamera() == null || currentAircraft.getCamera().getMediaManager() == null) {
                                Toast.makeText(MainActivity.this, "MediaManager chưa sẵn sàng!", Toast.LENGTH_SHORT).show();
                                return;
                            }

                            Camera delCamera = currentAircraft.getCamera();
                            MediaManager mediaManager = delCamera.getMediaManager();
                            List<MediaFile> toDelete = new ArrayList<>(selected);

                            adapter.clearSelection();
                            adapter.setSelectionMode(false);

                            Toast.makeText(MainActivity.this, "Đang xóa " + toDelete.size() + " file khỏi máy bay...", Toast.LENGTH_SHORT).show();

                            deleteFilesPermanently(delCamera, mediaManager, toDelete, storageFiles, adapter);
                        })
                        .setNegativeButton("HỦY", null)
                        .show();
            });
        }

        // Đóng giao diện kho lưu trữ
        btnCloseStorage.setOnClickListener(v -> {

            if (DJISDKManager.getInstance().getProduct() != null) {

                Aircraft aircraft = (Aircraft) DJISDKManager
                        .getInstance()
                        .getProduct();

                Camera closeCamera = aircraft.getCamera();

                if (closeCamera != null) {

                    SettingsDefinitions.CameraMode mode;

                    if (isCurrentModePhoto) {
                        mode = SettingsDefinitions.CameraMode.SHOOT_PHOTO;
                    } else {
                        mode = SettingsDefinitions.CameraMode.RECORD_VIDEO;
                    }

                    boolean flat = false;
                    try { flat = closeCamera.isFlatCameraModeSupported(); } catch (Exception ignored) {}
                    if (flat) {
                        closeCamera.exitPlayback(null);
                    } else {
                        closeCamera.setMode(mode, null);
                    }
                }
            }

            // Huỷ các thumbnail còn trong hàng đợi để không chiếm kết nối drone
            dji.sdk.media.FetchMediaTaskScheduler scheduler = StorageAdapter.getScheduler();
            if (scheduler != null) {
                try { scheduler.removeAllTasks(); } catch (Exception ignored) {}
            }

            tvSpeedStats = null;
            dialog.dismiss();
        });

        // Đóng bằng nút Back / chạm ngoài cũng phải huỷ hàng đợi thumbnail,
        // nếu không lần mở sau drone vẫn bận và quét ra 0 file
        dialog.setOnDismissListener(d -> {
            localAdapter.shutdown();
            dji.sdk.media.FetchMediaTaskScheduler scheduler = StorageAdapter.getScheduler();
            if (scheduler != null) {
                try { scheduler.removeAllTasks(); } catch (Exception ignored) {}
            }
            tvSpeedStats = null;
        });

        dialog.show();

        // Kiểm tra kết nối Drone
        if (DJISDKManager.getInstance().getProduct() == null) {
            Toast.makeText(
                    MainActivity.this,
                    "Chưa kết nối Drone!",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        Aircraft aircraft = (Aircraft) DJISDKManager
                .getInstance()
                .getProduct();

        Camera camera = aircraft.getCamera();

        if (camera == null) {
            Toast.makeText(
                    MainActivity.this,
                    "Không tìm thấy Camera!",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        // 1. Chuyển sang chế độ MEDIA_DOWNLOAD
        Runnable startScan = () -> {
            MediaManager mediaManager = camera.getMediaManager();
            if (mediaManager == null) {
                retryGetMediaManager(camera, storageFiles, adapter, 1);
                return;
            }

            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Đang mở thẻ nhớ và quét toàn bộ file trên drone...", Toast.LENGTH_SHORT).show());

            // 3. Thực hiện quét
            loadMediaFilesForActiveStorage(camera, mediaManager, storageFiles, adapter, null);
        };

        switchCameraToMediaDownloadMode(camera, startScan);
    }

    // =========================================================
    // XÓA VĨNH VIỄN FILE TRÊN DRONE
    // deleteFiles() đôi khi báo thành công nhưng drone không xóa thật (đang bận tải thumbnail,
    // trộn file SD + bộ nhớ trong trong 1 lệnh...). Vì vậy: dừng thumbnail -> xóa theo từng
    // vùng nhớ -> quét lại thẻ để kiểm tra -> file còn sót thì xóa lại -> hiển thị đúng thực tế.
    // =========================================================
    private void deleteFilesPermanently(Camera camera, MediaManager mediaManager, List<MediaFile> toDelete,
                                        List<MediaFile> storageFiles, StorageAdapter adapter) {
        dji.sdk.media.FetchMediaTaskScheduler scheduler = mediaManager.getScheduler();
        if (scheduler != null) {
            try { scheduler.removeAllTasks(); } catch (Exception ignored) {}
        }

        Set<String> targetKeys = new HashSet<>();
        for (MediaFile f : toDelete) targetKeys.add(mediaKey(f));

        deleteLog.setLength(0);
        boolean flat = false, pageMode = false;
        try { flat = camera.isFlatCameraModeSupported(); } catch (Exception ignored) {}
        try { pageMode = mediaManager.isPlaybackPageSupported(); } catch (Exception ignored) {}
        delLog("Camera: " + camera.getDisplayName()
                + " | flatMode=" + flat + " | playbackPage=" + pageMode);
        delLog("Chọn xóa: " + toDelete.size() + " file");

        // Sau khi đổi chế độ camera, đối tượng MediaFile cũ có thể hết hiệu lực -> quét lại
        // và xóa bằng đối tượng mới khớp tên + thời gian tạo (không khớp được thì dùng đối tượng cũ).
        Runnable doDelete = () -> refreshAllStorage(mediaManager, () -> {
            List<MediaFile> freshTargets = new ArrayList<>();
            for (MediaFile f : collectMediaSnapshot(mediaManager)) {
                if (targetKeys.contains(mediaKey(f))) freshTargets.add(f);
            }
            delLog("Khớp sau khi quét lại: " + freshTargets.size() + "/" + targetKeys.size());
            List<MediaFile> list = freshTargets.isEmpty() ? toDelete : freshTargets;
            deleteGroupedByStorage(mediaManager, list, false, () ->
                    verifyAndRetryDelete(camera, mediaManager, targetKeys, storageFiles, adapter, 1));
        });

        if (flat) {
            // Drone đời mới (Mini 2, Air 2, Air 2S...) không dùng MEDIA_DOWNLOAD: phải vào Playback mới xóa được trên thẻ
            camera.enterPlayback(err -> {
                delLog("enterPlayback: " + (err != null ? err.getDescription() : "OK"));
                new Handler(Looper.getMainLooper()).postDelayed(doDelete, 800);
            });
        } else {
            switchCameraToMediaDownloadMode(camera, doDelete);
        }
    }

    // Nhật ký xóa hiện ngay trên màn hình (điện thoại cắm tay cầm nên không xem được Logcat)
    private final StringBuilder deleteLog = new StringBuilder();

    private void delLog(String msg) {
        android.util.Log.d("DJI_STORAGE", msg);
        synchronized (deleteLog) {
            deleteLog.append("• ").append(msg).append("\n");
        }
    }

    private void showDeleteLogDialog(String title) {
        String text;
        synchronized (deleteLog) { text = deleteLog.toString(); }
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextIsSelectable(true);
        tv.setTextSize(12);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad / 2, pad, 0);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(tv);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton("ĐÓNG", null)
                .show();
    }

    private static String safeListState(MediaManager mm) {
        try {
            Object s = mm.getSDCardFileListState();
            return s != null ? s.toString() : "?";
        } catch (Exception e) {
            return "?";
        }
    }

    private static String mediaKey(MediaFile f) {
        return f.getFileName() + "_" + f.getTimeCreated();
    }

    /** Gửi lệnh xóa riêng cho từng vùng nhớ (SD / bộ nhớ trong), lần lượt, rồi gọi onDone. */
    private void deleteGroupedByStorage(MediaManager mediaManager, List<MediaFile> files, boolean direct, Runnable onDone) {
        java.util.Map<String, List<MediaFile>> groups = new java.util.LinkedHashMap<>();
        for (MediaFile f : files) {
            SettingsDefinitions.StorageLocation loc = null;
            try { loc = f.getStorageLocation(); } catch (Exception ignored) {}
            String k = loc == null ? "UNKNOWN" : loc.name();
            List<MediaFile> g = groups.get(k);
            if (g == null) { g = new ArrayList<>(); groups.put(k, g); }
            g.add(f);
        }
        deleteGroupAt(mediaManager, new ArrayList<>(groups.values()), 0, direct, onDone);
    }

    private void deleteGroupAt(MediaManager mediaManager, List<List<MediaFile>> groups, int index, boolean direct, Runnable onDone) {
        if (index >= groups.size()) {
            // Cho drone ghi xong thay đổi lên thẻ trước khi quét lại
            new Handler(Looper.getMainLooper()).postDelayed(onDone, 1000);
            return;
        }
        Runnable next = () -> new Handler(Looper.getMainLooper()).postDelayed(
                () -> deleteGroupAt(mediaManager, groups, index + 1, direct, onDone), 500);
        CommonCallbacks.CompletionCallbackWithTwoParam<List<MediaFile>, DJICameraError> cb =
                new CommonCallbacks.CompletionCallbackWithTwoParam<List<MediaFile>, DJICameraError>() {
                @Override
                public void onSuccess(List<MediaFile> failedFiles, DJICameraError error) {
                    delLog((direct ? "deleteDirectly" : "deleteFiles") + " nhóm " + index + ": lỗi=" + (error != null ? error.getDescription() : "không")
                            + ", thất bại=" + (failedFiles != null ? failedFiles.size() : 0));
                    next.run();
                }

                @Override
                public void onFailure(DJIError djiError) {
                    delLog((direct ? "deleteDirectly" : "deleteFiles") + " nhóm " + index + " lỗi: " + (djiError != null ? djiError.getDescription() : "?"));
                    next.run();
                }
            };
        try {
            if (direct) mediaManager.deleteDirectly(groups.get(index), cb);
            else mediaManager.deleteFiles(groups.get(index), cb);
        } catch (Exception e) {
            delLog("Lệnh xóa ném lỗi: " + e);
            next.run();
        }
    }

    /** Quét lại thẻ nhớ drone, xem file nào còn tồn tại thật; còn thì xóa lại (tối đa 3 lần). */
    private void verifyAndRetryDelete(Camera camera, MediaManager mediaManager, Set<String> targetKeys,
                                      List<MediaFile> storageFiles, StorageAdapter adapter, int attempt) {
        refreshAllStorage(mediaManager, () -> {
            List<MediaFile> remaining = new ArrayList<>();
            for (MediaFile f : collectMediaSnapshot(mediaManager)) {
                if (targetKeys.contains(mediaKey(f))) remaining.add(f);
            }
            int total = targetKeys.size();
            delLog("Kiểm tra lần " + attempt + ": còn " + remaining.size() + "/" + total + " file trên drone"
                    + " (SD: " + safeListState(mediaManager) + ")");

            if (!remaining.isEmpty() && attempt < 3) {
                delLog("Còn " + remaining.size() + " file chưa xóa, thử lại lần " + (attempt + 1));
                // Dùng đối tượng MediaFile mới từ snapshot vừa quét (đối tượng cũ có thể đã hết hiệu lực).
                // Lần thử lại dùng deleteDirectly: xóa thẳng trên thẻ, không phụ thuộc danh sách tạm của SDK.
                deleteGroupedByStorage(mediaManager, remaining, true, () ->
                        verifyAndRetryDelete(camera, mediaManager, targetKeys, storageFiles, adapter, attempt + 1));
                return;
            }

            int deleted = total - remaining.size();
            // Hiển thị đúng danh sách thực tế trên drone
            List<MediaFile> fresh = collectMediaSnapshot(mediaManager);
            Collections.sort(fresh, (f1, f2) -> Long.compare(f2.getTimeCreated(), f1.getTimeCreated()));
            runOnUiThread(() -> {
                storageFiles.clear();
                storageFiles.addAll(fresh);
                adapter.notifyDataSetChanged();
                if (remaining.isEmpty()) {
                    showDeleteLogDialog("Đã xóa " + deleted + "/" + total + " file khỏi drone");
                } else {
                    showDeleteLogDialog("Drone không cho xóa " + remaining.size() + "/" + total + " file");
                }
            });
        });
    }

    /** Làm mới danh sách file của cả thẻ SD và bộ nhớ trong, xong thì gọi onDone trên main thread. */
    private void refreshAllStorage(MediaManager mediaManager, Runnable onDone) {
        // Bắt buộc đọc lại từ drone (force = true). Refresh thường có thể trả danh sách cache của SDK,
        // nên trước đây tưởng đã xóa nhưng file vẫn còn trên thẻ -> kết nối lại thì hiện lại.
        try {
            mediaManager.refreshFileList(true, err -> {
                delLog("Force refresh: " + (err != null ? err.getDescription() : "OK"));
                refreshByStorageLocation(mediaManager, onDone);
            });
        } catch (Exception e) {
            refreshByStorageLocation(mediaManager, onDone);
        }
    }

    private void refreshByStorageLocation(MediaManager mediaManager, Runnable onDone) {
        Handler main = new Handler(Looper.getMainLooper());
        try {
            mediaManager.refreshFileListOfStorageLocation(SettingsDefinitions.StorageLocation.SDCARD, sdErr -> {
                try {
                    mediaManager.refreshFileListOfStorageLocation(SettingsDefinitions.StorageLocation.INTERNAL_STORAGE,
                            intErr -> main.post(onDone));
                } catch (Exception e) {
                    main.post(onDone);
                }
            });
        } catch (Exception e) {
            try {
                mediaManager.refreshFileList(err -> main.post(onDone));
            } catch (Exception e2) {
                main.post(onDone);
            }
        }
    }

    private void retryGetMediaManager(Camera camera, List<MediaFile> storageFiles, StorageAdapter adapter, int attempt) {
        if (attempt > 3) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "MediaManager chưa sẵn sàng!", Toast.LENGTH_SHORT).show());
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (camera != null && camera.getMediaManager() != null) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Đang mở thẻ nhớ và quét toàn bộ file trên drone...", Toast.LENGTH_SHORT).show());
                loadMediaFilesForActiveStorage(camera, camera.getMediaManager(), storageFiles, adapter, null);
            } else {
                retryGetMediaManager(camera, storageFiles, adapter, attempt + 1);
            }
        }, 800);
    }

    private void switchCameraToMediaDownloadMode(Camera camera, Runnable onReady) {
        if (camera == null) {
            onReady.run();
            return;
        }

        camera.getMode(new CommonCallbacks.CompletionCallbackWith<SettingsDefinitions.CameraMode>() {
            @Override
            public void onSuccess(SettingsDefinitions.CameraMode currentMode) {
                if (currentMode == SettingsDefinitions.CameraMode.MEDIA_DOWNLOAD) {
                    new Handler(Looper.getMainLooper()).postDelayed(onReady, 300);
                } else {
                    boolean isSupported = true;
                    try {
                        isSupported = camera.isMediaDownloadModeSupported();
                    } catch (Exception ignored) {}

                    if (isSupported) {
                        camera.setMode(SettingsDefinitions.CameraMode.MEDIA_DOWNLOAD, djiError -> {
                            if (djiError == null) {
                                new Handler(Looper.getMainLooper()).postDelayed(onReady, 1000);
                            } else {
                                runOnUiThread(() -> Toast.makeText(
                                        MainActivity.this,
                                        "Chuyển chế độ Media: " + djiError.getDescription(),
                                        Toast.LENGTH_SHORT
                                ).show());
                                new Handler(Looper.getMainLooper()).postDelayed(onReady, 1000);
                            }
                        });
                    } else {
                        new Handler(Looper.getMainLooper()).postDelayed(onReady, 500);
                    }
                }
            }

            @Override
            public void onFailure(DJIError djiError) {
                camera.setMode(SettingsDefinitions.CameraMode.MEDIA_DOWNLOAD, error -> {
                    new Handler(Looper.getMainLooper()).postDelayed(onReady, 1000);
                });
            }
        });
    }

    // Quét toàn bộ file trên thẻ nhớ SD và bộ nhớ trong máy bay DJI
    private void loadMediaFilesForActiveStorage(
            Camera camera,
            MediaManager mediaManager,
            List<MediaFile> storageFiles,
            StorageAdapter adapter,
            Runnable onComplete
    ) {
        if (camera == null || mediaManager == null) {
            Toast.makeText(MainActivity.this, "Camera hoặc MediaManager không khả dụng!", Toast.LENGTH_SHORT).show();
            return;
        }

        final boolean[] isFinished = {false};
        final Handler handler = new Handler(Looper.getMainLooper());
        // Ghi lại lỗi drone trả về để hiện cho người dùng thay vì chỉ báo "0 file"
        final StringBuilder scanErrors = new StringBuilder();

        // Huỷ hàng đợi thumbnail cũ: drone đang bận tải thumbnail sẽ từ chối refresh danh sách file
        dji.sdk.media.FetchMediaTaskScheduler oldScheduler = mediaManager.getScheduler();
        if (oldScheduler != null) {
            try { oldScheduler.removeAllTasks(); } catch (Exception ignored) {}
        }

        // Kết quả quét thật sự (khi refresh xong) - luôn cập nhật lưới, kể cả khi đã hết timeout
        final Runnable finishAndPopulate = () -> {
            if (!isFinished[0]) {
                isFinished[0] = true;
                handler.removeCallbacksAndMessages(null);
                List<MediaFile> result = collectMediaSnapshot(mediaManager);
                finalizeMediaFiles(result, storageFiles, adapter, onComplete);
                if (result.isEmpty() && scanErrors.length() > 0) {
                    Toast.makeText(MainActivity.this, "Lỗi quét từ drone:\n" + scanErrors, Toast.LENGTH_LONG).show();
                }
                android.util.Log.d("DJI_STORAGE", "Quét xong: " + result.size() + " file. Lỗi: " + scanErrors);
            }
        };

        // Thẻ nhớ nhiều file có thể quét lâu: sau 8 giây hiện tạm những gì đã có,
        // nhưng KHÔNG chốt kết quả - callback refresh về sau vẫn cập nhật lại danh sách.
        handler.postDelayed(() -> {
            if (isFinished[0]) return;
            List<MediaFile> partial = collectMediaSnapshot(mediaManager);
            if (!partial.isEmpty()) {
                Collections.sort(partial, (f1, f2) -> Long.compare(f2.getTimeCreated(), f1.getTimeCreated()));
                storageFiles.clear();
                storageFiles.addAll(partial);
                adapter.notifyDataSetChanged();
            }
            Toast.makeText(MainActivity.this, "Drone vẫn đang quét thẻ nhớ, vui lòng đợi...", Toast.LENGTH_SHORT).show();
        }, 8000);
        // Chốt cứng sau 30 giây nếu drone không trả callback
        handler.postDelayed(finishAndPopulate, 30000);

        // Dự phòng: refresh kiểu cũ (drone đời cũ không hỗ trợ refreshFileListOfStorageLocation)
        final Runnable legacyRefresh = () -> {
            try {
                mediaManager.refreshFileList(genErr -> {
                    if (genErr != null) scanErrors.append("• Chung: ").append(genErr.getDescription()).append("\n");
                    handler.postDelayed(finishAndPopulate, 1500);
                });
            } catch (Exception e) {
                scanErrors.append("• Chung: ").append(e.getMessage()).append("\n");
                handler.post(finishAndPopulate);
            }
        };

        try {
            // Bước 1: Quét thẻ nhớ SD trước
            mediaManager.refreshFileListOfStorageLocation(SettingsDefinitions.StorageLocation.SDCARD, sdErr -> {
                if (sdErr != null) scanErrors.append("• Thẻ SD: ").append(sdErr.getDescription()).append("\n");
                // Bước 2: Quét tiếp bộ nhớ trong (INTERNAL_STORAGE) bất kể SDCARD lỗi hay không
                try {
                    mediaManager.refreshFileListOfStorageLocation(SettingsDefinitions.StorageLocation.INTERNAL_STORAGE, intErr -> {
                        if (intErr != null) scanErrors.append("• Bộ nhớ trong: ").append(intErr.getDescription()).append("\n");
                        if (getTotalMediaCount(mediaManager) > 0) {
                            handler.post(finishAndPopulate);
                        } else {
                            // Không có file nào (có thể do lỗi "bận") -> thử thêm 1 lần kiểu cũ
                            legacyRefresh.run();
                        }
                    });
                } catch (Exception e) {
                    if (getTotalMediaCount(mediaManager) > 0) handler.post(finishAndPopulate);
                    else legacyRefresh.run();
                }
            });
        } catch (Exception e) {
            // Firmware không hỗ trợ quét theo vị trí lưu trữ
            legacyRefresh.run();
        }
    }

    // Gộp danh sách file của thẻ SD và bộ nhớ trong (bỏ trùng theo tên + thời gian tạo)
    private List<MediaFile> collectMediaSnapshot(MediaManager mediaManager) {
        Set<String> keys = new HashSet<>();
        List<MediaFile> allFiles = new ArrayList<>();
        List<List<MediaFile>> sources = new ArrayList<>();
        try { sources.add(mediaManager.getSDCardFileListSnapshot()); } catch (Exception ignored) {}
        try { sources.add(mediaManager.getInternalStorageFileListSnapshot()); } catch (Exception ignored) {}
        for (List<MediaFile> list : sources) {
            if (list == null) continue;
            for (MediaFile f : list) {
                if (f != null && f.getFileName() != null && !f.getFileName().isEmpty()
                        && keys.add(f.getFileName() + "_" + f.getTimeCreated())) {
                    allFiles.add(f);
                }
            }
        }
        return allFiles;
    }

    private int getTotalMediaCount(MediaManager mediaManager) {
        if (mediaManager == null) return 0;
        Set<String> fileNames = new HashSet<>();

        List<MediaFile> sdList = mediaManager.getSDCardFileListSnapshot();
        if (sdList != null) {
            for (MediaFile f : sdList) {
                if (f != null && f.getFileName() != null && !f.getFileName().isEmpty()) {
                    fileNames.add(f.getFileName());
                }
            }
        }

        List<MediaFile> intList = mediaManager.getInternalStorageFileListSnapshot();
        if (intList != null) {
            for (MediaFile f : intList) {
                if (f != null && f.getFileName() != null && !f.getFileName().isEmpty()) {
                    fileNames.add(f.getFileName());
                }
            }
        }

        return fileNames.size();
    }

    private void finalizeMediaFiles(
            List<MediaFile> allFiles,
            List<MediaFile> storageFiles,
            StorageAdapter adapter,
            Runnable onComplete
    ) {
        Collections.sort(allFiles, (f1, f2) -> Long.compare(f2.getTimeCreated(), f1.getTimeCreated()));

        runOnUiThread(() -> {
            storageFiles.clear();
            if (!allFiles.isEmpty()) {
                storageFiles.addAll(allFiles);
                adapter.notifyDataSetChanged();
                Toast.makeText(
                        MainActivity.this,
                        "ĐÃ ĐỒNG BỘ XONG " + storageFiles.size() + " FILE!",
                        Toast.LENGTH_LONG
                ).show();
            } else {
                adapter.notifyDataSetChanged();
                Toast.makeText(
                        MainActivity.this,
                        "Bộ nhớ máy bay đang trống (0 file)!",
                        Toast.LENGTH_LONG
                ).show();
            }

            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

    /** Dự phòng khi camera drone không cho chụp trong lúc quay: lưu khung hình live view vào điện thoại. */
    private void captureFrameFromLiveView(String hwError) {
        if (videoSurface == null || !videoSurface.isAvailable()) {
            Toast.makeText(this, "Không chụp được: " + hwError, Toast.LENGTH_SHORT).show();
            return;
        }
        Bitmap currentFrame = videoSurface.getBitmap();
        if (currentFrame == null) {
            Toast.makeText(this, "Không lấy được khung hình video!", Toast.LENGTH_SHORT).show();
            return;
        }
        saveLiveFramePhoto(currentFrame);
    }

    private void saveLiveFramePhoto(Bitmap bitmap) {
        if (bitmap == null) return;
        new Thread(() -> {
            try {
                String fileName = "PHOTO_IN_VIDEO_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
                boolean savedSuccessfully = false;
                String savedFilePath = null;

                ContentResolver resolver = getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/DJI_Photos");

                Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                Uri imageUri = resolver.insert(collection, values);
                if (imageUri != null) {
                    try (OutputStream out = resolver.openOutputStream(imageUri)) {
                        if (out != null) {
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);
                            out.flush();
                            savedSuccessfully = true;
                        }
                    } catch (Exception e) {
                        savedSuccessfully = false;
                    }
                }

                // Nếu MediaStore không thành công, dùng fallback file hệ thống
                if (!savedSuccessfully) {
                    File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "DJI_Photos");
                    if (!dir.exists()) {
                        boolean created = dir.mkdirs();
                        if (!created && !dir.exists()) {
                            dir = new File(getExternalFilesDir(null), "DJI_Photos");
                            if (!dir.exists()) dir.mkdirs();
                        }
                    }
                    File file = new File(dir, fileName);
                    try (FileOutputStream out = new FileOutputStream(file)) {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);
                        out.flush();
                        savedSuccessfully = true;
                        savedFilePath = file.getAbsolutePath();
                    } catch (Exception e) {
                        savedSuccessfully = false;
                    }
                }

                if (savedSuccessfully) {
                    if (savedFilePath != null) {
                        MediaScannerConnection.scanFile(MainActivity.this, new String[]{savedFilePath}, new String[]{"image/jpeg"}, null);
                    }
                    photoCount++;
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "Đã lưu ảnh vào điện thoại. Xem ở Lưu trữ > tab ẢNH TRÊN ĐIỆN THOẠI", Toast.LENGTH_SHORT).show();
                    });
                } else {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Không thể lưu ảnh vào bộ nhớ!", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Lỗi lưu ảnh: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

}