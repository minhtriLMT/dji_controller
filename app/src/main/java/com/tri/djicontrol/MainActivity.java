package com.tri.djicontrol;

import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.view.View;
import android.view.TextureView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
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
import org.maplibre.android.annotations.Marker;
import org.maplibre.android.annotations.MarkerOptions;
import com.tri.djicontrol.connection.DJIConnectionManager;
import com.tri.djicontrol.flight.FlightMissionManager;

import java.io.File;
import java.util.List;

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

public class MainActivity extends AppCompatActivity implements TextureView.SurfaceTextureListener {
    private TextView tvStatus, tvGpsAndSdStatus, tvTelemetry, tvPhotoCount;
    private EditText edtAltitude, edtLat, edtLng;
    private Button btnStart, btnCancel, btnToggleMenu, btnCaptureManual, btnFormatSd, btnToggleCameraMode, btnViewPhotos, btnCameraMenuToggle;
    private LinearLayout layoutMenuContent, cameraControlPanel;
    private SeekBar seekBarGimbal;
    private RadioButton rbPhoto;

    private FlightMissionManager missionManager;
    private TextureView videoSurface;
    private DJICodecManager codecManager;

    private String sdStatusStr = "Thẻ: Sẵn sàng";
    private int batteryPercent = 0;
    private int rcSignalPercent = 0;
    private int photoCount = 0;
    private boolean isHomePointSet = false;
    private boolean isMenuVisible = true;
    private boolean isCurrentModePhoto = true;
    private boolean isRecordingVideo = false;
    private View miniMap;
    private MapView miniMapView;
    private MapLibreMap miniMapLibreMap;
    private Marker droneMarker;
    private LatLng lastDroneLocation;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Khởi tạo MapLibre
        MapLibre.getInstance(this);

        setContentView(R.layout.activity_main);

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

                        // Marker vị trí drone
                        droneMarker = miniMapLibreMap.addMarker(
                                new MarkerOptions()
                                        .position(droneLocation)
                                        .title("Vị trí drone")
                        );
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

                startActivity(intent);

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
        edtAltitude = findViewById(R.id.edtAltitude);
        edtLat = findViewById(R.id.edtLat);
        edtLng = findViewById(R.id.edtLng);
        btnStart = findViewById(R.id.btnStart);
        btnCancel = findViewById(R.id.btnCancel);
        btnToggleMenu = findViewById(R.id.btnToggleMenu);
        btnCaptureManual = findViewById(R.id.btnCaptureManual);
        btnFormatSd = findViewById(R.id.btnFormatSd);
        btnToggleCameraMode = findViewById(R.id.btnToggleCameraMode);
        btnViewPhotos = findViewById(R.id.btnViewPhotos);
        layoutMenuContent = findViewById(R.id.layoutMenuContent);
        seekBarGimbal = findViewById(R.id.seekBarGimbal);
        rbPhoto = findViewById(R.id.rbPhoto);
        videoSurface = findViewById(R.id.video_preview_texture_view);

        // Ánh xạ View cho nút bấm và panel camera
        btnCameraMenuToggle = findViewById(R.id.btnCameraMenuToggle);
        cameraControlPanel = findViewById(R.id.cameraControlPanel);

        if (videoSurface != null) {
            videoSurface.setSurfaceTextureListener(this);
        }

        btnFormatSd.setOnClickListener(v -> formatSDCard());
        btnToggleCameraMode.setOnClickListener(v -> toggleCameraMode());
        btnViewPhotos.setOnClickListener(v -> fetchAndDownloadLatestPhoto());

        btnToggleMenu.setOnClickListener(v -> {
            if (isMenuVisible) {
                layoutMenuContent.setVisibility(View.GONE);
                btnToggleMenu.setText("MỞ");
            } else {
                layoutMenuContent.setVisibility(View.VISIBLE);
                btnToggleMenu.setText("THU");
            }
            isMenuVisible = !isMenuVisible;
        });

        // Xử lý sự kiện Click cho nút btnCameraMenuToggle để ẩn/hiện cameraControlPanel
        if (btnCameraMenuToggle != null && cameraControlPanel != null) {
            btnCameraMenuToggle.setOnClickListener(v -> {
                if (cameraControlPanel.getVisibility() == View.VISIBLE) {
                    cameraControlPanel.setVisibility(View.GONE);
                } else {
                    cameraControlPanel.setVisibility(View.VISIBLE);
                }
            });
        }

        btnCaptureManual.setOnClickListener(v -> triggerManualCapture());

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

