package com.tri.djicontrol.flight;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.List;
import java.util.Locale;

import dji.common.camera.SettingsDefinitions;
import dji.common.flightcontroller.FlightControllerState;
import dji.common.flightcontroller.GPSSignalLevel;
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

/**
 * Bay quét ruộng tự động bằng Virtual Stick (Mini 2 không hỗ trợ Waypoint Mission của MSDK v4).
 *
 * Quy trình: (cất cánh nếu đang ở đất) -> lên độ cao di chuyển -> bay tới điểm đầu ->
 * hạ/nâng về độ cao quét -> bật camera (chụp theo quãng đường / quay video) -> bay zigzag
 * -> dừng camera -> bay về nhà (RTH).
 *
 * Ảnh/video được ghi vào thẻ nhớ drone -> xem trong Kho lưu trữ (tab THẺ NHỚ DRONE).
 */
public class FlightMissionManager {

    public interface MissionListener {
        /** Trạng thái hiển thị cho người dùng (chạy trên main thread). */
        void onStatus(String status);

        /** Đã đi qua index waypoint (0..total). */
        void onProgress(int reachedIndex, int total, int photoCount);

        void onFinished(boolean completed, String message);

        /** Bay quét chế độ video: bắt đầu (true) / dừng (false) quay. */
        default void onRecordingChanged(boolean recording) {}

        /** Nhiệm vụ dừng giữa chừng (pin yếu / drone tự về nhà) và đã lưu tiến độ để bay tiếp. */
        default void onCheckpointSaved(MissionCheckpoint checkpoint) {}

        /** Chế độ chụp đứng yên: số ảnh đã đẩy lên Cloudinary (thư mục WP) / lỗi / đang đẩy. */
        default void onCloudUpload(int uploaded, int failed, int pending, String message) {}
    }

    // --- Pin yếu: về nhà + lưu tiến độ để bay tiếp sau khi thay pin ---
    /** Ngưỡng mặc định (%): pin còn bằng hoặc dưới mức này thì dừng quét và về nhà. */
    public static final int DEFAULT_LOW_BATTERY_PERCENT = 30;
    /** Cộng thêm vào mức pin drone tính là cần để về nhà (dự phòng gió / hạ cánh). */
    private static final int RTH_BATTERY_MARGIN = 10;

    private volatile int batteryPercent = -1;
    private int lowBatteryPercent = DEFAULT_LOW_BATTERY_PERCENT;
    private int lastThreshold = DEFAULT_LOW_BATTERY_PERCENT;

    /** Lộ trình gốc đầy đủ (khi bay tiếp, "waypoints" chỉ là phần còn lại). */
    private List<LocationCoordinate2D> fullPlan;
    /** Đang bay tiếp nhiệm vụ dở: waypoints[i] ứng với fullPlan[resumeOffset + i]. */
    private boolean resumed = false;
    private int resumeOffset = 0;

    private static final String TAG = "FlightMissionManager";
    private static final long LOOP_MS = 100;              // 10Hz (DJI khuyến nghị 5–25Hz)
    private static final double ARRIVE_RADIUS = 1.5;      // m (bay ra / giữ vị trí)
    /** Bán kính coi là "tới điểm" khi quét: nhỏ để không cắt góc / vọt qua bờ lúc quay đầu. */
    private static final double SCAN_ARRIVE_RADIUS = 0.8;
    /** Lệch ngang tối đa vẫn cho chuyển sang đường kế (m); lệch hơn thì kéo về điểm cuối trước. */
    private static final double SCAN_ARRIVE_MAX_CROSS = 1.2;
    /** Hệ số kéo về đúng đường: vận tốc ngang (m/s) trên mỗi mét lệch. */
    private static final double CROSS_TRACK_GAIN = 0.8;
    /** Vận tốc kéo về đường tối đa (m/s). */
    private static final double CROSS_TRACK_MAX_SPEED = 1.5;
    /** Gia tốc phanh dùng để tính tốc độ giảm dần khi tới cuối đường (m/s²). */
    private static final double SCAN_DECEL = 0.6;
    /** Tốc độ tối thiểu khi bò tới điểm cuối đường (m/s). */
    private static final double SCAN_MIN_SPEED = 0.3;
    private static final float TRANSIT_ALT_DEFAULT = 20f; // độ cao bay ra mặc định
    private static final float MAX_TRANSIT_SPEED = 5f;    // m/s
    private static final long MIN_PHOTO_GAP_MS = 2200;    // Mini 2 cần ~2s để ghi 1 ảnh

    // --- Dừng hẳn rồi mới chụp (ảnh nét, không nhòe do drone đang trôi) ---
    /** Thời gian tối thiểu giữ yên tại điểm chụp trước khi bấm (cho gimbal + thân máy hết rung). */
    public static final long PHOTO_STABILIZE_MS = 1000;
    /** Phải đứng yên LIÊN TỤC bấy nhiêu ms (không chỉ 1 khoảnh khắc chậm lại giữa lúc lắc). */
    private static final long PHOTO_STABLE_WINDOW_MS = 700;
    /** Ngưỡng "đứng yên": tốc độ ngang, tốc độ đứng (m/s), độ nghiêng thân (độ), tốc độ xoay mũi (độ/s). */
    private static final double PHOTO_SETTLE_SPEED = 0.25;
    private static final double PHOTO_SETTLE_VZ = 0.2;
    private static final double PHOTO_SETTLE_TILT_DEG = 4.0;
    private static final double PHOTO_SETTLE_YAW_RATE = 5.0;
    /** Giữ yên tối đa bấy nhiêu ms; quá thì chụp luôn (gió mạnh không đứng yên được). */
    private static final long PHOTO_MAX_HOLD_MS = 8000;
    /**
     * Đứng thêm sau khi camera nhận lệnh chụp rồi mới bay tiếp. Callback startShootPhoto về khi camera
     * NHẬN lệnh, lúc đó có thể vẫn đang phơi sáng -> bay đi ngay sẽ nhòe ảnh.
     */
    private static final long PHOTO_AFTER_SHOT_MS = 1200;
    /** Trôi quá điểm chụp xa hơn bấy nhiêu mét mới kéo lại (gần hơn thì đứng yên tại chỗ, đỡ lắc). */
    private static final double PHOTO_HOLD_PULLBACK_M = 2.5;
    /** Bay chậm dần khi sắp tới điểm chụp để không phải phanh gấp (m/s tối thiểu). */
    private static final float PHOTO_APPROACH_MIN_SPEED = 1.0f;
    /** Camera không phản hồi quá lâu thì bỏ qua ảnh này và bay tiếp. */
    private static final long PHOTO_SHOT_TIMEOUT_MS = 5000;
    /** Ước tính thời gian đứng thêm ở mỗi điểm để tải ảnh gốc từ thẻ nhớ (đẩy lên Cloud chạy nền). */
    public static final double PHOTO_UPLOAD_SECONDS_ESTIMATE = 8.0;
    /** Ước tính thời gian dừng cho 1 ảnh khi chỉ lưu thẻ (phanh + ổn định + chụp). */
    public static final double PHOTO_STOP_SECONDS_CARD = 4.0;
    /** Ước tính tổng thời gian dừng cho 1 ảnh (phanh + ổn định + chụp + tải ảnh) — dùng để tính thời gian bay. */
    public static final double PHOTO_STOP_SECONDS_ESTIMATE = PHOTO_STOP_SECONDS_CARD + PHOTO_UPLOAD_SECONDS_ESTIMATE;

