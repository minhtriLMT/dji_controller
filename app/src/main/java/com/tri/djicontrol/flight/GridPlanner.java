package com.tri.djicontrol.flight;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dji.common.model.LocationCoordinate2D;

/**
 * Tạo đường bay zigzag (kiểu "luống cày") phủ kín khu ruộng 4 góc.
 *
 * - Sắp xếp lại 4 góc theo vòng (người dùng bấm theo thứ tự bất kỳ vẫn đúng).
 * - Các đường quét chạy song song với CẠNH DÀI NHẤT của ruộng -> ít lần quay đầu nhất.
 * - Lề an toàn: thu ruộng vào trong {@code margin} mét ở CẢ 4 cạnh trước khi kẻ đường, nên đường
 *   ngoài cùng và các điểm quay đầu đều cách bờ đúng {@code margin} (camera nhìn xuống vẫn phủ tới bờ).
 *   margin = 0: kiểu cũ, đường đầu/cuối cách mép nửa khoảng cách, điểm quay đầu nằm trên bờ.
 */
public final class GridPlanner {

    private static final double M_PER_DEG_LAT = 110_574.0;

    public static final class Plan {
        public final List<LocationCoordinate2D> waypoints;
        public final int lineCount;
        public final double lengthMeters;
        /** Lề đã áp dụng (m). */
        public final double margin;
        /** true khi lề quá lớn so với ruộng (thu vào thì không còn diện tích để bay). */
        public final boolean marginTooLarge;

        Plan(List<LocationCoordinate2D> waypoints, int lineCount, double lengthMeters,
             double margin, boolean marginTooLarge) {
            this.waypoints = waypoints;
            this.lineCount = lineCount;
            this.lengthMeters = lengthMeters;
            this.margin = margin;
            this.marginTooLarge = marginTooLarge;
        }
    }

    private GridPlanner() {}

    public static Plan plan(double[] lats, double[] lngs, double spacing) {
        return plan(lats, lngs, spacing, 0);
    }

    /**
     * @param lats, lngs các góc ruộng (>= 3 điểm), spacing khoảng cách giữa 2 đường bay (m),
     * @param margin lề an toàn cách bờ (m), 0 = không chừa lề.
     */
    public static Plan plan(double[] lats, double[] lngs, double spacing, double margin) {
        int n = lats.length;
        List<LocationCoordinate2D> out = new ArrayList<>();
        margin = Math.max(0, margin);
        if (n < 3 || spacing <= 0) return new Plan(out, 0, 0, margin, false);

        // Gốc tọa độ cục bộ (mét) tại tâm ruộng
        double lat0 = 0, lng0 = 0;
        for (int i = 0; i < n; i++) { lat0 += lats[i]; lng0 += lngs[i]; }
        lat0 /= n; lng0 /= n;
        double mPerDegLng = 111_320.0 * Math.cos(Math.toRadians(lat0));

        double[][] p = new double[n][2];
        for (int i = 0; i < n; i++) {
            p[i][0] = (lngs[i] - lng0) * mPerDegLng; // x = Đông
            p[i][1] = (lats[i] - lat0) * M_PER_DEG_LAT; // y = Bắc
        }

        // Sắp góc theo vòng quanh tâm -> đa giác không tự cắt (ngược chiều kim đồng hồ)
        List<double[]> ring = new ArrayList<>();
        Collections.addAll(ring, p);
        ring.sort((a, b) -> Double.compare(Math.atan2(a[1], a[0]), Math.atan2(b[1], b[0])));

        // Thu ruộng vào trong để chừa lề cách bờ
        if (margin > 0) {
            List<double[]> inner = inset(ring, margin);
            if (inner == null) return new Plan(out, 0, 0, margin, true);
            ring = inner;
        }

        // Hướng cạnh dài nhất
        double bestLen = -1, theta = 0;
        for (int i = 0; i < n; i++) {
            double[] a = ring.get(i), b = ring.get((i + 1) % n);
            double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
            if (len > bestLen) { bestLen = len; theta = Math.atan2(b[1] - a[1], b[0] - a[0]); }
        }

        // Xoay -theta để đường quét nằm ngang (song song trục x)
        double cos = Math.cos(-theta), sin = Math.sin(-theta);
        double[][] r = new double[n][2];
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            double[] q = ring.get(i);
            r[i][0] = q[0] * cos - q[1] * sin;
            r[i][1] = q[0] * sin + q[1] * cos;
            minY = Math.min(minY, r[i][1]);
            maxY = Math.max(maxY, r[i][1]);
        }

