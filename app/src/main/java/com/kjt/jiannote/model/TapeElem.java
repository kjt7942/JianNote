package com.kjt.jiannote.model;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import com.kjt.jiannote.render.RenderContext;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * 암기 테이프: 끝이 네모난 직사각형 띠.
 * (x0,y0)→(x1,y1) 중심선, width 두께. 터치하면 잠깐 투명(revealed)해지고 다시 터치하면 가려진다.
 */
public class TapeElem extends Element {
    public static final int SOLID = 0, STRIPE = 1, DOT = 2, GRID = 3;
    public static final String[] PATTERN_NAMES = {"단색", "줄무늬", "도트", "체크"};

    public final float x0, y0, x1, y1, width;
    public final int color;
    public final int pattern;
    /** 화면에서 잠깐 비쳐 보이는 상태 (저장하지 않음) */
    public transient boolean revealed;

    private static final Paint P = new Paint(Paint.ANTI_ALIAS_FLAG);

    public TapeElem(float x0, float y0, float x1, float y1, float width, int color, int pattern) {
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
        this.width = width;
        this.color = color;
        this.pattern = pattern;
    }

    @Override
    public byte type() {
        return T_TAPE;
    }

    @Override
    public boolean isContainer() {
        return true;
    }

    private float len() {
        return Math.max(1f, (float) Math.hypot(x1 - x0, y1 - y0));
    }

    private float angleDeg() {
        return (float) Math.toDegrees(Math.atan2(y1 - y0, x1 - x0));
    }

    @Override
    public RectF bounds() {
        float len = len();
        float ux = (x1 - x0) / len, uy = (y1 - y0) / len;
        float nx = -uy * width / 2, ny = ux * width / 2;
        float[] xs = {x0 + nx, x0 - nx, x1 + nx, x1 - nx};
        float[] ys = {y0 + ny, y0 - ny, y1 + ny, y1 - ny};
        RectF r = new RectF(xs[0], ys[0], xs[0], ys[0]);
        for (int i = 1; i < 4; i++) r.union(xs[i], ys[i]);
        return r;
    }

    @Override
    public void draw(Canvas c, RenderContext rc) {
        float len = len();
        c.save();
        c.translate(x0, y0);
        c.rotate(angleDeg());
        RectF r = new RectF(0, -width / 2, len, width / 2);
        int a = revealed ? 50 : 255;
        P.reset();
        P.setAntiAlias(true);
        P.setStyle(Paint.Style.FILL);
        P.setColor((color & 0x00FFFFFF) | (Math.round(Color.alpha(color) * a / 255f) << 24));
        c.drawRect(r, P);
        if (!revealed && pattern != SOLID) {
            int light = Color.argb(70, 255, 255, 255);
            P.setColor(light);
            c.save();
            c.clipRect(r);
            float step = Math.max(6f, width / 3f);
            if (pattern == STRIPE) {
                P.setStrokeWidth(step / 2.2f);
                P.setStyle(Paint.Style.STROKE);
                for (float x = -width; x < len + width; x += step) c.drawLine(x, -width / 2, x + width, width / 2, P);
            } else if (pattern == DOT) {
                float rr = step / 5f;
                for (float x = step / 2; x < len; x += step)
                    for (float y = -width / 2 + step / 2; y < width / 2; y += step) c.drawCircle(x, y, rr, P);
            } else if (pattern == GRID) {
                P.setStyle(Paint.Style.STROKE);
                P.setStrokeWidth(Math.max(1f, step / 6f));
                for (float x = 0; x < len; x += step) c.drawLine(x, -width / 2, x, width / 2, P);
                for (float y = -width / 2; y < width / 2; y += step) c.drawLine(0, y, len, y, P);
            }
            c.restore();
        }
        // 테두리를 살짝 (종이 테이프 느낌)
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeWidth(1f);
        P.setColor(revealed ? 0x55000000 : 0x22000000);
        if (revealed) P.setPathEffect(new android.graphics.DashPathEffect(new float[]{8, 6}, 0));
        c.drawRect(r, P);
        P.setPathEffect(null);
        c.restore();
    }

    @Override
    public boolean hit(float px, float py, float r) {
        float len = len();
        float ux = (x1 - x0) / len, uy = (y1 - y0) / len;
        float dx = px - x0, dy = py - y0;
        float along = dx * ux + dy * uy, across = -dx * uy + dy * ux;
        return along >= -r && along <= len + r && Math.abs(across) <= width / 2 + r;
    }

    @Override
    public Element transformed(float dx, float dy, float s, float px, float py) {
        TapeElem t = new TapeElem((x0 - px) * s + px + dx, (y0 - py) * s + py + dy,
                (x1 - px) * s + px + dx, (y1 - py) * s + py + dy, width * s, color, pattern);
        t.revealed = revealed;
        return t;
    }

    @Override
    public Element transformedKeepWidth(float dx, float dy, float s, float px, float py) {
        return transformed(dx, dy, s, px, py);
    }

    @Override
    public Element withColor(int c) {
        return new TapeElem(x0, y0, x1, y1, width, c, pattern);
    }

    @Override
    public void write(DataOutputStream o) throws IOException {
        o.writeByte(T_TAPE);
        o.writeFloat(x0);
        o.writeFloat(y0);
        o.writeFloat(x1);
        o.writeFloat(y1);
        o.writeFloat(width);
        o.writeInt(color);
        o.writeInt(pattern);
    }

    public static TapeElem read(DataInputStream in) throws IOException {
        return new TapeElem(in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readInt(), in.readInt());
    }

    /** 테이프 모양 (다각형) — 아래 요소와 겹치는지 판단용 */
    public Path outline() {
        float len = len();
        float ux = (x1 - x0) / len, uy = (y1 - y0) / len;
        float nx = -uy * width / 2, ny = ux * width / 2;
        Path p = new Path();
        p.moveTo(x0 + nx, y0 + ny);
        p.lineTo(x1 + nx, y1 + ny);
        p.lineTo(x1 - nx, y1 - ny);
        p.lineTo(x0 - nx, y0 - ny);
        p.close();
        return p;
    }
}
