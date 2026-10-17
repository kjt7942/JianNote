package com.kjt.jiannote;

import android.app.Activity;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.Color;
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
import com.kjt.jiannote.render.Paper;
import com.kjt.jiannote.render.RenderContext;
import com.kjt.jiannote.render.Renderer;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** PDF 가져오기, 노트 백업(.jnote) 내보내기/가져오기 — 모두 백그라운드 스레드에서 실행 */
public class ImportExport {
    public interface Progress {
        void on(int done, int total);
    }

    public interface Done<T> {
        void ok(T result);

        void fail(String message);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    public static final int MAX_PDF_PAGES = 500;

    private static void run(Runnable r) {
        new Thread(r, "jiannote-io").start();
    }

    // ================= PDF 가져오기 =================

    public static void importPdf(Activity a, Store store, Uri uri, String title, Progress progress, Done<NotebookMeta> done) {
        importPdf(a, store, uri, title, null, progress, done);
    }

    public static void importPdf(Activity a, Store store, Uri uri, String title, NotebookMeta base, Progress progress, Done<NotebookMeta> done) {
        run(() -> {
            File tmp = new File(a.getCacheDir(), "import_" + UUID.randomUUID() + ".pdf");
            NotebookMeta meta = null;
            try {
                copy(a.getContentResolver().openInputStream(uri), new FileOutputStream(tmp));
                try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY);
                     PdfRenderer pdf = new PdfRenderer(fd)) {
                    int n = Math.min(pdf.getPageCount(), MAX_PDF_PAGES);
                    if (n == 0) throw new IOException("페이지가 없는 PDF입니다");
                    NotebookMeta m = base != null ? base : defaultMeta(title);
                    m.infinite = false;
                    meta = store.create(m);
                    RenderContext rc = new RenderContext(store.dir(meta.id));
                    File imgDir = rc.imagesDir();
                    Document doc = new Document();
                    // 원본 PDF 를 노트 안에 보관 → 확대할 때 원본에서 선명하게 다시 그림
                    String src = newPdfName();
                    File srcFile = new File(imgDir, src);
                    if (!tmp.renameTo(srcFile)) copy(new FileInputStream(tmp), new FileOutputStream(srcFile));
                    for (int i = 0; i < n; i++) {
                        Page p = pdfPage(pdf, i, src, imgDir);
                        doc.pages.add(p);
                        if (i == 0) meta.landscape = p.w > p.h;
                        final int d = i + 1;
                        MAIN.post(() -> progress.on(d, n));
                    }
                    store.saveDoc(meta.id, doc);
                    meta.pageCount = doc.pages.size();
                    store.saveMeta(meta);
                    Bitmap th = Renderer.thumbnail(doc.pages.get(0), meta.paperColor, rc, 360, 500);
                    store.saveThumb(meta.id, th);
                    final NotebookMeta result = meta;
                    MAIN.post(() -> done.ok(result));
                }
            } catch (SecurityException e) {
                if (meta != null) store.delete(meta.id);
                MAIN.post(() -> done.fail("암호가 걸린 PDF는 가져올 수 없습니다"));
            } catch (Throwable e) {
                if (meta != null) store.delete(meta.id);
                final String msg = e instanceof OutOfMemoryError ? "메모리가 부족합니다" : String.valueOf(e.getMessage());
                MAIN.post(() -> done.fail(msg));
            } finally {
                tmp.delete();
            }
        });
    }

    // ================= 이미지 → 페이지 =================

    /** 이미지 파일 하나를 배경으로 하는 새 페이지 (images 폴더에 저장) */
    public static Page imageToPage(android.content.Context c, Uri uri, File imgDir) throws IOException {
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            android.graphics.BitmapFactory.decodeStream(in, null, o);
        }
        if (o.outWidth <= 0) throw new IOException("이미지를 읽을 수 없습니다");
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / sample > 3000) sample *= 2;
        android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap bm;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            bm = android.graphics.BitmapFactory.decodeStream(in, null, o2);
        }
        if (bm == null) throw new IOException("이미지를 읽을 수 없습니다");
        bm = rotateByExif(c, uri, bm);
        String name = "pg_" + UUID.randomUUID().toString().substring(0, 10) + ".jpg";
        Bitmap flat = Bitmap.createBitmap(bm.getWidth(), bm.getHeight(), Bitmap.Config.ARGB_8888);
        flat.eraseColor(Color.WHITE);
        new android.graphics.Canvas(flat).drawBitmap(bm, 0, 0, null);
        try (OutputStream os = new FileOutputStream(new File(imgDir, name))) {
            flat.compress(Bitmap.CompressFormat.JPEG, 92, os);
        }
        float w = bm.getWidth() >= bm.getHeight() ? 1754 : 1240;
        float h = w * bm.getHeight() / Math.max(1f, bm.getWidth());
        bm.recycle();
        flat.recycle();
        Page p = Page.create(w, h, Paper.BLANK);
        p.bgImage = name;
        return p;
    }

    private static NotebookMeta defaultMeta(String title) {
        NotebookMeta m = new NotebookMeta();
        m.title = title;
        m.coverColor = Ui.COVER_COLORS[(int) (Math.random() * Ui.COVER_COLORS.length)];
        m.paperColor = 0xFFFFFFFF;
        m.template = Paper.BLANK;
        return m;
    }

    /** 원본 PDF 를 보관할 파일 이름 (images/src_*.pdf) */
    private static String newPdfName() {
        return "src_" + UUID.randomUUID().toString().substring(0, 8) + ".pdf";
    }

    /** PDF 원본을 노트의 images 폴더에 복사하고 파일 이름을 돌려준다 */
    public static String copyPdf(android.content.Context c, Uri uri, File imgDir) throws IOException {
        String name = newPdfName();
        File f = new File(imgDir, name);
        try {
            copy(c.getContentResolver().openInputStream(uri), new FileOutputStream(f));
        } catch (IOException e) {
            f.delete();
            throw e;
        }
        return name;
    }

    /**
     * PDF 한 페이지를 그 크기 그대로의 새 페이지로. 배경 = 미리보기용 이미지(스크롤 중·썸네일)
     * + 원본 PDF(src) 연결 — 화면이 멈추면 원본에서 화면 배율로 다시 그린다.
     */
    private static Page pdfPage(PdfRenderer pdf, int index, String src, File imgDir) throws IOException {
        try (PdfRenderer.Page pg = pdf.openPage(index)) {
            float pw = pg.getWidth(), ph = pg.getHeight();
            float w = pw >= ph ? 1754 : 1240;
            float h = w * ph / Math.max(1f, pw);
            Bitmap bmp = Bitmap.createBitmap(Math.round(w * 1.25f), Math.round(h * 1.25f), Bitmap.Config.ARGB_8888);
            bmp.eraseColor(Color.WHITE);
            pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            String name = "pdf_" + index + "_" + UUID.randomUUID().toString().substring(0, 6) + ".jpg";
            try (OutputStream os = new FileOutputStream(new File(imgDir, name))) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, os);
            }
            bmp.recycle();
            Page p = Page.create(w, h, Paper.BLANK);
            p.setBackground(name, src, index);
            return p;
        }
    }

    /** PDF 의 모든 페이지(최대 MAX_PDF_PAGES)를 새 페이지로 — 원본은 한 번만 복사해 함께 보관 */
    public static List<Page> pdfToPages(android.content.Context c, Uri uri, File imgDir) throws IOException {
        String src = copyPdf(c, uri, imgDir);
        File f = new File(imgDir, src);
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer pdf = new PdfRenderer(fd)) {
            int n = Math.min(pdf.getPageCount(), MAX_PDF_PAGES);
            if (n == 0) throw new IOException("페이지가 없는 PDF입니다");
            ArrayList<Page> out = new ArrayList<>();
            for (int i = 0; i < n; i++) out.add(pdfPage(pdf, i, src, imgDir));
            return out;
        } catch (SecurityException e) {
            f.delete();
            throw new IOException("암호가 걸린 PDF는 사용할 수 없습니다");
        } catch (IOException | RuntimeException e) {
            f.delete();
            throw e;
        }
    }

    // ================= 사진·PDF 속지 =================

    /** 사진을 속지 이미지(tpl_*.jpg)로 저장하고 파일 이름을 돌려준다 */
    public static String templateFromImage(android.content.Context c, Uri uri, File imgDir) throws IOException {
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            android.graphics.BitmapFactory.decodeStream(in, null, o);
        }
        if (o.outWidth <= 0) throw new IOException("이미지를 읽을 수 없습니다");
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / sample > 2600) sample *= 2;
        android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap bm;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            bm = android.graphics.BitmapFactory.decodeStream(in, null, o2);
        }
        if (bm == null) throw new IOException("이미지를 읽을 수 없습니다");
        bm = rotateByExif(c, uri, bm);
        return saveTemplate(bm, imgDir);
    }

    public static int pdfPageCount(android.content.Context c, Uri uri) throws IOException {
        try (ParcelFileDescriptor fd = c.getContentResolver().openFileDescriptor(uri, "r");
             PdfRenderer pdf = new PdfRenderer(fd)) {
            return pdf.getPageCount();
        }
    }

    /** PDF 의 한 페이지(0부터)를 속지 이미지로. 돌려주는 값 = {속지 이미지, 원본 PDF} 파일 이름 */
    public static String[] templateFromPdf(android.content.Context c, Uri uri, int page, File imgDir) throws IOException {
        String src = copyPdf(c, uri, imgDir);
        File f = new File(imgDir, src);
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer pdf = new PdfRenderer(fd)) {
            page = Math.max(0, Math.min(page, pdf.getPageCount() - 1));
            try (PdfRenderer.Page pg = pdf.openPage(page)) {
                float k = 2200f / Math.max(pg.getWidth(), pg.getHeight());
                Bitmap bmp = Bitmap.createBitmap(Math.round(pg.getWidth() * k), Math.round(pg.getHeight() * k), Bitmap.Config.ARGB_8888);
                bmp.eraseColor(Color.WHITE);
                pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return new String[]{saveTemplate(bmp, imgDir), src};
            }
        } catch (SecurityException e) {
            f.delete();
            throw new IOException("암호가 걸린 PDF는 사용할 수 없습니다");
        } catch (IOException | RuntimeException e) {
            f.delete();
            throw e;
        }
    }

    private static String saveTemplate(Bitmap bm, File imgDir) throws IOException {
        Bitmap flat = Bitmap.createBitmap(bm.getWidth(), bm.getHeight(), Bitmap.Config.ARGB_8888);
        flat.eraseColor(Color.WHITE);
        new android.graphics.Canvas(flat).drawBitmap(bm, 0, 0, null);
        String name = "tpl_" + UUID.randomUUID().toString().substring(0, 10) + ".jpg";
        try (OutputStream os = new FileOutputStream(new File(imgDir, name))) {
            flat.compress(Bitmap.CompressFormat.JPEG, 92, os);
        }
        bm.recycle();
        flat.recycle();
        return name;
    }

    private static Bitmap rotateByExif(android.content.Context c, Uri uri, Bitmap bm) {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            android.media.ExifInterface ex = new android.media.ExifInterface(in);
            int or = ex.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1);
            int deg = or == 6 ? 90 : or == 3 ? 180 : or == 8 ? 270 : 0;
            if (deg == 0) return bm;
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.postRotate(deg);
            Bitmap r = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
            if (r != bm) bm.recycle();
            return r;
        } catch (Exception e) {
            return bm;
        }
    }

    /** 이미지 여러 장을 페이지로 하는 새 노트 */
    public static void importImages(Activity a, Store store, List<Uri> uris, String title, Progress progress, Done<NotebookMeta> done) {
        importImages(a, store, uris, title, null, progress, done);
    }

    public static void importImages(Activity a, Store store, List<Uri> uris, String title, NotebookMeta base, Progress progress, Done<NotebookMeta> done) {
        run(() -> {
            NotebookMeta meta = null;
            try {
                NotebookMeta m = base != null ? base : defaultMeta(title);
                m.infinite = false;
                meta = store.create(m);
                RenderContext rc = new RenderContext(store.dir(meta.id));
                Document doc = new Document();
                for (int i = 0; i < uris.size(); i++) {
                    doc.pages.add(imageToPage(a, uris.get(i), rc.imagesDir()));
                    final int d = i + 1;
                    MAIN.post(() -> progress.on(d, uris.size()));
                }
                store.saveDoc(meta.id, doc);
                meta.pageCount = doc.pages.size();
                meta.landscape = doc.pages.get(0).w > doc.pages.get(0).h;
                store.saveMeta(meta);
                Bitmap th = Renderer.thumbnail(doc.pages.get(0), meta.paperColor, rc, 360, 500);
                store.saveThumb(meta.id, th);
                final NotebookMeta result = meta;
                MAIN.post(() -> done.ok(result));
            } catch (Throwable e) {
                if (meta != null) store.delete(meta.id);
                final String msg = e instanceof OutOfMemoryError ? "메모리가 부족합니다" : String.valueOf(e.getMessage());
                MAIN.post(() -> done.fail(msg));
            }
        });
    }

    /** 표지 이미지 저장 (cover.jpg, 긴 변 900px) */
    public static void saveCover(android.content.Context c, Uri uri, File dir) throws IOException {
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            android.graphics.BitmapFactory.decodeStream(in, null, o);
        }
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / sample > 1800) sample *= 2;
        android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap bm;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            bm = android.graphics.BitmapFactory.decodeStream(in, null, o2);
        }
        if (bm == null) throw new IOException("이미지를 읽을 수 없습니다");
        bm = rotateByExif(c, uri, bm);
        float k = Math.min(1f, 900f / Math.max(bm.getWidth(), bm.getHeight()));
        Bitmap sc = Bitmap.createScaledBitmap(bm, Math.max(1, Math.round(bm.getWidth() * k)), Math.max(1, Math.round(bm.getHeight() * k)), true);
        try (OutputStream os = new FileOutputStream(new File(dir, "cover.jpg"))) {
            sc.compress(Bitmap.CompressFormat.JPEG, 90, os);
        }
    }

    // ================= 백업 내보내기 =================

    /** 노트(들)를 하나의 .jnote 파일로 다운로드 폴더에 저장 */
    public static void exportBackup(Activity a, Store store, List<NotebookMeta> notes, String fileTitle, Done<Uri> done) {
        run(() -> {
            try {
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.KOREA).format(new Date());
                String name = fileTitle.replaceAll("[\\\\/:*?\"<>|]", "_") + "_" + stamp + ".jnote";
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/JianNote");
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri uri = a.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IOException("저장 위치를 만들 수 없습니다");
                try (ZipOutputStream zip = new ZipOutputStream(a.getContentResolver().openOutputStream(uri))) {
                    for (NotebookMeta m : notes) {
                        File dir = store.dir(m.id);
                        addDir(zip, dir, m.id + "/");
                    }
                }
                ContentValues done2 = new ContentValues();
                done2.put(MediaStore.MediaColumns.IS_PENDING, 0);
                a.getContentResolver().update(uri, done2, null, null);
                MAIN.post(() -> done.ok(uri));
            } catch (Throwable e) {
                final String msg = String.valueOf(e.getMessage());
                MAIN.post(() -> done.fail(msg));
            }
        });
    }

    private static void addDir(ZipOutputStream zip, File dir, String prefix) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                addDir(zip, f, prefix + f.getName() + "/");
            } else if (!f.getName().endsWith(".tmp") && !f.getName().endsWith(".bak")) {
                zip.putNextEntry(new ZipEntry(prefix + f.getName()));
                try (InputStream in = new FileInputStream(f)) {
                    byte[] buf = new byte[1 << 16];
                    int r;
                    while ((r = in.read(buf)) > 0) zip.write(buf, 0, r);
                }
                zip.closeEntry();
            }
        }
    }

    // ================= 백업 가져오기 =================

    /** .jnote 파일 가져오기. limit = 더 만들 수 있는 노트 수 */
    public static void importBackup(Activity a, Store store, Uri uri, int limit, Done<Integer> done) {
        run(() -> {
            HashMap<String, String> idMap = new HashMap<>();
            ArrayList<String> created = new ArrayList<>();
            try (ZipInputStream zin = new ZipInputStream(a.getContentResolver().openInputStream(uri))) {
                ZipEntry e;
                while ((e = zin.getNextEntry()) != null) {
                    String name = e.getName().replace('\\', '/');
                    if (e.isDirectory() || name.contains("..") || name.startsWith("/")) continue;
                    int slash = name.indexOf('/');
                    if (slash <= 0) continue;
                    String top = name.substring(0, slash);
                    String rest = name.substring(slash + 1);
                    String newId = idMap.get(top);
                    if (newId == null) {
                        if (idMap.size() >= limit) continue;
                        newId = UUID.randomUUID().toString().substring(0, 12);
                        idMap.put(top, newId);
                        created.add(newId);
                    }
                    File base = store.dir(newId);
                    File out = new File(base, rest);
                    if (!out.getCanonicalPath().startsWith(base.getCanonicalPath() + File.separator)) continue;
                    out.getParentFile().mkdirs();
                    try (OutputStream os = new FileOutputStream(out)) {
                        byte[] buf = new byte[1 << 16];
                        int r;
                        while ((r = zin.read(buf)) > 0) os.write(buf, 0, r);
                    }
                }
                int ok = 0;
                for (String id : created) {
                    File metaFile = new File(store.dir(id), "meta.json");
                    File docFile = new File(store.dir(id), "doc.bin");
                    if (!metaFile.exists() || !docFile.exists()) {
                        store.delete(id);
                        continue;
                    }
                    NotebookMeta m = NotebookMeta.fromJson(new JSONObject(Store.readText(metaFile)));
                    m.id = id;
                    m.favorite = false;
                    store.saveMeta(m);
                    ok++;
                }
                final int count = ok;
                MAIN.post(() -> {
                    if (count == 0) done.fail("올바른 JianNote 백업 파일이 아닙니다");
                    else done.ok(count);
                });
            } catch (Throwable ex) {
                for (String id : created) store.delete(id);
                final String msg = String.valueOf(ex.getMessage());
                MAIN.post(() -> done.fail(msg));
            }
        });
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        try (InputStream i = in; OutputStream o = out) {
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = i.read(buf)) > 0) o.write(buf, 0, r);
        }
    }
}