                                            runOnUiThread(() -> {

                                                if (miniMapLibreMap != null) {

                                                    if (droneMarker == null) {

                                                        droneMarker = miniMapLibreMap.addMarker(
                                                                new MarkerOptions()
                                                                        .position(lastDroneLocation)
                                                                        .title("Vị trí drone")
                                                        );

                                                    } else {

                                                        droneMarker.setPosition(lastDroneLocation);
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
                                        try {
                                            String latStr = edtLat.getText().toString().replace(",", ".");
                                            String lngStr = edtLng.getText().toString().replace(",", ".");
                                            targetLat = Double.parseDouble(latStr);
                                            targetLng = Double.parseDouble(lngStr);
                                        } catch (Exception ignored) {}

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

        btnStart.setOnClickListener(v -> {
            boolean isRecordMode = !rbPhoto.isChecked();
            executeMission(isRecordMode);
        });

        btnCancel.setOnClickListener(v -> {
            if (missionManager != null) {
                missionManager.cancelMission();
                Toast.makeText(this, "ĐÃ HỦY LỆNH BAY!", Toast.LENGTH_SHORT).show();
            }
        });

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

    private void toggleCameraMode() {
        if (DJISDKManager.getInstance().getProduct() == null) {
            Toast.makeText(this, "Chưa kết nối Drone!", Toast.LENGTH_SHORT).show();
            return;
        }
        Camera camera = ((Aircraft) DJISDKManager.getInstance().getProduct()).getCamera();
        if (camera != null) {
            SettingsDefinitions.CameraMode targetMode = isCurrentModePhoto ?
                    SettingsDefinitions.CameraMode.RECORD_VIDEO :
                    SettingsDefinitions.CameraMode.SHOOT_PHOTO;

            camera.setMode(targetMode, djiError -> {
                runOnUiThread(() -> {
                    if (djiError == null) {
                        isCurrentModePhoto = !isCurrentModePhoto;
                        btnToggleCameraMode.setText(isCurrentModePhoto ? "PHOTO" : "VIDEO");

                        if (isCurrentModePhoto) {
                            btnCaptureManual.setText("CHỤP");
                            btnCaptureManual.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#03A9F4")));
                        } else {
                            btnCaptureManual.setText("QUAY");
                            btnCaptureManual.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#E91E63")));
                        }

                        Toast.makeText(MainActivity.this, isCurrentModePhoto ? "Chế độ: CHỤP ẢNH" : "Chế độ: QUAY VIDEO", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(MainActivity.this, "Không đổi được chế độ: " + djiError.getDescription(), Toast.LENGTH_SHORT).show();
                    }
                });
            });
        }
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
            Toast.makeText(this, "Chưa kết nối Drone!", Toast.LENGTH_SHORT).show();
            return;
        }

        Camera camera = ((Aircraft) DJISDKManager.getInstance().getProduct()).getCamera();
        if (camera != null) {
            if (isCurrentModePhoto) {
                // Đang ở chế độ CHỤP ẢNH
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
            } else {
                // Đang ở chế độ QUAY VIDEO
                if (!isRecordingVideo) {
                    camera.startRecordVideo(djiError -> {
                        if (djiError == null) {
                            isRecordingVideo = true;
                            runOnUiThread(() -> {
                                btnCaptureManual.setText("DỪNG");
                                Toast.makeText(MainActivity.this, "Bắt đầu quay Video!", Toast.LENGTH_SHORT).show();
                            });
                        } else {
                            // Đã bổ sung báo lỗi khi không thể bắt đầu quay
                            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Lỗi quay: " + djiError.getDescription(), Toast.LENGTH_LONG).show());
                        }
                    });
                } else {
                    camera.stopRecordVideo(djiError -> {
                        if (djiError == null) {
                            isRecordingVideo = false;
                            runOnUiThread(() -> {
                                btnCaptureManual.setText("QUAY");
                                Toast.makeText(MainActivity.this, "Đã lưu Video vào thẻ nhớ!", Toast.LENGTH_SHORT).show();
                            });
                        } else {
                            // Đã bổ sung báo lỗi khi không thể dừng quay
                            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Lỗi dừng quay: " + djiError.getDescription(), Toast.LENGTH_LONG).show());
                        }
                    });
                }
            }
        }
    }

    private void executeMission(boolean isRecord) {
        if (missionManager == null) {
            Toast.makeText(this, "Chưa kết nối Drone!", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isHomePointSet) {
            Toast.makeText(this, "Chưa lưu Home Point, chờ đủ vệ tinh GPS (>10)!", Toast.LENGTH_SHORT).show();
            return;
        }

        double targetLat;
        double targetLng;
        float targetAlt;

        try {
            String latStr = edtLat.getText().toString().replace(",", ".");
            String lngStr = edtLng.getText().toString().replace(",", ".");
            String altStr = edtAltitude.getText().toString().replace(",", ".");

            targetLat = Double.parseDouble(latStr);
            targetLng = Double.parseDouble(lngStr);
            targetAlt = Float.parseFloat(altStr);
        } catch (Exception e) {
            Toast.makeText(this, "Lỗi định dạng tọa độ! Hãy kiểm tra lại số liệu.", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "Đã nhấn START! Bắt đầu bay lịch trình...", Toast.LENGTH_SHORT).show();
        missionManager.flyToTargetAndExecute(targetLat, targetLng, targetAlt, isRecord);
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

    @Override
    protected void onStart() {
        super.onStart();

        if (miniMapView != null) {
            miniMapView.onStart();
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
}