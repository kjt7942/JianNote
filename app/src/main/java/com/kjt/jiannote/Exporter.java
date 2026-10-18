package com.kjt.jiannote;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import com.kjt.jiannote.model.Document;
import com.kjt.jiannote.model.NotebookMeta;
import com.kjt.jiannote.model.Page;
import com.kjt.jiannote.render.PdfBackground;
import com.kjt.jiannote.render.RenderContext;
import com.kjt.jiannote.render.Renderer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

/** PDF / PNG 내보내기 (다운로드 / 사진 폴더에 저장 후 공유) */
public class Exporter {

    private static String safeName(String s) {
        String n = s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (n.isEmpty()) n = "note";
        return n + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(new Date());
    }

    /** 원본 PDF 를 연결한 페이지의 배경을 내보내기 배율로 다시 그리는 도우미 (호출한 스레드에서 실행) */
    private static class SharpBg implements AutoCloseable {
        private final RenderContext rc;
        private final HashMap<String, ParcelFileDescriptor> fds = new HashMap<>();
        private final HashMap<String, PdfRenderer> pdfs = new HashMap<>();

        SharpBg(RenderContext rc) {
            this.rc = rc;
        }

        /** 페이지 전체를 scale 배율로 그려 rc 에 넣는다. 실패하면 저해상도 배경 그대로 */
        void prepare(Page p, float scale) {
            if (p.bgPdf == null || p.isInfinite() || scale <= 0) return;
            try {
                PdfRenderer r = pdfs.get(p.bgPdf);
                if (r == null) {
                    ParcelFileDescriptor fd = ParcelFileDescriptor.open(new File(rc.imagesDir(), p.bgPdf), ParcelFileDescriptor.MODE_READ_ONLY);
                    fds.put(p.bgPdf, fd);
                    r = new PdfRenderer(fd);
                    pdfs.put(p.bgPdf, r);
                }
                PdfBackground.Patch t = PdfBackground.renderPage(r, p, scale);
                if (t != null) rc.putPdfPatch(p, t);
            } catch (Throwable ignored) {
            }
        }

