package com.kjt.jiannote.model;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import com.kjt.jiannote.render.RenderContext;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class ImageElem extends Element {
    public final String file;
    public final float x, y, w, h;
    private static final Paint PAINT = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private static final Paint MISSING = new Paint();

    static {
        MISSING.setColor(Color.LTGRAY);
    }

    public ImageElem(String file, float x, float y, float w, float h) {
        this.file = file;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    @Override
    public byte type() {
        return T_IMAGE;
    }

    @Override
    public RectF bounds() {
        return new RectF(x, y, x + w, y + h);
    }

    @Override
    public void draw(Canvas c, RenderContext rc) {
        Bitmap b = rc == null ? null : rc.image(file);
        if (b == null) {
            c.drawRect(bounds(), MISSING);
        } else {
            c.drawBitmap(b, null, bounds(), PAINT);
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
        return new ImageElem(file, (x - px) * s + px + dx, (y - py) * s + py + dy, w * s, h * s);
    }

    @Override
    public void write(DataOutputStream o) throws IOException {
        o.writeByte(T_IMAGE);
        o.writeUTF(file);
        o.writeFloat(x);
        o.writeFloat(y);
        o.writeFloat(w);
        o.writeFloat(h);
    }

    public static ImageElem read(DataInputStream in) throws IOException {
        String f = in.readUTF();
        return new ImageElem(f, in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat());
    }
}
