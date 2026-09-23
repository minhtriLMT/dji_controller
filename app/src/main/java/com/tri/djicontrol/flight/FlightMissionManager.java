package com.tri.djicontrol.flight;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import dji.common.camera.SettingsDefinitions;
import dji.common.flightcontroller.virtualstick.FlightControlData;
import dji.common.flightcontroller.virtualstick.FlightCoordinateSystem;
import dji.common.flightcontroller.virtualstick.RollPitchControlMode;
import dji.common.flightcontroller.virtualstick.VerticalControlMode;
import dji.common.flightcontroller.virtualstick.YawControlMode;
import dji.common.gimbal.Rotation;
import dji.common.gimbal.RotationMode;
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

    private double targetLatitude;
    private double targetLongitude;
    private float targetAltitude;
    private float safeTransitAltitude = 20.0f;
    private boolean isRecordMode;

    private enum MissionState {
        RISING_TO_SAFE_ALT,
        FLYING_TO_TARGET,
        DESCENDING_TO_TASK,
        EXECUTING_TASK,
        RISING_BEFORE_HOME,
        COMPLETED
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

    public void flyToTargetAndExecute(double lat, double lng, float altitude, boolean recordMode) {
        if (flightController == null) {
            Log.e(TAG, "FlightController chưa sẵn sàng!");
            return;
        }

        this.targetLatitude = lat;
        this.targetLongitude = lng;
        this.targetAltitude = altitude;
        this.isRecordMode = recordMode;
        this.isMissionRunning = true;
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

                double distance = calculateDistance(currentLat, currentLng, targetLatitude, targetLongitude);

                switch (currentMissionState) {
                    case RISING_TO_SAFE_ALT:
                        flightController.sendVirtualStickFlightControlData(
                                new FlightControlData(0.0f, 0.0f, 0.0f, safeTransitAltitude), djiError -> {}
                        );
                        if (Math.abs(currentAlt - safeTransitAltitude) < 1.0) {
                            currentMissionState = MissionState.FLYING_TO_TARGET;
                        }
                        break;

                    case FLYING_TO_TARGET:
                        if (distance < 2.0) {
                            currentMissionState = MissionState.DESCENDING_TO_TASK;
                            flightController.sendVirtualStickFlightControlData(
                                    new FlightControlData(0.0f, 0.0f, 0.0f, safeTransitAltitude), null
                            );
                            break;
                        }

                        double bearing = calculateBearing(currentLat, currentLng, targetLatitude, targetLongitude);
                        double speed = Math.max(0.5, Math.min(distance * 0.4, 5.0));

                        float targetYaw = (float) bearing;
                        float forwardVelocity = (float) speed;

                        flightController.sendVirtualStickFlightControlData(
                                new FlightControlData(forwardVelocity, 0.0f, targetYaw, safeTransitAltitude), djiError -> {}
                        );
                        break;

                    case DESCENDING_TO_TASK:
                        flightController.sendVirtualStickFlightControlData(
                                new FlightControlData(0.0f, 0.0f, 0.0f, targetAltitude), djiError -> {}
                        );
                        if (Math.abs(currentAlt - targetAltitude) < 0.8) {
                            currentMissionState = MissionState.EXECUTING_TASK;
                            stopMovementAndExecuteTask();
                            return;
                        }
                        break;

                    default:
                        break;
                }

                if (isMissionRunning) {
                    missionHandler.postDelayed(this, 50);
                }
            }
        };
        missionHandler.post(missionRunnable);
    }

    private void stopMovementAndExecuteTask() {
        if (missionRunnable != null) {
            missionHandler.removeCallbacks(missionRunnable);
        }

        if (flightController != null) {
            flightController.sendVirtualStickFlightControlData(new FlightControlData(0, 0, 0, targetAltitude), null);
        }

        setGimbalAngle(-90f);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (isRecordMode) {
                executeVideoAndGoHome();
            } else {
                executePhotoAndGoHome();
            }
        }, 1500);
    }

    private void executeVideoAndGoHome() {
        if (camera == null) return;
        camera.setMode(SettingsDefinitions.CameraMode.RECORD_VIDEO, djiError -> {
            camera.startRecordVideo(startErr -> {
                if (startErr == null) {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        camera.stopRecordVideo(stopErr -> {
                            prepareAndGoHome();
                        });
                    }, 5000);
                }
            });
        });
    }

    private void executePhotoAndGoHome() {
        if (camera == null) return;
        camera.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO, djiError -> {
            shootMultiplePhotos(5);
        });
    }

    private void shootMultiplePhotos(int remainingPhotos) {
        if (remainingPhotos <= 0) {
            prepareAndGoHome();
            return;
        }

        camera.startShootPhoto(djiError -> {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                shootMultiplePhotos(remainingPhotos - 1);
            }, 2000);
        });
    }

    private void prepareAndGoHome() {
        currentMissionState = MissionState.RISING_BEFORE_HOME;

        if (flightController != null) {
            flightController.setVirtualStickModeEnabled(false, djiError -> {
                flightController.startGoHome(error -> {
                    if (error == null) {
                        Log.d(TAG, "Đã kích hoạt chế độ tự động bay về Home!");
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