package com.kjt.jiannote;

import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;

import com.kjt.jiannote.model.Document;
import com.kjt.jiannote.render.RenderContext;
import com.kjt.jiannote.render.Renderer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;

/** 점검 환경이 실제로 그림을 그리는지 확인 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HarnessTest {
    @Test
    public void rendersPage() {
        Document d = T.doc(1, 1, 700);
        RenderContext rc = new RenderContext(new File(RuntimeEnvironment.getApplication().getFilesDir(), "nb"));
        long t0 = System.nanoTime();
        Bitmap b = Renderer.thumbnail(d.pages.get(0), 0xFFFFFFFF, rc, 1600, 2263);
        long ms = (System.nanoTime() - t0) / 1000000;
        System.out.println("[점검] 700획 페이지 1600px 그리기 " + ms + "ms");
        T.save(b, "harness_page.png");
        // 가운데 부분을 4배로 확대해 획 가장자리 확인
        Bitmap z = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(z);
        c.drawColor(0xFFFFFFFF);
        c.scale(5f, 5f);
        c.translate(-500, -700);
        Renderer.drawPage(c, d.pages.get(0), 0xFFFFFFFF, rc, new android.graphics.RectF(500, 700, 740, 880), 5f, null);
        T.save(z, "harness_zoom.png");
        int ink = 0;
        for (int y = 0; y < b.getHeight(); y += 7) for (int x = 0; x < b.getWidth(); x += 7) if ((b.getPixel(x, y) & 0xFFFFFF) < 0x505050) ink++;
        assertTrue("획이 그려져야 함: " + ink, ink > 200);
    }
}
