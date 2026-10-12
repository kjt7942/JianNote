package com.kjt.jiannote.model;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import com.kjt.jiannote.render.RenderContext;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

/** 손글씨 획. 각 점은 (x, y, 너비) 3개 float — 필압은 입력 시점에 너비로 변환되어 저장된다. */
public class StrokeElem extends Element {
    public static final int PEN = 0;        // 볼펜
    public static final int FOUNTAIN = 1;   // 만년필 (필압 강함)
    public static final int HIGHLIGHTER = 2;
    public static final int BRUSH = 3;      // 붓펜 (필압 매우 강함)

    public final int pen;
    public final int color;
    public float[] pts;
    public int n;

    private transient Path path;
    private transient int built;
    private transient RectF bounds;
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);

    public StrokeElem(int pen, int color) {
        this(pen, color, new float[3 * 64], 0);
    }

    public StrokeElem(int pen, int color, float[] pts, int n) {
        this.pen = pen;
        this.color = color;
        this.pts = pts;
        this.n = n;
    }

    @Override
    public byte type() {
        return T_STROKE;
    }

    public void add(float x, float y, float w) {
        if (3 * (n + 1) > pts.length) pts = Arrays.copyOf(pts, pts.length * 2);
        pts[3 * n] = x;
        pts[3 * n + 1] = y;
        pts[3 * n + 2] = w;
        n++;
        bounds = null;
    }

    public float lastX() { return pts[3 * (n - 1)]; }

    public float lastY() { return pts[3 * (n - 1) + 1]; }

    public float avgWidth() {
        if (n == 0) return 1;
        float s = 0;
        for (int i = 0; i < n; i++) s += pts[3 * i + 2];
        return s / n;
    }

    public float maxWidth() {
        float m = 0;
        for (int i = 0; i < n; i++) m = Math.max(m, pts[3 * i + 2]);
        return m;
    }

    private void ensurePath() {
        if (path == null) {
            path = new Path();
            path.setFillType(Path.FillType.WINDING);
            built = 0;
        }
        if (pen == HIGHLIGHTER) {
            for (int i = built; i < n; i++) {
                float x = pts[3 * i], y = pts[3 * i + 1];
                if (i == 0) {
                    path.moveTo(x, y);
                    if (n == 1) path.lineTo(x + 0.1f, y);
                } else {
                    path.lineTo(x, y);
                }
            }
        } else {
            for (int i = built; i < n; i++) {
                float x = pts[3 * i], y = pts[3 * i + 1], r = Math.max(pts[3 * i + 2] / 2f, 0.35f);
                path.addCircle(x, y, r, Path.Direction.CCW);
                if (i > 0) {
                    float px = pts[3 * (i - 1)], py = pts[3 * (i - 1) + 1];
                    float pr = Math.max(pts[3 * (i - 1) + 2] / 2f, 0.35f);
                    float dx = x - px, dy = y - py;
                    float len = (float) Math.hypot(dx, dy);
                    if (len > 0.01f) {
                        float nx = -dy / len, ny = dx / len;
                        path.moveTo(px + nx * pr, py + ny * pr);
                        path.lineTo(x + nx * r, y + ny * r);
                        path.lineTo(x - nx * r, y - ny * r);
                        path.lineTo(px - nx * pr, py - ny * pr);
                        path.close();
                    }
                }
            }
        }
        built = n;
    }

    @Override
    public void draw(Canvas c, RenderContext rc) {
        if (n == 0) return;
        if (pen == HIGHLIGHTER && built != n && built > 0) {
            // 형광펜 경로는 lineTo 누적이라 그대로 이어붙이면 된다
        }
        ensurePath();
        Paint p = PAINT;
        p.reset();
        p.setAntiAlias(true);
        if (pen == HIGHLIGHTER) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(pts[2]);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            int a = 0x70;
            p.setColor((color & 0x00FFFFFF) | (a << 24));
        } else {
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
        }
        c.drawPath(path, p);
    }

    @Override
    public RectF bounds() {
        if (bounds == null) {
            float l = Float.MAX_VALUE, t = Float.MAX_VALUE, r = -Float.MAX_VALUE, b = -Float.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                float x = pts[3 * i], y = pts[3 * i + 1], w = pts[3 * i + 2] / 2f + 1;
                l = Math.min(l, x - w);
                t = Math.min(t, y - w);
                r = Math.max(r, x + w);
                b = Math.max(b, y + w);
            }
            if (n == 0) { l = t = r = b = 0; }
            bounds = new RectF(l, t, r, b);
        }
        return bounds;
    }

    @Override
    public boolean hit(float x, float y, float rad) {
        RectF b = bounds();
        if (x < b.left - rad || x > b.right + rad || y < b.top - rad || y > b.bottom + rad) return false;
        if (n == 1) {
            return Math.hypot(x - pts[0], y - pts[1]) <= rad + pts[2] / 2f;
        }
        for (int i = 1; i < n; i++) {
            float w = Math.max(pts[3 * i + 2], pts[3 * (i - 1) + 2]) / 2f;
            if (segDist(x, y, pts[3 * (i - 1)], pts[3 * (i - 1) + 1], pts[3 * i], pts[3 * i + 1]) <= rad + w) {
                return true;
            }
        }
        return false;
    }

    public static float segDist(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay;
        float l2 = dx * dx + dy * dy;
        float t = l2 == 0 ? 0 : ((px - ax) * dx + (py - ay) * dy) / l2;
        t = Math.max(0, Math.min(1, t));
        float cx = ax + t * dx, cy = ay + t * dy;
        return (float) Math.hypot(px - cx, py - cy);
    }

    /** 부분 지우개: 원 안의 점을 제거하고 남은 구간들로 분할. 영향이 없으면 null */
    public ArrayList<StrokeElem> eraseSplit(float x, float y, float rad) {
        if (!hit(x, y, rad)) return null;
        ArrayList<StrokeElem> out = new ArrayList<>();
        StrokeElem cur = null;
        boolean changed = false;
        for (int i = 0; i < n; i++) {
            float px = pts[3 * i], py = pts[3 * i + 1];
            boolean inside = Math.hypot(px - x, py - y) <= rad + pts[3 * i + 2] / 2f;
            if (inside) {
                changed = true;
                if (cur != null && cur.n >= 2) out.add(cur);
                cur = null;
            } else {
                if (cur == null) cur = new StrokeElem(pen, color);
                cur.add(px, py, pts[3 * i + 2]);
            }
        }
        if (cur != null && cur.n >= 2) out.add(cur);
        if (!changed) {
            // 선분만 걸친 경우: 획 전체를 가로지르는 부분을 끊는다
            out.clear();
            cur = new StrokeElem(pen, color);
            for (int i = 0; i < n; i++) {
                if (i > 0 && segDist(x, y, pts[3 * (i - 1)], pts[3 * (i - 1) + 1], pts[3 * i], pts[3 * i + 1]) <= rad) {
                    if (cur.n >= 2) out.add(cur);
                    cur = new StrokeElem(pen, color);
                    changed = true;
                }
                cur.add(pts[3 * i], pts[3 * i + 1], pts[3 * i + 2]);
            }
            if (cur.n >= 2) out.add(cur);
            if (!changed) return null;
        }
        return out;
    }

    @Override
    public Element transformed(float dx, float dy, float s, float px, float py) {
        float[] np = new float[3 * Math.max(n, 1)];
        for (int i = 0; i < n; i++) {
            np[3 * i] = (pts[3 * i] - px) * s + px + dx;
            np[3 * i + 1] = (pts[3 * i + 1] - py) * s + py + dy;
            np[3 * i + 2] = pts[3 * i + 2] * s;
        }
        return new StrokeElem(pen, color, np, n);
    }

    @Override
    public Element withColor(int c) {
        return new StrokeElem(pen, c, Arrays.copyOf(pts, Math.max(3 * n, 3)), n);
    }

    @Override
    public void write(DataOutputStream o) throws IOException {
        o.writeByte(T_STROKE);
        o.writeInt(pen);
        o.writeInt(color);
        o.writeInt(n);
        for (int i = 0; i < 3 * n; i++) o.writeFloat(pts[i]);
    }

    public static StrokeElem read(DataInputStream in) throws IOException {
        int pen = in.readInt();
        int color = in.readInt();
        int n = in.readInt();
        float[] p = new float[3 * Math.max(n, 1)];
        for (int i = 0; i < 3 * n; i++) p[i] = in.readFloat();
        return new StrokeElem(pen, color, p, n);
    }
}
