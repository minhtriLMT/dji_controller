package com.tri.djicontrol.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.tri.djicontrol.R;

/**
 * Toast theo giao diện app: thẻ tối bo góc, viền + icon màu theo loại (thông tin / thành công / cảnh báo / lỗi),
 * hiện ở giữa phía trên để không che thanh thông số bay. Thông báo mới thay ngay thông báo cũ (không xếp hàng).
 *
 * Dùng giống Toast: {@code AppToast.makeText(ctx, "Đã lưu!", Toast.LENGTH_SHORT).show();}
 * — loại được đoán từ nội dung, hoặc chỉ định rõ bằng {@link #success}, {@link #error}...
 * An toàn khi gọi từ luồng nền (tự chuyển về UI thread).
 */
public final class AppToast {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Toast current;

    private final Context context;
    private final CharSequence text;
    private final int duration;
    private NoticeStyle style;

    private AppToast(Context context, CharSequence text, int duration, NoticeStyle style) {
        this.context = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        this.text = text;
        this.duration = duration;
        this.style = style;
    }

    public static AppToast makeText(Context context, CharSequence text, int duration) {
        return new AppToast(context, text, duration, NoticeStyle.guess(text));
    }

    public static void info(Context c, CharSequence t) { show(c, t, NoticeStyle.INFO); }
    public static void success(Context c, CharSequence t) { show(c, t, NoticeStyle.SUCCESS); }
    public static void warning(Context c, CharSequence t) { show(c, t, NoticeStyle.WARNING); }
    public static void error(Context c, CharSequence t) { show(c, t, NoticeStyle.ERROR); }

    private static void show(Context c, CharSequence t, NoticeStyle s) {
        int dur = s == NoticeStyle.ERROR ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT;
        new AppToast(c, t, dur, s).show();
    }

    public AppToast setStyle(NoticeStyle style) {
        this.style = style;
        return this;
    }

    public void show() {
        if (Looper.myLooper() == Looper.getMainLooper()) showNow();
        else MAIN.post(this::showNow);
    }

    private void showNow() {
        View v = LayoutInflater.from(context).inflate(R.layout.toast_app, null);
        v.setBackground(style.card(context, 14));
        TextView icon = v.findViewById(R.id.tvAppToastIcon);
        icon.setText(style.glyph);
        icon.setTextColor(style.accent);
        icon.setBackground(style.iconCircle(context));
        ((TextView) v.findViewById(R.id.tvAppToastMessage)).setText(text);

        if (current != null) current.cancel();
        Toast t = new Toast(context);
        t.setView(v);
        t.setDuration(duration);
        float d = context.getResources().getDisplayMetrics().density;
        t.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, Math.round(48 * d));
        current = t;
        t.show();
    }
}
