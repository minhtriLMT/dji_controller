package com.tri.djicontrol;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Biểu tượng cột sóng (4 cột tăng dần) cho chip RC: số cột sáng + màu theo chất lượng tín hiệu. */
public class SignalBarsDrawable extends Drawable {

    public static final int BAR_COUNT = 4;
    private static final int DIM_COLOR = 0x4DFFFFFF;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final int widthPx, heightPx;
    private final float gapPx, radiusPx;
    private int level = 0;          // số cột sáng: 0..BAR_COUNT
    private int color = DIM_COLOR;

    public SignalBarsDrawable(float density) {
        widthPx = Math.round(16 * density);
        heightPx = Math.round(12 * density);
        gapPx = 1.5f * density;
        radiusPx = 1f * density;
        setBounds(0, 0, widthPx, heightPx);
    }

    /** @param level số cột sáng (0..4); @param color màu cột sáng. */
    public void setSignal(int level, int color) {
        level = Math.max(0, Math.min(BAR_COUNT, level));
        if (this.level == level && this.color == color) return;
        this.level = level;
        this.color = color;
        invalidateSelf();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        android.graphics.Rect b = getBounds();
        float barW = (b.width() - gapPx * (BAR_COUNT - 1)) / BAR_COUNT;
        for (int i = 0; i < BAR_COUNT; i++) {
            float h = b.height() * (i + 1) / (float) BAR_COUNT;
            float left = b.left + i * (barW + gapPx);
            rect.set(left, b.bottom - h, left + barW, b.bottom);
            paint.setColor(i < level ? color : DIM_COLOR);
            canvas.drawRoundRect(rect, radiusPx, radiusPx, paint);
        }
    }

    @Override public int getIntrinsicWidth() { return widthPx; }
    @Override public int getIntrinsicHeight() { return heightPx; }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(@Nullable ColorFilter cf) { paint.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
