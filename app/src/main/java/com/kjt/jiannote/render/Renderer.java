package com.kjt.jiannote.render;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;

import com.kjt.jiannote.model.Element;
import com.kjt.jiannote.model.Layer;
import com.kjt.jiannote.model.Page;

import java.util.Collection;

public class Renderer {

    /** 캔버스는 이미 월드 좌표로 변환된 상태여야 한다. vis = 보이는 월드 영역 */
    public static void drawPage(Canvas c, Page p, int paperColor, RenderContext rc, RectF vis, float scale,
                                Collection<Element> excluded) {
        if (p.isInfinite()) {
            Paper.drawInfinite(c, p.template, paperColor, vis, scale);
        } else {
            Paper.drawPage(c, p.template, paperColor, p.w, p.h, vis, scale);
            c.save();
            c.clipRect(0, 0, p.w, p.h);
        }
        for (Layer l : p.layers) {
            if (!l.visible || l.alpha <= 0) continue;
            int save = -1;
            if (l.alpha < 255) save = c.saveLayerAlpha(vis, l.alpha);
            for (Element e : l.elems) {
                if (excluded != null && excluded.contains(e)) continue;
                if (vis != null && !RectF.intersects(vis, e.bounds())) continue;
                e.draw(c, rc);
            }
            if (save >= 0) c.restoreToCount(save);
        }
        if (!p.isInfinite()) c.restore();
    }

    public static RectF contentBounds(Page p) {
        RectF r = null;
        for (Layer l : p.layers) {
            for (Element e : l.elems) {
                if (r == null) r = new RectF(e.bounds());
                else r.union(e.bounds());
            }
        }
        return r;
    }

    /** 표지/페이지 썸네일 */
    public static Bitmap thumbnail(Page p, int paperColor, RenderContext rc, int maxW, int maxH) {
        RectF area;
        if (p.isInfinite()) {
            RectF cb = contentBounds(p);
            if (cb == null) cb = new RectF(0, 0, 1200, 900);
            cb.inset(-60, -60);
            float ratio = (float) maxW / maxH;
            if (cb.width() / cb.height() > ratio) {
                float h = cb.width() / ratio;
                cb.top = cb.centerY() - h / 2;
                cb.bottom = cb.top + h;
            } else {
                float w = cb.height() * ratio;
                cb.left = cb.centerX() - w / 2;
                cb.right = cb.left + w;
            }
            area = cb;
        } else {
            area = new RectF(0, 0, p.w, p.h);
        }
        float s = Math.min(maxW / area.width(), maxH / area.height());
        int bw = Math.max(1, Math.round(area.width() * s));
        int bh = Math.max(1, Math.round(area.height() * s));
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(s, s);
        c.translate(-area.left, -area.top);
        drawPage(c, p, paperColor, rc, area, s, null);
        return b;
    }
}