        @Override
        public void close() {
            for (PdfRenderer r : pdfs.values()) r.close();
            for (ParcelFileDescriptor fd : fds.values()) {
                try {
                    fd.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * PDF 내보내기 때 원본 PDF 배경을 다시 그릴 배율 (페이지 1단위당 픽셀). 최대 2배(A4 기준 약 300dpi).
     * 내보내는 동안 모든 페이지의 배경이 메모리에 남으므로 전체 크기를 제한하고,
     * 지금 배경 이미지(1.25배)보다 나아지지 않으면 0(다시 그리지 않음).
     */
    private static float sharpPdfScale(Document doc) {
        double area = 0;
        for (Page p : doc.pages) if (p.bgPdf != null && !p.isInfinite()) area += (double) p.w * p.h;
        if (area <= 0) return 0;
        double budget = 384.0 * 1024 * 1024;
        float s = (float) Math.min(2.0, Math.sqrt(budget / (area * 4)));
        return s < 1.4f ? 0 : s;
    }

    /**
     * PDF 내보내기. 요소 그리기는 메인 스레드에서만 안전하므로 한 페이지씩 메인 스레드에 나눠 그리고
     * (그 사이 화면은 계속 움직임), 파일 쓰기만 백그라운드에서 한다. progress·done 은 메인 스레드에서 호출.
     */
    public static void exportPdf(Activity a, NotebookMeta m, Document doc, RenderContext rc, boolean watermark,
                                 ImportExport.Progress progress, ImportExport.Done<Uri> done) {
        Handler main = new Handler(Looper.getMainLooper());
        // 이미지 캐시는 함께 쓰고, 선명한 배경 조각은 이 내보내기 전용으로
        RenderContext erc = new RenderContext(rc);
        float bgScale = sharpPdfScale(doc);
        PdfDocument pdf = new PdfDocument();
        SharpBg sharp = new SharpBg(erc);
        Paint wm = new Paint(Paint.ANTI_ALIAS_FLAG);
        wm.setColor(0x88888888);
        wm.setTextSize(9);
        int total = doc.pages.size();
        main.post(new Runnable() {
            int i = 0;

            @Override
            public void run() {
                try {
                    drawPdfPage(pdf, doc.pages.get(i), i + 1, m, erc, sharp, bgScale, watermark ? wm : null);
                } catch (Throwable e) {
                    sharp.close();
                    pdf.close();
                    done.fail(e instanceof OutOfMemoryError ? "메모리가 부족합니다" : String.valueOf(e.getMessage()));
                    return;
                }
                i++;
                progress.on(i, total);
                if (i < total) {
                    main.post(this);
                    return;
                }
                sharp.close();
                new Thread(() -> {
                    try {
                        Uri uri = insert(a, MediaStore.Downloads.EXTERNAL_CONTENT_URI, safeName(m.title) + ".pdf",
                                "application/pdf", Environment.DIRECTORY_DOWNLOADS + "/JianNote");
                        try (OutputStream os = a.getContentResolver().openOutputStream(uri)) {
                            pdf.writeTo(os);
                        }
                        finish(a, uri);
                        main.post(() -> done.ok(uri));
                    } catch (Throwable e) {
                        final String msg = e instanceof OutOfMemoryError ? "메모리가 부족합니다" : String.valueOf(e.getMessage());
                        main.post(() -> done.fail(msg));
                    } finally {
                        pdf.close();
                    }
                }, "jiannote-export").start();
            }
        });
    }

    private static void drawPdfPage(PdfDocument pdf, Page p, int num, NotebookMeta m, RenderContext rc, SharpBg sharp,
                                    float bgScale, Paint watermark) {
        sharp.prepare(p, Math.min(bgScale, 4000f / Math.max(p.w, p.h)));
        RectF area;
        if (p.isInfinite()) {
            RectF cb = Renderer.contentBounds(p);
            if (cb == null) cb = new RectF(0, 0, 1240, 1754);
            cb.inset(-60, -60);
            area = cb;
        } else {
            area = new RectF(0, 0, p.w, p.h);
        }
        // A4 기준 1240px → 595pt
        float s = 595f / 1240f;
        if (p.isInfinite()) s = Math.min(s, 14000f / Math.max(area.width(), area.height()));
        int pw = Math.max(1, Math.round(area.width() * s));
        int ph = Math.max(1, Math.round(area.height() * s));
        PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(pw, ph, num).create();
        PdfDocument.Page page = pdf.startPage(info);
        Canvas c = page.getCanvas();
        c.save();
        c.scale(s, s);
        c.translate(-area.left, -area.top);
        Renderer.drawPage(c, p, m.paperColor, rc, area, s, null);
        c.restore();
        if (watermark != null) {
            c.drawText("JianNote Free", pw - 70, ph - 8, watermark);
        }
        pdf.finishPage(page);
        rc.forgetPdfPatches();
    }

    public static Uri exportPng(Activity a, NotebookMeta m, Page p, RenderContext rc) throws IOException {
        RectF area;
        if (p.isInfinite()) {
            RectF cb = Renderer.contentBounds(p);
            if (cb == null) cb = new RectF(0, 0, 1200, 900);
            cb.inset(-40, -40);
            area = cb;
        } else {
            area = new RectF(0, 0, p.w, p.h);
        }
        float s = Math.min(2f, 4096f / Math.max(area.width(), area.height()));
        Bitmap b = Bitmap.createBitmap(Math.max(1, (int) (area.width() * s)), Math.max(1, (int) (area.height() * s)), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.WHITE);
        c.scale(s, s);
        c.translate(-area.left, -area.top);
        RenderContext erc = new RenderContext(rc);
        try (SharpBg sharp = new SharpBg(erc)) {
            sharp.prepare(p, s);
            Renderer.drawPage(c, p, m.paperColor, erc, area, s, null);
        }
        erc.keepPdfPatches(java.util.Collections.emptyList());
        Uri uri = insert(a, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, safeName(m.title) + ".png",
                "image/png", Environment.DIRECTORY_PICTURES + "/JianNote");
        try (OutputStream os = a.getContentResolver().openOutputStream(uri)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, os);
        }
        b.recycle();
        finish(a, uri);
        return uri;
    }

    /** 페이지의 일부 영역(올가미 선택 영역)을 이미지로 저장 */
    public static Uri exportRegion(Activity a, NotebookMeta m, Page p, RenderContext rc, RectF region) throws IOException {
        RectF area = new RectF(region);
        area.inset(-10, -10);
        if (!p.isInfinite()) {
            area.left = Math.max(0, area.left);
            area.top = Math.max(0, area.top);
            area.right = Math.min(p.w, area.right);
            area.bottom = Math.min(p.h, area.bottom);
        }
        if (area.width() < 2 || area.height() < 2) throw new IOException("캡처할 영역이 너무 작습니다");
        float s = Math.min(3f, 4096f / Math.max(area.width(), area.height()));
        Bitmap b = Bitmap.createBitmap(Math.max(1, (int) (area.width() * s)), Math.max(1, (int) (area.height() * s)), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.WHITE);
        c.scale(s, s);
        c.translate(-area.left, -area.top);
        RenderContext erc = new RenderContext(rc);
        try (SharpBg sharp = new SharpBg(erc)) {
            sharp.prepare(p, s);
            Renderer.drawPage(c, p, m.paperColor, erc, area, s, null);
        }
        erc.keepPdfPatches(java.util.Collections.emptyList());
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.KOREA).format(new java.util.Date());
        Uri uri = insert(a, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, safeName(m.title) + "_캡처_" + stamp + ".png",
                "image/png", Environment.DIRECTORY_PICTURES + "/JianNote");
        try (OutputStream os = a.getContentResolver().openOutputStream(uri)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, os);
        }
        b.recycle();
        finish(a, uri);
        return uri;
    }

    private static Uri insert(Activity a, Uri collection, String name, String mime, String relPath) throws IOException {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, relPath);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = a.getContentResolver().insert(collection, v);
        if (uri == null) throw new IOException("저장 위치를 만들 수 없습니다");
        return uri;
    }

    private static void finish(Activity a, Uri uri) {
        ContentResolver r = a.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.IS_PENDING, 0);
        r.update(uri, v, null, null);
    }

    public static void share(Activity a, Uri uri, String mime) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        a.startActivity(Intent.createChooser(i, "공유"));
    }
}