    /** Thời gian dừng mỗi ảnh theo lựa chọn lưu: chỉ thẻ, hay thẻ + đẩy Cloud ngay. */
    public static double photoStopSeconds(boolean cloudUpload) {
        return cloudUpload ? PHOTO_STOP_SECONDS_ESTIMATE : PHOTO_STOP_SECONDS_CARD;
    }

    /** true: vừa bay vừa tải ảnh và đẩy lên Cloud; false: chỉ lưu vào thẻ nhớ (bay nhanh hơn). */
    private boolean cloudUpload = false;

    public void setCloudUpload(boolean enabled) {
        cloudUpload = enabled;
    }

    /** Chụp đứng yên: tải ảnh vừa chụp và đẩy lên Cloudinary (thư mục WP) ngay tại điểm dừng. */
    private WpPhotoUploader uploader;
    /** Đang tải ảnh từ thẻ nhớ -> drone giữ yên, chưa bay tiếp. */
    private boolean transferring = false;

    private boolean holding = false, shotFired = false;
    private double holdLat, holdLng;
    private float holdYaw;
    private long holdStartMs, shotFiredMs, holdReleaseAtMs;
    private long stableSinceMs;          // 0 = chưa đứng yên
    private double lastYawDeg = Double.NaN;
    private long lastYawMs;

    private FlightController flightController;
    private Camera camera;
    private dji.sdk.gimbal.Gimbal gimbal;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable loop;
    private volatile boolean running = false;

    private List<LocationCoordinate2D> waypoints;
    private int wpIndex = 0;
    private float scanAlt, transitAlt, cruiseSpeed;
    private boolean videoMode;
    private double photoDistance;
    private MissionListener listener;

    // Chụp ảnh theo quãng đường
    private double lastShotLat = Double.NaN, lastShotLng = Double.NaN;
    private long lastShotTime = 0;
    private boolean shooting = false;
    private int photoCount = 0;
    private boolean cameraReady = false;
    private boolean recording = false;
    private long stateStartTime;

    private enum State { TAKING_OFF, RISING, TO_FIRST_POINT, TO_SCAN_ALT, PREPARING_CAMERA, SCANNING, FINISHING, IDLE }
    private State state = State.IDLE;

    private final Context appContext;
    private String missionId;
    private boolean missionLogOpen = false;

    public FlightMissionManager(Context context) {
        appContext = context.getApplicationContext();
        if (DJISDKManager.getInstance().getProduct() instanceof Aircraft) {
            Aircraft aircraft = (Aircraft) DJISDKManager.getInstance().getProduct();
            flightController = aircraft.getFlightController();
            camera = aircraft.getCamera();
            gimbal = aircraft.getGimbal();
        }
    }

    public boolean isRunning() {
        return running;
    }

    /** Mã chuyến bay đang/vừa chạy (vd M_20261122_101530), null nếu chưa bay. */
    public String getMissionId() {
        return missionId;
    }

    /** MainActivity chuyển tiếp % pin từ Battery state callback (mỗi pin chỉ có 1 callback). */
    public void onBatteryUpdate(int percent) {
        batteryPercent = percent;
    }

    // Độ cao bay ra (tới ruộng) và bay về Home do người dùng đặt
    private float transitAltSetting = TRANSIT_ALT_DEFAULT;
    private int returnAltSetting = 30;
    public static final int RETURN_ALT_MIN = 20;  // DJI không nhận độ cao RTH dưới 20 m
    public static final int RETURN_ALT_MAX = 120;

    /** Độ cao bay từ chỗ cất cánh tới ruộng (m). Không thấp hơn độ cao quét. */
    public void setTransitAltitude(float meters) {
        transitAltSetting = Math.max(0f, Math.min(120f, meters));
    }

    /** Độ cao drone bay về Home (ghi vào Go Home Height của DJI trước khi bay). */
    public void setReturnAltitude(int meters) {
        returnAltSetting = Math.max(RETURN_ALT_MIN, Math.min(RETURN_ALT_MAX, meters));
    }

    /** Ngưỡng pin (%) để dừng quét và về nhà. */
    public void setLowBatteryThreshold(int percent) {
        lowBatteryPercent = Math.max(15, Math.min(80, percent));
    }

    /**
     * @param photoDistance chụp 1 ảnh mỗi photoDistance mét (chỉ dùng khi chụp ảnh)
     */
    public void startMappingMission(List<LocationCoordinate2D> gridPoints, float scanAltitude, float speed,
                                    boolean video, double photoDistance, MissionListener l) {
        inspectMode = false;
        startInternal(gridPoints, scanAltitude, speed, video, photoDistance, l, null);
    }

