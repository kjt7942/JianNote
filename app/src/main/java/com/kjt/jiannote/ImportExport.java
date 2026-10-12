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
        run(() -> {
            File tmp = new File(a.getCacheDir(), "import.pdf");
            NotebookMeta meta = null;
            try {
                copy(a.getContentResolver().openInputStream(uri), new FileOutputStream(tmp));
                try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY);
                     PdfRenderer pdf = new PdfRenderer(fd)) {
                    int n = Math.min(pdf.getPageCount(), MAX_PDF_PAGES);
                    if (n == 0) throw new IOException("페이지가 없는 PDF입니다");
                    NotebookMeta m = new NotebookMeta();
                    m.title = title;
                    m.coverColor = Ui.COVER_COLORS[(int) (Math.random() * Ui.COVER_COLORS.length)];
                    m.paperColor = 0xFFFFFFFF;
                    m.template = Paper.BLANK;
                    meta = store.create(m);
                    RenderContext rc = new RenderContext(store.dir(meta.id));
                    File imgDir = rc.imagesDir();
                    Document doc = new Document();
                    for (int i = 0; i < n; i++) {
                        try (PdfRenderer.Page pg = pdf.openPage(i)) {
                            float pw = pg.getWidth(), ph = pg.getHeight();
                            float w = pw >= ph ? 1754 : 1240;
                            float h = w * ph / Math.max(1f, pw);
                            int bw = Math.round(w * 1.25f), bh = Math.round(h * 1.25f);
                            Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                            bmp.eraseColor(Color.WHITE);
                            pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                            String name = "pdf_" + i + "_" + UUID.randomUUID().toString().substring(0, 6) + ".jpg";
                            try (OutputStream os = new FileOutputStream(new File(imgDir, name))) {
                                bmp.compress(Bitmap.CompressFormat.JPEG, 90, os);
                            }
                            bmp.recycle();
                            Page p = Page.create(w, h, Paper.BLANK);
                            p.bgImage = name;
                            doc.pages.add(p);
                            if (i == 0) meta.landscape = w > h;
                        }
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
            } else if (!f.getName().endsWith(".tmp")) {
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
