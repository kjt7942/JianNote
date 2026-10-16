package com.kjt.jiannote.model;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

import com.kjt.jiannote.render.RenderContext;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

/**
 * 손글씨 획. 각 점은 (x, y, 너비) 3개 float — 필압은 입력 시점에 너비로 변환되어 저장된다.
 * color 는 ARGB (알파 = 투명도).
 */
public class StrokeElem extends Element {
    public static final int PEN = 0;        // 볼펜
    public static final int FOUNTAIN = 1;   // 만년필 (필압 강함)
    public static final int HIGHLIGHTER = 2;
    public static final int BRUSH = 3;      // 붓펜 (필압 매우 강함)
    public static final int PENCIL = 4;     // 연필 (종이 질감)
    public static final int MARKER = 5;     // 마커 (굵기 일정)
    public static final int AIRBRUSH = 6;   // 에어브러시 (흐릿하게 퍼짐)
    public static final int WATERCOLOR = 7; // 붓/수채 (반투명하게 겹침)

    public static final String[] PEN_NAMES = {"볼펜", "만년필", "형광펜", "붓펜", "연필", "마커", "에어브러시", "붓/수채"};

    private static final int SQUARE_FLAG = 0x100;

    public final int pen;
    public final int color;
    /** 끝이 네모난 펜/형광펜 */
    public final boolean square;
    public float[] pts;
    public int n;

    private transient Path path;
    private transient int built;
    private transient RectF bounds;
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static Bitmap grain;

    public StrokeElem(int pen, int color) {
        this(pen, color, false);
    }

    public StrokeElem(int pen, int color, boolean square) {
        this(pen, color, square, new float[3 * 64], 0);
    }

    public StrokeElem(int pen, int color, boolean square, float[] pts, int n) {
        this.pen = pen;
        this.color = color;
        this.square = square;
        this.pts = pts;
        this.n = n;
    }

    /** 같은 펜 설정의 빈 획 */
    public StrokeElem blank() {
        return new StrokeElem(pen, color, square);
    }

    @Override
    public byte type() {
        return T_STROKE;
    }

    public boolean isHighlighter() {
        return pen == HIGHLIGHTER;
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

    public float length() {
        float len = 0;
        for (int i = 1; i < n; i++) len += (float) Math.hypot(pts[3 * i] - pts[3 * i - 3], pts[3 * i + 1] - pts[3 * i - 2]);
        return len;
    }

    /** 선(stroke)으로 그리는 종류 — 굵기 일정 */
    private boolean strokeStyle() {
        return pen == HIGHLIGHTER || pen == MARKER;
    }

    private void ensurePath() {
        if (path == null) {
            path = new Path();
            path.setFillType(Path.FillType.WINDING);
            built = 0;
        }
        if (strokeStyle()) {
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
                boolean endpoint = i == 0 || i == n - 1;
                // 네모 끝: 양 끝점에는 둥근 마개를 그리지 않음 (완성된 획에서만 판단 가능)
                if (!square || !endpoint || n == 1) {
                    if (square && n == 1) path.addRect(x - r, y - r, x + r, y + r, Path.Direction.CCW);
                    else path.addCircle(x, y, r, Path.Direction.CCW);
                }
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

    /** 그리는 중(점이 계속 추가됨)에는 네모 끝 판단이 바뀌므로 경로를 다시 만든다 */
    public void invalidatePath() {
        path = null;
    }

    private static Bitmap grain() {
        if (grain == null) {
            int s = 96;
            Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ALPHA_8);
            Random r = new Random(7);
            int[] px = new int[s * s];
            for (int i = 0; i < px.length; i++) {
                int a = 70 + r.nextInt(186);
                px[i] = Color.argb(a, 0, 0, 0);
            }
            Bitmap tmp = Bitmap.createBitmap(px, s, s, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.drawBitmap(tmp, 0, 0, null);
            tmp.recycle();
            grain = b;
        }
        return grain;
    }

    private static int withAlpha(int color, float factor) {
        int a = Math.round(Color.alpha(color) * factor);
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, a)) << 24);
    }

