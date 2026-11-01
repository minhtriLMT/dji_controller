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
import android.view.TextureView;
import android.view.MotionEvent;
import android.widget.Button;
import android.graphics.Color;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.view.WindowManager;
import android.app.Dialog;
import android.widget.FrameLayout;
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
    private TextView tvStatus, tvGpsAndSdStatus, tvTelemetry, tvPhotoCount, tvSpeedStats;
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
                            .fromUri("asset://osm_style.json"),

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
                    }
            );

            miniMapLibreMap.addOnMapClickListener(point -> {

                Intent intent =
                        new Intent(
                                MainActivity.this,
                                MapActivity.class
                        );

                startActivityForResult(
                        intent,
                        REQUEST_FIELD_MAP
                );

                return true;
            });
        });

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
        btnLockRoute.setOnClickListener(v -> openWPDialog());

        cameraControlPanel = findViewById(R.id.cameraControlPanel);
        cameraModePicker = findViewById(R.id.cameraModePicker);
        tvCameraVideo = findViewById(R.id.tvCameraVideo);
        tvCameraPhoto = findViewById(R.id.tvCameraPhoto);
        setupCameraModeSwipe();

        if (videoSurface != null) {
            videoSurface.setSurfaceTextureListener(this);
        }

        btnViewPhotos.setOnClickListener(v -> {
            if (isRecordingVideo) {
                if (!isVideoPaused) {
                    isVideoPaused = true;
                    videoPauseStartTime = System.currentTimeMillis();
                    btnViewPhotos.setText("▶");
                    btnViewPhotos.setContentDescription("Tiếp tục quay");
                    Toast.makeText(MainActivity.this, "Đã tạm dừng quay", Toast.LENGTH_SHORT).show();
                } else {
                    isVideoPaused = false;
                    videoPausedTime += System.currentTimeMillis() - videoPauseStartTime;
                    btnViewPhotos.setText("Ⅱ");
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
                // Nếu đang quay video, chụp khung hình trực tiếp từ video stream
                if (videoSurface != null && videoSurface.isAvailable()) {
                    Bitmap currentFrame = videoSurface.getBitmap();
                    if (currentFrame != null) {
                        saveLiveFramePhoto(currentFrame);
                    } else {
                        Toast.makeText(MainActivity.this, "Không lấy được khung hình video!", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(MainActivity.this, "Giao diện video chưa sẵn sàng!", Toast.LENGTH_SHORT).show();
                }
            } else {
                // Nếu chưa quay video nhưng đang ở chế độ Video UI
                // Bước 1: Thử chụp trực tiếp bằng phần cứng trước
                camera.startShootPhoto(djiError -> {
                    if (djiError == null) {
                        photoCount++;
                        runOnUiThread(() -> {
                            tvPhotoCount.setText("Ảnh đã chụp: " + photoCount + " tấm");
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
                                                tvPhotoCount.setText("Ảnh đã chụp: " + photoCount + " tấm");
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
                tvStatus.setText("Trạng thái: " + status);
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

                                        String speedInfo = String.format("%s | %s", downloadSpeedText, uploadSpeedText);
                                        if (tvSpeedStats != null) {
                                            tvSpeedStats.setText(speedInfo);
                                            tvTelemetry.setText(String.format("Cao: %.1fm | Tốc độ: %.1fm/s\nCách đích: %.1fm", currentAlt, speed, distance));
                                        } else {
                                            tvTelemetry.setText(String.format("Cao: %.1fm | Tốc độ: %.1fm/s\nCách đích: %.1fm | %s", currentAlt, speed, distance, speedInfo));
                                        }
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
                }
            });
        });

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
            return;
        }

        MediaFile targetMedia = photoList.get(index);
        File destDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "DJI_Photos");
        if (!destDir.exists()) destDir.mkdirs();

        runOnUiThread(() -> Toast.makeText(MainActivity.this, "Đang tải ảnh (" + (index + 1) + "/" + photoList.size() + "): " + targetMedia.getFileName(), Toast.LENGTH_SHORT).show());

        targetMedia.fetchFileData(destDir, targetMedia.getFileName(), new DownloadListener<String>() {
            @Override
            public void onStart() {}
            @Override
            public void onRateUpdate(long total, long current, long persize) {}
            @Override
            public void onProgress(long total, long current) {}

            @Override
            public void onSuccess(String filePath) {
                MediaScannerConnection.scanFile(MainActivity.this, new String[]{filePath}, new String[]{"image/jpeg", "image/tiff"}, null);
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

        mediaFile.fetchFileData(finalDestDir, fileName, new DownloadListener<String>() {
            @Override public void onStart() {}
            @Override public void onRateUpdate(long total, long current, long persize) {}
            @Override public void onProgress(long total, long current) {}

            @Override
            public void onSuccess(String filePath) {
                if (filePath != null) {
                    MediaScannerConnection.scanFile(
                            MainActivity.this,
                            new String[]{filePath},
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
        });
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

        selected.setTextColor(Color.YELLOW);
        selected.setTextSize(20);
        selected.setTypeface(null, Typeface.BOLD);
        selected.setAlpha(1.0f);

        unselected.setTextColor(Color.WHITE);
        unselected.setTextSize(16);
        unselected.setTypeface(null, Typeface.NORMAL);
        unselected.setAlpha(0.6f);

        if (animate) {
            if (photo) {
                tvCameraPhoto.animate().translationY(0).scaleX(1.0f).scaleY(1.0f).setDuration(180).start();
                tvCameraVideo.animate().translationY(-42).scaleX(0.9f).scaleY(0.9f).setDuration(180).start();
            } else {
                tvCameraVideo.animate().translationY(0).scaleX(1.0f).scaleY(1.0f).setDuration(180).start();
                tvCameraPhoto.animate().translationY(42).scaleX(0.9f).scaleY(0.9f).setDuration(180).start();
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
                        tvPhotoCount.setText("Ảnh đã chụp: " + photoCount + " tấm");
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
                        btnViewPhotos.setText("Ⅱ");
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
                        btnViewPhotos.setText("□");
                        btnViewPhotos.setContentDescription("Mở kho lưu trữ");
                        Toast.makeText(MainActivity.this, "Đã lưu Video vào thẻ nhớ!", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(MainActivity.this, "Lỗi dừng quay: " + djiError.getDescription(), Toast.LENGTH_LONG).show();
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
                    long elapsed = System.currentTimeMillis() - videoStartTime - videoPausedTime;
                    long totalSeconds = elapsed / 1000;
                    long minutes = totalSeconds / 60;
                    long seconds = totalSeconds % 60;
                    String time = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
                    tvVideoTimer.setText(time);
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

    private List<LocationCoordinate2D> generateGrid(List<LatLng> polygon, double spacingMeters) {
        List<LocationCoordinate2D> waypoints = new ArrayList<>();
        if (polygon == null || polygon.size() < 3) return waypoints;

        double minLat = polygon.get(0).getLatitude();
        double maxLat = minLat;
        for (LatLng p : polygon) {
            double currentLat = p.getLatitude();
            if (currentLat < minLat) minLat = currentLat;
            if (currentLat > maxLat) maxLat = currentLat;
        }

        double stepLat = spacingMeters / 111320.0;
        boolean leftToRight = true;
        int polySize = polygon.size();

        for (double lat = minLat; lat <= maxLat; lat += stepLat) {
            List<Double> intersectLngs = new ArrayList<>();

            for (int i = 0; i < polySize; i++) {
                LatLng p1 = polygon.get(i);
                LatLng p2 = polygon.get((i + 1) % polySize);

                double lat1 = p1.getLatitude();
                double lat2 = p2.getLatitude();

                if ((lat1 <= lat && lat2 > lat) || (lat2 <= lat && lat1 > lat)) {
                    double fraction = (lat - lat1) / (lat2 - lat1);
                    double lng = p1.getLongitude() + fraction * (p2.getLongitude() - p1.getLongitude());
                    intersectLngs.add(lng);
                }
            }

            if (intersectLngs.size() >= 2) {
                Collections.sort(intersectLngs);
                double firstLng = intersectLngs.get(0);
                double lastLng = intersectLngs.get(intersectLngs.size() - 1);

                if (leftToRight) {
                    waypoints.add(new LocationCoordinate2D(lat, firstLng));
                    waypoints.add(new LocationCoordinate2D(lat, lastLng));
                } else {
                    waypoints.add(new LocationCoordinate2D(lat, lastLng));
                    waypoints.add(new LocationCoordinate2D(lat, firstLng));
                }
                leftToRight = !leftToRight;
            }
        }
        return waypoints;
    }

    private void openWPDialog() {
        Dialog dialog = new Dialog(MainActivity.this);
        dialog.setContentView(R.layout.dialog_wp);

        Button btnCloseWP = dialog.findViewById(R.id.btnCloseWP);
        Button btnEditWPField = dialog.findViewById(R.id.btnEditWPField);
        TextView tvWPFieldStatus = dialog.findViewById(R.id.tvWPFieldStatus);
        TextView tvWPFieldArea = dialog.findViewById(R.id.tvWPFieldArea);
        Button btnStartMapping = dialog.findViewById(R.id.btnStartMapping);

        if (fieldPoints.size() == 4) {
            tvWPFieldStatus.setText("Đã xác định khu vực");
            tvWPFieldArea.setText(String.format(Locale.US, "Diện tích: %.2f ha", fieldAreaHa));
            if(btnStartMapping != null) btnStartMapping.setVisibility(View.VISIBLE);
        } else {
            tvWPFieldStatus.setText("Chưa xác định khu vực");
            tvWPFieldArea.setText("Diện tích: 0.00 ha");
            if(btnStartMapping != null) btnStartMapping.setVisibility(View.GONE);
        }

        if(btnStartMapping != null) {
            btnStartMapping.setOnClickListener(view -> {
                if (missionManager != null && fieldPoints.size() == 4) {
                    float targetAltitude = 30.0f;
                    double pathSpacing = 20.0;

                    List<LocationCoordinate2D> gridWaypoints = generateGrid(fieldPoints, pathSpacing);

                    if (!gridWaypoints.isEmpty()) {
                        if (miniMapLibreMap != null && flightPathSource != null) {
                            List<Point> gridMapPoints = new ArrayList<>();
                            for (LocationCoordinate2D wp : gridWaypoints) {
                                gridMapPoints.add(Point.fromLngLat(wp.getLongitude(), wp.getLatitude()));
                            }
                            flightPathSource.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(gridMapPoints)));
                        }

                        Toast.makeText(MainActivity.this, "Đã tạo " + gridWaypoints.size() + " điểm quét!", Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    }
                } else {
                    Toast.makeText(MainActivity.this, "Vui lòng khoanh vùng và kết nối Drone!", Toast.LENGTH_SHORT).show();
                }
            });
        }

        btnCloseWP.setOnClickListener(view -> dialog.dismiss());

        btnEditWPField.setOnClickListener(view -> {
            Intent intent = new Intent(MainActivity.this, MapActivity.class);
            intent.putExtra("EDIT_FIELD", true);
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

        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.80),
                    (int) (getResources().getDisplayMetrics().heightPixels * 0.85)
            );
        }
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
        if (videoSurface != null && videoSurface.isAvailable()) {
            if (codecManager == null) {
                codecManager = new DJICodecManager(this, videoSurface.getSurfaceTexture(), videoSurface.getWidth(), videoSurface.getHeight());
            }
        }
    }

    @Override
    protected void onDestroy() {
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
    };



    private void showStorageDialog() {
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_storage);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(
                    android.R.color.transparent
            );

            dialog.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.75),
                    (int) (getResources().getDisplayMetrics().heightPixels * 0.85)
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

        tvSpeedStats = dialog.findViewById(R.id.tvSpeedStats);

        List<MediaFile> storageFiles = new ArrayList<>();

        StorageAdapter adapter = new StorageAdapter(
                MainActivity.this,
                storageFiles
        );

        gridStorage.setAdapter(adapter);

        adapter.setOnSelectionListener(new StorageAdapter.OnSelectionListener() {
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
            }

            @Override
            public void onSelectionModeChanged(boolean isSelectionMode) {
                if (layoutHeaderNormal != null && layoutHeaderSelection != null) {
                    layoutHeaderNormal.setVisibility(isSelectionMode ? View.GONE : View.VISIBLE);
                    layoutHeaderSelection.setVisibility(isSelectionMode ? View.VISIBLE : View.GONE);
                }
            }
        });

        if (btnCancelSelection != null) {
            btnCancelSelection.setOnClickListener(v -> adapter.setSelectionMode(false));
        }

        if (btnDownloadSelected != null) {
            btnDownloadSelected.setOnClickListener(v -> {
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

        if (btnDeleteSelected != null) {
            btnDeleteSelected.setOnClickListener(v -> {
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

                            MediaManager mediaManager = currentAircraft.getCamera().getMediaManager();
                            List<MediaFile> toDelete = new ArrayList<>(selected);

                            Toast.makeText(MainActivity.this, "Đang xóa " + toDelete.size() + " file khỏi máy bay...", Toast.LENGTH_SHORT).show();

                            mediaManager.deleteFiles(toDelete, new CommonCallbacks.CompletionCallbackWithTwoParam<List<MediaFile>, DJICameraError>() {
                                @Override
                                public void onSuccess(List<MediaFile> failedFiles, DJICameraError error) {
                                    runOnUiThread(() -> {
                                        int failedCount = (failedFiles != null) ? failedFiles.size() : 0;
                                        int successCount = toDelete.size() - failedCount;

                                        for (MediaFile f : toDelete) {
                                            if (failedFiles == null || !failedFiles.contains(f)) {
                                                storageFiles.remove(f);
                                            }
                                        }

                                        adapter.clearSelection();
                                        adapter.setSelectionMode(false);
                                        adapter.notifyDataSetChanged();

                                        if (error == null && failedCount == 0) {
                                            Toast.makeText(MainActivity.this, "Đã xóa thành công " + successCount + " file!", Toast.LENGTH_LONG).show();
                                        } else {
                                            Toast.makeText(MainActivity.this, "Đã xóa " + successCount + " file. Thất bại: " + failedCount + " file", Toast.LENGTH_LONG).show();
                                        }
                                    });
                                }

                                @Override
                                public void onFailure(DJIError djiError) {
                                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Lỗi xóa file: " + (djiError != null ? djiError.getDescription() : "Thất bại"), Toast.LENGTH_LONG).show());
                                }
                            });
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

                    closeCamera.setMode(mode, null);
                }
            }

            tvSpeedStats = null;
            dialog.dismiss();
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

        final Runnable finishAndPopulate = () -> {
            if (!isFinished[0]) {
                isFinished[0] = true;

                Set<String> fileNames = new HashSet<>();
                List<MediaFile> allFiles = new ArrayList<>();

                // 1. Quét Thẻ SD (SDCARD)
                List<MediaFile> sdList = mediaManager.getSDCardFileListSnapshot();
                if (sdList != null) {
                    for (MediaFile f : sdList) {
                        if (f != null && f.getFileName() != null && !f.getFileName().isEmpty() && fileNames.add(f.getFileName())) {
                            allFiles.add(f);
                        }
                    }
                }

                // 2. Quét Bộ nhớ trong (INTERNAL_STORAGE)
                List<MediaFile> intList = mediaManager.getInternalStorageFileListSnapshot();
                if (intList != null) {
                    for (MediaFile f : intList) {
                        if (f != null && f.getFileName() != null && !f.getFileName().isEmpty() && fileNames.add(f.getFileName())) {
                            allFiles.add(f);
                        }
                    }
                }

                finalizeMediaFiles(allFiles, storageFiles, adapter, onComplete);
            }
        };

        // Safety timeout tối đa 8 giây
        handler.postDelayed(finishAndPopulate, 8000);

        // Bước 1: Quét thẻ nhớ SD trước
        mediaManager.refreshFileListOfStorageLocation(SettingsDefinitions.StorageLocation.SDCARD, sdErr -> {
            // Bước 2: Quét tiếp bộ nhớ trong (INTERNAL_STORAGE) bất kể SDCARD lỗi hay không
            mediaManager.refreshFileListOfStorageLocation(SettingsDefinitions.StorageLocation.INTERNAL_STORAGE, intErr -> {
                // Nếu ít nhất 1 vị trí quét thành công hoặc đã lấy được dữ liệu snapshot
                if (sdErr == null || intErr == null || getTotalMediaCount(mediaManager) > 0) {
                    handler.post(finishAndPopulate);
                } else {
                    // Dự phòng cho drone đời cũ không hỗ trợ refreshFileListOfStorageLocation
                    mediaManager.refreshFileList(genErr -> {
                        handler.postDelayed(finishAndPopulate, 1500);
                    });
                }
            });
        });
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
                        tvPhotoCount.setText("Ảnh đã chụp: " + photoCount + " tấm");
                        Toast.makeText(MainActivity.this, "Đã lưu ảnh chụp từ video vào Bộ sưu tập!", Toast.LENGTH_SHORT).show();
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