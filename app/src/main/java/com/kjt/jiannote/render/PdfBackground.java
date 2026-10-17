package com.kjt.jiannote.render;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import com.kjt.jiannote.model.Page;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 원본 PDF 를 보관한 페이지의 배경을 PDF 뷰어처럼 화면 배율에 맞춰 다시 그린다.
 * 화면이 멈추면 보이는 부분만 그 배율로 렌더링한 조각(Patch)을 만들어 RenderContext 에 넣고,
 * Renderer 는 저해상도 배경 이미지 위에 이 조각을 덮어 그린다.
 */
public class PdfBackground {

    /** 페이지 일부(rect, 페이지 좌표)를 scale 배율로 렌더링한 조각 */
    public static class Patch {
        public final String file;
        public final int index;
        public final float pageW, pageH;
        public final RectF rect;
        public final float scale;
        public final Bitmap bmp;

        Patch(String file, int index, float pageW, float pageH, RectF rect, float scale, Bitmap bmp) {
            this.file = file;
            this.index = index;
            this.pageW = pageW;
            this.pageH = pageH;
            this.rect = rect;
            this.scale = scale;
            this.bmp = bmp;
        }

        /** 페이지의 배경·크기가 그대로인지 (속지를 바꾸거나 크기를 바꾸면 버림) */
        public boolean matches(Page p) {
            return file.equals(p.bgPdf) && index == p.bgPdfPage && pageW == p.w && pageH == p.h && !bmp.isRecycled();
        }
    }

    private static class Job {
        Page page;
        String file;
        int index;
        float pageW, pageH;
        RectF rect;
        float scale;
    }

