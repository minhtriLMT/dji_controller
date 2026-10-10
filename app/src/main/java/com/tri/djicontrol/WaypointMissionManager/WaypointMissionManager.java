package com.tri.djicontrol.WaypointMissionManager;

import android.util.Log;
import java.util.ArrayList;
import java.util.List;

import dji.common.error.DJIError;
import dji.common.mission.waypoint.Waypoint;
import dji.common.mission.waypoint.WaypointMission;
import dji.common.mission.waypoint.WaypointMissionFinishedAction;
import dji.common.mission.waypoint.WaypointMissionFlightPathMode;
import dji.common.mission.waypoint.WaypointMissionState;
import dji.common.model.LocationCoordinate2D;
import dji.sdk.mission.waypoint.WaypointMissionOperator;
import dji.sdk.sdkmanager.DJISDKManager;

public class WaypointMissionManager {
    private static final String TAG = "WaypointMissionManager";

    public interface MissionCallback {
        void onStatus(String message);
    }

    private WaypointMissionOperator getOperator() {
        if (DJISDKManager.getInstance().getMissionControl() != null) {
            return DJISDKManager.getInstance().getMissionControl().getWaypointMissionOperator();
        }
        return null;
    }

    public WaypointMissionState getCurrentState() {
        WaypointMissionOperator operator = getOperator();
        return operator != null ? operator.getCurrentState() : null;
    }

    public void startMission(List<LocationCoordinate2D> coords, float altitude, MissionCallback callback) {
        WaypointMissionOperator operator = getOperator();
        if (operator == null) {
            callback.onStatus("WaypointMissionOperator chưa sẵn sàng!");
            return;
        }

        WaypointMissionState state = operator.getCurrentState();
        if (state != null && state != WaypointMissionState.READY_TO_UPLOAD && state != WaypointMissionState.READY_TO_EXECUTE && state != WaypointMissionState.DISCONNECTED) {
            Log.w(TAG, "Trạng thái mission hiện tại: " + state.getName() + ". Đang dừng mission cũ trước khi tạo mới...");
            operator.stopMission(error -> buildAndUploadMission(operator, coords, altitude, callback));
            return;
        }

        buildAndUploadMission(operator, coords, altitude, callback);
    }

    private void buildAndUploadMission(WaypointMissionOperator operator, List<LocationCoordinate2D> coords, float altitude, MissionCallback callback) {
        List<Waypoint> waypoints = new ArrayList<>();
        for (LocationCoordinate2D c : coords) {
            Waypoint wp = new Waypoint(c.getLatitude(), c.getLongitude(), altitude);
            wp.heading = 0;
            wp.shootPhotoTimeInterval = 2.0f; // Chụp ảnh mỗi 2 giây tại mỗi điểm waypoint
            waypoints.add(wp);
        }

        WaypointMission.Builder builder = new WaypointMission.Builder()
                .finishedAction(WaypointMissionFinishedAction.GO_HOME)
                .flightPathMode(WaypointMissionFlightPathMode.CURVED)
                .maxFlightSpeed(8.0f)
                .autoFlightSpeed(4.0f)
                .waypointList(waypoints)
                .waypointCount(waypoints.size());

        WaypointMission mission = builder.build();
        DJIError paramError = mission.checkParameters();
        if (paramError != null) {
            callback.onStatus("Lỗi tham số Mission: " + paramError.getDescription());
            return;
        }

        callback.onStatus("Đang tải nhiệm vụ lên Drone (" + waypoints.size() + " điểm)...");
        DJIError loadError = operator.loadMission(mission);
        if (loadError != null) {
            callback.onStatus("Lỗi load mission: " + loadError.getDescription());
            return;
        }

        operator.uploadMission(error -> {
            if (error == null) {
                callback.onStatus("Upload lộ trình thành công! Đang khởi động bay quét tự động...");
                operator.startMission(startError -> {
                    if (startError == null) {
                        callback.onStatus("Drone đã cất cánh bay quét tự động thành công!");
                    } else {
                        callback.onStatus("Lỗi bắt đầu bay: " + startError.getDescription());
                    }
                });
            } else {
                callback.onStatus("Lỗi upload mission: " + error.getDescription());
            }
        });
    }

    public void pauseMission(MissionCallback callback) {
        WaypointMissionOperator operator = getOperator();
        if (operator != null) {
            operator.pauseMission(error -> {
                if (error == null && callback != null) {
                    callback.onStatus("Đã tạm dừng nhiệm vụ bay!");
                } else if (error != null && callback != null) {
                    callback.onStatus("Lỗi tạm dừng bay: " + error.getDescription());
                }
            });
        }
    }

    public void resumeMission(MissionCallback callback) {
        WaypointMissionOperator operator = getOperator();
        if (operator != null) {
            operator.resumeMission(error -> {
                if (error == null && callback != null) {
                    callback.onStatus("Đã tiếp tục nhiệm vụ bay!");
                } else if (error != null && callback != null) {
                    callback.onStatus("Lỗi tiếp tục bay: " + error.getDescription());
                }
            });
        }
    }

    public void cancelMission(MissionCallback callback) {
        WaypointMissionOperator operator = getOperator();
        if (operator != null) {
            operator.stopMission(error -> {
                if (error == null && callback != null) {
                    callback.onStatus("Đã hủy và dừng nhiệm vụ bay!");
                } else if (error != null && callback != null) {
                    callback.onStatus("Lỗi hủy bay: " + error.getDescription());
                }
            });
        }
    }
}