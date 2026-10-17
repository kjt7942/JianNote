package com.kjt.jiannote.render;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import com.kjt.jiannote.model.Page;

import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class RenderContext {
    public final File dir;
    private final LruCache<String, Bitmap> cache;
    /** 원본 PDF 를 화면 배율로 다시 그린 배경 조각 (PdfBackground 가 채움) */
    private final HashMap<Page, PdfBackground.Patch> pdfPatches = new HashMap<>();

    public RenderContext(File notebookDir) {
        this.dir = notebookDir;
        this.cache = new LruCache<String, Bitmap>(120 * 1024 * 1024) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
    }

    /** 이미지 캐시는 함께 쓰고 PDF 조각은 따로 갖는 사본 (내보내기 스레드용) */
    public RenderContext(RenderContext shareImages) {
        this.dir = shareImages.dir;
        this.cache = shareImages.cache;
    }

    public File imagesDir() {
        File f = new File(dir, "images");
        if (!f.exists()) f.mkdirs();
        return f;
    }

    public Bitmap image(String name) {
        Bitmap b = cache.get(name);
        if (b == null) {
            File f = new File(imagesDir(), name);
            if (!f.exists()) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            if (name.endsWith(".jpg")) o.inPreferredConfig = Bitmap.Config.RGB_565;
            b = BitmapFactory.decodeFile(f.getPath(), o);
            if (b != null) cache.put(name, b);
        }
        return b;
    }

    /** 이 페이지의 현재 배경과 맞는 선명한 PDF 조각, 없으면 null */
    public PdfBackground.Patch pdfPatch(Page p) {
        if (p.bgPdf == null) return null;
        PdfBackground.Patch t = pdfPatches.get(p);
        return t != null && t.matches(p) ? t : null;
    }

    public void putPdfPatch(Page p, PdfBackground.Patch t) {
        PdfBackground.Patch old = pdfPatches.put(p, t);
        if (old != null && old != t) old.bmp.recycle();
    }

    /** 조각을 목록에서만 뺀다 (recycle 하지 않음 — 내보낸 PDF 가 아직 그 비트맵을 쓰고 있을 수 있음) */
    public void forgetPdfPatches() {
        pdfPatches.clear();
    }

    /** keep 에 없는 페이지의 조각은 버린다 */
    public void keepPdfPatches(Collection<Page> keep) {
        Iterator<Map.Entry<Page, PdfBackground.Patch>> it = pdfPatches.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Page, PdfBackground.Patch> e = it.next();
            if (!keep.contains(e.getKey())) {
                e.getValue().bmp.recycle();
                it.remove();
            }
        }
    }
}
