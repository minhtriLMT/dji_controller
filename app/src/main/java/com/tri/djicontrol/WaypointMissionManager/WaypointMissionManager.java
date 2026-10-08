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

    public void startMission(List<LocationCoordinate2D> coords, float altitude, MissionCallback callback) {
        WaypointMissionOperator operator = getOperator();
        if (operator == null) {
            callback.onStatus("WaypointMissionOperator chưa sẵn sàng!");
            return;
        }

        // Đã sửa lỗi truy cập .name() bằng cách dùng .equals(WaypointMissionState.READY_TO_UPLOAD) và .getName()
        if (operator.getCurrentState() != null && !operator.getCurrentState().equals(WaypointMissionState.READY_TO_UPLOAD)) {
            callback.onStatus("Nhiệm vụ đang chạy hoặc chưa sẵn sàng! Trạng thái: " + operator.getCurrentState().getName());
            return;
        }

        List<Waypoint> waypoints = new ArrayList<>();
        for (LocationCoordinate2D c : coords) {
            Waypoint wp = new Waypoint(c.getLatitude(), c.getLongitude(), altitude);
            wp.heading = 0;
            wp.shootPhotoTimeInterval = 2.0f; // Chụp ảnh mỗi 2 giây tại mỗi điểm
            waypoints.add(wp);
        }

        WaypointMission.Builder builder = new WaypointMission.Builder()
                .finishedAction(WaypointMissionFinishedAction.GO_HOME)
                .flightPathMode(WaypointMissionFlightPathMode.CURVED)
                .maxFlightSpeed(5.0f)
                .autoFlightSpeed(3.0f)
                .waypointList(waypoints)
                .waypointCount(waypoints.size());

        WaypointMission mission = builder.build();
        if (mission.checkParameters() != null) {
            callback.onStatus("Lỗi tham số Mission: " + mission.checkParameters().getDescription());
            return;
        }

        callback.onStatus("Đang tải nhiệm vụ lên Drone...");
        operator.loadMission(mission);

        operator.uploadMission(error -> {
            if (error == null) {
                callback.onStatus("Upload thành công! Đang thực thi bay...");
                operator.startMission(startError -> {
                    if (startError == null) {
                        callback.onStatus("Drone đã cất cánh bay quét tự động!");
                    } else {
                        callback.onStatus("Lỗi bắt đầu bay: " + startError.getDescription());
                    }
                });
            } else {
                callback.onStatus("Lỗi upload mission: " + error.getDescription());
            }
        });
    }

    public void cancelMission(MissionCallback callback) {
        WaypointMissionOperator operator = getOperator();
        if (operator != null) {
            operator.stopMission(error -> {
                if (error == null && callback != null) {
                    callback.onStatus("Đã hủy nhiệm vụ bay!");
                }
            });
        }
    }
}