package com.tri.djicontrol.flight;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.List;

import dji.common.camera.SettingsDefinitions;
import dji.common.camera.SettingsDefinitions.PhotoTimeIntervalSettings;
import dji.common.flightcontroller.virtualstick.FlightControlData;
import dji.common.flightcontroller.virtualstick.FlightCoordinateSystem;
import dji.common.flightcontroller.virtualstick.RollPitchControlMode;
import dji.common.flightcontroller.virtualstick.VerticalControlMode;
import dji.common.flightcontroller.virtualstick.YawControlMode;
import dji.common.gimbal.Rotation;
import dji.common.gimbal.RotationMode;
import dji.common.model.LocationCoordinate2D;
import dji.sdk.camera.Camera;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.products.Aircraft;
import dji.sdk.sdkmanager.DJISDKManager;

public class FlightMissionManager {
    private static final String TAG = "FlightMissionManager";
    private FlightController flightController;
    private Camera camera;
    private dji.sdk.gimbal.Gimbal gimbal;
    private Handler missionHandler;
    private Runnable missionRunnable;
    private boolean isMissionRunning = false;

    // Các biến phục vụ Mapping
    private List<LocationCoordinate2D> waypoints;
    private int currentWaypointIndex = 0;

    private float targetAltitude; // Độ cao bay sát quét lúa (User setup)
    private float safeTransitAltitude = 20.0f; // Độ cao an toàn lúc di chuyển ra đồng

    private enum MissionState {
        RISING_TO_SAFE_ALT,       // 1. Bay lên độ cao an toàn (20m)
        FLYING_TO_FIRST_POINT,    // 2. Bay đến điểm quét đầu tiên
        DESCENDING_TO_SCAN,       // 3. Hạ độ cao xuống sát lúa (Target Altitude)
        FLYING_WAYPOINTS,         // 4. Bắt đầu bay quét zigzag
        COMPLETED                 // 5. Hoàn thành
    }
    private MissionState currentMissionState = MissionState.RISING_TO_SAFE_ALT;

    public FlightMissionManager() {
        if (DJISDKManager.getInstance().getProduct() instanceof Aircraft) {
            Aircraft aircraft = (Aircraft) DJISDKManager.getInstance().getProduct();
            flightController = aircraft.getFlightController();
            camera = aircraft.getCamera();
            gimbal = aircraft.getGimbal();
        }
        missionHandler = new Handler(Looper.getMainLooper());
    }

    public void startMappingMission(List<LocationCoordinate2D> gridPoints, float scanAltitude) {
        if (flightController == null || gridPoints == null || gridPoints.isEmpty()) {
            Log.e(TAG, "FlightController chưa sẵn sàng hoặc không có điểm bay!");
            return;
        }

        this.waypoints = gridPoints;
        this.currentWaypointIndex = 0;
        this.targetAltitude = scanAltitude;
        this.isMissionRunning = true;

        // Luôn nâng gimbal lên 0 độ khi đang di chuyển ra đồng để an toàn
        setGimbalAngle(0f);
        this.currentMissionState = MissionState.RISING_TO_SAFE_ALT;

        flightController.setVirtualStickModeEnabled(true, djiError -> {
            if (djiError == null) {
                flightController.setRollPitchCoordinateSystem(FlightCoordinateSystem.BODY);
                flightController.setRollPitchControlMode(RollPitchControlMode.VELOCITY);
                flightController.setVerticalControlMode(VerticalControlMode.POSITION);
                flightController.setYawControlMode(YawControlMode.ANGLE);
                flightController.setVirtualStickAdvancedModeEnabled(true);

                startMissionLoop();
            } else {
                Log.e(TAG, "Không thể bật Virtual Stick: " + djiError.getDescription());
            }
        });
    }

    private void startMissionLoop() {
        missionRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isMissionRunning || flightController == null || flightController.getState() == null
                        || flightController.getState().getAircraftLocation() == null) {
                    return;
                }

                double currentLat = flightController.getState().getAircraftLocation().getLatitude();
                double currentLng = flightController.getState().getAircraftLocation().getLongitude();
                float currentAlt = flightController.getState().getAircraftLocation().getAltitude();

