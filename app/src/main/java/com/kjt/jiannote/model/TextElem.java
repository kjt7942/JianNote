package com.kjt.jiannote.model;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import com.kjt.jiannote.render.Fonts;
import com.kjt.jiannote.render.RenderContext;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** 텍스트 상자: 글꼴·크기·색·굵게/기울임/밑줄/취소선·정렬·배경색 */
public class TextElem extends Element {
    public static final int BOLD = 1, ITALIC = 2, UNDERLINE = 4, STRIKE = 8;
    public static final int ALIGN_LEFT = 0, ALIGN_CENTER = 1, ALIGN_RIGHT = 2;

    /** 텍스트 서식 (도구 설정 및 편집에 사용) */
    public static class Style {
        public int font = 0;
        public float size = 40f;
        public int color = 0xFF1A1A1A;
        public int flags = 0;
        public int bg = 0;      // 0 = 배경 없음
        public int align = ALIGN_LEFT;

        public Style copy() {
            Style s = new Style();
            s.font = font;
            s.size = size;
            s.color = color;
            s.flags = flags;
            s.bg = bg;
            s.align = align;
            return s;
        }

        public TextPaint paint() {
            TextPaint tp = new TextPaint(TextPaint.ANTI_ALIAS_FLAG | TextPaint.SUBPIXEL_TEXT_FLAG);
            boolean bold = (flags & BOLD) != 0;
            tp.setTypeface(Fonts.get(font, bold));
            tp.setFakeBoldText(bold && !Fonts.hasRealBold(font));
            tp.setTextSkewX((flags & ITALIC) != 0 ? -0.22f : 0f);
            tp.setUnderlineText((flags & UNDERLINE) != 0);
            tp.setStrikeThruText((flags & STRIKE) != 0);
            tp.setTextSize(size);
            tp.setColor(color);
            return tp;
        }

        public Layout.Alignment alignment() {
            switch (align) {
                case ALIGN_CENTER: return Layout.Alignment.ALIGN_CENTER;
                case ALIGN_RIGHT: return Layout.Alignment.ALIGN_OPPOSITE;
                default: return Layout.Alignment.ALIGN_NORMAL;
            }
        }
    }

    public final float x, y, maxWidth;
    public final String text;
    public final Style style;

    private transient StaticLayout layout;
    private static final Paint BG = new Paint(Paint.ANTI_ALIAS_FLAG);

    public TextElem(float x, float y, String text, Style style, float maxWidth) {
        this.x = x;
        this.y = y;
        this.text = text;
        this.style = style.copy();
        this.maxWidth = maxWidth;
    }

    public float size() { return style.size; }

    public int color() { return style.color; }

    @Override
    public byte type() {
        return T_TEXT2;
    }

    private StaticLayout layout() {
        if (layout == null) {
            TextPaint tp = style.paint();
            int w;
            if (maxWidth > 0) {
                w = (int) Math.ceil(maxWidth);
            } else {
                float max = 0;
                for (String line : text.split("\n", -1)) max = Math.max(max, tp.measureText(line));
                w = (int) Math.ceil(max + style.size * 0.25f) + 4;
            }
            w = Math.max(w, 10);
            layout = StaticLayout.Builder.obtain(text, 0, text.length(), tp, w)
                    .setAlignment(style.alignment())
                    .setIncludePad(true)
                    .build();
        }
        return layout;
    }

    private float pad() {
        return style.bg != 0 ? style.size * 0.25f : 0f;
    }

    @Override
    public RectF bounds() {
        StaticLayout l = layout();
        float p = pad();
        return new RectF(x - p, y - p, x + l.getWidth() + p, y + l.getHeight() + p);
    }

    @Override
    public void draw(Canvas c, RenderContext rc) {
        if (style.bg != 0) {
            BG.setColor(style.bg);
            float r = style.size * 0.2f;
            c.drawRoundRect(bounds(), r, r, BG);
        }
        c.save();
        c.translate(x, y);
        layout().draw(c);
        c.restore();
    }

    @Override
    public boolean hit(float px, float py, float r) {
        RectF b = bounds();
        b.inset(-r, -r);
        return b.contains(px, py);
    }

    @Override
    public Element transformed(float dx, float dy, float s, float px, float py) {
        Style st = style.copy();
        st.size = style.size * s;
        return new TextElem((x - px) * s + px + dx, (y - py) * s + py + dy, text, st, maxWidth * s);
    }

    @Override
    public Element withColor(int c) {
        Style st = style.copy();
        st.color = c;
        return new TextElem(x, y, text, st, maxWidth);
    }

    public TextElem withText(String t, Style st) {
        return new TextElem(x, y, t, st, maxWidth);
    }

    @Override
    public void write(DataOutputStream o) throws IOException {
        o.writeByte(T_TEXT2);
        o.writeFloat(x);
        o.writeFloat(y);
        o.writeUTF(text);
        o.writeFloat(maxWidth);
        o.writeInt(style.font);
        o.writeFloat(style.size);
        o.writeInt(style.color);
        o.writeInt(style.flags);
        o.writeInt(style.bg);
        o.writeInt(style.align);
    }

    /** 구버전(v1) 텍스트 */
    public static TextElem readV1(DataInputStream in) throws IOException {
        float x = in.readFloat(), y = in.readFloat();
        String t = in.readUTF();
        Style s = new Style();
        s.size = in.readFloat();
        s.color = in.readInt();
        float mw = in.readFloat();
        return new TextElem(x, y, t, s, mw);
    }

    public static TextElem read(DataInputStream in) throws IOException {
        float x = in.readFloat(), y = in.readFloat();
        String t = in.readUTF();
        float mw = in.readFloat();
        Style s = new Style();
        s.font = in.readInt();
        s.size = in.readFloat();
        s.color = in.readInt();
        s.flags = in.readInt();
        s.bg = in.readInt();
        s.align = in.readInt();
        return new TextElem(x, y, t, s, mw);
    }
}
