package com.kjt.jiannote.model;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import com.kjt.jiannote.render.RenderContext;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class TextElem extends Element {
    public final float x, y, size, maxWidth;
    public final int color;
    public final String text;

    private transient StaticLayout layout;

    public TextElem(float x, float y, String text, float size, int color, float maxWidth) {
        this.x = x;
        this.y = y;
        this.text = text;
        this.size = size;
        this.color = color;
        this.maxWidth = maxWidth;
    }

    @Override
    public byte type() {
        return T_TEXT;
    }

    private StaticLayout layout() {
        if (layout == null) {
            TextPaint tp = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
            tp.setTextSize(size);
            tp.setColor(color);
            int w;
            if (maxWidth > 0) {
                w = (int) Math.ceil(maxWidth);
            } else {
                float max = 0;
                for (String line : text.split("\n", -1)) {
                    max = Math.max(max, tp.measureText(line));
                }
                w = (int) Math.ceil(max) + 4;
            }
            w = Math.max(w, 10);
            layout = StaticLayout.Builder.obtain(text, 0, text.length(), tp, w)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .build();
        }
        return layout;
    }

    @Override
    public RectF bounds() {
        StaticLayout l = layout();
        return new RectF(x, y, x + l.getWidth(), y + l.getHeight());
    }

    @Override
    public void draw(Canvas c, RenderContext rc) {
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
        return new TextElem((x - px) * s + px + dx, (y - py) * s + py + dy, text, size * s, color, maxWidth * s);
    }

    @Override
    public Element withColor(int c) {
        return new TextElem(x, y, text, size, c, maxWidth);
    }

    @Override
    public void write(DataOutputStream o) throws IOException {
        o.writeByte(T_TEXT);
        o.writeFloat(x);
        o.writeFloat(y);
        o.writeUTF(text);
        o.writeFloat(size);
        o.writeInt(color);
        o.writeFloat(maxWidth);
    }

    public static TextElem read(DataInputStream in) throws IOException {
        float x = in.readFloat(), y = in.readFloat();
        String t = in.readUTF();
        float size = in.readFloat();
        int color = in.readInt();
        float mw = in.readFloat();
        return new TextElem(x, y, t, size, color, mw);
    }
}
