package com.kjt.jiannote.model;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import com.kjt.jiannote.render.RenderContext;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * 메모지: 페이지 위에 붙이는 색 종이. 위에 쓴 손글씨·텍스트는 같은 group 으로 묶여 함께 움직인다.
 */
public class MemoElem extends Element {
    public static final int BLANK = 0, LINED = 1, GRID = 2, CHECK = 3;
    public static final String[] PATTERN_NAMES = {"무지", "줄", "모눈", "체크리스트"};
    public static final int[] COLORS = {0xFFFFF59D, 0xFFFFCC80, 0xFFF8BBD0, 0xFFB3E5FC, 0xFFC8E6C9, 0xFFE1BEE7, 0xFFFFFFFF};

    public final float x, y, w, h;
    public final int color;
    public final int pattern;

    private static final Paint P = new Paint(Paint.ANTI_ALIAS_FLAG);

    public MemoElem(float x, float y, float w, float h, int color, int pattern) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.color = color;
        this.pattern = pattern;
    }

    @Override
    public byte type() {
        return T_MEMO;
    }

    @Override
    public boolean isContainer() {
        return true;
    }

    @Override
    public RectF bounds() {
        return new RectF(x, y, x + w, y + h);
    }

    public boolean contains(float px, float py) {
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }

    @Override
    public void draw(Canvas c, RenderContext rc) {
        float r = Math.min(w, h) * 0.03f;
        P.reset();
        P.setAntiAlias(true);
        // 그림자
        P.setColor(0x22000000);
        c.drawRoundRect(x + 4, y + 6, x + w + 4, y + h + 6, r, r, P);
        P.setColor(color);
        c.drawRoundRect(x, y, x + w, y + h, r, r, P);
        // 윗부분 접착 띠
        P.setColor(0x14000000);
        c.drawRect(x, y, x + w, y + Math.min(28, h * 0.1f), P);
        P.setColor(0x33000000);
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeWidth(1.5f);
        float pad = 24, sp = 40;
        float top = y + Math.min(28, h * 0.1f) + sp * 0.6f;
        switch (pattern) {
            case LINED:
                for (float ly = top + sp * 0.4f; ly < y + h - 10; ly += sp) c.drawLine(x + pad, ly, x + w - pad, ly, P);
                break;
            case GRID: {
                float g = 26;
                for (float gx = x + g; gx < x + w; gx += g) c.drawLine(gx, y + 4, gx, y + h - 4, P);
                for (float gy = y + g; gy < y + h; gy += g) c.drawLine(x + 4, gy, x + w - 4, gy, P);
                break;
            }
            case CHECK:
                for (float ly = top + sp * 0.4f; ly < y + h - 10; ly += sp) {
                    c.drawRect(x + pad, ly - 24, x + pad + 22, ly - 2, P);
                    c.drawLine(x + pad + 34, ly, x + w - pad, ly, P);
                }
                break;
            default:
                break;
        }
    }

    @Override
    public boolean hit(float px, float py, float r) {
        RectF b = bounds();
        b.inset(-r, -r);
        return b.contains(px, py);
    }

    @Override
    public Element transformed(float dx, float dy, float s, float px, float py) {
        return new MemoElem((x - px) * s + px + dx, (y - py) * s + py + dy, w * s, h * s, color, pattern);
    }

    @Override
    public Element withColor(int c) {
        return new MemoElem(x, y, w, h, c | 0xFF000000, pattern);
    }

    public MemoElem withPattern(int p) {
        return new MemoElem(x, y, w, h, color, p);
    }

    @Override
    public void write(DataOutputStream o) throws IOException {
        o.writeByte(T_MEMO);
        o.writeFloat(x);
        o.writeFloat(y);
        o.writeFloat(w);
        o.writeFloat(h);
        o.writeInt(color);
        o.writeInt(pattern);
    }

    public static MemoElem read(DataInputStream in) throws IOException {
        return new MemoElem(in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readInt(), in.readInt());
    }
}
