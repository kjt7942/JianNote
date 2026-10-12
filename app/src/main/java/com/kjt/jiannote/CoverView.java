package com.kjt.jiannote;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import com.kjt.jiannote.model.NotebookMeta;
import com.kjt.jiannote.render.Paper;

/** 서재의 노트북 표지 */
public class CoverView extends View {
    private NotebookMeta meta;
    private Bitmap thumb;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    public CoverView(Context c) {
        super(c);
    }

    public void bind(NotebookMeta m, Bitmap thumb) {
        this.meta = m;
        this.thumb = thumb;
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = (int) (w * 1.3f);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (meta == null) return;
        float d = getResources().getDisplayMetrics().density;
        float w = getWidth(), h = getHeight();
        float pad = 6 * d;
        // 그림자
        p.setColor(0x26000000);
        r.set(pad + 3 * d, pad + 4 * d, w - pad + 3 * d, h - pad + 4 * d);
        c.drawRoundRect(r, 10 * d, 10 * d, p);
        // 표지
        p.setColor(meta.coverColor);
        r.set(pad, pad, w - pad, h - pad);
        c.drawRoundRect(r, 10 * d, 10 * d, p);
        // 책등
        p.setColor(0x33000000);
        c.drawRect(pad + 10 * d, pad, pad + 14 * d, h - pad, p);
        // 속지 미리보기
        float il = pad + 24 * d, it = pad + 16 * d, ir = w - pad - 12 * d, ib = h - pad - 16 * d;
        RectF inner = new RectF(il, it, ir, ib);
        p.setColor(meta.paperColor);
        c.drawRoundRect(inner, 4 * d, 4 * d, p);
        if (thumb != null) {
            float s = Math.min(inner.width() / thumb.getWidth(), inner.height() / thumb.getHeight());
            float tw = thumb.getWidth() * s, th = thumb.getHeight() * s;
            RectF dst = new RectF(inner.centerX() - tw / 2, inner.top, inner.centerX() + tw / 2, inner.top + th);
            c.save();
            c.clipRect(inner);
            c.drawBitmap(thumb, null, dst, bmp);
            c.restore();
        }
        // 제목 라벨
        p.setColor(Color.WHITE);
        float lh = 30 * d;
        RectF label = new RectF(il + 6 * d, it + 10 * d, ir - 6 * d, it + 10 * d + lh);
        p.setColor(0xEEFFFFFF);
        c.drawRoundRect(label, 6 * d, 6 * d, p);
        p.setColor(0xFF222222);
        p.setTextSize(13 * d);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        String t = meta.title;
        float maxW = label.width() - 12 * d;
        if (p.measureText(t) > maxW) {
            while (t.length() > 1 && p.measureText(t + "…") > maxW) t = t.substring(0, t.length() - 1);
            t = t + "…";
        }
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText(t, label.centerX(), label.centerY() + 4.5f * d, p);
        p.setTextAlign(Paint.Align.LEFT);
        // 뱃지
        if (meta.infinite || meta.favorite) {
            String badge = (meta.favorite ? "★" : "") + (meta.infinite ? " ∞" : "");
            p.setTextSize(14 * d);
            p.setColor(meta.favorite ? 0xFFFFC107 : Color.WHITE);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            c.drawText(badge.trim(), w - pad - 30 * d, h - pad - 4 * d, p);
        }
        if (Paper.isDark(meta.paperColor)) {
            // 다크 속지 표시 없음
        }
    }
}
