package com.tri.djicontrol;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Vòng tròn pin cho chip PIN: cung tròn theo % pin, số % ở giữa. percent < 0 = chưa có dữ liệu. */
public class BatteryRingDrawable extends Drawable {

    private static final int TRACK_COLOR = 0x33FFFFFF;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final int sizePx;
    private final float strokePx;
    private int percent = -1;

    public BatteryRingDrawable(float density, float scaledDensity) {
        sizePx = Math.round(21 * density);
        strokePx = 2.2f * density;
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(strokePx);
        track.setColor(TRACK_COLOR);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(strokePx);
        arc.setStrokeCap(Paint.Cap.ROUND);
        text.setColor(0xFFFFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        text.setTextSize(7.5f * scaledDensity);
        setBounds(0, 0, sizePx, sizePx);
    }

    public void setBattery(int percent, int color) {
        if (this.percent == percent && arc.getColor() == color) return;
        this.percent = percent;
        arc.setColor(color);
        invalidateSelf();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        android.graphics.Rect b = getBounds();
        float half = strokePx / 2f;
        oval.set(b.left + half, b.top + half, b.right - half, b.bottom - half);
        canvas.drawOval(oval, track);
        if (percent > 0) {
            canvas.drawArc(oval, -90f, 360f * Math.min(100, percent) / 100f, false, arc);
        }
        String label = percent < 0 ? "--" : String.valueOf(Math.min(100, percent));
        // 100 hơi rộng: thu nhỏ chữ một chút để vừa vòng
        float base = text.getTextSize();
        if (label.length() >= 3) text.setTextSize(base * 0.82f);
        Paint.FontMetrics fm = text.getFontMetrics();
        float y = b.exactCenterY() - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(label, b.exactCenterX(), y, text);
        text.setTextSize(base);
    }

    @Override public int getIntrinsicWidth() { return sizePx; }
    @Override public int getIntrinsicHeight() { return sizePx; }
    @Override public void setAlpha(int alpha) { arc.setAlpha(alpha); track.setAlpha(alpha); text.setAlpha(alpha); }
    @Override public void setColorFilter(@Nullable ColorFilter cf) { arc.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
