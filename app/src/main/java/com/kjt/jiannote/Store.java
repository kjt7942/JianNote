package com.kjt.jiannote;

import android.content.Context;
import android.graphics.Bitmap;

import com.kjt.jiannote.model.Document;
import com.kjt.jiannote.model.NotebookMeta;
import com.kjt.jiannote.model.Page;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;

/** 노트북 파일 저장소: files/notebooks/&lt;id&gt;/{meta.json, doc.bin, thumb.png, images/} */
public class Store {
    private final File root;

    public Store(Context ctx) {
        root = new File(ctx.getFilesDir(), "notebooks");
        if (!root.exists()) root.mkdirs();
    }

    public File dir(String id) {
        return new File(root, id);
    }

    public File thumbFile(String id) {
        return new File(dir(id), "thumb.png");
    }

    public ArrayList<NotebookMeta> list() {
        ArrayList<NotebookMeta> out = new ArrayList<>();
        File[] dirs = root.listFiles();
        if (dirs == null) return out;
        for (File d : dirs) {
            File m = new File(d, "meta.json");
            if (!m.exists()) continue;
            try {
                out.add(NotebookMeta.fromJson(new JSONObject(readText(m))));
            } catch (Exception ignored) {
            }
        }
        Collections.sort(out, (a, b) -> {
            if (a.favorite != b.favorite) return a.favorite ? -1 : 1;
            return Long.compare(b.modified, a.modified);
        });
        return out;
    }

    public int count() {
        return list().size();
    }

    public NotebookMeta create(NotebookMeta m) throws IOException {
        m.id = UUID.randomUUID().toString().substring(0, 12);
        m.created = m.modified = System.currentTimeMillis();
        File d = dir(m.id);
        d.mkdirs();
        Document doc = new Document();
        doc.pages.add(Page.create(m.pageW(), m.pageH(), m.template));
        m.pageCount = 1;
        doc.write(new File(d, "doc.bin"));
        saveMeta(m);
        return m;
    }

    public void saveMeta(NotebookMeta m) throws IOException {
        try {
            writeText(new File(dir(m.id), "meta.json"), m.toJson().toString(2));
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
    }

    public NotebookMeta loadMeta(String id) throws IOException {
        try {
            return NotebookMeta.fromJson(new JSONObject(readText(new File(dir(id), "meta.json"))));
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
    }

    public Document loadDoc(String id) throws IOException {
        File f = new File(dir(id), "doc.bin");
        try {
            return Document.read(f);
        } catch (Exception e) {
            File bak = new File(dir(id), "doc.bin.bak");
            if (bak.exists()) return Document.read(bak);
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    public void saveDoc(String id, Document doc) throws IOException {
        doc.write(new File(dir(id), "doc.bin"));
    }

    public void saveThumb(String id, Bitmap b) {
        try (OutputStream os = new FileOutputStream(thumbFile(id))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, os);
        } catch (IOException ignored) {
        }
    }

    public void delete(String id) {
        deleteRec(dir(id));
    }

    public NotebookMeta duplicate(NotebookMeta src) throws IOException {
        NotebookMeta m = loadMeta(src.id);
        String newId = UUID.randomUUID().toString().substring(0, 12);
        copyRec(dir(src.id), dir(newId));
        m.id = newId;
        m.title = src.title + " 사본";
        m.favorite = false;
        m.created = m.modified = System.currentTimeMillis();
        saveMeta(m);
        return m;
    }

    private static void deleteRec(File f) {
        File[] ch = f.listFiles();
        if (ch != null) for (File c : ch) deleteRec(c);
        f.delete();
    }

    private static void copyRec(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            dst.mkdirs();
            File[] ch = src.listFiles();
            if (ch != null) for (File c : ch) copyRec(c, new File(dst, c.getName()));
        } else {
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[1 << 16];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            }
        }
    }

    static String readText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            while (off < b.length) {
                int r = in.read(b, off, b.length - off);
                if (r < 0) break;
                off += r;
            }
            return new String(b, 0, off, StandardCharsets.UTF_8);
        }
    }

    static void writeText(File f, String s) throws IOException {
        try (OutputStream o = new FileOutputStream(f)) {
            o.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }
}
