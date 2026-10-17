package com.kjt.jiannote.render;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;

import com.kjt.jiannote.model.Element;
import com.kjt.jiannote.model.Layer;
import com.kjt.jiannote.model.Page;

import java.util.Collection;

public class Renderer {
    private static final android.graphics.Paint BG_PAINT = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);

    /** 캔버스는 이미 월드 좌표로 변환된 상태여야 한다. vis = 보이는 월드 영역 */
    public static void drawPage(Canvas c, Page p, int paperColor, RenderContext rc, RectF vis, float scale,
                                Collection<Element> excluded) {
        if (p.isInfinite()) {
            Paper.drawInfinite(c, p.template, paperColor, vis, scale);
        } else if (p.bgImage != null) {
            Paper.drawPage(c, Paper.BLANK, 0xFFFFFFFF, p.w, p.h, vis, scale);
            // 원본 PDF 를 화면(또는 내보내기) 배율로 다시 그린 조각 — 저해상도 배경 위에 덮어 그림
            PdfBackground.Patch sharp = rc == null ? null : rc.pdfPatch(p);
            boolean full = sharp != null && sharp.rect.left <= 0 && sharp.rect.top <= 0 && sharp.rect.right >= p.w && sharp.rect.bottom >= p.h;
            android.graphics.Bitmap bg = rc == null || full ? null : rc.image(p.bgImage);
            if (bg != null) {
                // 비율을 유지한 채 페이지를 꽉 채우도록 가운데 맞춰 자르기
                float sc = Math.max(p.w / bg.getWidth(), p.h / bg.getHeight());
                float sw = p.w / sc, sh = p.h / sc;
                android.graphics.Rect src = new android.graphics.Rect(Math.round((bg.getWidth() - sw) / 2), Math.round((bg.getHeight() - sh) / 2),
                        Math.round((bg.getWidth() + sw) / 2), Math.round((bg.getHeight() + sh) / 2));
                c.drawBitmap(bg, src, new RectF(0, 0, p.w, p.h), BG_PAINT);
            }
            if (sharp != null) c.drawBitmap(sharp.bmp, null, sharp.rect, BG_PAINT);
            c.save();
        } else {
            Paper.drawPage(c, p.template, paperColor, p.w, p.h, vis, scale);
            c.save();
            c.clipRect(0, 0, p.w, p.h);
        }
        // 그리는 순서: 0) 이미지·메모지  1) 형광펜(글씨 뒤)  2) 나머지(글씨·펜·테이프 등)
        for (int pass = 0; pass < 3; pass++) {
            for (Layer l : p.layers) {
                if (!l.visible || l.alpha <= 0) continue;
                int save = -1;
                for (Element e : l.elems) {
                    if (passOf(e) != pass) continue;
                    if (excluded != null && excluded.contains(e)) continue;
                    if (vis != null && !RectF.intersects(vis, e.bounds())) continue;
                    if (save < 0 && l.alpha < 255) save = c.saveLayerAlpha(vis, l.alpha);
                    e.draw(c, rc);
                }
                if (save >= 0) c.restoreToCount(save);
            }
        }
        if (!p.isInfinite()) c.restore();
    }

    private static int passOf(Element e) {
        if (e instanceof com.kjt.jiannote.model.ImageElem || e instanceof com.kjt.jiannote.model.MemoElem) return 0;
        if (e instanceof com.kjt.jiannote.model.StrokeElem && ((com.kjt.jiannote.model.StrokeElem) e).isHighlighter()) return 1;
        return 2;
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