    /**
     * Bay tiếp nhiệm vụ đang dở: bay thẳng tới chỗ đã dừng rồi quét phần còn lại
     * (cùng mission_id, số ảnh cộng dồn).
     */
    public void resumeMission(MissionCheckpoint cp, MissionListener l) {
        inspectMode = false;
        List<LocationCoordinate2D> list = new java.util.ArrayList<>();
        if (cp.scanStarted && cp.nextIndex > 0) {
            list.add(new LocationCoordinate2D(cp.resumeLat, cp.resumeLng));
            list.addAll(cp.waypoints.subList(cp.nextIndex, cp.waypoints.size()));
        } else {
            list.addAll(cp.waypoints); // chưa kịp quét -> bay lại từ đầu
        }
        startInternal(list, cp.scanAltitude, cp.speed, cp.video, cp.photoDistance, l, cp);
    }

    // =========================================================
    // GIÁM SÁT: bay tới đúng vị trí 1 ảnh cũ, chụp lại 1 tấm mới rồi về Home
    // =========================================================
    /** Đang bay giám sát 1 điểm (không lưu/xóa tiến độ nhiệm vụ quét dở). */
    private boolean inspectMode = false;
    /** Hướng mũi + góc gimbal của ảnh cũ (NaN = giữ hướng hiện tại / nhìn thẳng xuống). */
    private float inspectYaw = Float.NaN, inspectPitch = Float.NaN;

    public boolean isInspecting() {
        return running && inspectMode;
    }

    /**
     * Bay tới (lat, lng) ở độ cao altitude (m, so với điểm cất cánh), xoay mũi theo yaw, gimbal theo pitch,
     * chụp 1 ảnh (tải về điện thoại + đẩy Cloud nếu đã cấu hình) rồi bay về Home.
     */
    public void startInspection(double lat, double lng, float altitude, float yaw, float gimbalPitch,
                                float speed, MissionListener l) {
        if (running) { if (l != null) l.onFinished(false, "Đang có nhiệm vụ khác chạy!"); return; }
        inspectMode = true;
        inspectYaw = Float.isNaN(yaw) ? Float.NaN : normalizeYaw(yaw);
        inspectPitch = Float.isNaN(gimbalPitch) ? Float.NaN : Math.max(-90f, Math.min(0f, gimbalPitch));
        List<LocationCoordinate2D> pts = new java.util.ArrayList<>();
        LocationCoordinate2D p = new LocationCoordinate2D(lat, lng);
        pts.add(p);
        pts.add(p); // 2 điểm trùng nhau: tới nơi -> chụp 1 ảnh -> xong
        startInternal(pts, Math.max(2f, Math.min(120f, altitude)), speed, false, 1000.0, l, null);
        if (!running) inspectMode = false;
    }

    private void startInternal(List<LocationCoordinate2D> gridPoints, float scanAltitude, float speed,
                               boolean video, double photoDistance, MissionListener l, MissionCheckpoint cp) {
        this.listener = l;
        if (running) { status("Nhiệm vụ đang chạy!"); return; }
        if (flightController == null || flightController.getState() == null) {
            finish(false, "Chưa kết nối được bộ điều khiển bay!");
            return;
        }
        if (gridPoints == null || gridPoints.size() < 2) {
            finish(false, "Chưa có lộ trình bay!");
            return;
        }
        FlightControllerState st = flightController.getState();
        GPSSignalLevel gps = st.getGPSSignalLevel();
        if (gps == null || gps == GPSSignalLevel.NONE || gps == GPSSignalLevel.LEVEL_0
                || gps == GPSSignalLevel.LEVEL_1 || gps == GPSSignalLevel.LEVEL_2) {
            finish(false, "GPS yếu (" + gps + "). Cần GPS mạnh hơn để bay tự động!");
            return;
        }

        waypoints = gridPoints;
        wpIndex = 0;
        scanAlt = scanAltitude;
        transitAlt = Math.max(scanAltitude, transitAltSetting);
        cruiseSpeed = speed;
        videoMode = video;
        this.photoDistance = Math.max(1.0, photoDistance);
        photoCount = 0;
        shooting = false;
        cameraReady = false;
        recording = false;
        lastShotLat = Double.NaN;
        lastShotTime = 0;
        holding = false;
        shotFired = false;
        transferring = false;
        setupUploader();
        running = true;
        if (cp == null) {
            fullPlan = gridPoints;
            resumed = false;
            resumeOffset = 0;
            // Ghi lại chuyến bay để ảnh/video upload sau này được gắn đúng mission_id
            missionId = MissionLog.start(appContext, gridPoints, scanAltitude, video);
        } else {
            fullPlan = cp.waypoints;
            resumed = cp.scanStarted && cp.nextIndex > 0;
            resumeOffset = resumed ? cp.nextIndex - 1 : 0;
            photoCount = cp.photoCount;
            lastShotLat = cp.lastShotLat; // ảnh tiếp theo cách ảnh cuối đúng photoDistance
            lastShotLng = cp.lastShotLng;
            missionId = MissionLog.start(appContext, cp.missionId, gridPoints, scanAltitude, video);
        }
        missionLogOpen = true;

        setGimbalAngle(0f);

        // Đặt độ cao bay về (RTH) trước, để lúc xong / pin yếu drone về đúng độ cao người dùng chọn
        final int returnAlt = returnAltSetting;
        status(String.format(Locale.US, "Đặt độ cao bay về %d m...", returnAlt));
        flightController.setGoHomeHeightInMeters(returnAlt, hErr -> handler.post(() -> {
            if (!running) return;
            if (hErr != null) {
                finish(false, "Không đặt được độ cao bay về " + returnAlt + " m: " + hErr.getDescription());
                return;
            }
            launch(flightController.getState());
        }));
    }

    private void launch(FlightControllerState st) {
        if (st != null && st.isFlying()) {
            enableVirtualStick();
        } else {
            setState(State.TAKING_OFF);
            status("Đang cất cánh...");
            flightController.startTakeoff(err -> {
                if (err != null) {
                    finish(false, "Không cất cánh được: " + err.getDescription());
                    return;
                }
                waitForTakeoff(0);
            });
        }
    }

    private void waitForTakeoff(int tries) {
        handler.postDelayed(() -> {
            if (!running) return;
            FlightControllerState st = flightController.getState();
            if (st != null && st.isFlying() && st.getAircraftLocation() != null
                    && st.getAircraftLocation().getAltitude() >= 1.0f) {
                enableVirtualStick();
            } else if (tries > 40) { // ~20s
                finish(false, "Cất cánh quá lâu, đã hủy nhiệm vụ!");
            } else {
                waitForTakeoff(tries + 1);
            }
        }, 500);
    }

