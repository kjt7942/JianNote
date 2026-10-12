package com.kjt.jiannote;

import com.kjt.jiannote.model.StrokeElem;

/** 도형 생성 및 "펜을 누르고 있으면 도형으로 보정" 인식 */
public class ShapeRecognizer {
    public static final int LINE = 0, ARROW = 1, RECT = 2, ELLIPSE = 3, TRIANGLE = 4;
    public static final String[] NAMES = {"직선", "화살표", "사각형", "원", "삼각형"};

    public static StrokeElem build(int type, float x0, float y0, float x1, float y1, int color, float w) {
        StrokeElem s = new StrokeElem(StrokeElem.PEN, color);
        switch (type) {
            case LINE:
                seg(s, x0, y0, x1, y1, w, true);
                break;
            case ARROW: {
                seg(s, x0, y0, x1, y1, w, true);
                double ang = Math.atan2(y1 - y0, x1 - x0);
                float len = (float) Math.min(60 + w * 2, Math.hypot(x1 - x0, y1 - y0) * 0.4);
                float ax = (float) (x1 - len * Math.cos(ang - 0.45)), ay = (float) (y1 - len * Math.sin(ang - 0.45));
                float bx = (float) (x1 - len * Math.cos(ang + 0.45)), by = (float) (y1 - len * Math.sin(ang + 0.45));
                seg(s, x1, y1, ax, ay, w, false);
                seg(s, ax, ay, x1, y1, w, false);
                seg(s, x1, y1, bx, by, w, false);
                break;
            }
            case RECT: {
                float l = Math.min(x0, x1), r = Math.max(x0, x1), t = Math.min(y0, y1), b = Math.max(y0, y1);
                seg(s, l, t, r, t, w, true);
                seg(s, r, t, r, b, w, false);
                seg(s, r, b, l, b, w, false);
                seg(s, l, b, l, t, w, false);
                break;
            }
            case ELLIPSE: {
                float cx = (x0 + x1) / 2, cy = (y0 + y1) / 2, rx = Math.abs(x1 - x0) / 2, ry = Math.abs(y1 - y0) / 2;
                int steps = (int) Math.max(24, Math.min(240, (rx + ry) / 3));
                for (int i = 0; i <= steps; i++) {
                    double a = 2 * Math.PI * i / steps;
                    s.add((float) (cx + rx * Math.cos(a)), (float) (cy + ry * Math.sin(a)), w);
                }
                break;
            }
            case TRIANGLE: {
                float l = Math.min(x0, x1), r = Math.max(x0, x1), t = Math.min(y0, y1), b = Math.max(y0, y1);
                float mx = (l + r) / 2;
                seg(s, mx, t, r, b, w, true);
                seg(s, r, b, l, b, w, false);
                seg(s, l, b, mx, t, w, false);
                break;
            }
            default: break;
        }
        return s;
    }

    private static void seg(StrokeElem s, float x0, float y0, float x1, float y1, float w, boolean includeStart) {
        float len = (float) Math.hypot(x1 - x0, y1 - y0);
        int steps = Math.max(1, (int) (len / 4));
        for (int i = includeStart ? 0 : 1; i <= steps; i++) {
            float t = (float) i / steps;
            s.add(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, w);
        }
    }

    /** 손으로 그린 획을 직선/사각형/원/삼각형으로 인식. 인식 실패 시 null */
    public static StrokeElem recognize(StrokeElem src) {
        int n = src.n;
        if (n < 4) return null;
        float[] p = src.pts;
        float l = Float.MAX_VALUE, t = Float.MAX_VALUE, r = -Float.MAX_VALUE, b = -Float.MAX_VALUE;
        float len = 0;
        for (int i = 0; i < n; i++) {
            float x = p[3 * i], y = p[3 * i + 1];
            l = Math.min(l, x); r = Math.max(r, x); t = Math.min(t, y); b = Math.max(b, y);
            if (i > 0) len += (float) Math.hypot(x - p[3 * (i - 1)], y - p[3 * (i - 1) + 1]);
        }
        float bw = r - l, bh = b - t;
        float diag = (float) Math.hypot(bw, bh);
        if (diag < 25) return null;
        float w = src.avgWidth();
        int color = src.color;
        float fx = p[0], fy = p[1], lx = p[3 * (n - 1)], ly = p[3 * (n - 1) + 1];
        float chord = (float) Math.hypot(lx - fx, ly - fy);

        if (chord > 0.9f * len) {
            return build(LINE, fx, fy, lx, ly, color, w);
        }
        if (chord < 0.3f * diag) {
            // 닫힌 도형: 면적 비율로 판별
            double area = 0;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                area += p[3 * i] * p[3 * j + 1] - p[3 * j] * p[3 * i + 1];
            }
            area = Math.abs(area) / 2;
            double ratio = area / Math.max(1, bw * bh);
            if (bw < 10 || bh < 10) return null;
            if (ratio > 0.86) return build(RECT, l, t, r, b, color, w);
            if (ratio > 0.64) return build(ELLIPSE, l, t, r, b, color, w);
            if (ratio > 0.35) return triangle(src, color, w);
        }
        return null;
    }

    private static StrokeElem triangle(StrokeElem src, int color, float w) {
        int n = src.n;
        float[] p = src.pts;
        float cx = 0, cy = 0;
        for (int i = 0; i < n; i++) { cx += p[3 * i]; cy += p[3 * i + 1]; }
        cx /= n; cy /= n;
        int a = 0;
        float best = -1;
        for (int i = 0; i < n; i++) {
            float d = (float) Math.hypot(p[3 * i] - cx, p[3 * i + 1] - cy);
            if (d > best) { best = d; a = i; }
        }
        int bIdx = 0;
        best = -1;
        for (int i = 0; i < n; i++) {
            float d = (float) Math.hypot(p[3 * i] - p[3 * a], p[3 * i + 1] - p[3 * a + 1]);
            if (d > best) { best = d; bIdx = i; }
        }
        int c = 0;
        best = -1;
        for (int i = 0; i < n; i++) {
            float d = StrokeElem.segDist(p[3 * i], p[3 * i + 1], p[3 * a], p[3 * a + 1], p[3 * bIdx], p[3 * bIdx + 1]);
            if (d > best) { best = d; c = i; }
        }
        StrokeElem s = new StrokeElem(StrokeElem.PEN, color);
        float ax = p[3 * a], ay = p[3 * a + 1], bx = p[3 * bIdx], by = p[3 * bIdx + 1], qx = p[3 * c], qy = p[3 * c + 1];
        seg(s, ax, ay, bx, by, w, true);
        seg(s, bx, by, qx, qy, w, false);
        seg(s, qx, qy, ax, ay, w, false);
        return s;
    }
}