    private final RenderContext rc;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> new Thread(r, "jiannote-pdf"));
    private volatile int generation;
    private volatile boolean closed;

    // 아래는 작업 스레드에서만 사용
    private String openName;
    private ParcelFileDescriptor openFd;
    private PdfRenderer openPdf;

    public PdfBackground(RenderContext rc) {
        this.rc = rc;
    }

    /**
     * 화면에 보이는 PDF 페이지들의 선명한 조각을 요청한다 (메인 스레드).
     * pages[i] 의 보이는 영역 = vis[i] (페이지 좌표), origin[i] = 페이지 왼쪽 위의 화면 좌표.
     * budgetPx = 조각 전체 픽셀 상한. 이미 충분히 선명한 조각이 있는 페이지는 건너뛴다.
     * 새 조각이 준비되면 onReady 실행.
     */
    public void request(List<Page> pages, List<RectF> vis, List<float[]> origin, float scale, long budgetPx, Runnable onReady) {
        if (closed) return;
        rc.keepPdfPatches(pages);
        ArrayList<Job> jobs = new ArrayList<>();
        long perPage = budgetPx / Math.max(1, pages.size());
        // 화면 픽셀 경계에 맞추느라 가장자리가 1픽셀 안쪽으로 줄 수 있음
        float tol = Math.max(0.5f, 1.5f / scale);
        for (int i = 0; i < pages.size(); i++) {
            Page p = pages.get(i);
            RectF v = new RectF(vis.get(i));
            if (!v.intersect(0, 0, p.w, p.h)) continue;
            Patch have = rc.pdfPatch(p);
            if (have != null && have.scale >= scale * 0.97f && have.scale <= scale * 2.5f
                    && have.rect.left <= v.left + tol && have.rect.top <= v.top + tol
                    && have.rect.right >= v.right - tol && have.rect.bottom >= v.bottom - tol) continue;
            Job j = new Job();
            j.page = p;
            j.file = p.bgPdf;
            j.index = p.bgPdfPage;
            j.pageW = p.w;
            j.pageH = p.h;
            j.scale = scale;
            j.rect = snap(expand(v, p, scale, perPage), origin.get(i), scale);
            jobs.add(j);
        }
        if (jobs.isEmpty()) return;
        final int gen = ++generation;
        exec.execute(() -> {
            for (Job j : jobs) {
                if (closed || gen != generation) return;
                Bitmap b;
                try {
                    b = render(j);
                } catch (Throwable e) {
                    continue;
                }
                if (b == null) continue;
                Patch t = new Patch(j.file, j.index, j.pageW, j.pageH, j.rect, j.scale, b);
                main.post(() -> {
                    if (closed || gen != generation || !t.matches(j.page)) {
                        b.recycle();
                        return;
                    }
                    rc.putPdfPatch(j.page, t);
                    onReady.run();
                });
            }
        });
    }

    /** 조금 움직여도 다시 그리지 않도록 보이는 영역 주변을 여유 있게 (픽셀 상한 안에서) */
    private static RectF expand(RectF v, Page p, float scale, long maxPx) {
        for (float m = 0.3f; ; m -= 0.1f) {
            RectF r = new RectF(v);
            if (m > 0) r.inset(-v.width() * m, -v.height() * m);
            r.intersect(0, 0, p.w, p.h);
            if (m <= 0 || (double) r.width() * r.height() * scale * scale <= maxPx) return r;
        }
    }

    /** 조각의 네 변을 화면 픽셀 경계에 맞춘다 — 그대로 옮겨 그려져 반 픽셀 번짐이 없게 */
    private static RectF snap(RectF r, float[] o, float s) {
        float l = (float) Math.ceil(o[0] + r.left * s), t = (float) Math.ceil(o[1] + r.top * s);
        float rr = (float) Math.floor(o[0] + r.right * s), b = (float) Math.floor(o[1] + r.bottom * s);
        if (rr - l < 1 || b - t < 1) return r;
        return new RectF((l - o[0]) / s, (t - o[1]) / s, (rr - o[0]) / s, (b - o[1]) / s);
    }

    private Bitmap render(Job j) throws IOException {
        if (!j.file.equals(openName)) {
            closeRenderer();
            openFd = ParcelFileDescriptor.open(new File(rc.imagesDir(), j.file), ParcelFileDescriptor.MODE_READ_ONLY);
            openPdf = new PdfRenderer(openFd);
            openName = j.file;
        }
        if (j.index < 0 || j.index >= openPdf.getPageCount()) return null;
        return renderRegion(openPdf, j.index, j.pageW, j.pageH, j.rect, j.scale);
    }

    /**
     * PDF 한 페이지의 일부를 렌더링. 페이지(pageW×pageH)에 PDF 를 비율 유지·가운데 맞춰 꽉 채운 배치
     * (Renderer 의 배경 이미지 배치와 같음)에서 rect 영역을 scale 배율로 그린다.
     */
    public static Bitmap renderRegion(PdfRenderer pdf, int index, float pageW, float pageH, RectF rect, float scale) {
        try (PdfRenderer.Page pg = pdf.openPage(index)) {
            float pw = pg.getWidth(), ph = pg.getHeight();
            float k = Math.max(pageW / pw, pageH / ph);
            float ox = (pageW - pw * k) / 2f, oy = (pageH - ph * k) / 2f;
            int bw = Math.max(1, Math.round(rect.width() * scale));
            int bh = Math.max(1, Math.round(rect.height() * scale));
            Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            b.eraseColor(Color.WHITE);
            Matrix m = new Matrix();
            m.setScale(k, k);
            m.postTranslate(ox - rect.left, oy - rect.top);
            m.postScale(scale, scale);
            pg.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return b;
        }
    }

    /** 페이지 전체를 scale 배율로 렌더링한 조각 (내보내기용, 호출한 스레드에서 실행) */
    public static Patch renderPage(PdfRenderer pdf, Page p, float scale) {
        if (p.bgPdfPage < 0 || p.bgPdfPage >= pdf.getPageCount()) return null;
        RectF r = new RectF(0, 0, p.w, p.h);
        return new Patch(p.bgPdf, p.bgPdfPage, p.w, p.h, r, scale, renderRegion(pdf, p.bgPdfPage, p.w, p.h, r, scale));
    }

    private void closeRenderer() {
        try {
            if (openPdf != null) openPdf.close();
            if (openFd != null) openFd.close();
        } catch (Exception ignored) {
        }
        openPdf = null;
        openFd = null;
        openName = null;
    }

    /** 화면을 닫을 때 (메인 스레드) */
    public void close() {
        if (closed) return;
        closed = true;
        generation++;
        exec.execute(this::closeRenderer);
        exec.shutdown();
        rc.keepPdfPatches(java.util.Collections.emptyList());
    }
}
