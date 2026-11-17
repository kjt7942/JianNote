package com.kjt.jiannote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import com.kjt.jiannote.model.Document;
import com.kjt.jiannote.model.Element;
import com.kjt.jiannote.model.TextElem;
import com.kjt.jiannote.render.RenderContext;

import org.robolectric.Robolectric;
import org.robolectric.Shadows;

import java.io.File;
import java.util.concurrent.TimeUnit;

/** 캔버스를 실제처럼 세워 두고 펜·손가락 입력을 흉내 내는 점검 장치 */
public class Rig implements CanvasView.Listener {
    public final Activity act;
    public final CanvasView cv;
    public final Document doc;
    public final RenderContext rc;
    public final int W, H;
    public long now = 100000;
    public int checks;
    /** 가는 선은 그리기 시작점에 따라 농도가 1~2단계 달라질 수 있어 그 정도는 같은 것으로 본다 (255단계 중) */
    static final int TOL = 3;

    public Rig(Document d, int w, int h) {
        act = Robolectric.buildActivity(Activity.class).setup().get();
        doc = d;
        W = w;
        H = h;
        rc = new RenderContext(new File(act.getFilesDir(), "nb" + System.nanoTime()));
        cv = new CanvasView(act);
        cv.setListener(this);
        cv.setDocument(d, rc, 0xFFFFFFFF);
        cv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        cv.layout(0, 0, w, h);
        cv.fitPage();
    }

    // ---- 입력
    private void send(long down, int action, float x, float y, int tt, float pr) {
        now += 8;
        MotionEvent e = T.ev(down, now, action, x, y, tt, pr);
        cv.onTouchEvent(e);
        e.recycle();
    }

    /** 한 손가락/펜으로 (x0,y0) → (x1,y1) 끌기. hold = 떼기 전에 멈춰 있는 시간(ms): 길면 튕겨 넘기기(fling)가 안 생김 */
    public void drag(int tt, float x0, float y0, float x1, float y1, int steps, int hold) {
        now += 50;
        long down = now;
        send(down, MotionEvent.ACTION_DOWN, x0, y0, tt, 0.5f);
        for (int i = 1; i <= steps; i++) {
            float t = (float) i / steps;
            send(down, MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, tt, 0.4f + 0.3f * (float) Math.sin(i));
        }
        if (hold > 0) {
            for (int k = 0; k < 4; k++) {
                now += hold / 4;
                send(down, MotionEvent.ACTION_MOVE, x1, y1, tt, 0.5f);
            }
        }
        send(down, MotionEvent.ACTION_UP, x1, y1, tt, 0.5f);
    }

    /** 여러 점을 지나는 펜 획 */
    public void path(int tt, float[] xy) {
        now += 50;
        long down = now;
        send(down, MotionEvent.ACTION_DOWN, xy[0], xy[1], tt, 0.5f);
        for (int i = 2; i + 1 < xy.length; i += 2) send(down, MotionEvent.ACTION_MOVE, xy[i], xy[i + 1], tt, 0.5f);
        send(down, MotionEvent.ACTION_UP, xy[xy.length - 2], xy[xy.length - 1], tt, 0.5f);
    }

    public void pen(float x0, float y0, float x1, float y1) {
        drag(MotionEvent.TOOL_TYPE_STYLUS, x0, y0, x1, y1, 14, 0);
    }

    public void tap(int tt, float x, float y) {
        now += 50;
        long down = now;
        send(down, MotionEvent.ACTION_DOWN, x, y, tt, 0.5f);
        now += 30;
        send(down, MotionEvent.ACTION_UP, x, y, tt, 0.5f);
    }

    /** 손가락으로 화면 넘기기 (튕김 없이) */
    public void scroll(float dx, float dy) {
        drag(MotionEvent.TOOL_TYPE_FINGER, W / 2f, H / 2f, W / 2f + dx, H / 2f + dy, 12, 400);
        settle();
    }

