package com.kjt.jiannote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;

import com.kjt.jiannote.model.Document;
import com.kjt.jiannote.model.Layer;
import com.kjt.jiannote.model.NotebookMeta;
import com.kjt.jiannote.model.Page;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/** 화면 단위 점검: 서재·편집 화면을 띄워 모든 버튼을 눌러 보고, 저장·복구가 되는지 확인 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w1280dp-h800dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AppTest {

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static Bitmap shot(View v) {
        if (v.getWidth() == 0 || v.getHeight() == 0) {
            v.measure(View.MeasureSpec.makeMeasureSpec(2560, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.AT_MOST));
            v.layout(0, 0, v.getMeasuredWidth(), v.getMeasuredHeight());
        }
        Bitmap b = Bitmap.createBitmap(Math.max(1, v.getWidth()), Math.max(1, v.getHeight()), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFFFFFFFF);
        v.draw(c);
        return b;
    }

    private static void collect(View v, List<View> out) {
        if (v instanceof CanvasView) return;
        if (v.getVisibility() != View.VISIBLE) return;
        if (v.hasOnClickListeners() && v.isEnabled()) out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }

    private static CanvasView findCanvas(View v) {
        if (v instanceof CanvasView) return (CanvasView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                CanvasView c = findCanvas(g.getChildAt(i));
                if (c != null) return c;
            }
        }
        return null;
    }

    private static String describe(View v) {
        CharSequence d = v.getContentDescription();
        if (d == null && v instanceof android.widget.TextView) d = ((android.widget.TextView) v).getText();
        return v.getClass().getSimpleName() + "[" + d + "]";
    }

    /** 떠 있는 대화상자·팝업을 닫는다. 처음 몇 개는 그림으로 남긴다 */
    private int shots;

    private void closeOverlays(String label) {
        idle();
        for (int guard = 0; guard < 6; guard++) {
            Dialog d = ShadowDialog.getLatestDialog();
            if (d == null || !d.isShowing()) break;
            if (shots < 40 && d.getWindow() != null) {
                try {
                    T.save(shot(d.getWindow().getDecorView()), String.format("ui_%02d_dialog_%s.png", shots++, label));
                } catch (Throwable ignored) {
                }
            }
            d.dismiss();
            idle();
        }
        PopupWindow pw = Shadows.shadowOf(RuntimeEnvironment.getApplication()).getLatestPopupWindow();
        if (pw != null && pw.isShowing()) {
            if (shots < 40 && pw.getContentView() != null) {
                try {
                    T.save(shot(pw.getContentView()), String.format("ui_%02d_popup_%s.png", shots++, label));
                } catch (Throwable ignored) {
                }
            }
            pw.dismiss();
            idle();
        }
    }

    private static NotebookMeta makeNote(int pages, int strokes) throws Exception {
        Store store = new Store(RuntimeEnvironment.getApplication());
        NotebookMeta m = new NotebookMeta();
        m.title = "점검 노트";
        m.coverColor = 0xFF5C6BC0;
        m.paperColor = 0xFFFFFFFF;
        m.template = com.kjt.jiannote.render.Paper.GRID;
        m = store.create(m);
        store.saveDoc(m.id, T.doc(42, pages, strokes));
        return m;
    }

    private static int elems(Document d) {
        int n = 0;
        for (Page p : d.pages) for (Layer l : p.layers) n += l.elems.size();
        return n;
    }

    @Test
    public void libraryOpensAndEveryButtonWorks() throws Exception {
        new Prefs(RuntimeEnvironment.getApplication()).put("autoUpdate", false);
        new Prefs(RuntimeEnvironment.getApplication()).put("onboarded", true);
        makeNote(2, 120);
        makeNote(1, 30);
        ActivityController<LibraryActivity> ac = Robolectric.buildActivity(LibraryActivity.class).setup();
        idle();
        LibraryActivity a = ac.get();
        View root = a.getWindow().getDecorView();
        T.save(shot(root), "ui_library.png");
        ArrayList<View> views = new ArrayList<>();
        collect(a.findViewById(android.R.id.content), views);
        assertTrue("서재에 누를 수 있는 것이 있어야 함", views.size() > 3);
        int clicked = 0;
        for (View v : views) {
            if (a.isFinishing()) break;
            if (!v.isAttachedToWindow()) continue;
            try {
                v.performClick();
                clicked++;
                closeOverlays("lib" + clicked);
                v.performLongClick();
                closeOverlays("libL" + clicked);
            } catch (Throwable e) {
                throw new AssertionError("서재에서 " + describe(v) + " 누르다 오류: " + e, e);
            }
        }
        System.out.println("[점검] 서재 화면: 버튼 " + clicked + "개 눌러 봄, 오류 없음");
        ac.pause().stop().destroy();
    }

    @Test
    public void editorOpensEveryButtonWorksAndSaves() throws Exception {
        new Prefs(RuntimeEnvironment.getApplication()).put("autoUpdate", false);
        NotebookMeta m = makeNote(2, 300);
        Store store = new Store(RuntimeEnvironment.getApplication());
        int before = elems(store.loadDoc(m.id));
        Intent i = new Intent(RuntimeEnvironment.getApplication(), EditorActivity.class).putExtra("id", m.id);

        ActivityController<EditorActivity> ac = Robolectric.buildActivity(EditorActivity.class, i).setup();
        idle();
        EditorActivity a = ac.get();
        CanvasView cv = findCanvas(a.getWindow().getDecorView());
        assertNotNull("편집 화면에 캔버스가 있어야 함", cv);
        assertTrue("캔버스 크기 " + cv.getWidth() + "x" + cv.getHeight(), cv.getWidth() > 500 && cv.getHeight() > 500);
        T.save(shot(a.getWindow().getDecorView()), "ui_editor.png");

        // 펜으로 3획 쓰기
        long t = 1000;
        for (int k = 0; k < 3; k++) {
            float y = cv.getHeight() * (0.3f + 0.1f * k);
            long down = t += 100;
            MotionEvent e = T.ev(down, t, MotionEvent.ACTION_DOWN, 600, y, MotionEvent.TOOL_TYPE_STYLUS, 0.5f);
            cv.onTouchEvent(e);
            for (int s = 1; s <= 12; s++) {
                e = T.ev(down, t += 8, MotionEvent.ACTION_MOVE, 600 + s * 30, y + (s % 3) * 8, MotionEvent.TOOL_TYPE_STYLUS, 0.5f);
                cv.onTouchEvent(e);
            }
            e = T.ev(down, t += 8, MotionEvent.ACTION_UP, 960, y, MotionEvent.TOOL_TYPE_STYLUS, 0.5f);
            cv.onTouchEvent(e);
        }
        idle();
        T.save(shot(a.getWindow().getDecorView()), "ui_editor_written.png");

        // 화면에 보이는 버튼을 전부 눌러 본다 (누른 뒤 뜨는 창은 닫음). 버튼이 바뀌므로 여러 번 반복
        int clicked = 0;
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (int round = 0; round < 4 && !a.isFinishing(); round++) {
            ArrayList<View> views = new ArrayList<>();
            collect(a.findViewById(android.R.id.content), views);
            for (View v : views) {
                if (a.isFinishing()) break;
                if (!v.isAttachedToWindow() || !v.isShown()) continue;
                String key = describe(v);
                if (key.contains("뒤로") || key.contains("서재")) continue; // 화면을 닫는 버튼은 마지막에
                if (!seen.add(key + "@" + round)) continue;
                try {
                    v.performClick();
                    clicked++;
                    closeOverlays("ed" + clicked);
                    assertEquals(key + " 누른 뒤 캔버스 상태", null, cv.debugCheck());
                    shot(cv);
                } catch (Throwable e) {
                    throw new AssertionError("편집 화면에서 " + key + " 누르다 오류: " + e, e);
                }
            }
        }
        System.out.println("[점검] 편집 화면: 버튼 " + clicked + "번 눌러 봄, 오류 없음");
        T.save(shot(a.getWindow().getDecorView()), "ui_editor_after_clicks.png");

        // 나갔다 들어오기: 저장되는지
        int now = elems(cv.doc());
        ac.pause();
        idle();
        Document saved = store.loadDoc(m.id);
        assertEquals("나갈 때 저장된 요소 수", now, elems(saved));
        assertTrue("쓴 획이 저장돼야 함 (" + before + " → " + elems(saved) + ")", elems(saved) != before || now == before);
        ac.resume();
        idle();
        ac.pause().stop().destroy();

        // 다시 열기
        ActivityController<EditorActivity> ac2 = Robolectric.buildActivity(EditorActivity.class, i).setup();
        idle();
        CanvasView cv2 = findCanvas(ac2.get().getWindow().getDecorView());
        assertEquals("다시 열었을 때 요소 수", now, elems(cv2.doc()));
        ac2.pause().stop().destroy();
    }

    @Test
    public void damagedFileFallsBackToBackup() throws Exception {
        NotebookMeta m = makeNote(2, 100);
        Store store = new Store(RuntimeEnvironment.getApplication());
        Document d = store.loadDoc(m.id);
        int n = elems(d);
        store.saveDoc(m.id, d); // 한 번 더 저장 → .bak 생김
        File f = new File(store.dir(m.id), "doc.bin");
        assertTrue(new File(store.dir(m.id), "doc.bin.bak").exists());

        // 1) 파일이 중간에 잘림
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.setLength(raf.length() / 2);
        }
        assertEquals("잘린 파일 → 직전 저장본으로 복구", n, elems(store.loadDoc(m.id)));

        // 2) 내용이 엉망 (개수 자리에 큰 값)
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.seek(8);
            raf.writeInt(0x7FFFFFF0);
            raf.seek(40);
            for (int k = 0; k < 50; k++) raf.writeInt(0x7FFFFFFF);
        }
        assertEquals("깨진 파일 → 직전 저장본으로 복구", n, elems(store.loadDoc(m.id)));

        // 3) 파일이 아예 없음
        assertTrue(f.delete());
        assertEquals("파일 없음 → 직전 저장본으로 복구", n, elems(store.loadDoc(m.id)));

        // 4) 복구한 뒤 다시 저장하면 정상 파일이 됨
        store.saveDoc(m.id, store.loadDoc(m.id));
        assertEquals(n, elems(Document.read(f)));

        // 5) 손상된 노트를 편집 화면으로 열어도 앱이 죽지 않음
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.setLength(20);
        }
        new File(store.dir(m.id), "doc.bin.bak").delete();
        Intent i = new Intent(RuntimeEnvironment.getApplication(), EditorActivity.class).putExtra("id", m.id);
        ActivityController<EditorActivity> ac = Robolectric.buildActivity(EditorActivity.class, i).setup();
        idle();
        assertTrue("열 수 없는 노트는 화면을 닫음", ac.get().isFinishing());
        ac.pause().stop().destroy();
        assertEquals("열지 못한 노트 파일을 덮어쓰지 않음", 20, f.length());
        System.out.println("[점검] 손상된 파일 복구 5가지 경우 통과");
    }

    @Test
    public void saveAndReloadKeepsEverything() throws Exception {
        // 무작위로 한참 쓴 문서를 저장했다 읽으면 화면이 똑같아야 한다
        Rig r = new Rig(T.doc(9, 2, 200), 1280, 1800);
        CanvasView cv = r.cv;
        cv.tool = CanvasView.TOOL_PEN;
        r.pen(200, 300, 700, 400);
        cv.tool = CanvasView.TOOL_HIGHLIGHTER;
        r.pen(200, 500, 900, 505);
        cv.tool = CanvasView.TOOL_TAPE;
        r.pen(300, 700, 800, 705);
        cv.tool = CanvasView.TOOL_MEMO;
        r.pen(400, 900, 800, 1200);
        cv.commitText(150, 1300, null, "저장 점검 ABC", new com.kjt.jiannote.model.TextElem.Style());
        cv.tool = CanvasView.TOOL_ERASER;
        cv.eraserPartial = true;
        r.drag(MotionEvent.TOOL_TYPE_STYLUS, 100, 200, 1000, 1500, 30, 0);
        cv.invalidateScene();
        Bitmap a = r.snap();
        File f = new File(r.act.getFilesDir(), "roundtrip.bin");
        r.doc.write(f);
        Document d2 = Document.read(f);
        assertEquals(elems(r.doc), elems(d2));
        Rig r2 = new Rig(d2, 1280, 1800);
        Bitmap b = r2.snap();
        int diff = T.diff(a, b, 3);
        if (diff != 0) {
            T.save(a, "FAIL_roundtrip_before.png");
            T.save(b, "FAIL_roundtrip_after.png");
        }
        assertEquals("저장했다 읽은 화면이 다른 픽셀 수", 0, diff);
        System.out.println("[점검] 저장 후 다시 읽기: 화면 일치");
    }
}