    @Override
    public void draw(Canvas c, RenderContext rc) {
        if (n == 0) return;
        if (square && path != null && built != n) path = null; // 끝점이 바뀌었으면 다시
        ensurePath();
        Paint p = PAINT;
        p.reset();
        p.setAntiAlias(true);
        switch (pen) {
            case HIGHLIGHTER: {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(pts[2]);
                p.setStrokeCap(square ? Paint.Cap.SQUARE : Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
                int a = Color.alpha(color) == 255 ? 0x70 : Color.alpha(color);
                p.setColor((color & 0x00FFFFFF) | (a << 24));
                break;
            }
            case MARKER: {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(avgWidth());
                p.setStrokeCap(square ? Paint.Cap.SQUARE : Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
                p.setColor(withAlpha(color, 0.92f));
                break;
            }
            case PENCIL: {
                p.setStyle(Paint.Style.FILL);
                p.setColor(withAlpha(color, 0.9f));
                p.setShader(new BitmapShader(grain(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
                break;
            }
            case AIRBRUSH: {
                p.setStyle(Paint.Style.FILL);
                p.setColor(withAlpha(color, 0.55f));
                p.setMaskFilter(new BlurMaskFilter(Math.max(1f, avgWidth() * 0.45f), BlurMaskFilter.Blur.NORMAL));
                break;
            }
            case WATERCOLOR: {
                p.setStyle(Paint.Style.FILL);
                p.setColor(withAlpha(color, 0.45f));
                p.setMaskFilter(new BlurMaskFilter(Math.max(0.6f, avgWidth() * 0.12f), BlurMaskFilter.Blur.NORMAL));
                break;
            }
            default: {
                p.setStyle(Paint.Style.FILL);
                p.setColor(color);
            }
        }
        c.drawPath(path, p);
    }

    @Override
    public RectF bounds() {
        if (bounds == null) {
            float pad = 1;
            if (pen == AIRBRUSH || pen == WATERCOLOR) pad += avgWidth();
            if (strokeStyle()) pad += avgWidth() * 0.5f;
            float l = Float.MAX_VALUE, t = Float.MAX_VALUE, r = -Float.MAX_VALUE, b = -Float.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                float x = pts[3 * i], y = pts[3 * i + 1], w = pts[3 * i + 2] / 2f + pad;
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
                if (cur == null) cur = blank();
                cur.add(px, py, pts[3 * i + 2]);
            }
        }
        if (cur != null && cur.n >= 2) out.add(cur);
        if (!changed) {
            out.clear();
            cur = blank();
            for (int i = 0; i < n; i++) {
                if (i > 0 && segDist(x, y, pts[3 * (i - 1)], pts[3 * (i - 1) + 1], pts[3 * i], pts[3 * i + 1]) <= rad) {
                    if (cur.n >= 2) out.add(cur);
                    cur = blank();
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
        return transform(dx, dy, s, px, py, s);
    }

    @Override
    public Element transformedKeepWidth(float dx, float dy, float s, float px, float py) {
        return transform(dx, dy, s, px, py, 1f);
    }

    private StrokeElem transform(float dx, float dy, float s, float px, float py, float ws) {
        float[] np = new float[3 * Math.max(n, 1)];
        for (int i = 0; i < n; i++) {
            np[3 * i] = (pts[3 * i] - px) * s + px + dx;
            np[3 * i + 1] = (pts[3 * i + 1] - py) * s + py + dy;
            np[3 * i + 2] = pts[3 * i + 2] * ws;
        }
        StrokeElem e = new StrokeElem(pen, color, square, np, n);
        e.locked = locked;
        return e;
    }

    /** 기준점(ax, ay)을 고정하고 가로 sx, 세로 sy 배로 늘린 복사본 (두께 유지) */
    public StrokeElem stretched(float ax, float ay, float sx, float sy) {
        float[] np = new float[3 * Math.max(n, 1)];
        for (int i = 0; i < n; i++) {
            np[3 * i] = ax + (pts[3 * i] - ax) * sx;
            np[3 * i + 1] = ay + (pts[3 * i + 1] - ay) * sy;
            np[3 * i + 2] = pts[3 * i + 2];
        }
        return new StrokeElem(pen, color, square, np, n);
    }

    @Override
    public Element withColor(int c) {
        return new StrokeElem(pen, c, square, Arrays.copyOf(pts, Math.max(3 * n, 3)), n);
    }

    /** Catmull-Rom 스플라인으로 촘촘하게 다시 샘플링 → 꺾인 직선 없이 매끄러운 획 */
    public StrokeElem smoothResampled(float maxSeg) {
        if (n < 3) return this;
        StrokeElem out = blank();
        for (int i = 0; i < n - 1; i++) {
            int i0 = Math.max(0, i - 1), i1 = i, i2 = i + 1, i3 = Math.min(n - 1, i + 2);
            float x1 = pts[3 * i1], y1 = pts[3 * i1 + 1], x2 = pts[3 * i2], y2 = pts[3 * i2 + 1];
            float seg = (float) Math.hypot(x2 - x1, y2 - y1);
            int steps = Math.max(1, (int) Math.ceil(seg / maxSeg));
            for (int k = 0; k < steps; k++) {
                float t = (float) k / steps;
                out.add(cr(pts[3 * i0], x1, x2, pts[3 * i3], t), cr(pts[3 * i0 + 1], y1, y2, pts[3 * i3 + 1], t),
                        pts[3 * i1 + 2] + (pts[3 * i2 + 2] - pts[3 * i1 + 2]) * t);
            }
        }
        out.add(pts[3 * (n - 1)], pts[3 * (n - 1) + 1], pts[3 * (n - 1) + 2]);
        return out;
    }

    private static float cr(float p0, float p1, float p2, float p3, float t) {
        float t2 = t * t, t3 = t2 * t;
        return 0.5f * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }

    /** 곡선 보정: 손떨림을 크게 줄인 부드러운 곡선 (양 끝점 고정) */
    public StrokeElem smoothedCurve() {
        if (n < 5) return null;
        float[] xs = new float[n], ys = new float[n], ws = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = pts[3 * i];
            ys[i] = pts[3 * i + 1];
            ws[i] = pts[3 * i + 2];
        }
        int win = Math.max(2, n / 8);
        for (int pass = 0; pass < 4; pass++) {
            float[] nx = xs.clone(), ny = ys.clone();
            for (int i = 1; i < n - 1; i++) {
                int a = Math.max(0, i - win), b = Math.min(n - 1, i + win);
                float sx = 0, sy = 0;
                for (int j = a; j <= b; j++) {
                    sx += xs[j];
                    sy += ys[j];
                }
                nx[i] = sx / (b - a + 1);
                ny[i] = sy / (b - a + 1);
            }
            xs = nx;
            ys = ny;
        }
        float avg = avgWidth();
        StrokeElem out = blank();
        for (int i = 0; i < n; i++) out.add(xs[i], ys[i], avg);
        return out.smoothResampled(3f);
    }

    @Override
    public void write(DataOutputStream o) throws IOException {
        o.writeByte(T_STROKE);
        o.writeInt(pen | (square ? SQUARE_FLAG : 0));
        o.writeInt(color);
        o.writeInt(n);
        for (int i = 0; i < 3 * n; i++) o.writeFloat(pts[i]);
    }

    public static StrokeElem read(DataInputStream in) throws IOException {
        int penRaw = in.readInt();
        int color = in.readInt();
        int n = in.readInt();
        float[] p = new float[3 * Math.max(n, 1)];
        for (int i = 0; i < 3 * n; i++) p[i] = in.readFloat();
        return new StrokeElem(penRaw & 0xFF, color, (penRaw & SQUARE_FLAG) != 0, p, n);
    }
}
