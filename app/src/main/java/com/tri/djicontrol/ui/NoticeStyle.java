package com.tri.djicontrol.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;

import java.util.Locale;

/** Bảng màu + icon dùng chung cho AppToast và AppDialog (khớp giao diện tối, nhấn xanh #69F0AE của app). */
public enum NoticeStyle {
    INFO("i", 0xFF8AB4F8),
    SUCCESS("✓", 0xFF69F0AE),
    WARNING("!", 0xFFFFC107),
    ERROR("✕", 0xFFFF6E66);

    /** Nền thẻ tối dùng chung trong app. */
    static final int CARD_BG = 0xF2191C21;

    public final String glyph;
    public final int accent;

    NoticeStyle(String glyph, int accent) {
        this.glyph = glyph;
        this.accent = accent;
    }

    int withAlpha(int alpha) {
        return (accent & 0x00FFFFFF) | (alpha << 24);
    }

    GradientDrawable card(Context c, float radiusDp) {
        float d = c.getResources().getDisplayMetrics().density;
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD_BG);
        g.setCornerRadius(radiusDp * d);
        g.setStroke(Math.max(1, Math.round(d)), withAlpha(0x55));
        return g;
    }

    GradientDrawable iconCircle(Context c) {
        float d = c.getResources().getDisplayMetrics().density;
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(withAlpha(0x26));
        g.setStroke(Math.round(1.5f * d), accent);
        return g;
    }

    /** Đoán loại thông báo từ nội dung tiếng Việt (để thay Toast cũ mà không phải sửa từng chỗ). */
    public static NoticeStyle guess(CharSequence text) {
        if (text == null) return INFO;
        String s = text.toString().trim().toLowerCase(new Locale("vi"));
        // Kết quả một phần ("Đã xóa 3 file. Thất bại: 1 file") -> cảnh báo
        if (s.startsWith("đã ") && s.contains("thất bại")) return WARNING;
        if (s.contains("lỗi") || s.contains("thất bại") || s.contains("không thể") || s.contains("không cho")
                || s.contains("không được") || s.contains("không nhận") || s.contains("không khả dụng")
                || s.contains("không tìm thấy") || s.startsWith("không ")) {
            return ERROR;
        }
        if (s.startsWith("vui lòng") || s.startsWith("chưa ") || s.startsWith("chọn đủ")
                || s.contains("vui lòng đợi") || s.contains("chưa sẵn sàng") || s.contains("đang có nhiệm vụ")
                || s.contains("đang chạy!") || s.contains("vẫn đang")) {
            return WARNING;
        }
        if (s.startsWith("đã ") || s.contains("thành công") || s.contains("xong")
                || s.contains("hoàn thành") || s.startsWith("tìm thấy")) {
            return SUCCESS;
        }
        return INFO;
    }
}
