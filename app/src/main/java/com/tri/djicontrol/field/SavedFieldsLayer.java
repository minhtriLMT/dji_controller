package com.tri.djicontrol.field;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import com.google.gson.JsonObject;

import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.FillLayer;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.Property;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.Point;
import org.maplibre.geojson.Polygon;

import java.util.ArrayList;
import java.util.List;

/**
 * Vẽ tất cả ruộng đã lưu lên một bản đồ MapLibre (dùng chung cho bản đồ chọn ruộng và mini map).
 * Màu theo trạng thái: xanh = đã bay xong, vàng = bay dở, xám = chưa bay. Gọi lại {@link #update} để làm mới.
 */
public final class SavedFieldsLayer {

    public static final String SOURCE_ID = "saved-fields-source";
    public static final String LABEL_SOURCE_ID = "saved-fields-label-source";
    public static final String FILL_LAYER_ID = "saved-fields-fill";
    public static final String LINE_LAYER_ID = "saved-fields-line";
    public static final String LABEL_LAYER_ID = "saved-fields-label";
    private static final String IMG_PREFIX = "saved-field-label-";

    private SavedFieldsLayer() { }

    public static int statusColor(String status) {
        if (SavedFieldStore.STATUS_DONE.equals(status)) return 0xFF69F0AE;
        if (SavedFieldStore.STATUS_PARTIAL.equals(status)) return 0xFFFFD54F;
        return 0xFF9AA0A6;
    }

    /**
     * Tạo (lần đầu) hoặc cập nhật lớp ruộng đã lưu.
     *
     * @param excludeId    id ruộng không vẽ (ruộng đang chọn đã được vẽ riêng), có thể null
     * @param belowLayerId chèn các lớp xuống dưới lớp này (nếu có trong style), null = trên cùng
     */
    public static void update(Context ctx, Style style, String excludeId, String belowLayerId) {
        if (style == null || !style.isFullyLoaded()) return;
        float d = ctx.getResources().getDisplayMetrics().density;
        List<Feature> polys = new ArrayList<>();
        List<Feature> labels = new ArrayList<>();
        for (SavedFieldStore.SavedField f : SavedFieldStore.list(ctx)) {
            if (f.id == null || f.id.equals(excludeId) || f.lats == null || f.lats.length < 3) continue;

            List<double[]> pts = new ArrayList<>();
            for (int i = 0; i < f.lats.length; i++) pts.add(new double[]{f.lats[i], f.lngs[i]});
            sortAroundCenter(pts);
            List<Point> ring = new ArrayList<>();
            for (double[] p : pts) ring.add(Point.fromLngLat(p[1], p[0]));
            ring.add(ring.get(0));
            List<List<Point>> rings = new ArrayList<>();
            rings.add(ring);

            int color = statusColor(f.status);
            JsonObject props = new JsonObject();
            props.addProperty("color", String.format("#%06X", color & 0xFFFFFF));
            polys.add(Feature.fromGeometry(Polygon.fromLngLats(rings), props));

            String img = IMG_PREFIX + f.id;
            style.addImage(img, labelBitmap(d, f.name != null ? f.name : "Ruộng", f.statusLabel(), color));
            JsonObject lp = new JsonObject();
            lp.addProperty("img", img);
            labels.add(Feature.fromGeometry(Point.fromLngLat(f.centerLng(), f.centerLat()), lp));
        }

        FeatureCollection polyFc = FeatureCollection.fromFeatures(polys);
        FeatureCollection labelFc = FeatureCollection.fromFeatures(labels);
        GeoJsonSource src = style.getSourceAs(SOURCE_ID);
        GeoJsonSource lblSrc = style.getSourceAs(LABEL_SOURCE_ID);
        if (src != null && lblSrc != null) {
            src.setGeoJson(polyFc);
            lblSrc.setGeoJson(labelFc);
            return;
        }

        style.addSource(new GeoJsonSource(SOURCE_ID, polyFc));
        style.addSource(new GeoJsonSource(LABEL_SOURCE_ID, labelFc));

        FillLayer fill = new FillLayer(FILL_LAYER_ID, SOURCE_ID);
        fill.setProperties(PropertyFactory.fillColor(Expression.get("color")), PropertyFactory.fillOpacity(0.18f));
        LineLayer line = new LineLayer(LINE_LAYER_ID, SOURCE_ID);
        line.setProperties(PropertyFactory.lineColor(Expression.get("color")), PropertyFactory.lineWidth(2f),
                PropertyFactory.lineDasharray(new Float[]{2f, 1.5f}));
        SymbolLayer lbl = new SymbolLayer(LABEL_LAYER_ID, LABEL_SOURCE_ID);
        lbl.setProperties(PropertyFactory.iconImage(Expression.get("img")),
                PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true));

        boolean below = belowLayerId != null && style.getLayer(belowLayerId) != null;
        for (Layer l : new Layer[]{fill, line, lbl}) {
            if (below) style.addLayerBelow(l, belowLayerId);
            else style.addLayer(l);
        }
    }

    /** Ẩn/hiện nhãn tên ruộng (mini map nhỏ thì ẩn cho đỡ rối). */
    public static void setLabelsVisible(Style style, boolean visible) {
        if (style == null) return;
        Layer l = style.getLayer(LABEL_LAYER_ID);
        if (l != null) l.setProperties(PropertyFactory.visibility(visible ? Property.VISIBLE : Property.NONE));
    }

    private static void sortAroundCenter(List<double[]> pts) {
        double cLat = 0, cLng = 0;
        for (double[] p : pts) { cLat += p[0]; cLng += p[1]; }
        final double lat0 = cLat / pts.size(), lng0 = cLng / pts.size();
        pts.sort((a, b) -> Double.compare(
                Math.atan2(a[0] - lat0, a[1] - lng0),
                Math.atan2(b[0] - lat0, b[1] - lng0)));
    }

    /** Nhãn tên ruộng (vẽ thành ảnh để không phụ thuộc font của kiểu bản đồ). */
    private static Bitmap labelBitmap(float d, String name, String status, int color) {
        Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
        title.setColor(0xFFFFFFFF);
        title.setTextSize(12 * d);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        Paint sub = new Paint(Paint.ANTI_ALIAS_FLAG);
        sub.setColor(color);
        sub.setTextSize(9.5f * d);

        if (name.length() > 24) name = name.substring(0, 23) + "…";
        float pad = 8 * d, dot = 7 * d;
        float textW = Math.max(title.measureText(name), sub.measureText(status));
        int w = (int) (pad * 2 + dot + 6 * d + textW);
        int h = (int) (pad * 2 + 12 * d + 11 * d);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0xE6191C21);
        c.drawRoundRect(new RectF(0, 0, w, h), 8 * d, 8 * d, bg);
        bg.setStyle(Paint.Style.STROKE);
        bg.setStrokeWidth(1.2f * d);
        bg.setColor(color);
        c.drawRoundRect(new RectF(0.6f * d, 0.6f * d, w - 0.6f * d, h - 0.6f * d), 8 * d, 8 * d, bg);
        Paint dp = new Paint(Paint.ANTI_ALIAS_FLAG);
        dp.setColor(color);
        c.drawCircle(pad + dot / 2, h / 2f, dot / 2, dp);
        float tx = pad + dot + 6 * d;
        c.drawText(name, tx, pad + 11 * d, title);
        c.drawText(status, tx, pad + 12 * d + 9.5f * d, sub);
        return bmp;
    }
}
