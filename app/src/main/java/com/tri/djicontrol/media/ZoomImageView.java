package com.tri.djicontrol.media;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

/**
 * ImageView hỗ trợ phóng to / thu nhỏ bằng 2 ngón tay để xem ảnh rõ hơn.
 * - Chụm / mở 2 ngón: zoom quanh điểm giữa 2 ngón (1x .. 6x)
 * - Kéo 1 ngón khi đang phóng to: di chuyển ảnh (không kéo ra ngoài mép)
 * - Chạm 2 lần: phóng to 3x tại điểm chạm / trở về vừa khung
 * - Khi đặt ảnh mới (thumbnail -> preview) tự động về vừa khung
 */
public class ZoomImageView extends AppCompatImageView {

    private static final float MIN_SCALE = 1f;
    private static final float MAX_SCALE = 6f;
    private static final float DOUBLE_TAP_SCALE = 3f;

    private final Matrix baseMatrix = new Matrix();   // ảnh vừa khung (fitCenter)
    private final Matrix userMatrix = new Matrix();   // zoom/pan của người dùng
    private final Matrix drawMatrix = new Matrix();
    private final float[] values = new float[9];
    private final RectF tmpRect = new RectF();

    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private ValueAnimator animator;

    public ZoomImageView(@NonNull Context context) {
        this(context, null);
    }

    public ZoomImageView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ZoomImageView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        super.setScaleType(ScaleType.MATRIX);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(@NonNull ScaleGestureDetector d) {
                cancelAnim();
                float current = getUserScale();
                float target = clamp(current * d.getScaleFactor(), MIN_SCALE, MAX_SCALE);
                float factor = target / current;
                userMatrix.postScale(factor, factor, d.getFocusX(), d.getFocusY());
                fixBounds();
                apply();
                return true;
            }
        });
        scaleDetector.setQuickScaleEnabled(false);

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(@NonNull MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float dx, float dy) {
                if (scaleDetector.isInProgress() || getUserScale() <= MIN_SCALE + 0.01f) return false;
                cancelAnim();
                userMatrix.postTranslate(-dx, -dy);
                fixBounds();
                apply();
                return true;
            }

            @Override
            public boolean onDoubleTap(@NonNull MotionEvent e) {
                if (getDrawable() == null) return false;
                if (getUserScale() > MIN_SCALE + 0.05f) {
                    animateTo(new Matrix());
                } else {
                    Matrix target = new Matrix();
                    target.postScale(DOUBLE_TAP_SCALE, DOUBLE_TAP_SCALE, e.getX(), e.getY());
                    animateTo(clampMatrix(target));
                }
                return true;
            }
        });
    }

    /* ===== Đặt ảnh / kích thước -> tính lại khung vừa ảnh và reset zoom ===== */

    @Override
    public void setImageDrawable(@Nullable Drawable drawable) {
        super.setImageDrawable(drawable);
        resetZoom();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        resetZoom();
    }

    @Override
    public void setScaleType(ScaleType scaleType) {
        // Luôn dùng MATRIX; bỏ qua scaleType từ XML/code
        super.setScaleType(ScaleType.MATRIX);
    }

    /** Trở về ảnh vừa khung, không phóng to. */
    public void resetZoom() {
        if (userMatrix == null) return; // gọi từ constructor của lớp cha
        cancelAnim();
        userMatrix.reset();
        computeBaseMatrix();
        apply();
    }

    private void computeBaseMatrix() {
        baseMatrix.reset();
        Drawable d = getDrawable();
        int vw = getWidth() - getPaddingLeft() - getPaddingRight();
        int vh = getHeight() - getPaddingTop() - getPaddingBottom();
        if (d == null || vw <= 0 || vh <= 0) return;
        int dw = d.getIntrinsicWidth();
        int dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) return;
        float s = Math.min((float) vw / dw, (float) vh / dh);
        baseMatrix.postScale(s, s);
        baseMatrix.postTranslate(getPaddingLeft() + (vw - dw * s) / 2f, getPaddingTop() + (vh - dh * s) / 2f);
    }

    private void apply() {
        drawMatrix.set(baseMatrix);
        drawMatrix.postConcat(userMatrix);
        setImageMatrix(drawMatrix);
    }

    /* ===== Chạm ===== */

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (getDrawable() == null) return super.onTouchEvent(event);
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        // Đang phóng to hoặc đang chụm 2 ngón -> không cho view cha cướp sự kiện
        boolean zooming = getUserScale() > MIN_SCALE + 0.01f || event.getPointerCount() > 1;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(zooming);
        return true;
    }

    /* ===== Giới hạn ===== */

    private float getUserScale() {
        userMatrix.getValues(values);
        return values[Matrix.MSCALE_X];
    }

    private void fixBounds() {
        userMatrix.set(clampMatrix(userMatrix));
    }

    /** Ép tỉ lệ trong [MIN, MAX] và giữ ảnh không trượt ra khỏi mép khung. */
    private Matrix clampMatrix(Matrix m) {
        Matrix out = new Matrix(m);
        out.getValues(values);
        float s = values[Matrix.MSCALE_X];
        if (s < MIN_SCALE) {
            out.reset();
            return out;
        }
        if (s > MAX_SCALE) {
            float f = MAX_SCALE / s;
            out.postScale(f, f, getWidth() / 2f, getHeight() / 2f);
        }
        Drawable d = getDrawable();
        if (d == null) return out;

        Matrix full = new Matrix(baseMatrix);
        full.postConcat(out);
        tmpRect.set(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        full.mapRect(tmpRect);

        float vw = getWidth(), vh = getHeight();
        float dx = 0, dy = 0;
        if (tmpRect.width() <= vw) dx = (vw - tmpRect.width()) / 2f - tmpRect.left;
        else if (tmpRect.left > 0) dx = -tmpRect.left;
        else if (tmpRect.right < vw) dx = vw - tmpRect.right;

        if (tmpRect.height() <= vh) dy = (vh - tmpRect.height()) / 2f - tmpRect.top;
        else if (tmpRect.top > 0) dy = -tmpRect.top;
        else if (tmpRect.bottom < vh) dy = vh - tmpRect.bottom;

        out.postTranslate(dx, dy);
        return out;
    }

    /* ===== Hiệu ứng chạm 2 lần ===== */

    private void animateTo(Matrix target) {
        cancelAnim();
        float[] from = new float[9];
        float[] to = new float[9];
        userMatrix.getValues(from);
        target.getValues(to);
        float[] cur = new float[9];
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(220);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            for (int i = 0; i < 9; i++) cur[i] = from[i] + (to[i] - from[i]) * t;
            userMatrix.setValues(cur);
            apply();
        });
        animator.start();
    }

    private void cancelAnim() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelAnim();
        super.onDetachedFromWindow();
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