        double height = maxY - minY;
        int lines;
        double[] ys;
        if (margin > 0) {
            // Ruộng đã thu vào: đường ngoài cùng nằm ĐÚNG trên mép trong (cách bờ = margin),
            // các đường giữa chia đều, khoảng cách <= spacing
            lines = Math.max(1, (int) Math.ceil(height / spacing) + 1);
            ys = new double[lines];
            for (int k = 0; k < lines; k++) {
                ys[k] = lines == 1 ? (minY + maxY) / 2 : minY + height * k / (lines - 1);
            }
            // Đẩy nhẹ vào trong để cắt được cạnh (tránh trúng đúng đỉnh)
            final double eps = 0.02;
            if (lines > 1) { ys[0] += eps; ys[lines - 1] -= eps; }
        } else {
            lines = Math.max(1, (int) Math.ceil(height / spacing));
            double step = height / lines; // chia đều để 2 mép cách đều nhau, step <= spacing
            ys = new double[lines];
            for (int k = 0; k < lines; k++) ys[k] = minY + step * (k + 0.5);
        }
        boolean leftToRight = true;
        List<double[]> local = new ArrayList<>();
        int lineCount = 0;

        for (int k = 0; k < lines; k++) {
            double y = ys[k];
            double xMin = Double.MAX_VALUE, xMax = -Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                double[] a = r[i], b = r[(i + 1) % n];
                if ((a[1] <= y && b[1] > y) || (b[1] <= y && a[1] > y)) {
                    double x = a[0] + (y - a[1]) / (b[1] - a[1]) * (b[0] - a[0]);
                    xMin = Math.min(xMin, x);
                    xMax = Math.max(xMax, x);
                }
            }
            if (xMin > xMax) continue;
            if (leftToRight) { local.add(new double[]{xMin, y}); local.add(new double[]{xMax, y}); }
            else { local.add(new double[]{xMax, y}); local.add(new double[]{xMin, y}); }
            leftToRight = !leftToRight;
            lineCount++;
        }

        // Xoay ngược +theta và đổi về lat/lng
        double c2 = Math.cos(theta), s2 = Math.sin(theta);
        double length = 0;
        double[] prev = null;
        for (double[] q : local) {
            double x = q[0] * c2 - q[1] * s2;
            double y = q[0] * s2 + q[1] * c2;
            if (prev != null) length += Math.hypot(x - prev[0], y - prev[1]);
            prev = new double[]{x, y};
            out.add(new LocationCoordinate2D(lat0 + y / M_PER_DEG_LAT, lng0 + x / mPerDegLng));
        }
        return new Plan(out, lineCount, length, margin, false);
    }

    /**
     * Thu đa giác (ngược chiều kim đồng hồ) vào trong d mét: dời mỗi cạnh vào trong d rồi lấy giao điểm
     * 2 cạnh kề làm đỉnh mới. Trả null nếu thu xong bị lật / quá nhỏ (lề lớn hơn ruộng).
     */
    private static List<double[]> inset(List<double[]> ring, double d) {
        int n = ring.size();
        double area0 = signedArea(ring);
        if (Math.abs(area0) < 1e-6) return null;
        double sign = area0 > 0 ? 1 : -1; // CCW: pháp tuyến trong = quay trái 90°
        double[][] ptA = new double[n][], dir = new double[n][];
        for (int i = 0; i < n; i++) {
            double[] a = ring.get(i), b = ring.get((i + 1) % n);
            double dx = b[0] - a[0], dy = b[1] - a[1];
            double len = Math.hypot(dx, dy);
            if (len < 1e-6) return null;
            double ux = dx / len, uy = dy / len;
            double nx = -uy * sign, ny = ux * sign;
            ptA[i] = new double[]{a[0] + nx * d, a[1] + ny * d};
            dir[i] = new double[]{ux, uy};
        }
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int prev = (i - 1 + n) % n;
            double[] hit = intersect(ptA[prev], dir[prev], ptA[i], dir[i]);
            if (hit == null) return null;
            out.add(hit);
        }
        double area1 = signedArea(out);
        // Lật chiều hoặc còn dưới 1 m² -> lề quá lớn
        if (area1 * area0 <= 0 || Math.abs(area1) < 1.0) return null;
        // Mỗi cạnh mới phải cùng hướng cạnh cũ (không bị đảo khi một cạnh ngắn hơn 2*lề)
        for (int i = 0; i < n; i++) {
            double[] a = out.get(i), b = out.get((i + 1) % n);
            if ((b[0] - a[0]) * dir[i][0] + (b[1] - a[1]) * dir[i][1] <= 0) return null;
        }
        return out;
    }

    private static double[] intersect(double[] p1, double[] d1, double[] p2, double[] d2) {
        double cross = d1[0] * d2[1] - d1[1] * d2[0];
        if (Math.abs(cross) < 1e-9) return null; // 2 cạnh song song
        double t = ((p2[0] - p1[0]) * d2[1] - (p2[1] - p1[1]) * d2[0]) / cross;
        return new double[]{p1[0] + d1[0] * t, p1[1] + d1[1] * t};
    }

    private static double signedArea(List<double[]> ring) {
        double s = 0;
        for (int i = 0; i < ring.size(); i++) {
            double[] a = ring.get(i), b = ring.get((i + 1) % ring.size());
            s += a[0] * b[1] - b[0] * a[1];
        }
        return s / 2;
    }

    /** Khoảng cách 2 tọa độ (m). */
    public static double distance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
