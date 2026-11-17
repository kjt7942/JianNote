package com.kjt.jiannote;

import android.graphics.Bitmap;
import android.view.InputDevice;
import android.view.MotionEvent;

import com.kjt.jiannote.model.Document;
import com.kjt.jiannote.model.Page;
import com.kjt.jiannote.model.StrokeElem;
import com.kjt.jiannote.render.Paper;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Random;

/** 점검용 도우미 */
public class T {
    public static File artifacts() {
        File d = new File(System.getProperty("artifacts.dir", "build/test-artifacts"));
        d.mkdirs();
        return d;
    }

    public static void save(Bitmap b, String name) {
        try (FileOutputStream o = new FileOutputStream(new File(artifacts(), name))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 손글씨 비슷한 획: 곡률이 조금씩 바뀌며 가끔 꺾인다 */
    public static StrokeElem stroke(Random r, float x, float y, int pen, int color, float width, int n) {
        StrokeElem s = new StrokeElem(pen, color);
        double ang = r.nextDouble() * 6.28, curv = (r.nextDouble() - 0.5) * 0.5;
        float w = width;
        for (int i = 0; i < n; i++) {
            s.add(x, y, pen == StrokeElem.HIGHLIGHTER ? width : Math.max(0.6f, w));
            if (r.nextDouble() < 0.07) ang += (r.nextDouble() - 0.5) * 5;
            curv += (r.nextDouble() - 0.5) * 0.15;
            curv = Math.max(-0.5, Math.min(0.5, curv));
            ang += curv;
            float d = 1.2f + r.nextFloat() * 1.4f;
            x += d * Math.cos(ang);
            y += d * Math.sin(ang);
            w += (r.nextFloat() - 0.5f) * 0.5f;
            w = Math.max(width * 0.4f, Math.min(width * 1.6f, w));
        }
        return s;
    }

    /** 필기가 가득한 문서 (쪽마다 strokesPerPage 획) */
    public static Document doc(long seed, int pages, int strokesPerPage) {
        Random r = new Random(seed);
        Document d = new Document();
        for (int p = 0; p < pages; p++) {
            Page pg = Page.create(1240, 1754, Paper.GRID);
            for (int i = 0; i < strokesPerPage; i++) {
                float x = 80 + r.nextFloat() * 1080, y = 80 + r.nextFloat() * 1590;
                int pen = i % 23 == 0 ? StrokeElem.HIGHLIGHTER : i % 17 == 0 ? StrokeElem.FOUNTAIN : StrokeElem.PEN;
                float w = pen == StrokeElem.HIGHLIGHTER ? 26 : 3.5f;
                int color = pen == StrokeElem.HIGHLIGHTER ? 0xFFFFEB3B : (i % 5 == 0 ? 0xFF1565C0 : 0xFF1A1A1A);
                pg.layers.get(0).elems.add(stroke(r, x, y, pen, color, w, 15 + r.nextInt(50)));
            }
            d.pages.add(pg);
        }
        return d;
    }

    public static MotionEvent ev(long down, long t, int action, float x, float y, int toolType, float pressure) {
        MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
        pp.id = 0;
        pp.toolType = toolType;
        MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
        pc.x = x;
        pc.y = y;
        pc.pressure = pressure;
        pc.size = 1;
        return MotionEvent.obtain(down, t, action, 1, new MotionEvent.PointerProperties[]{pp}, new MotionEvent.PointerCoords[]{pc},
                0, 0, 1f, 1f, 0, 0, toolType == MotionEvent.TOOL_TYPE_STYLUS ? InputDevice.SOURCE_STYLUS : InputDevice.SOURCE_TOUCHSCREEN, 0);
    }

    /** 두 그림에서 다른 픽셀 수 (tol = 채널별 허용 오차) */
    public static int diff(Bitmap a, Bitmap b, int tol) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return Integer.MAX_VALUE;
        int w = a.getWidth(), h = a.getHeight();
        int[] pa = new int[w * h], pb = new int[w * h];
        a.getPixels(pa, 0, w, 0, 0, w, h);
        b.getPixels(pb, 0, w, 0, 0, w, h);
        int n = 0;
        for (int i = 0; i < pa.length; i++) {
            if (pa[i] == pb[i]) continue;
            int x = pa[i], y = pb[i];
            if (Math.abs((x >>> 24) - (y >>> 24)) > tol || Math.abs(((x >> 16) & 255) - ((y >> 16) & 255)) > tol
                    || Math.abs(((x >> 8) & 255) - ((y >> 8) & 255)) > tol || Math.abs((x & 255) - (y & 255)) > tol) n++;
        }
        return n;
    }

    /** 차이 나는 곳을 빨갛게 표시한 그림 */
    public static Bitmap diffImage(Bitmap a, Bitmap b) {
        int w = a.getWidth(), h = a.getHeight();
        int[] pa = new int[w * h], pb = new int[w * h];
        a.getPixels(pa, 0, w, 0, 0, w, h);
        b.getPixels(pb, 0, w, 0, 0, w, h);
        for (int i = 0; i < pa.length; i++) pa[i] = pa[i] == pb[i] ? 0xFFFFFFFF : 0xFFFF0000;
        return Bitmap.createBitmap(pa, w, h, Bitmap.Config.ARGB_8888);
    }
}