    private void enableVirtualStick() {
        flightController.setVirtualStickModeEnabled(true, err -> {
            if (err != null) {
                finish(false, "Không bật được Virtual Stick: " + err.getDescription());
                return;
            }
            // Hệ GROUND: roll = vận tốc hướng Bắc, pitch = vận tốc hướng Đông (quy ước DJI).
            // (Code cũ dùng BODY + pitch -> drone bay ngang sang phải thay vì bay tới điểm.)
            flightController.setRollPitchCoordinateSystem(FlightCoordinateSystem.GROUND);
            flightController.setRollPitchControlMode(RollPitchControlMode.VELOCITY);
            flightController.setVerticalControlMode(VerticalControlMode.POSITION);
            flightController.setYawControlMode(YawControlMode.ANGLE);
            flightController.setVirtualStickAdvancedModeEnabled(true);
            setState(State.RISING);
            status(String.format(Locale.US, "Bay lên độ cao di chuyển %.0fm...", transitAlt));
            startLoop();
        });
    }

    private void startLoop() {
        loop = new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                try {
                    tick();
                } catch (Exception e) {
                    Log.e(TAG, "tick", e);
                }
                if (running) handler.postDelayed(this, LOOP_MS);
            }
        };
        handler.post(loop);
    }

    private void tick() {
        FlightControllerState st = flightController.getState();
        if (st == null || st.getAircraftLocation() == null) return;

        double lat = st.getAircraftLocation().getLatitude();
        double lng = st.getAircraftLocation().getLongitude();
        float alt = st.getAircraftLocation().getAltitude();
        float yaw = (float) st.getAttitude().yaw;
        // Giám sát: quay mũi đúng hướng ảnh cũ ngay từ lúc bay tới
        if (inspectMode && !Float.isNaN(inspectYaw) && state != State.RISING) yaw = inspectYaw;

        boolean active = state == State.RISING || state == State.TO_FIRST_POINT || state == State.TO_SCAN_ALT
                || state == State.PREPARING_CAMERA || state == State.SCANNING;

        // Pin xuống ngưỡng -> lưu tiến độ, dừng camera, tự bay về Home
        if (active && batteryPercent > 0) {
            lastThreshold = batteryThreshold(st);
            if (batteryPercent <= lastThreshold && inspectMode) {
                abort(String.format(Locale.US, "Pin yếu (%d%%) — dừng giám sát, drone đang bay về Home.",
                        batteryPercent), true);
                return;
            }
            if (batteryPercent <= lastThreshold) {
                MissionCheckpoint cp = saveCheckpoint(lat, lng, "Pin yếu");
                abort(String.format(Locale.US,
                        "Pin yếu (%d%%) — đã lưu tiến độ %d%%, drone đang bay về Home.\n"
                                + "Thay pin đầy rồi bấm TIẾP TỤC để quét phần còn lại.",
                        batteryPercent, cp.progressPercent()), true);
                return;
            }
        }

        // Người dùng/drone tự kích hoạt RTH (vd Smart RTH khi pin yếu) hoặc đáp -> dừng nhiệm vụ
        if (st.isGoingHome()) {
            if (active && !inspectMode) {
                MissionCheckpoint cp = saveCheckpoint(lat, lng, "Drone tự bay về nhà");
                abort("Drone đang tự bay về nhà — đã lưu tiến độ " + cp.progressPercent()
                        + "%, có thể bay tiếp sau.", false);
            } else {
                abort("Drone đang tự bay về nhà, đã dừng nhiệm vụ", false);
            }
            return;
        }
        if (!st.isFlying() && state != State.TAKING_OFF) { abort("Drone đã hạ cánh, đã dừng nhiệm vụ", false); return; }

        switch (state) {
            case RISING:
                send(0, 0, yaw, transitAlt);
                if (Math.abs(alt - transitAlt) < 1.0f) {
                    setState(State.TO_FIRST_POINT);
                    status("Bay tới điểm bắt đầu quét...");
                }
                break;

            case TO_FIRST_POINT: {
                LocationCoordinate2D first = waypoints.get(0);
                if (flyToward(lat, lng, first, MAX_TRANSIT_SPEED, transitAlt, yaw)) {
                    setState(State.TO_SCAN_ALT);
                    // camera nhìn thẳng xuống (giám sát: đúng góc gimbal của ảnh cũ)
                    setGimbalAngle(inspectMode && !Float.isNaN(inspectPitch) ? inspectPitch : -90f);
                    status(String.format(Locale.US, "Về độ cao quét %.0fm...", scanAlt));
                }
                break;
            }

            case TO_SCAN_ALT:
                holdAt(lat, lng, waypoints.get(0), yaw, scanAlt);
                if (Math.abs(alt - scanAlt) < 0.5f) {
                    setState(State.PREPARING_CAMERA);
                    status(videoMode ? "Bật quay video..." : "Chuẩn bị camera chụp ảnh...");
                    prepareCamera();
                }
                break;

            case PREPARING_CAMERA:
                holdAt(lat, lng, waypoints.get(0), yaw, scanAlt);
                if (cameraReady) {
                    setState(State.SCANNING);
                    wpIndex = 1; // đang ở điểm 0, bay tới điểm 1
                    status("Đang bay quét...");
                    progress();
                } else if (System.currentTimeMillis() - stateStartTime > 15000) {
                    abort("Camera không sẵn sàng sau 15s, đã dừng nhiệm vụ", true);
                }
                break;

            case SCANNING: {
                if (!videoMode) {
                    // Đang dừng tại điểm chụp: giữ yên, chưa bay tiếp
                    if (holding) { tickPhotoHold(st, lat, lng); break; }
                    if (shouldShootHere(lat, lng)) {
                        startPhotoHold(lat, lng, yaw);
                        tickPhotoHold(st, lat, lng);
                        break;
                    }
                }
                if (wpIndex >= waypoints.size()) {
                    setState(State.FINISHING);
                    status("Quét xong, dừng camera và bay về...");
                    finishMission();
                    return;
                }
                LocationCoordinate2D target = waypoints.get(wpIndex);
                float speedCap = cruiseSpeed;
                if (!videoMode && !Double.isNaN(lastShotLat)) {
                    // Còn bao xa tới điểm chụp kế tiếp -> giảm tốc trước để dừng êm, không vọt qua
                    double remain = photoDistance - GridPlanner.distance(lat, lng, lastShotLat, lastShotLng);
                    speedCap = (float) Math.min(cruiseSpeed, Math.max(PHOTO_APPROACH_MIN_SPEED, remain * 0.7));
                }
                LocationCoordinate2D from = waypoints.get(Math.max(0, wpIndex - 1));
                if (flyAlong(lat, lng, from, target, speedCap, scanAlt, yaw)) {
                    wpIndex++;
                    progress();
                }
                break;
            }

            case FINISHING:
                send(0, 0, yaw, scanAlt); // giữ yên trong lúc dừng camera
                break;

            default:
                break;
        }
    }

    /** Bay về target. Trả true khi đã tới nơi. */
    private boolean flyToward(double lat, double lng, LocationCoordinate2D target, float maxSpeed, float altitude, float yaw) {
        double dist = GridPlanner.distance(lat, lng, target.getLatitude(), target.getLongitude());
        if (dist < ARRIVE_RADIUS) return true;
        double bearing = bearing(lat, lng, target.getLatitude(), target.getLongitude());
        // Giảm tốc khi gần điểm để không vọt qua góc
        float speed = (float) Math.max(0.5, Math.min(maxSpeed, dist * 0.6));
        double rad = Math.toRadians(bearing);
        float vNorth = (float) (speed * Math.cos(rad));
        float vEast = (float) (speed * Math.sin(rad));
        // Chỉ xoay mũi theo hướng bay khi còn xa, gần điểm giữ hướng cũ để khỏi xoay vòng
        float yawCmd = dist > 3 ? (float) bearing : yaw;
        send(vEast, vNorth, yawCmd, altitude);
        return false;
    }

    /**
     * Bay bám ĐÚNG đoạn thẳng from -> to (không chỉ nhắm hướng về điểm): vận tốc dọc đường + vận tốc
     * ngang kéo về đường khi bị gió đẩy lệch, giảm tốc dần theo quãng còn lại để dừng đúng điểm cuối
     * (không vọt qua bờ lúc quay đầu). Mũi drone giữ theo hướng đoạn, không lắc theo sai số GPS.
     * Trả true khi đã tới điểm cuối.
     */
    private boolean flyAlong(double lat, double lng, LocationCoordinate2D from, LocationCoordinate2D to,
                             float maxSpeed, float altitude, float yaw) {
        // Tọa độ cục bộ (m) gốc tại from: x = Đông, y = Bắc
        double mLat = 110_574.0;
        double mLng = 111_320.0 * Math.cos(Math.toRadians(from.getLatitude()));
        double tx = (to.getLongitude() - from.getLongitude()) * mLng;
        double ty = (to.getLatitude() - from.getLatitude()) * mLat;
        double px = (lng - from.getLongitude()) * mLng;
        double py = (lat - from.getLatitude()) * mLat;
        double segLen = Math.hypot(tx, ty);
        if (segLen < 0.5) return flyToward(lat, lng, to, maxSpeed, altitude, yaw);

        double ux = tx / segLen, uy = ty / segLen;          // hướng dọc đường
        double along = px * ux + py * uy;                   // đã đi được dọc đường
        double cross = -px * uy + py * ux;                  // lệch ngang (+ = bên trái)
        double remain = segLen - along;
        double distToEnd = Math.hypot(tx - px, ty - py);
        // Giám sát 1 ảnh giữ đúng hướng ảnh cũ; quét thì mũi theo hướng đoạn
        float segYaw = inspectMode ? yaw : (float) Math.toDegrees(Math.atan2(ux, uy)); // 0 = Bắc, 90 = Đông

        if (distToEnd < SCAN_ARRIVE_RADIUS
                || (remain < SCAN_ARRIVE_RADIUS && Math.abs(cross) < SCAN_ARRIVE_MAX_CROSS)) {
            return true;
        }
        if (remain < 0) {
            // Đã vọt qua cuối đường (gió đuôi): quay về đúng điểm cuối rồi mới sang đường kế
            holdAt(lat, lng, to, segYaw, altitude);
            return distToEnd < ARRIVE_RADIUS;
        }

        // Tốc độ dọc đường: giảm dần theo v = sqrt(2·a·s) để dừng êm tại điểm cuối
        double vAlong = Math.min(maxSpeed, Math.sqrt(2 * SCAN_DECEL * remain));
        vAlong = Math.max(SCAN_MIN_SPEED, vAlong);
        // Vận tốc ngang kéo về đường
        double vCross = Math.max(-CROSS_TRACK_MAX_SPEED, Math.min(CROSS_TRACK_MAX_SPEED, -cross * CROSS_TRACK_GAIN));

        double vEast = ux * vAlong - uy * vCross;
        double vNorth = uy * vAlong + ux * vCross;
        send((float) vEast, (float) vNorth, segYaw, altitude);
        return false;
    }

    /** Giữ vị trí (tự kéo về điểm nếu bị gió đẩy). */
    private void holdAt(double lat, double lng, LocationCoordinate2D p, float yaw, float altitude) {
        double dist = GridPlanner.distance(lat, lng, p.getLatitude(), p.getLongitude());
        if (dist < ARRIVE_RADIUS) { send(0, 0, yaw, altitude); return; }
        double rad = Math.toRadians(bearing(lat, lng, p.getLatitude(), p.getLongitude()));
        float speed = (float) Math.min(1.5, dist * 0.5);
        send((float) (speed * Math.sin(rad)), (float) (speed * Math.cos(rad)), yaw, altitude);
    }

    /** pitch = vận tốc Đông, roll = vận tốc Bắc (hệ GROUND). */
    private void send(float vEast, float vNorth, float yaw, float altitude) {
        flightController.sendVirtualStickFlightControlData(
                new FlightControlData(vEast, vNorth, normalizeYaw(yaw), altitude), null);
    }

    // =========================================================
    // CAMERA
    // =========================================================
    private boolean isFlat() {
        try { return camera != null && camera.isFlatCameraModeSupported(); } catch (Exception e) { return false; }
    }

    private void prepareCamera() {
        if (camera == null) { cameraReady = true; return; }
        Runnable afterMode = () -> {
            if (videoMode) {
                camera.startRecordVideo(err -> {
                    if (err != null) {
                        abort("Không bật được quay video: " + err.getDescription(), true);
                    } else {
                        recording = true;
                        cameraReady = true;
                        notifyRecording(true);
                    }
                });
            } else if (!isFlat()) {
                camera.setShootPhotoMode(SettingsDefinitions.ShootPhotoMode.SINGLE, err -> cameraReady = true);
            } else {
                cameraReady = true;
            }
        };

        if (isFlat()) {
            // Mini 2 / Air 2...: dùng FlatCameraMode (setMode(SHOOT_PHOTO) không hỗ trợ -> code cũ không chụp được)
            camera.exitPlayback(ignored -> camera.setFlatMode(
                    videoMode ? SettingsDefinitions.FlatCameraMode.VIDEO_NORMAL
                              : SettingsDefinitions.FlatCameraMode.PHOTO_SINGLE,
                    err -> {
                        if (err != null) Log.w(TAG, "setFlatMode: " + err.getDescription());
                        handler.postDelayed(afterMode, 800);
                    }));
        } else {
            camera.setMode(videoMode ? SettingsDefinitions.CameraMode.RECORD_VIDEO
                                     : SettingsDefinitions.CameraMode.SHOOT_PHOTO,
                    err -> {
                        if (err != null) Log.w(TAG, "setMode: " + err.getDescription());
                        handler.postDelayed(afterMode, 800);
                    });
        }
    }

    /** Đã đi đủ photoDistance mét kể từ ảnh trước (ảnh đầu tiên: chụp ngay). */
    private boolean shouldShootHere(double lat, double lng) {
        if (camera == null || shooting) return false;
        if (System.currentTimeMillis() - lastShotTime < MIN_PHOTO_GAP_MS) return false;
        return Double.isNaN(lastShotLat)
                || GridPlanner.distance(lat, lng, lastShotLat, lastShotLng) >= photoDistance;
    }

    /** Bắt đầu phanh lại và giữ yên tại vị trí hiện tại để chụp. */
    private void startPhotoHold(double lat, double lng, float yaw) {
        holding = true;
        shotFired = false;
        holdLat = lat;
        holdLng = lng;
        holdYaw = yaw; // giữ nguyên hướng mũi, không xoay khi chụp
        holdStartMs = System.currentTimeMillis();
        holdReleaseAtMs = 0;
        stableSinceMs = 0;
        lastYawDeg = Double.NaN;
        // Ghi nhận điểm chụp ngay để quãng đường tới ảnh sau tính từ đây
        lastShotLat = lat;
        lastShotLng = lng;
    }

    /**
     * Mỗi vòng lặp khi đang dừng chụp: giữ yên -> đợi ổn định -> bấm chụp -> đợi ghi xong -> bay tiếp.
     */
    private void tickPhotoHold(FlightControllerState st, double lat, double lng) {
        // Đứng yên tại chỗ (DJI tự giữ vị trí bằng GPS khi vận tốc = 0). Chỉ kéo về khi trôi quá xa,
        // vì vừa phanh xong mà kéo ngược lại thì drone lắc qua lắc lại -> ảnh nhòe.
        double drift = GridPlanner.distance(lat, lng, holdLat, holdLng);
        if (drift > PHOTO_HOLD_PULLBACK_M) {
            holdAt(lat, lng, new LocationCoordinate2D(holdLat, holdLng), holdYaw, scanAlt);
        } else {
            send(0, 0, holdYaw, scanAlt);
        }
        long now = System.currentTimeMillis();

        if (!shotFired) {
            double vx = st.getVelocityX(), vy = st.getVelocityY(), vz = st.getVelocityZ();
            double hSpeed = Math.sqrt(vx * vx + vy * vy);
            double tilt = 0, yawRate = 0;
            if (st.getAttitude() != null) {
                tilt = Math.max(Math.abs(st.getAttitude().pitch), Math.abs(st.getAttitude().roll));
                double yawNow = st.getAttitude().yaw;
                if (!Double.isNaN(lastYawDeg) && now > lastYawMs) {
                    double dYaw = Math.abs(((yawNow - lastYawDeg + 540) % 360) - 180);
                    yawRate = dYaw * 1000.0 / (now - lastYawMs);
                }
                lastYawDeg = yawNow;
                lastYawMs = now;
            }
            boolean stillNow = hSpeed < PHOTO_SETTLE_SPEED && Math.abs(vz) < PHOTO_SETTLE_VZ
                    && tilt < PHOTO_SETTLE_TILT_DEG && yawRate < PHOTO_SETTLE_YAW_RATE;
            if (!stillNow) stableSinceMs = 0;
            else if (stableSinceMs == 0) stableSinceMs = now;

            long held = now - holdStartMs;
            boolean stable = held >= PHOTO_STABILIZE_MS && stableSinceMs > 0
                    && now - stableSinceMs >= PHOTO_STABLE_WINDOW_MS;
            if (stable || held >= PHOTO_MAX_HOLD_MS) {
                if (!stable) Log.w(TAG, String.format(Locale.US,
                        "Chụp khi chưa đứng yên hẳn (%.2f m/s, nghiêng %.1f°, xoay %.1f°/s)", hSpeed, tilt, yawRate));
                fireHoldShot();
            }
            return;
        }

        // Camera không phản hồi -> bỏ qua ảnh này, bay tiếp
        if (shooting && now - shotFiredMs > PHOTO_SHOT_TIMEOUT_MS) {
            Log.w(TAG, "Camera không phản hồi, bỏ qua ảnh");
            shooting = false;
            holdReleaseAtMs = now;
        }
        if (!shooting && !transferring && holdReleaseAtMs > 0 && now >= holdReleaseAtMs) {
            holding = false; // vòng lặp sau sẽ bay tiếp tới waypoint
        }
    }

    private void fireHoldShot() {
        fireHoldShot(true);
    }

    /** retry = true: chụp lỗi thì đưa camera về chế độ chụp ảnh rồi thử lại đúng 1 lần. */
    private void fireHoldShot(boolean retry) {
        shotFired = true;
        shooting = true;
        shotFiredMs = System.currentTimeMillis();
        lastShotTime = shotFiredMs;
        camera.startShootPhoto(err -> handler.post(() -> {
            if (!running) { shooting = false; return; }
            if (err == null) {
                shooting = false;
                holdReleaseAtMs = System.currentTimeMillis() + PHOTO_AFTER_SHOT_MS;
                photoCount++;
                progress();
                if (uploader != null) transferShotToCloud();
                return;
            }
            Log.w(TAG, "Chụp lỗi: " + err.getDescription());
            if (retry) {
                // Thường do camera còn kẹt ở Playback / sai chế độ -> đặt lại chế độ chụp rồi bấm lại
                status("Chụp lỗi (" + err.getDescription() + "), đặt lại camera và chụp lại...");
                shotFiredMs = System.currentTimeMillis() + 4000; // tránh timeout trong lúc đổi chế độ
                restorePhotoMode(() -> { if (running && holding) fireHoldShot(false); });
            } else {
                shooting = false;
                holdReleaseAtMs = System.currentTimeMillis() + PHOTO_AFTER_SHOT_MS;
                status("Không chụp được ảnh tại điểm này: " + err.getDescription());
            }
        }));
    }

    /** Đưa camera về chế độ chụp ảnh đơn (thoát Playback / MEDIA_DOWNLOAD nếu đang kẹt). */
    private void restorePhotoMode(Runnable then) {
        Runnable done = () -> handler.postDelayed(then, 800);
        try {
            if (isFlat()) {
                camera.exitPlayback(ignored -> camera.setFlatMode(SettingsDefinitions.FlatCameraMode.PHOTO_SINGLE,
                        err -> {
                            if (err != null) Log.w(TAG, "setFlatMode: " + err.getDescription());
                            handler.post(done);
                        }));
            } else {
                camera.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO, err -> {
                    if (err != null) Log.w(TAG, "setMode SHOOT_PHOTO: " + err.getDescription());
                    camera.setShootPhotoMode(SettingsDefinitions.ShootPhotoMode.SINGLE, e2 -> handler.post(done));
                });
            }
        } catch (Exception e) {
            Log.w(TAG, "restorePhotoMode", e);
            handler.post(done);
        }
    }

    /** Chỉ dùng cho chế độ chụp đứng yên và khi đã cấu hình Cloudinary. */
    private void setupUploader() {
        if (uploader != null) uploader.detach();
        uploader = null;
        if (videoMode || !(cloudUpload || inspectMode) || camera == null) return;
        if (!WpPhotoUploader.isAvailable(appContext)) {
            status("Chưa cấu hình Cloud -> ảnh chỉ lưu vào thẻ nhớ");
            return;
        }
        uploader = new WpPhotoUploader(appContext, camera, isFlat());
        uploader.setUploadListener((up, failed, pending, msg) -> {
            if (listener != null) handler.post(() -> listener.onCloudUpload(up, failed, pending, msg));
            if (msg != null && msg.startsWith("Lỗi Cloud") && running) handler.post(() -> status(msg));
        });
        uploader.attach();
    }

    /** Drone giữ yên trong lúc tải ảnh vừa chụp; tải xong (camera về chế độ chụp) mới bay tiếp. */
    private void transferShotToCloud() {
        transferring = true;
        status("Đang tải ảnh " + photoCount + " để đẩy lên Cloud...");
        uploader.transferLatest((ok, msg) -> {
            transferring = false;
            holdReleaseAtMs = System.currentTimeMillis() + PHOTO_AFTER_SHOT_MS;
            if (!ok) {
                Log.w(TAG, "Upload WP: " + msg);
                if (running) status("Ảnh " + photoCount + ": " + msg);
            } else if (running) {
                status("Đang bay quét...");
            }
        });
    }

    private void releaseUploader() {
        transferring = false;
        if (uploader != null) uploader.detach(); // upload đang chạy nền vẫn tiếp tục
    }

    /** Đợi tối đa ~30s cho ảnh đang tải về điện thoại xong rồi mới dừng camera. */
    private void stopCameraAfterTransfer(Runnable then, int tries) {
        if ((transferring || shooting) && tries < 60) {
            handler.postDelayed(() -> stopCameraAfterTransfer(then, tries + 1), 500);
            return;
        }
        stopCamera(then);
    }

    private void stopCamera(Runnable then) {
        if (uploader != null) {
            uploader.cancel(); // đang tải dở -> trả camera về chế độ chụp
            transferring = false;
        }
        if (camera != null && recording) {
            camera.stopRecordVideo(err -> handler.post(() -> {
                recording = false;
                notifyRecording(false);
                then.run();
            }));
        } else {
            // Đợi ảnh cuối ghi xong
            handler.postDelayed(then, shooting ? 2000 : 300);
        }
    }

    // =========================================================
    // KẾT THÚC / DỪNG
    // =========================================================
    private void finishMission() {
        if (inspectMode) {
            // Không đụng tới tiến độ nhiệm vụ quét đang dở. Đợi ảnh tải về điện thoại xong mới về.
            stopCameraAfterTransfer(() -> {
                setGimbalAngle(0f);
                goHome(photoCount > 0 ? "Đã chụp lại ảnh giám sát. Drone đang bay về."
                        : "Không chụp được ảnh giám sát. Drone đang bay về.", photoCount > 0);
            }, 0);
            return;
        }
        MissionCheckpoint.clear(appContext); // quét xong toàn bộ -> không còn gì để bay tiếp
        stopCamera(() -> {
            setGimbalAngle(0f);
            goHome(String.format(Locale.US, "Hoàn thành quét! %s Drone đang bay về.",
                    videoMode ? "Đã lưu video vào thẻ nhớ." : "Đã chụp " + photoCount + " ảnh."), true);
        });
    }

    /** Người dùng bấm DỪNG: dừng camera, thoát Virtual Stick và bay về nhà. */
    public void stopAndReturnHome() {
        if (!running) {
            startReturnToHome();
            return;
        }
        if (!inspectMode) MissionCheckpoint.clear(appContext); // người dùng chủ động dừng -> bỏ tiến độ cũ
        abort("Đã dừng nhiệm vụ, drone đang bay về nhà", true);
    }

    private void abort(String msg, boolean goHome) {
        if (!running) return;
        setState(State.FINISHING);
        stopCamera(() -> {
            setGimbalAngle(0f);
            if (goHome) goHome(msg, false);
            else {
                stopLoopAndStick();
                finish(false, msg);
            }
        });
    }

    private void goHome(String msg, boolean completed) {
        stopLoopAndStick();
        if (flightController != null) {
            flightController.startGoHome(err -> handler.post(() -> finish(completed,
                    err == null ? msg : msg + " (RTH lỗi: " + err.getDescription() + ")")));
        } else {
            finish(completed, msg);
        }
    }

    private void stopLoopAndStick() {
        running = false;
        if (loop != null) handler.removeCallbacks(loop);
        if (flightController != null) flightController.setVirtualStickModeEnabled(false, null);
    }

    private void finish(boolean completed, String msg) {
        running = false;
        releaseUploader();
        endMissionLog(completed);
        state = State.IDLE;
        Log.d(TAG, msg);
        if (listener != null) handler.post(() -> listener.onFinished(completed, msg));
    }

    /** Nút RTH trên màn hình chính. */
    public void startReturnToHome() {
        if (running) { stopAndReturnHome(); return; }
        if (flightController == null) return;
        flightController.setVirtualStickModeEnabled(false, err ->
                flightController.startGoHome(e -> Log.d(TAG, "RTH: " + (e == null ? "OK" : e.getDescription()))));
    }

    // =========================================================
    // PIN YẾU: LƯU TIẾN ĐỘ ĐỂ BAY TIẾP
    // =========================================================
    /** max(ngưỡng người dùng đặt, mức pin drone tính là cần để về nhà + dự phòng). */
    private int batteryThreshold(FlightControllerState st) {
        int need = 0;
        try {
            if (st.getGoHomeAssessment() != null) {
                need = st.getGoHomeAssessment().getBatteryPercentageNeededToGoHome();
            }
        } catch (Exception ignored) {}
        return Math.max(lowBatteryPercent, need > 0 ? need + RTH_BATTERY_MARGIN : 0);
    }

    /** waypoints[i] -> chỉ số trong lộ trình gốc. */
    private int toPlanIndex(int i) {
        return resumed ? resumeOffset + i : i;
    }

    private MissionCheckpoint saveCheckpoint(double lat, double lng, String reason) {
        MissionCheckpoint cp = new MissionCheckpoint();
        cp.missionId = missionId;
        cp.waypoints = new java.util.ArrayList<>(fullPlan);
        if (state == State.SCANNING) {
            // Đang quét: bay tiếp từ đúng vị trí hiện tại tới waypoint đang hướng tới
            cp.scanStarted = true;
            cp.nextIndex = Math.min(toPlanIndex(Math.max(1, wpIndex)), fullPlan.size() - 1);
            cp.resumeLat = lat;
            cp.resumeLng = lng;
        } else if (resumed) {
            // Đang bay tiếp mà chưa tới chỗ dừng lần trước -> giữ nguyên chỗ dừng cũ
            cp.scanStarted = true;
            cp.nextIndex = toPlanIndex(1);
            cp.resumeLat = waypoints.get(0).getLatitude();
            cp.resumeLng = waypoints.get(0).getLongitude();
        } else {
            // Chưa bắt đầu quét -> lần sau bay lại từ đầu
            cp.scanStarted = false;
            cp.nextIndex = 0;
            cp.resumeLat = fullPlan.get(0).getLatitude();
            cp.resumeLng = fullPlan.get(0).getLongitude();
        }
        cp.lastShotLat = lastShotLat;
        cp.lastShotLng = lastShotLng;
        cp.scanAltitude = scanAlt;
        cp.speed = cruiseSpeed;
        cp.video = videoMode;
        cp.photoDistance = photoDistance;
        cp.photoCount = photoCount;
        cp.batteryAtPause = batteryPercent;
        cp.reason = reason;
        cp.savedAtMs = System.currentTimeMillis();
        cp.save(appContext);
        Log.d(TAG, "Đã lưu tiến độ: điểm " + cp.nextIndex + "/" + fullPlan.size() + " (" + reason + ")");
        if (listener != null) handler.post(() -> listener.onCheckpointSaved(cp));
        return cp;
    }

    /** Ghi giờ kết thúc chuyến bay (chỉ 1 lần cho mỗi chuyến). */
    private void endMissionLog(boolean completed) {
        if (!missionLogOpen) return;
        missionLogOpen = false;
        MissionLog.end(appContext, missionId, photoCount, completed);
    }

    public void cancelMission() {
        boolean wasRunning = running;
        running = false;
        releaseUploader();
        endMissionLog(false);
        if (loop != null) handler.removeCallbacks(loop);
        if (wasRunning && recording && camera != null) {
            camera.stopRecordVideo(null);
            recording = false;
            notifyRecording(false);
        }
        if (flightController != null) flightController.setVirtualStickModeEnabled(false, null);
    }

    // =========================================================
    // TIỆN ÍCH
    // =========================================================
    public void setGimbalAngle(float pitchAngle) {
        if (gimbal != null) {
            gimbal.rotate(new Rotation.Builder().mode(RotationMode.ABSOLUTE_ANGLE).pitch(pitchAngle).time(1).build(), null);
        }
    }

    private void setState(State s) {
        state = s;
        stateStartTime = System.currentTimeMillis();
    }

    private void status(String s) {
        Log.d(TAG, s);
        if (listener != null) handler.post(() -> listener.onStatus(s));
    }

    private void notifyRecording(boolean rec) {
        if (listener != null) handler.post(() -> listener.onRecordingChanged(rec));
    }

    private void progress() {
        if (listener != null) {
            // Báo theo chỉ số lộ trình gốc (khi bay tiếp, waypoints chỉ là phần còn lại)
            int idx = Math.min(toPlanIndex(Math.min(wpIndex, waypoints.size())), fullPlan.size());
            int total = fullPlan.size();
            int pc = photoCount;
            handler.post(() -> listener.onProgress(idx, total, pc));
        }
    }

    private static float normalizeYaw(float y) {
        while (y > 180) y -= 360;
        while (y < -180) y += 360;
        return y;
    }

    private static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1), phi2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dl);
        return Math.toDegrees(Math.atan2(y, x));
    }
}
