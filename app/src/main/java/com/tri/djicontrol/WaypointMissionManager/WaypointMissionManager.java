package com.tri.djicontrol.WaypointMissionManager;

import android.os.Handler;
import android.os.Looper;
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
    // Giới hạn của DJI MSDK v4: 2..99 điểm, các điểm liên tiếp cách nhau >= 0.5m
    private static final int MAX_WAYPOINTS = 99;
    private static final double MIN_POINT_DISTANCE = 0.6;
    private final Handler handler = new Handler(Looper.getMainLooper());

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
        if (state == WaypointMissionState.NOT_SUPPORTED) {
            callback.onStatus("Drone này không hỗ trợ Waypoint Mission của SDK!");
            return;
        }
        if (state == WaypointMissionState.DISCONNECTED || state == WaypointMissionState.UNKNOWN) {
            callback.onStatus("Chưa kết nối drone (trạng thái mission: " + (state != null ? state.getName() : "null") + ")");
            return;
        }
        if (coords == null || coords.size() < 2) {
            callback.onStatus("Cần ít nhất 2 điểm để bay!");
            return;
        }
        if (state != null && state != WaypointMissionState.READY_TO_UPLOAD && state != WaypointMissionState.READY_TO_EXECUTE && state != WaypointMissionState.DISCONNECTED) {
            Log.w(TAG, "Trạng thái mission hiện tại: " + state.getName() + ". Đang dừng mission cũ trước khi tạo mới...");
            operator.stopMission(error -> buildAndUploadMission(operator, coords, altitude, callback));
            return;
        }

        buildAndUploadMission(operator, coords, altitude, callback);
    }

    private void buildAndUploadMission(WaypointMissionOperator operator, List<LocationCoordinate2D> coords, float altitude, MissionCallback callback) {
        List<LocationCoordinate2D> reduced = reduceWaypoints(coords);
        if (reduced.size() > MAX_WAYPOINTS) {
            callback.onStatus("Lộ trình có " + reduced.size() + " điểm rẽ, vượt giới hạn " + MAX_WAYPOINTS
                    + " điểm của DJI. Hãy tăng khoảng cách giữa các đường quét!");
            return;
        }
        if (reduced.size() < 2) {
            callback.onStatus("Khu vực quá nhỏ, không đủ điểm để bay!");
            return;
        }

        List<Waypoint> waypoints = new ArrayList<>();
        for (LocationCoordinate2D c : reduced) {
            Waypoint wp = new Waypoint(c.getLatitude(), c.getLongitude(), altitude);
            wp.heading = 0;
            wp.shootPhotoTimeInterval = 2.0f; // Chụp ảnh mỗi 2 giây tại mỗi điểm waypoint
            waypoints.add(wp);
        }

        WaypointMission.Builder builder = new WaypointMission.Builder()
                .finishedAction(WaypointMissionFinishedAction.GO_HOME)
                .flightPathMode(WaypointMissionFlightPathMode.NORMAL)
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
                waitReadyThenStart(operator, callback, 0);
            } else {
                callback.onStatus("Lỗi upload mission: " + error.getDescription());
            }
        });
    }

    // Chỉ startMission khi drone đã nhận xong lộ trình (READY_TO_EXECUTE), gọi sớm sẽ bị từ chối
    private void waitReadyThenStart(WaypointMissionOperator operator, MissionCallback callback, int attempt) {
        if (operator.getCurrentState() == WaypointMissionState.READY_TO_EXECUTE) {
            operator.startMission(startError -> {
                if (startError == null) {
                    callback.onStatus("Drone đã cất cánh bay quét tự động thành công!");
                } else {
                    callback.onStatus("Lỗi bắt đầu bay: " + startError.getDescription());
                }
            });
        } else if (attempt >= 60) { // ~30 giây
            WaypointMissionState st = operator.getCurrentState();
            callback.onStatus("Hết thời gian chờ upload (trạng thái: " + (st != null ? st.getName() : "null") + ")");
        } else {
            handler.postDelayed(() -> waitReadyThenStart(operator, callback, attempt + 1), 500);
        }
    }

    // Bỏ các điểm trùng/quá gần; nếu vượt 99 điểm thì chỉ giữ các điểm rẽ (đầu/cuối mỗi đường quét).
    // Ảnh vẫn được chụp đều nhờ shootPhotoTimeInterval khi bay giữa các điểm.
    private List<LocationCoordinate2D> reduceWaypoints(List<LocationCoordinate2D> coords) {
        List<LocationCoordinate2D> dedup = new ArrayList<>();
        for (LocationCoordinate2D c : coords) {
            if (dedup.isEmpty() || distance(dedup.get(dedup.size() - 1), c) >= MIN_POINT_DISTANCE) {
                dedup.add(c);
            }
        }
        if (dedup.size() <= MAX_WAYPOINTS) return dedup;

        List<LocationCoordinate2D> turns = new ArrayList<>();
        for (int i = 0; i < dedup.size(); i++) {
            if (i == 0 || i == dedup.size() - 1) {
                turns.add(dedup.get(i));
                continue;
            }
            LocationCoordinate2D prev = dedup.get(i - 1), cur = dedup.get(i), next = dedup.get(i + 1);
            // Điểm nằm giữa một đường quét thẳng (cùng vĩ độ với 2 điểm kề) thì bỏ
            boolean straight = cur.getLatitude() == prev.getLatitude() && cur.getLatitude() == next.getLatitude();
            if (!straight) turns.add(cur);
        }
        return turns;
    }

    private static double distance(LocationCoordinate2D a, LocationCoordinate2D b) {
        double dLat = Math.toRadians(b.getLatitude() - a.getLatitude());
        double dLon = Math.toRadians(b.getLongitude() - a.getLongitude());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(a.getLatitude())) * Math.cos(Math.toRadians(b.getLatitude()))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371000 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
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