                switch (currentMissionState) {

                    // GIAI ĐOẠN 1: Bay lên độ cao an toàn để không vướng cây
                    case RISING_TO_SAFE_ALT:
                        flightController.sendVirtualStickFlightControlData(
                                new FlightControlData(0.0f, 0.0f, 0.0f, safeTransitAltitude), djiError -> {}
                        );
                        if (Math.abs(currentAlt - safeTransitAltitude) < 1.0) {
                            currentMissionState = MissionState.FLYING_TO_FIRST_POINT;
                        }
                        break;

                    // GIAI ĐOẠN 2: Di chuyển đến điểm đánh dấu đầu tiên
                    case FLYING_TO_FIRST_POINT:
                        LocationCoordinate2D firstPoint = waypoints.get(0);
                        double distToFirst = calculateDistance(currentLat, currentLng, firstPoint.getLatitude(), firstPoint.getLongitude());

                        if (distToFirst < 2.0) {
                            // Đã tới điểm đầu tiên trên không -> bắt đầu hạ thấp
                            currentMissionState = MissionState.DESCENDING_TO_SCAN;
                            // Cắm góc camera vuông góc xuống đất chuẩn bị quét
                            setGimbalAngle(-90f);
                        } else {
                            double bearing = calculateBearing(currentLat, currentLng, firstPoint.getLatitude(), firstPoint.getLongitude());
                            float speed = (float) Math.max(0.5, Math.min(distToFirst * 0.4, 5.0)); // Bay tối đa 5m/s lúc transit
                            flightController.sendVirtualStickFlightControlData(
                                    new FlightControlData(speed, 0.0f, (float)bearing, safeTransitAltitude), djiError -> {}
                            );
                        }
                        break;

                    // GIAI ĐOẠN 3: Hạ cao độ xuống sát lúa (Target Altitude)
                    case DESCENDING_TO_SCAN:
                        flightController.sendVirtualStickFlightControlData(
                                new FlightControlData(0.0f, 0.0f, 0.0f, targetAltitude), djiError -> {}
                        );
                        if (Math.abs(currentAlt - targetAltitude) < 0.5) {
                            currentMissionState = MissionState.FLYING_WAYPOINTS;

                            // Bắt đầu chụp ảnh liên tục (Timelapse) khi đã đạt độ cao sát lúa
                            startIntervalShooting(() -> {});
                        }
                        break;

                    // GIAI ĐOẠN 4: Bay ziczac để chụp ảnh
                    case FLYING_WAYPOINTS:
                        if (currentWaypointIndex >= waypoints.size()) {
                            // Đã quét xong
                            currentMissionState = MissionState.COMPLETED;
                            stopIntervalShootingAndGoHome();
                            return;
                        }

                        LocationCoordinate2D target = waypoints.get(currentWaypointIndex);
                        double distance = calculateDistance(currentLat, currentLng, target.getLatitude(), target.getLongitude());

                        if (distance < 2.0) {
                            currentWaypointIndex++; // Qua điểm tiếp theo
                            break;
                        }

                        double gridBearing = calculateBearing(currentLat, currentLng, target.getLatitude(), target.getLongitude());
                        // Giới hạn tốc độ Mapping (Max 3.0 m/s để ảnh ko mờ do bay sát đất)
                        double gridSpeed = Math.max(0.5, Math.min(distance * 0.4, 3.0));

                        flightController.sendVirtualStickFlightControlData(
                                new FlightControlData((float)gridSpeed, 0.0f, (float)gridBearing, targetAltitude), djiError -> {}
                        );
                        break;

                    default:
                        break;
                }

                if (isMissionRunning) {
                    missionHandler.postDelayed(this, 50); // 20Hz
                }
            }
        };
        missionHandler.post(missionRunnable);
    }

    private void startIntervalShooting(Runnable onReady) {
        if (camera == null) {
            onReady.run();
            return;
        }

        camera.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO, djiError -> {
            if (djiError == null) {
                // Cài đặt chụp 255 ảnh liên tục, mỗi 2 giây 1 tấm
                PhotoTimeIntervalSettings intervalSettings = new PhotoTimeIntervalSettings(255, 2);
                camera.setPhotoTimeIntervalSettings(intervalSettings, error -> {
                    if (error == null) {
                        camera.setShootPhotoMode(SettingsDefinitions.ShootPhotoMode.INTERVAL, modeError -> {
                            if (modeError == null) {
                                camera.startShootPhoto(startError -> onReady.run());
                            } else {
                                onReady.run();
                            }
                        });
                    } else {
                        onReady.run();
                    }
                });
            } else {
                onReady.run();
            }
        });
    }

    private void stopIntervalShootingAndGoHome() {
        if (missionRunnable != null) {
            missionHandler.removeCallbacks(missionRunnable);
        }

        if (camera != null) {
            camera.stopShootPhoto(djiError -> prepareAndGoHome());
        } else {
            prepareAndGoHome();
        }
    }

    private void prepareAndGoHome() {
        setGimbalAngle(0f); // Ngẩng cam lên nhìn thẳng
        if (flightController != null) {
            flightController.setVirtualStickModeEnabled(false, djiError -> {
                flightController.startGoHome(error -> {
                    if (error == null) {
                        Log.d(TAG, "Đã kích hoạt RTH!");
                    }
                    cancelMission();
                });
            });
        }
    }

    public void setGimbalAngle(float pitchAngle) {
        if (gimbal != null) {
            Rotation rotation = new Rotation.Builder()
                    .mode(RotationMode.ABSOLUTE_ANGLE)
                    .pitch(pitchAngle)
                    .build();
            gimbal.rotate(rotation, null);
        }
    }

    public void startReturnToHome() {
        if (flightController == null) {
            Log.e(TAG, "FlightController chưa sẵn sàng!");
            return;
        }

        isMissionRunning = false;
        if (missionRunnable != null) {
            missionHandler.removeCallbacks(missionRunnable);
        }

        flightController.setVirtualStickModeEnabled(false, djiError -> {
            flightController.startGoHome(error -> {
                if (error == null) {
                    Log.d(TAG, "Đã kích hoạt RTH!");
                } else {
                    Log.e(TAG, "RTH thất bại: " + error.getDescription());
                }
            });
        });
    }

    public void cancelMission() {
        isMissionRunning = false;
        if (missionRunnable != null) {
            missionHandler.removeCallbacks(missionRunnable);
        }
        if (flightController != null) {
            flightController.setVirtualStickModeEnabled(false, null);
        }
    }

    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    private double calculateBearing(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double deltaLambda = Math.toRadians(lon2 - lon1);

        double y = Math.sin(deltaLambda) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2) -
                Math.sin(phi1) * Math.cos(phi2) * Math.cos(deltaLambda);
        return Math.toDegrees(Math.atan2(y, x));
    }
}