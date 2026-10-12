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
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import com.kjt.jiannote.model.Document;
import com.kjt.jiannote.model.NotebookMeta;
import com.kjt.jiannote.model.Page;
import com.kjt.jiannote.render.RenderContext;
import com.kjt.jiannote.render.Renderer;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** PDF / PNG 내보내기 (다운로드 / 사진 폴더에 저장 후 공유) */
public class Exporter {

    private static String safeName(String s) {
        String n = s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (n.isEmpty()) n = "note";
        return n + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(new Date());
    }

    public static Uri exportPdf(Activity a, NotebookMeta m, Document doc, RenderContext rc, boolean watermark) throws IOException {
        PdfDocument pdf = new PdfDocument();
        Paint wm = new Paint(Paint.ANTI_ALIAS_FLAG);
        wm.setColor(0x88888888);
        wm.setTextSize(9);
        int num = 1;
        for (Page p : doc.pages) {
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
            PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(pw, ph, num++).create();
            PdfDocument.Page page = pdf.startPage(info);
            Canvas c = page.getCanvas();
            c.save();
            c.scale(s, s);
            c.translate(-area.left, -area.top);
            Renderer.drawPage(c, p, m.paperColor, rc, area, s, null);
            c.restore();
            if (watermark) {
                c.drawText("JianNote Free", pw - 70, ph - 8, wm);
            }
            pdf.finishPage(page);
        }
        Uri uri = insert(a, MediaStore.Downloads.EXTERNAL_CONTENT_URI, safeName(m.title) + ".pdf",
                "application/pdf", Environment.DIRECTORY_DOWNLOADS + "/JianNote");
        try (OutputStream os = a.getContentResolver().openOutputStream(uri)) {
            pdf.writeTo(os);
        } finally {
            pdf.close();
        }
        finish(a, uri);
        return uri;
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
        Renderer.drawPage(c, p, m.paperColor, rc, area, s, null);
        Uri uri = insert(a, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, safeName(m.title) + ".png",
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
