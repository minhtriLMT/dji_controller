package com.tri.djicontrol.firebase;

/**
 * Cấu hình Cloudinary (gói miễn phí, không cần thẻ).
 * - CLOUD_NAME: Dashboard -> "Cloud name"
 * - UPLOAD_PRESET: Settings -> Upload -> Upload presets (Signing mode = Unsigned)
 */
public final class CloudConfig {
    public static final String CLOUD_NAME = "or0vke0r";
    public static final String UPLOAD_PRESET = "cvhn49hi";
    public static final String FOLDER = "dji_media";
    /** Ảnh chụp trong lúc bay tự động (WP) được đẩy ngay vào thư mục riêng này. */
    public static final String WP_FOLDER = FOLDER + "/wp_mission";

    private CloudConfig() {}
}
