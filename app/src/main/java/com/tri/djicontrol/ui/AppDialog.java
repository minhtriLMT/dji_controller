package com.tri.djicontrol.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.tri.djicontrol.R;

/**
 * Hộp thoại thông báo / xác nhận theo giao diện app (thay AlertDialog mặc định của hệ thống).
 * API giống AlertDialog.Builder để thay thế trực tiếp:
 * <pre>
 * new AppDialog(ctx).setStyle(NoticeStyle.ERROR).setDanger(true)
 *     .setTitle("Xóa?").setMessage("...")
 *     .setPositiveButton("XÓA", (d, i) -> ...)
 *     .setNegativeButton("HỦY", null)
 *     .show();
 * </pre>
 */
public final class AppDialog {

    private final Context context;
    private NoticeStyle style = NoticeStyle.INFO;
    private String icon;
    private CharSequence title;
    private CharSequence message;
    private boolean messageSelectable;
    private View customView;
    private CharSequence positiveText, negativeText;
    private DialogInterface.OnClickListener positiveListener, negativeListener;
    private boolean danger;
    private boolean cancelable = true;

    public AppDialog(Context context) {
        this.context = context;
    }

    /** Màu icon + viền thẻ. */
    public AppDialog setStyle(NoticeStyle style) { this.style = style; return this; }

    /** Ký tự icon riêng (mặc định theo style). */
    public AppDialog setIcon(String glyph) { this.icon = glyph; return this; }

    /** Nút chính màu đỏ (thao tác phá hủy: xóa, hủy nhiệm vụ...). */
    public AppDialog setDanger(boolean danger) { this.danger = danger; return this; }

    public AppDialog setTitle(CharSequence title) { this.title = title; return this; }

    public AppDialog setMessage(CharSequence message) { this.message = message; return this; }

    public AppDialog setMessageSelectable(boolean selectable) { this.messageSelectable = selectable; return this; }

    public AppDialog setView(View view) { this.customView = view; return this; }

    public AppDialog setCancelable(boolean cancelable) { this.cancelable = cancelable; return this; }

    public AppDialog setPositiveButton(CharSequence text, DialogInterface.OnClickListener l) {
        positiveText = text; positiveListener = l; return this;
    }

    public AppDialog setNegativeButton(CharSequence text, DialogInterface.OnClickListener l) {
        negativeText = text; negativeListener = l; return this;
    }

    public Dialog show() {
        Dialog d = new Dialog(context, R.style.CfDialog);
        d.setContentView(R.layout.dialog_app_alert);
        d.setCancelable(cancelable);

        View root = d.findViewById(R.id.appDialogRoot);
        root.setBackground(danger ? NoticeStyle.ERROR.card(context, 16) : style.card(context, 16));

        TextView tvIcon = d.findViewById(R.id.tvAppDialogIcon);
        tvIcon.setText(icon != null ? icon : style.glyph);
        tvIcon.setTextColor(style.accent);
        tvIcon.setBackground(style.iconCircle(context));

        TextView tvTitle = d.findViewById(R.id.tvAppDialogTitle);
        tvTitle.setText(title);

        TextView tvMsg = d.findViewById(R.id.tvAppDialogMessage);
        if (message != null && message.length() > 0) {
            tvMsg.setText(message);
            tvMsg.setTextIsSelectable(messageSelectable);
        } else {
            tvMsg.setVisibility(View.GONE);
        }
        if (customView != null) {
            FrameLayout fl = d.findViewById(R.id.flAppDialogCustom);
            fl.setVisibility(View.VISIBLE);
            fl.addView(customView);
        }
        if ((message == null || message.length() == 0) && customView == null) {
            d.findViewById(R.id.svAppDialogBody).setVisibility(View.GONE);
        }

        Button pos = d.findViewById(R.id.btnAppDialogPositive);
        Button neg = d.findViewById(R.id.btnAppDialogNegative);
        if (positiveText == null && negativeText == null) positiveText = "OK";
        if (positiveText != null) {
            pos.setVisibility(View.VISIBLE);
            pos.setText(positiveText);
            if (danger) {
                pos.setBackgroundResource(R.drawable.bg_st_btn_danger);
                pos.setTextColor(context.getColorStateList(R.color.txt_cf_btn_fly_danger));
            } else {
                pos.setBackgroundResource(R.drawable.bg_cf_btn_fly);
                pos.setTextColor(context.getColorStateList(R.color.txt_cf_btn_fly));
            }
            pos.setOnClickListener(v -> {
                d.dismiss();
                if (positiveListener != null) positiveListener.onClick(d, DialogInterface.BUTTON_POSITIVE);
            });
        } else {
            ((ViewGroup.MarginLayoutParams) neg.getLayoutParams()).setMarginEnd(0);
        }
        if (negativeText != null) {
            neg.setVisibility(View.VISIBLE);
            neg.setText(negativeText);
            neg.setOnClickListener(v -> {
                d.dismiss();
                if (negativeListener != null) negativeListener.onClick(d, DialogInterface.BUTTON_NEGATIVE);
            });
        } else {
            ((ViewGroup.MarginLayoutParams) pos.getLayoutParams()).setMarginStart(0);
        }

        d.show();
        if (d.getWindow() != null) {
            DisplayMetrics dm = context.getResources().getDisplayMetrics();
            d.getWindow().setLayout((int) Math.min(dm.widthPixels * 0.9f, 440 * dm.density),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return d;
    }
}
