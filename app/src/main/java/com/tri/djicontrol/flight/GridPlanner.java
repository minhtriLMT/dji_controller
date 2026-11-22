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
 * - Đường đầu/cuối cách mép ruộng nửa khoảng cách để ảnh phủ sát mép.
 */
public final class GridPlanner {

    private static final double M_PER_DEG_LAT = 110_574.0;

    public static final class Plan {
        public final List<LocationCoordinate2D> waypoints;
        public final int lineCount;
        public final double lengthMeters;

        Plan(List<LocationCoordinate2D> waypoints, int lineCount, double lengthMeters) {
            this.waypoints = waypoints;
            this.lineCount = lineCount;
            this.lengthMeters = lengthMeters;
        }
    }

    private GridPlanner() {}

    /** @param lats, lngs các góc ruộng (>= 3 điểm), spacing khoảng cách giữa 2 đường bay (m). */
    public static Plan plan(double[] lats, double[] lngs, double spacing) {
        int n = lats.length;
        List<LocationCoordinate2D> out = new ArrayList<>();
        if (n < 3 || spacing <= 0) return new Plan(out, 0, 0);

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

        // Sắp góc theo vòng quanh tâm -> đa giác không tự cắt
        List<double[]> ring = new ArrayList<>();
        Collections.addAll(ring, p);
        ring.sort((a, b) -> Double.compare(Math.atan2(a[1], a[0]), Math.atan2(b[1], b[0])));

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
        int lines = Math.max(1, (int) Math.ceil(height / spacing));
        double step = height / lines; // chia đều để 2 mép cách đều nhau, step <= spacing
        boolean leftToRight = true;
        List<double[]> local = new ArrayList<>();
        int lineCount = 0;

        for (int k = 0; k < lines; k++) {
            double y = minY + step * (k + 0.5);
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
        return new Plan(out, lineCount, length);
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