    /** 두 손가락 확대/축소: 가운데(cx,cy)에서 간격 d0 → d1 */
    public void pinch(float cx, float cy, float d0, float d1) {
        now += 50;
        long down = now;
        two(down, MotionEvent.ACTION_DOWN, cx - d0 / 2, cy, cx + d0 / 2, cy, 1);
        two(down, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), cx - d0 / 2, cy, cx + d0 / 2, cy, 2);
        for (int i = 1; i <= 10; i++) {
            float d = d0 + (d1 - d0) * i / 10f;
            two(down, MotionEvent.ACTION_MOVE, cx - d / 2, cy, cx + d / 2, cy, 2);
        }
        two(down, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), cx - d1 / 2, cy, cx + d1 / 2, cy, 2);
        two(down, MotionEvent.ACTION_UP, cx - d1 / 2, cy, cx + d1 / 2, cy, 1);
        settle();
    }

    private void two(long down, int action, float x0, float y0, float x1, float y1, int count) {
        now += 8;
        MotionEvent.PointerProperties[] pp = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] pc = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            pp[i] = new MotionEvent.PointerProperties();
            pp[i].id = i;
            pp[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            pc[i] = new MotionEvent.PointerCoords();
            pc[i].x = i == 0 ? x0 : x1;
            pc[i].y = i == 0 ? y0 : y1;
            pc[i].pressure = 0.5f;
            pc[i].size = 1;
        }
        MotionEvent e = MotionEvent.obtain(down, now, action, count, pp, pc, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
        cv.onTouchEvent(e);
        e.recycle();
    }

    /** 시간을 흘려 튕겨 넘기기·예약된 동작을 끝낸다 */
    public void settle() {
        for (int i = 0; i < 40; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS);
            cv.computeScroll();
            if (!cv.isBusy()) break;
        }
        now = Math.max(now, SystemClock.uptimeMillis()) + 10;
    }

    // ---- 화면 확인
    public Bitmap snap() {
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        cv.draw(new Canvas(b));
        return b;
    }

    /**
     * 지금 화면(바뀐 부분만 고쳐 그린 결과)이 처음부터 전부 다시 그린 화면과 똑같은지,
     * 스크롤할 때 쓰는 페이지 캐시도 새로 만든 것과 똑같은지 확인한다.
     */
    public void verify(String what) {
        checks++;
        assertNull(what + ": 내부 상태", cv.debugCheck());
        Bitmap a = snap();
        Bitmap ca = null;
        if (!cv.isInfinite()) {
            cv.debugForceCache = true;
            ca = snap();
            cv.debugForceCache = false;
        }
        cv.invalidateScene();
        Bitmap b = snap();
        compare(what, a, b, "부분만 다시 그린 화면이 전체를 다시 그린 화면과 다름", 1f);
        if (ca != null) {
            cv.debugForceCache = true;
            Bitmap cb = snap();
            cv.debugForceCache = false;
            // 캐시는 낮은 해상도로 그려 화면에 늘려 보여 주므로, 확대한 만큼 다른 픽셀도 커 보인다
            float z = Math.max(1f, cv.getScale() / 1.2f);
            compare("cache_" + what, ca, cb, "스크롤용 캐시가 새로 만든 것과 다름", z * z);
        }
    }

    /** 엄격하게 볼지 (테이프·메모지처럼 가는 테두리가 있는 요소가 없을 때) */
    public boolean strict = true;

    /**
     * 같은 그림이어야 하는 둘을 비교. 부분만 다시 그리면 그 영역 가장자리에 걸친 획·가는 테두리의 부드러운 가장자리 픽셀이
     * 조금 달라질 수 있어(눈에 안 보임), 조금 다른 픽셀 얼마까지는 봐주고 크게 다른 픽셀은 허용하지 않는다.
     * 획이 빠지거나 지워진 자리가 남으면 크게 다른 픽셀이 뭉텅이로 생긴다.
     */
    private void compare(String what, Bitmap a, Bitmap b, String msg, float k) {
        int small = T.diff(a, b, TOL), mid = T.diff(a, b, 40), big = T.diff(a, b, strict ? 48 : 80);
        boolean bad = strict ? (small > 150 * k || big > 0) : (small > 1500 * k || mid > 30 * k || big > 0);
        if (bad) {
            String n = "FAIL_" + what.replaceAll("[^A-Za-z0-9가-힣]+", "_");
            T.save(a, n + "_got.png");
            T.save(b, n + "_want.png");
            T.save(T.diffImage(a, b), n + "_diff.png");
            throw new AssertionError(what + ": " + msg + " (조금 다른 픽셀 " + small + "개, 중간 " + mid + "개, 크게 다른 픽셀 " + big + "개)");
        }
    }

    // ---- CanvasView.Listener
    public int selectionEdits;

    @Override public void onPageChanged() { }
    @Override public void onSelectionChanged(boolean hasSelection) { }
    @Override public void onHistoryChanged() { }
    @Override public void onTextRequest(float x, float y, TextElem existing) { }
    @Override public void onZoomChanged(float scale) { }
    @Override public void onDocChanged() { }
    @Override public void onLayerLimit() { }
    @Override public void onLongPressSelected() { }
    @Override public void onSelectionEdited() { selectionEdits++; }
    @Override public void onColorPicked(int color) { }
    @Override public void onLockedPressed(Element e) { }
    @Override public void onLockedLassoed(java.util.List<Element> list) { }
}
