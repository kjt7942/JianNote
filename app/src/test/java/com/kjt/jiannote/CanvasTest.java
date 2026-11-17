package com.kjt.jiannote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.RectF;
import android.view.MotionEvent;

import com.kjt.jiannote.model.Document;
import com.kjt.jiannote.model.Element;
import com.kjt.jiannote.model.Layer;
import com.kjt.jiannote.model.Page;
import com.kjt.jiannote.model.StrokeElem;
import com.kjt.jiannote.model.TextElem;
import com.kjt.jiannote.render.Paper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.Random;

/** 캔버스: 도구를 쓴 뒤 화면이 정확한지, 아무렇게나 눌러도 죽지 않는지 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CanvasTest {
    static final int PEN = MotionEvent.TOOL_TYPE_STYLUS, FINGER = MotionEvent.TOOL_TYPE_FINGER;

    private static int count(Rig r) {
        int n = 0;
        for (Layer l : r.cv.page().layers) n += l.elems.size();
        return n;
    }

    @Test
    public void toolsRedrawOnlyWhatChanged() {
        Rig r = new Rig(T.doc(7, 2, 500), 1600, 2300);
        CanvasView cv = r.cv;
        r.verify("처음");
        T.save(r.snap(), "canvas_01_start.png");

        // 펜
        cv.tool = CanvasView.TOOL_PEN;
        int before = count(r);
        r.pen(300, 500, 700, 620);
        r.pen(320, 700, 900, 705);
        r.path(PEN, new float[]{400, 900, 420, 960, 470, 980, 520, 950, 540, 900, 520, 860, 460, 850, 410, 880});
        assertEquals("펜 획 3개가 추가됨", before + 3, count(r));
        r.verify("펜");

        // 형광펜 (글씨 뒤로 들어감)
        cv.tool = CanvasView.TOOL_HIGHLIGHTER;
        r.pen(250, 610, 1000, 615);
        r.verify("형광펜");

        // 부분 지우개
        cv.tool = CanvasView.TOOL_ERASER;
        cv.eraserPartial = true;
        cv.eraserHlOnly = false;
        cv.eraserSize = 40;
        before = count(r);
        r.drag(PEN, 200, 1000, 1300, 1400, 40, 0);
        assertTrue("지우개가 무언가 지워야 함", count(r) != before);
        r.verify("부분 지우개");
        // 획 지우개
        cv.eraserPartial = false;
        before = count(r);
        r.drag(PEN, 1300, 400, 300, 1500, 40, 0);
        assertTrue("획 지우개가 획을 줄여야 함", count(r) < before);
        r.verify("획 지우개");

        // 올가미로 선택
        cv.tool = CanvasView.TOOL_LASSO;
        cv.lassoRect = false;
        cv.lassoFilter = 0;
        r.path(PEN, new float[]{500, 1300, 1100, 1300, 1150, 1700, 800, 1800, 480, 1700, 470, 1400, 500, 1300});
        assertTrue("올가미로 선택되어야 함", cv.hasSelection());
        int sel = cv.debugSelectionSize();
        System.out.println("[점검] 올가미 선택 " + sel + "개");
        r.verify("올가미 선택");
        T.save(r.snap(), "canvas_02_selected.png");

        // 선택한 것 옮기기
        RectF b = cv.debugSelScreenBounds();
        r.drag(PEN, b.centerX(), b.centerY(), b.centerX() - 180, b.centerY() - 420, 12, 0);
        assertEquals("옮겨도 선택 개수는 그대로", sel, cv.debugSelectionSize());
        r.verify("선택 옮기기");

        // 복제 — 복제본이 선택된 채로 남아야 한다
        before = count(r);
        int edits = r.selectionEdits;
        cv.duplicateSelection();
        assertEquals("복제한 만큼 늘어남", before + sel, count(r));
        assertTrue("복제본이 선택된 상태", cv.hasSelection());
        assertEquals("복제는 '편집 끝'으로 치지 않음 (선택이 풀리지 않게)", edits, r.selectionEdits);
        r.verify("복제");
        b = cv.debugSelScreenBounds();
        r.drag(PEN, b.centerX(), b.centerY(), b.centerX() + 300, b.centerY() + 200, 10, 0);
        r.verify("복제본 옮기기");

        // 크기 조절 (오른쪽 아래 동그라미)
        b = cv.debugSelScreenBounds();
        r.drag(PEN, b.right, b.bottom, b.right + 120, b.bottom + 90, 10, 0);
        r.verify("크기 조절");

        // 색 바꾸기 → 지우기
        cv.recolorSelection(0xFFD32F2F);
        r.verify("색 바꾸기");
        before = count(r);
        cv.deleteSelection();
        assertFalse(cv.hasSelection());
        assertEquals(before - sel, count(r));
        r.verify("선택 지우기");

        // 실행 취소 / 다시 실행
        for (int i = 0; i < 4; i++) cv.undo();
        r.verify("실행 취소");
        for (int i = 0; i < 2; i++) cv.redo();
        r.verify("다시 실행");

        // 손가락으로 넘기기 → 화면을 밀어서 다시 쓰는 경로
        r.scroll(0, -333);
        r.verify("스크롤 1");
        r.scroll(0, -900);
        r.verify("스크롤 2");
        r.scroll(0, 500);
        r.verify("스크롤 3");
        cv.tool = CanvasView.TOOL_PEN;
        r.pen(300, 1200, 800, 1300);
        r.scroll(0, -120);
        r.pen(350, 900, 900, 1000);
        r.verify("쓰고 넘기고 쓰기");

        // 확대한 상태에서 쓰기·지우기
        r.pinch(800, 1100, 300, 700);
        assertTrue(cv.getScale() > cv.baseScale() * 1.5f);
        r.verify("확대");
        r.pen(400, 900, 900, 1000);
        cv.tool = CanvasView.TOOL_ERASER;
        cv.eraserPartial = true;
        r.drag(PEN, 300, 800, 1200, 1500, 30, 0);
        r.verify("확대 후 쓰고 지우기");
        T.save(r.snap(), "canvas_03_zoomed.png");
        r.scroll(-200, -300);
        r.verify("확대 후 넘기기");

        // 테이프·메모지·텍스트
        r.strict = false;
        cv.tool = CanvasView.TOOL_TAPE;
        r.pen(400, 1000, 1000, 1005);
        r.verify("테이프");
        cv.tool = CanvasView.TOOL_PEN;
        r.tap(PEN, 700, 1002); // 테이프를 누르면 비쳐 보임
        r.verify("테이프 비쳐 보기");
        cv.tool = CanvasView.TOOL_MEMO;
        r.pen(500, 1200, 900, 1500);
        r.verify("메모지");
        TextElem.Style st = new TextElem.Style();
        cv.commitText(100, 100, null, "점검 ABC 123", st);
        r.verify("텍스트");
        System.out.println("[점검] 화면 비교 " + r.checks + "번 모두 일치");
    }

    @Test
    public void randomUseNeverBreaks() {
        long total = 0;
        for (int seed = 1; seed <= 6; seed++) {
            Rig r = new Rig(T.doc(100 + seed, 3, 150), 1280, 1800);
            CanvasView cv = r.cv;
            r.strict = false;
            Random rnd = new Random(seed);
            String last = "";
            try {
                for (int step = 0; step < 220; step++) {
                    int op = rnd.nextInt(30);
                    float x0 = 60 + rnd.nextFloat() * (r.W - 120), y0 = 60 + rnd.nextFloat() * (r.H - 120);
                    float x1 = 60 + rnd.nextFloat() * (r.W - 120), y1 = 60 + rnd.nextFloat() * (r.H - 120);
                    if (op < 6) {
                        last = "pen";
                        cv.tool = CanvasView.TOOL_PEN;
                        cv.penType = rnd.nextInt(8);
                        if (cv.penType == StrokeElem.HIGHLIGHTER) cv.penType = StrokeElem.PEN;
                        cv.penSquare = rnd.nextInt(5) == 0;
                        r.pen(x0, y0, x0 + (x1 - x0) * 0.3f, y0 + (y1 - y0) * 0.3f);
                    } else if (op < 8) {
                        last = "highlighter";
                        cv.tool = CanvasView.TOOL_HIGHLIGHTER;
                        r.pen(x0, y0, x1, y0 + 5);
                    } else if (op < 11) {
                        last = "eraser";
                        cv.tool = CanvasView.TOOL_ERASER;
                        cv.eraserPartial = rnd.nextBoolean();
                        cv.eraserHlOnly = rnd.nextInt(6) == 0;
                        cv.eraserSize = 10 + rnd.nextInt(60);
                        r.drag(PEN, x0, y0, x1, y1, 25, 0);
                    } else if (op < 14) {
                        last = "lasso";
                        cv.tool = CanvasView.TOOL_LASSO;
                        cv.lassoRect = rnd.nextBoolean();
                        cv.lassoFilter = rnd.nextInt(4) == 0 ? rnd.nextInt(7) : 0;
                        if (cv.lassoRect) r.drag(PEN, x0, y0, x1, y1, 8, 0);
                        else r.path(PEN, new float[]{x0, y0, x1, y0, x1, y1, x0, y1, x0, y0 + 10});
                    } else if (op < 17) {
                        last = "selection edit";
                        if (cv.hasSelection()) {
                            cv.tool = CanvasView.TOOL_LASSO;
                            RectF b = cv.debugSelScreenBounds();
                            int k = rnd.nextInt(7);
                            if (k == 0) r.drag(PEN, b.centerX(), b.centerY(), x1, y1, 8, 0);
                            else if (k == 1) r.drag(PEN, b.right, b.bottom, b.right + rnd.nextInt(200) - 100, b.bottom + rnd.nextInt(200) - 100, 8, 0);
                            else if (k == 2) cv.duplicateSelection();
                            else if (k == 3) cv.deleteSelection();
                            else if (k == 4) cv.recolorSelection(0xFF000000 | rnd.nextInt(0xFFFFFF));
                            else if (k == 5) cv.lockSelection();
                            else cv.clearSelection();
                        }
                    } else if (op < 19) {
                        last = "undo/redo";
                        if (rnd.nextBoolean()) cv.undo();
                        else cv.redo();
                    } else if (op < 22) {
                        last = "scroll";
                        r.scroll(rnd.nextInt(5) == 0 ? rnd.nextInt(400) - 200 : 0, rnd.nextInt(1600) - 800);
                    } else if (op < 23) {
                        last = "fling";
                        r.drag(FINGER, r.W / 2f, r.H / 2f, r.W / 2f, r.H / 2f + rnd.nextInt(900) - 450, 6, 0);
                        r.settle();
                    } else if (op < 24) {
                        last = "pinch";
                        r.pinch(x0, y0, 200 + rnd.nextInt(300), 200 + rnd.nextInt(500));
                    } else if (op < 25) {
                        last = "tape/memo";
                        cv.tool = rnd.nextBoolean() ? CanvasView.TOOL_TAPE : CanvasView.TOOL_MEMO;
                        r.pen(x0, y0, x1, y1);
                        cv.tool = CanvasView.TOOL_PEN;
                        r.tap(PEN, (x0 + x1) / 2, (y0 + y1) / 2);
                    } else if (op < 26) {
                        last = "shape";
                        cv.tool = CanvasView.TOOL_SHAPE;
                        cv.shapeType = rnd.nextInt(5);
                        r.pen(x0, y0, x1, y1);
                    } else if (op < 27) {
                        last = "text";
                        cv.commitText(100 + rnd.nextInt(600), 100 + rnd.nextInt(900), null, "글 " + step, new TextElem.Style());
                    } else if (op < 28) {
                        last = "page";
                        int k = rnd.nextInt(5);
                        if (k == 0) cv.addPage(Page.create(1240, 1754, Paper.LINED), cv.pageIndex() + 1);
                        else if (k == 1) cv.addPage(Page.create(0, 0, Paper.DOT), cv.pageIndex() + 1);
                        else if (k == 2) cv.deletePage(cv.pageIndex());
                        else if (k == 3) cv.goToPage(rnd.nextInt(cv.pageCount()));
                        else cv.movePage(cv.pageIndex(), rnd.nextInt(cv.pageCount()));
                    } else if (op < 29) {
                        last = "layer";
                        int k = rnd.nextInt(5);
                        if (k == 0) cv.addLayer(10);
                        else if (k == 1) cv.setActiveLayer(rnd.nextInt(cv.page().layers.size()));
                        else if (k == 2 && cv.page().layers.size() > 1) cv.mergeLayerDown(cv.page().layers.size() - 1);
                        else if (k == 3 && cv.hasSelection()) cv.moveSelectionToLayer(rnd.nextInt(cv.page().layers.size()));
                        else {
                            cv.page().layers.get(0).alpha = rnd.nextBoolean() ? 255 : 140;
                            cv.invalidateContent();
                        }
                    } else {
                        last = "finger tap / hover";
                        r.tap(FINGER, x0, y0);
                    }
                    assertNull("seed " + seed + " step " + step + " (" + last + ") 내부 상태", cv.debugCheck());
                    if (step % 4 == 0) r.verify("seed" + seed + "_step" + step + "_" + last.replace(' ', '_').replace('/', '_'));
                    else r.snap();
                }
            } catch (RuntimeException | Error e) {
                throw new AssertionError("seed " + seed + " 마지막 동작: " + last + " → " + e, e);
            }
            total += r.checks;
        }
        System.out.println("[점검] 무작위 사용 6회 × 220동작, 화면 비교 " + total + "번 모두 일치");
    }

    /** 예전 방식(점마다 원)과 새 방식으로 그린 결과와 걸리는 시간 비교 */
    @Test
    public void fasterStrokesLookTheSame() {
        Document d = T.doc(5, 1, 900);
        Rig r = new Rig(d, 1600, 2300);
        long[] ms = new long[2];
        Bitmap[] img = new Bitmap[2];
        for (int mode = 0; mode < 2; mode++) {
            StrokeElem.simplifyPaths = StrokeElem.cheapJoins = mode == 1;
            // 경로를 새로 만들도록 획을 복사
            Layer l = d.pages.get(0).layers.get(0);
            for (int i = 0; i < l.elems.size(); i++) l.elems.set(i, l.elems.get(i).transformed(0, 0, 1, 0, 0));
            r.cv.invalidateScene();
            r.snap(); // 경로 만들기 포함 1회
            long best = Long.MAX_VALUE;
            for (int k = 0; k < 5; k++) {
                r.cv.invalidateScene();
                long t0 = System.nanoTime();
                img[mode] = r.snap();
                best = Math.min(best, System.nanoTime() - t0);
            }
            ms[mode] = best / 1000000;
        }
        StrokeElem.simplifyPaths = StrokeElem.cheapJoins = true;
        int strong = T.diff(img[0], img[1], 40), bad = T.diff(img[0], img[1], 100), any = T.diff(img[0], img[1], 0);
        System.out.println("[점검] 900획 화면 전체 그리기: 예전 " + ms[0] + "ms → 지금 " + ms[1] + "ms, 가장자리 농도가 조금 다른 픽셀 " + strong
                + "개, 크게 다른 픽셀 " + bad + "개 (조금이라도 다른 픽셀 " + any + "개 / " + (1600 * 2300) + ")");
        T.save(img[0], "stroke_old.png");
        T.save(img[1], "stroke_new.png");
        T.save(T.diffImage(img[0], img[1]), "stroke_diff.png");
        assertEquals("새 방식이 예전과 크게 다른 픽셀", 0, bad);
        assertTrue("가장자리 농도 차이가 너무 많음: " + strong, strong < 3680);
    }
}
