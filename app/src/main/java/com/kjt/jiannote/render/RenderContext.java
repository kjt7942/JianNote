package com.kjt.jiannote.render;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import java.io.File;

public class RenderContext {
    public final File dir;
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(120 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    public RenderContext(File notebookDir) {
        this.dir = notebookDir;
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
}
