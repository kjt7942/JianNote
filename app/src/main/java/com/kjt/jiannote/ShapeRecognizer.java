package com.kjt.jiannote;

import com.kjt.jiannote.model.StrokeElem;

/** 도형 생성 및 "펜을 누르고 있으면 도형으로 보정" 인식 */
public class ShapeRecognizer {
    public static final int LINE = 0, ARROW = 1, RECT = 2, ELLIPSE = 3, TRIANGLE = 4;
    public static final String[] NAMES = {"직선", "화살표", "사각형", "원", "삼각형"};

    /** 마지막으로 인식된 도형 종류 (-1 = 곡선 보정) */
    public static int lastType = -1;

    public static StrokeElem build(int type, float x0, float y0, float x1, float y1, int color, float w) {
        return build(type, x0, y0, x1, y1, new StrokeElem(StrokeElem.PEN, color), w);
    }

    /** template 의 펜 종류·색·끝 모양을 그대로 쓰는 도형 */
    public static StrokeElem build(int type, float x0, float y0, float x1, float y1, StrokeElem template, float w) {
        StrokeElem s = template.blank();
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
                int steps = (int) Math.max(72, Math.min(720, (rx + ry) * 0.8f));
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
        int steps = Math.max(1, (int) (len / 2.5f));
        for (int i = includeStart ? 0 : 1; i <= steps; i++) {
            float t = (float) i / steps;
            s.add(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, w);
        }
    }

    /** 손으로 그린 획을 직선/사각형/원/삼각형으로 인식. 인식 실패 시 null */
    public static StrokeElem recognize(StrokeElem src) {
        int n = src.n;
        if (n < 3) return null;
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
        if (diag < 6) return null; // 작은 도형도 인식
        float w = src.avgWidth();
        int color = src.color;
        float fx = p[0], fy = p[1], lx = p[3 * (n - 1)], ly = p[3 * (n - 1) + 1];
        float chord = (float) Math.hypot(lx - fx, ly - fy);

        // 꺾이는 점 찾기 (Douglas-Peucker): 2점이면 직선, 3~5점이고 확실히 꺾였으면 꺾은선
        int[] keep = new int[n];
        int kc = 0;
        boolean[] mark = new boolean[n];
        mark[0] = true;
        mark[n - 1] = true;
        float eps = Math.max(1.5f, Math.min(len * 0.045f, 14f + len * 0.01f));
        dp(p, 0, n - 1, eps, mark);
        for (int i = 0; i < n; i++) if (mark[i]) keep[kc++] = i;

        if (kc == 2 && chord > 0.6f * len) {
            lastType = LINE;
            return build(LINE, fx, fy, lx, ly, src, w);
        }
        if (chord > 0.9f * len) {
            lastType = LINE;
            return build(LINE, fx, fy, lx, ly, src, w);
        }
        if (chord >= 0.3f * diag && kc >= 3 && kc <= 5 && sharpCorners(p, keep, kc)) {
            StrokeElem s = src.blank();
            for (int k = 1; k < kc; k++) {
                int a = keep[k - 1], b2 = keep[k];
                seg(s, p[3 * a], p[3 * a + 1], p[3 * b2], p[3 * b2 + 1], w, k == 1);
            }
            lastType = -1; // 시작점 기준으로 돌리고 늘이는 방식
            return s;
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
            if (bw < 4 || bh < 4) return null;
            if (ratio > 0.86) {
                lastType = RECT;
                return build(RECT, l, t, r, b, src, w);
            }
            if (ratio > 0.64) {
                lastType = ELLIPSE;
                return build(ELLIPSE, l, t, r, b, src, w);
            }
            if (ratio > 0.35) {
                lastType = TRIANGLE;
                return triangle(src, color, w);
            }
        }
        return null;
    }

    private static void dp(float[] p, int a, int b, float eps, boolean[] mark) {
        if (b <= a + 1) return;
        float best = -1;
        int idx = -1;
        for (int i = a + 1; i < b; i++) {
            float d = StrokeElem.segDist(p[3 * i], p[3 * i + 1], p[3 * a], p[3 * a + 1], p[3 * b], p[3 * b + 1]);
            if (d > best) { best = d; idx = i; }
        }
        if (best > eps) {
            mark[idx] = true;
            dp(p, a, idx, eps, mark);
            dp(p, idx, b, eps, mark);
        }
    }

    /** 꺾이는 점마다 55° 이상 꺾였고 각 변이 충분히 긴지 확인 (완만한 곡선은 제외) */
    private static boolean sharpCorners(float[] p, int[] keep, int kc) {
        float total = 0;
        float[] sl = new float[kc - 1];
        for (int k = 1; k < kc; k++) {
            sl[k - 1] = (float) Math.hypot(p[3 * keep[k]] - p[3 * keep[k - 1]], p[3 * keep[k] + 1] - p[3 * keep[k - 1] + 1]);
            total += sl[k - 1];
        }
        for (float v : sl) if (v < total * 0.12f) return false;
        for (int k = 1; k < kc - 1; k++) {
            double a1 = Math.atan2(p[3 * keep[k] + 1] - p[3 * keep[k - 1] + 1], p[3 * keep[k]] - p[3 * keep[k - 1]]);
            double a2 = Math.atan2(p[3 * keep[k + 1] + 1] - p[3 * keep[k] + 1], p[3 * keep[k + 1]] - p[3 * keep[k]]);
            double d = Math.abs(a2 - a1);
            if (d > Math.PI) d = 2 * Math.PI - d;
            if (Math.toDegrees(d) < 55) return false;
        }
        return true;
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
        StrokeElem s = src.blank();
        float ax = p[3 * a], ay = p[3 * a + 1], bx = p[3 * bIdx], by = p[3 * bIdx + 1], qx = p[3 * c], qy = p[3 * c + 1];
        seg(s, ax, ay, bx, by, w, true);
        seg(s, bx, by, qx, qy, w, false);
        seg(s, qx, qy, ax, ay, w, false);
        return s;
    }
}
