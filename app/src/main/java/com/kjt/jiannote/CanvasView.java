package com.kjt.jiannote;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.OverScroller;

import com.kjt.jiannote.model.Document;
import com.kjt.jiannote.model.Element;
import com.kjt.jiannote.model.ImageElem;
import com.kjt.jiannote.model.Layer;
import com.kjt.jiannote.model.MemoElem;
import com.kjt.jiannote.model.TapeElem;
import com.kjt.jiannote.model.Page;
import com.kjt.jiannote.model.StrokeElem;
import com.kjt.jiannote.model.TextElem;
import com.kjt.jiannote.render.RenderContext;
import com.kjt.jiannote.render.Renderer;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * 필기 캔버스. 일반 노트는 모든 페이지가 세로로 이어진 스크롤 방식.
 * - S펜(스타일러스): 필기 / 측면 버튼 누르고 쓰면 지우개
 * - 손가락: 스크롤(관성), 두 손가락 확대/축소, 손가락 필기 옵션
 * - 펜이 화면 근처(호버)에 있을 때 손바닥 터치 무시
 * 요소 좌표는 각 페이지 기준(로컬), 화면 좌표 = (로컬 + 페이지오프셋) * scale + t
 */
public class CanvasView extends View {

    public interface Listener {
        void onPageChanged();

        void onSelectionChanged(boolean hasSelection);

        void onHistoryChanged();

        void onTextRequest(float x, float y, TextElem existing);

        void onZoomChanged(float scale);

        void onDocChanged();

        void onLayerLimit();

        /** 꾹 눌러서 텍스트/이미지 등을 선택함 */
        void onLongPressSelected();

        /** 선택 영역 편집(이동·크기·삭제 등)이 한 번 끝남 */
        void onSelectionEdited();

        /** 스포이드로 색을 집음 */
        void onColorPicked(int color);

        /** 고정된 요소를 꾹 누름 */
        void onLockedPressed(Element e);
    }

    public static final int TOOL_PEN = 0, TOOL_HIGHLIGHTER = 1, TOOL_ERASER = 2, TOOL_LASSO = 3,
            TOOL_SHAPE = 4, TOOL_TEXT = 5, TOOL_HAND = 6, TOOL_TAPE = 7, TOOL_MEMO = 8;

    private static final int M_NONE = 0, M_DRAW = 1, M_GESTURE = 2, M_IGNORE = 3, M_PALM = 4;
    private static final int IN_NONE = 0, IN_STROKE = 1, IN_ERASE = 2, IN_LASSO = 3, IN_MOVE = 4,
            IN_SCALE = 5, IN_SHAPE = 6, IN_TAP = 7, IN_TAPE = 8, IN_MEMO = 9, IN_TAPE_TAP = 10;
    private static final float GAP = 48f;

    // ---- 문서 상태
    private Document doc;
    private RenderContext rc;
    private int paperColor = 0xFFFFFFFF;
    private int pageIndex = 0;
    private int activeLayer = 0;
    private Listener listener;
    public final UndoManager undo = new UndoManager();

    // 페이지 배치 (세로 스크롤)
    private float[] pageTop = new float[0];
    private float[] pageLeft = new float[0];
    private float maxPageW = 1, totalH = 1;

    // ---- 도구 설정
    public int tool = TOOL_PEN;
    public int penType = StrokeElem.PEN;
    public int penColor = 0xFF1A1A1A;
    public float penWidth = 4f;
    public int hlColor = 0xFFFFEB3B;
    public float hlWidth = 26f;
    public float eraserSize = 24f;
    public boolean eraserPartial = true;
    public int shapeType = ShapeRecognizer.RECT;
    public TextElem.Style textStyle = new TextElem.Style();
    public boolean fingerDraw = false;
    public boolean pressureEnabled = true;
    public float pressureGamma = 1.0f;
    public boolean holdToShape = true;
    public boolean penSquare = false;
    public boolean hlSquare = false;
    public boolean scribbleErase = true;
    /** 형광펜만 지우는 지우개 */
    public boolean eraserHlOnly = false;
    /** 사각형 올가미 */
    public boolean lassoRect = false;
    /** 0=전체 1=펜 2=형광펜 3=이미지 4=텍스트 */
    public int lassoFilter = 0;
    /** 스포이드 모드: 다음 터치 위치의 색을 집는다 */
    public boolean eyedropper = false;
    // 테이프
    public float tapeWidth = 48f;
    public int tapeColor = 0xFFFF8A65;
    public int tapePattern = TapeElem.STRIPE;
    /** 켜면 테이프 위에 글씨를 쓰고, 끄면 테이프를 터치해 가리기/보이기 */
    public boolean tapeWriteMode = false;
    /** 테이프를 붙일 때 아래 내용과 자동으로 묶기 */
    public boolean tapeAutoAttach = true;
    // 메모지
    public int memoColor = MemoElem.COLORS[0];
    public int memoPattern = MemoElem.LINED;
    private TapeElem tapTarget;
    private Element previewElem;
    private float tapeX0, tapeY0;

    // 호버 커서
    private float hoverX = -1, hoverY = -1;
    // 꾹 누르기 선택
    private final Runnable longPressRunnable = this::onLongPress;
    private float lpSx, lpSy;
    private int firstPointerId;
    // 도형 보정 후 계속 누른 채 편집
    private StrokeElem snapBase;
    private float snapAx, snapAy, snapPx, snapPy;
    private int snapType = -1;
    // 스크롤용 페이지 캐시
    private final java.util.LinkedHashMap<Page, Bitmap> pageCache = new java.util.LinkedHashMap<Page, Bitmap>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<Page, Bitmap> e) {
            if (size() > 6) {
                e.getValue().recycle();
                return true;
            }
            return false;
        }
    };

    // ---- 변환
    private float scale = 1f, tx = 0f, ty = 0f;

    // ---- 장면 캐시
    private Bitmap scene;
    private boolean sceneDirty = true;
    private float sceneScale = 1f, sceneTx = 0f, sceneTy = 0f;
    private long lastRenderMs = 0;
    private final Matrix tmpMatrix = new Matrix();
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    // ---- 입력 상태
    private int mode = M_NONE;
    private int inputKind = IN_NONE;
    private int drawPointerId = -1;
    private boolean drawIsStylus;
    private long downTime;
    private long lastHoverTime;
    private float downSx, downSy;
    private boolean movedFar;

    private StrokeElem current;
    private float lastWidth;
    private float lastSx, lastSy;
    private boolean snapped;
    private float holdAnchorX, holdAnchorY;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable holdRunnable = this::onHold;

    // 지우개
    private ArrayList<Element> eraseBefore;
    private boolean eraseChanged;
    private float eraserSx = -1, eraserSy = -1;
    private float lastEraseX, lastEraseY;

    // 올가미 / 선택
    private final Path lassoPath = new Path();
    private final ArrayList<float[]> lassoPts = new ArrayList<>();
    private final ArrayList<Element> selection = new ArrayList<>();
    private final HashSet<Element> excluded = new HashSet<>();
    private ArrayList<Element> selPreview;
    private RectF selBounds;
    private float dragStartX, dragStartY;
    private float previewDx, previewDy, previewScale = 1f;

    // 도형
    private float shapeX0, shapeY0;

    // 제스처 / 관성 스크롤
    private boolean gestureActive;
    private boolean pinching, pinchedThisGesture;
    private float gLastCx, gLastCy, gLastDist;
    private int gMaxPointers;
    /** 한 손가락 스크롤 방향 고정: 0=미정 1=세로 2=가로 3=자유 */
    private int axisLock;
    private VelocityTracker velocity;
    private final OverScroller scroller;
    private final int minFling, maxFling;

    // 페인트
    private final Paint deskPaint = new Paint();
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lassoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float density;

    public CanvasView(Context c) {
        super(c);
        density = getResources().getDisplayMetrics().density;
        scroller = new OverScroller(c);
        ViewConfiguration vc = ViewConfiguration.get(c);
        minFling = vc.getScaledMinimumFlingVelocity();
        maxFling = vc.getScaledMaximumFlingVelocity();
        deskPaint.setColor(0xFFD3D3CE);
        shadowPaint.setColor(0x1F000000);
        lassoPaint.setStyle(Paint.Style.STROKE);
        lassoPaint.setColor(0xFF3D5AFE);
        lassoPaint.setStrokeWidth(2 * density);
        lassoPaint.setPathEffect(new DashPathEffect(new float[]{8 * density, 6 * density}, 0));
        selPaint.setStyle(Paint.Style.STROKE);
        selPaint.setColor(0xFF3D5AFE);
        selPaint.setStrokeWidth(1.5f * density);
        selPaint.setPathEffect(new DashPathEffect(new float[]{6 * density, 4 * density}, 0));
        handlePaint.setColor(0xFF3D5AFE);
        cursorPaint.setStyle(Paint.Style.STROKE);
        cursorPaint.setStrokeWidth(1.5f * density);
        cursorPaint.setColor(0xAA555555);
        setFocusable(true);
    }

    // ================= 공개 API =================

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void setDocument(Document d, RenderContext rc, int paperColor) {
        this.doc = d;
        this.rc = rc;
        this.paperColor = paperColor;
        undo.clear();
        pageIndex = 0;
        activeLayer = d.pages.get(0).layers.size() - 1;
        relayout();
        post(this::fitPage);
    }

    public Document doc() { return doc; }

    public int pageIndex() { return pageIndex; }

    public int pageCount() { return doc == null ? 0 : doc.pages.size(); }

    public Page page() { return doc.pages.get(pageIndex); }

    public int activeLayerIndex() { return activeLayer; }

    public boolean isInfinite() { return doc != null && doc.pages.get(0).isInfinite(); }

    public Layer activeLayer() {
        Page p = page();
        if (activeLayer < 0 || activeLayer >= p.layers.size()) activeLayer = p.layers.size() - 1;
        return p.layers.get(activeLayer);
    }

    public void setActiveLayer(int i) {
        clearSelection();
        activeLayer = Math.max(0, Math.min(i, page().layers.size() - 1));
        invalidateScene();
    }

    public float getScale() { return scale; }

    /** 100% 기준 배율 (화면 너비 맞춤) */
    public float baseScale() {
        if (isInfinite() || getWidth() == 0) return 1f;
        return (getWidth() - 32 * density) / maxPageW;
    }

    public void setPaperColor(int c) {
        paperColor = c;
        invalidateContent();
    }

    /** 페이지 배치 다시 계산 */
    public void relayout() {
        if (doc == null) return;
        int n = doc.pages.size();
        pageTop = new float[n];
        pageLeft = new float[n];
        if (isInfinite()) {
            maxPageW = 1;
            totalH = 1;
            return;
        }
        maxPageW = 1;
        for (Page p : doc.pages) maxPageW = Math.max(maxPageW, p.w);
        float y = 0;
        for (int i = 0; i < n; i++) {
            Page p = doc.pages.get(i);
            pageTop[i] = y;
            pageLeft[i] = (maxPageW - p.w) / 2f;
            y += p.h + GAP;
        }
        totalH = y - GAP;
    }

    private float ox() { return pageIndex < pageLeft.length ? pageLeft[pageIndex] : 0; }

    private float oy() { return pageIndex < pageTop.length ? pageTop[pageIndex] : 0; }

    private int pageAtWorldY(float gy) {
        if (isInfinite()) return 0;
        for (int i = 0; i < doc.pages.size(); i++) {
            if (gy < pageTop[i] + doc.pages.get(i).h + GAP / 2f) return i;
        }
        return doc.pages.size() - 1;
    }

    private void setCurrentPage(int i) {
        if (i == pageIndex) return;
        clearSelection();
        pageIndex = i;
        activeLayer = Math.min(activeLayer, page().layers.size() - 1);
        if (listener != null) listener.onPageChanged();
    }

    private void switchToPageAt(float sx, float sy) {
        if (isInfinite()) return;
        setCurrentPage(pageAtWorldY((sy - ty) / scale));
    }

    /** 화면 중앙에 있는 페이지를 현재 페이지로 */
    private void updatePageFromViewport() {
        if (isInfinite() || inputKind != IN_NONE || !selection.isEmpty() || getHeight() == 0) return;
        int i = pageAtWorldY((getHeight() / 3f - ty) / scale);
        setCurrentPage(i);
    }

    public void goToPage(int i) {
        if (doc == null) return;
        i = Math.max(0, Math.min(i, doc.pages.size() - 1));
        clearSelection();
        scroller.forceFinished(true);
        pageIndex = i;
        activeLayer = Math.min(activeLayer, page().layers.size() - 1);
        if (!isInfinite()) {
            ty = 16 * density - pageTop[i] * scale;
            clampTransform();
        }
        invalidateScene();
        if (listener != null) listener.onPageChanged();
    }

    public void invalidateScene() {
        for (Bitmap b : pageCache.values()) b.recycle();
        pageCache.clear();
        sceneDirty = true;
        invalidate();
    }

    public void invalidateContent() {
        invalidateScene();
    }

    private void dropPageCache(Page p) {
        Bitmap b = pageCache.remove(p);
        if (b != null) b.recycle();
    }

    public void fitPage() {
        if (doc == null || getWidth() == 0) return;
        if (isInfinite()) {
            if (tx == 0 && ty == 0) {
                scale = 1;
                tx = 40 * density;
                ty = 40 * density;
            }
        } else {
            scale = baseScale();
            tx = (getWidth() - maxPageW * scale) / 2f;
            ty = 16 * density - oy() * scale;
            clampTransform();
        }
        if (listener != null) listener.onZoomChanged(scale);
        invalidateScene();
    }

    public void resetInfiniteView() {
        scale = 1;
        tx = 40 * density;
        ty = 40 * density;
        if (listener != null) listener.onZoomChanged(scale);
        invalidateScene();
    }

    public void undo() {
        clearSelection();
        afterHistory(undo.undo());
    }

    public void redo() {
        clearSelection();
        afterHistory(undo.redo());
    }

    private void afterHistory(UndoManager.Cmd c) {
        if (c != null && c.page() >= 0 && c.page() != pageIndex && c.page() < doc.pages.size()) {
            goToPage(c.page());
        }
        activeLayer = Math.min(activeLayer, page().layers.size() - 1);
        changed();
    }

    private void changed() {
        invalidateScene();
        if (listener != null) {
            listener.onHistoryChanged();
            listener.onDocChanged();
        }
    }

    private void pushElems(Layer l, ArrayList<Element> before) {
        undo.push(new UndoManager.ElemsCmd(pageIndex, l, before, new ArrayList<>(l.elems)));
    }

    // ---- 레이어 조작 (되돌리기 지원)
    public boolean addLayer(int limit) {
        Page p = page();
        if (p.layers.size() >= limit) {
            if (listener != null) listener.onLayerLimit();
            return false;
        }
        ArrayList<Layer> before = new ArrayList<>(p.layers);
        p.layers.add(activeLayer + 1, new Layer("레이어 " + (p.layers.size() + 1)));
        activeLayer++;
        undo.push(new UndoManager.LayersCmd(pageIndex, p, before, new ArrayList<>(p.layers)));
        changed();
        return true;
    }

    public void deleteLayer(int i) {
        Page p = page();
        if (p.layers.size() <= 1) return;
        clearSelection();
        ArrayList<Layer> before = new ArrayList<>(p.layers);
        p.layers.remove(i);
        activeLayer = Math.max(0, Math.min(activeLayer, p.layers.size() - 1));
        undo.push(new UndoManager.LayersCmd(pageIndex, p, before, new ArrayList<>(p.layers)));
        changed();
    }

    public void moveLayer(int i, int dir) {
        Page p = page();
        int j = i + dir;
        if (j < 0 || j >= p.layers.size()) return;
        ArrayList<Layer> before = new ArrayList<>(p.layers);
        Layer l = p.layers.remove(i);
        p.layers.add(j, l);
        if (activeLayer == i) activeLayer = j;
        else if (activeLayer == j) activeLayer = i;
        undo.push(new UndoManager.LayersCmd(pageIndex, p, before, new ArrayList<>(p.layers)));
        changed();
    }

    public void mergeLayerDown(int i) {
        Page p = page();
        if (i <= 0) return;
        ArrayList<Layer> before = new ArrayList<>(p.layers);
        Layer top = p.layers.get(i);
        Layer below = p.layers.get(i - 1).copy();
        below.elems.addAll(top.elems);
        p.layers.set(i - 1, below);
        p.layers.remove(i);
        activeLayer = i - 1;
        undo.push(new UndoManager.LayersCmd(pageIndex, p, before, new ArrayList<>(p.layers)));
        changed();
    }

    public void clearActiveLayer() {
        Layer l = activeLayer();
        if (l.elems.isEmpty()) return;
        clearSelection();
        ArrayList<Element> before = new ArrayList<>(l.elems);
        l.elems = new ArrayList<>();
        pushElems(l, before);
        changed();
    }

    // ---- 페이지 조작 (되돌리기 기록 초기화)
    public void addPage(Page p, int at) {
        doc.pages.add(at, p);
        relayout();
        undo.clear();
        goToPage(at);
        changed();
    }

    public void deletePage(int i) {
        if (doc.pages.size() <= 1) return;
        doc.pages.remove(i);
        relayout();
        undo.clear();
        pageIndex = Math.min(pageIndex, doc.pages.size() - 1);
        goToPage(Math.min(i, doc.pages.size() - 1));
        changed();
    }

    public void movePage(int from, int to) {
        if (to < 0 || to >= doc.pages.size()) return;
        Page p = doc.pages.remove(from);
        doc.pages.add(to, p);
        relayout();
        undo.clear();
        goToPage(to);
        changed();
    }

    public void pagesChanged() {
        relayout();
        clampTransform();
        changed();
    }

    // ---- 텍스트/이미지
    public void commitText(float x, float y, TextElem existing, String text, TextElem.Style style) {
        Layer l = activeLayer();
        ArrayList<Element> before = new ArrayList<>(l.elems);
        int idx = existing == null ? -1 : l.elems.indexOf(existing);
        if (text == null || text.trim().isEmpty()) {
            if (idx >= 0) l.elems.remove(idx);
            else return;
        } else if (idx >= 0) {
            l.elems.set(idx, existing.withText(text, style));
        } else {
            TextElem t = new TextElem(x, y, text, style, 0);
            joinContainer(t, x, y);
            l.elems.add(t);
        }
        pushElems(l, before);
        changed();
    }

    /** 선택된 것이 텍스트 하나뿐이면 반환 */
    public TextElem singleSelectedText() {
        if (selection.size() == 1 && selection.get(0) instanceof TextElem) return (TextElem) selection.get(0);
        return null;
    }

    public void editSelectedText(TextElem old, String text, TextElem.Style st) {
        if (text == null || text.trim().isEmpty()) {
            deleteSelection();
            return;
        }
        replaceSelection(e -> e == old ? old.withText(text, st) : e);
    }

    public void addImage(String file, int bw, int bh) {
        RectF vis = visibleWorld();
        float w = Math.min(vis.width() * 0.5f, bw);
        float h = w * bh / Math.max(1, bw);
        if (h > vis.height() * 0.6f) {
            h = vis.height() * 0.6f;
            w = h * bw / Math.max(1, bh);
        }
        float x = vis.centerX() - w / 2, y = vis.centerY() - h / 2;
        if (!isInfinite()) {
            Page p = page();
            x = Math.max(0, Math.min(x, p.w - w));
            y = Math.max(0, Math.min(y, p.h - h));
        }
        ImageElem e = new ImageElem(file, x, y, w, h);
        Layer l = activeLayer();
        ArrayList<Element> before = new ArrayList<>(l.elems);
        l.elems.add(e);
        pushElems(l, before);
        changed();
        tool = TOOL_LASSO;
        select(java.util.Collections.<Element>singletonList(e));
    }

    // ---- 선택 영역 동작
    public boolean hasSelection() { return !selection.isEmpty(); }

    public void clearSelection() {
        if (selection.isEmpty() && excluded.isEmpty()) return;
        selection.clear();
        excluded.clear();
        selPreview = null;
        selBounds = null;
        invalidateScene();
        if (listener != null) listener.onSelectionChanged(false);
    }

    private void select(java.util.List<Element> list) {
        selection.clear();
        excluded.clear();
        selection.addAll(list);
        // 테이프·메모지가 포함되면 함께 묶인 내용도 선택
        java.util.HashSet<Integer> groups = new java.util.HashSet<>();
        for (Element e : list) if (e.isContainer() && e.group != 0) groups.add(e.group);
        if (!groups.isEmpty()) {
            for (Element e : activeLayer().elems) {
                if (e.group != 0 && groups.contains(e.group) && !selection.contains(e) && !e.locked) selection.add(e);
            }
        }
        list = new ArrayList<>(selection);
        excluded.addAll(list);
        updateSelBounds();
        invalidateScene();
        if (listener != null) listener.onSelectionChanged(!selection.isEmpty());
    }

    private void updateSelBounds() {
        RectF b = null;
        for (Element e : selection) {
            if (b == null) b = new RectF(e.bounds());
            else b.union(e.bounds());
        }
        selBounds = b;
    }

    public void deleteSelection() {
        if (selection.isEmpty()) return;
        Layer l = activeLayer();
        ArrayList<Element> before = new ArrayList<>(l.elems);
        l.elems.removeAll(selection);
        pushElems(l, before);
        selection.clear();
        excluded.clear();
        selBounds = null;
        if (listener != null) listener.onSelectionChanged(false);
        changed();
        if (listener != null) listener.onSelectionEdited();
    }

    public static int newGroup() {
        int g;
        do {
            g = (int) (Math.random() * Integer.MAX_VALUE);
        } while (g == 0);
        return g;
    }

    /** (x,y) 아래의 메모지 (또는 쓰기 모드일 때 테이프) — 위에 쓴 것을 묶기 위해 */
    private Element containerAt(float x, float y) {
        Layer l = activeLayer();
        for (int i = l.elems.size() - 1; i >= 0; i--) {
            Element e = l.elems.get(i);
            if (e instanceof MemoElem && ((MemoElem) e).contains(x, y)) return e;
            if (tapeWriteMode && e instanceof TapeElem && e.hit(x, y, 0)) return e;
        }
        return null;
    }

    private void joinContainer(Element el, float x, float y) {
        if (el.isContainer()) return;
        Element c = containerAt(x, y);
        if (c == null) return;
        if (c.group == 0) c.group = newGroup();
        el.group = c.group;
    }

    /** 테이프 아래 내용과 묶기 */
    private int attachTape(TapeElem t) {
        if (t.group == 0) t.group = newGroup();
        int n = 0;
        for (Element e : activeLayer().elems) {
            if (e == t || e.isContainer() || e.locked) continue;
            if (!RectF.intersects(t.bounds(), e.bounds())) continue;
            boolean under = false;
            if (e instanceof StrokeElem) {
                StrokeElem s = (StrokeElem) e;
                for (int i = 0; i < s.n && !under; i++) under = t.hit(s.pts[3 * i], s.pts[3 * i + 1], 0);
            } else {
                under = t.hit(e.centerX(), e.centerY(), 0);
            }
            if (under) {
                e.group = t.group;
                n++;
            }
        }
        return n;
    }

    /** 선택한 테이프를 아래 내용에 붙이기(true) / 떼기(false) */
    public int attachSelectedTapes(boolean attach) {
        int n = 0;
        for (Element e : new ArrayList<>(selection)) {
            if (!(e instanceof TapeElem)) continue;
            if (attach) {
                n += attachTape((TapeElem) e);
            } else {
                int g = e.group;
                if (g != 0) for (Element o : activeLayer().elems) if (o != e && o.group == g) {
                    o.group = 0;
                    n++;
                }
                e.group = 0;
            }
        }
        clearSelection();
        changed();
        return n;
    }

    public boolean selectionHasTape() {
        for (Element e : selection) if (e instanceof TapeElem) return true;
        return false;
    }

    public MemoElem singleSelectedMemo() {
        MemoElem m = null;
        for (Element e : selection) if (e instanceof MemoElem) {
            if (m != null) return null;
            m = (MemoElem) e;
        }
        return m;
    }

    public void setMemoPattern(MemoElem m, int pattern) {
        replaceSelection(e -> e == m ? m.withPattern(pattern) : e);
    }

    /** 현재 선택 항목의 복사본 (스티커 저장용) */
    public ArrayList<Element> selectionCopy() {
        ArrayList<Element> out = new ArrayList<>();
        for (Element e : selection) {
            Element c = e.transformed(0, 0, 1, 0, 0);
            c.group = e.group;
            out.add(c);
        }
        return out;
    }

    public RectF selectionBounds() {
        return selBounds == null ? null : new RectF(selBounds);
    }

    /** 요소 묶음을 화면 가운데에 넣고 선택 (스티커) */
    public void insertElements(java.util.List<Element> elems) {
        if (elems.isEmpty()) return;
        RectF b = null;
        for (Element e : elems) {
            if (b == null) b = new RectF(e.bounds());
            else b.union(e.bounds());
        }
        RectF vis = visibleWorld();
        float maxW = vis.width() * 0.6f;
        float s = b.width() > maxW ? maxW / b.width() : 1f;
        float dx = vis.centerX() - b.centerX(), dy = vis.centerY() - b.centerY();
        java.util.HashMap<Integer, Integer> gmap = new java.util.HashMap<>();
        ArrayList<Element> placed = new ArrayList<>();
        for (Element e : elems) {
            Element c = e.transformed(dx, dy, s, b.centerX(), b.centerY());
            if (e.group != 0) {
                Integer ng = gmap.get(e.group);
                if (ng == null) {
                    ng = newGroup();
                    gmap.put(e.group, ng);
                }
                c.group = ng;
            }
            placed.add(c);
        }
        Layer l = activeLayer();
        ArrayList<Element> before = new ArrayList<>(l.elems);
        l.elems.addAll(placed);
        pushElems(l, before);
        changed();
        select(placed);
        if (listener != null) listener.onLongPressSelected();
    }

    /** 선택 항목 고정 */
    public void lockSelection() {
        if (selection.isEmpty()) return;
        for (Element e : selection) e.locked = true;
        selection.clear();
        excluded.clear();
        selBounds = null;
        if (listener != null) listener.onSelectionChanged(false);
        changed();
        if (listener != null) listener.onSelectionEdited();
    }

    public void unlock(Element e) {
        e.locked = false;
        changed();
    }

    public int unlockAllOnPage() {
        int c = 0;
        for (Layer l : page().layers) for (Element e : l.elems) if (e.locked) {
            e.locked = false;
            c++;
        }
        if (c > 0) changed();
        return c;
    }

    public void duplicateSelection() {
        if (selection.isEmpty()) return;
        Layer l = activeLayer();
        ArrayList<Element> before = new ArrayList<>(l.elems);
        ArrayList<Element> copies = new ArrayList<>();
        java.util.HashMap<Integer, Integer> gmap = new java.util.HashMap<>();
        for (Element e : selection) {
            Element c = e.transformed(30, 30, 1, 0, 0);
            if (e.group != 0) {
                Integer ng = gmap.get(e.group);
                if (ng == null) {
                    ng = newGroup();
                    gmap.put(e.group, ng);
                }
                c.group = ng;
            }
            copies.add(c);
        }
        l.elems.addAll(copies);
        pushElems(l, before);
        changed();
        select(copies);
        if (listener != null) listener.onSelectionEdited();
    }

    public void recolorSelection(int color) {
        if (selection.isEmpty()) return;
        replaceSelection(e -> e.withColor(color));
    }

    public void moveSelectionToLayer(int target) {
        if (selection.isEmpty()) return;
        Page p = page();
        if (target < 0 || target >= p.layers.size() || target == activeLayer) return;
        Layer src = activeLayer(), dst = p.layers.get(target);
        ArrayList<Layer> before = new ArrayList<>(p.layers);
        Layer ns = src.copy(), nd = dst.copy();
        ns.elems.removeAll(selection);
        nd.elems.addAll(selection);
        p.layers.set(activeLayer, ns);
        p.layers.set(target, nd);
        undo.push(new UndoManager.LayersCmd(pageIndex, p, before, new ArrayList<>(p.layers)));
        ArrayList<Element> moved = new ArrayList<>(selection);
        activeLayer = target;
        changed();
        select(moved);
        if (listener != null) listener.onSelectionEdited();
    }

    private interface ElemFn {
        Element apply(Element e);
    }

    private void replaceSelection(ElemFn fn) {
        Layer l = activeLayer();
        ArrayList<Element> before = new ArrayList<>(l.elems);
        ArrayList<Element> newSel = new ArrayList<>();
        for (Element e : selection) {
            int idx = l.elems.indexOf(e);
            Element ne = fn.apply(e);
            if (ne != e) {
                ne.group = e.group;
                ne.locked = e.locked;
            }
            if (idx >= 0) l.elems.set(idx, ne);
            newSel.add(ne);
        }
        pushElems(l, before);
        changed();
        select(newSel);
        if (listener != null) listener.onSelectionEdited();
    }

    // ================= 좌표 (현재 페이지 기준) =================

    private float wx(float sx) { return (sx - tx) / scale - ox(); }

    private float wy(float sy) { return (sy - ty) / scale - oy(); }

    private float sxOf(float lx) { return (lx + ox()) * scale + tx; }

    private float syOf(float ly) { return (ly + oy()) * scale + ty; }

    public RectF visibleWorld() {
        return new RectF(wx(0), wy(0), wx(getWidth()), wy(getHeight()));
    }

    // ================= 그리기 =================

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w <= 0 || h <= 0) return;
        if (scene != null) scene.recycle();
        scene = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        if (doc != null && (ow == 0 || !isInfinite())) fitPage();
        sceneDirty = true;
    }

    private void renderScene() {
        if (scene == null || doc == null) return;
        long t0 = SystemClock.uptimeMillis();
        Canvas c = new Canvas(scene);
        int W = scene.getWidth(), H = scene.getHeight();
        c.drawRect(0, 0, W, H, deskPaint);
        if (isInfinite()) {
            c.save();
            c.translate(tx, ty);
            c.scale(scale, scale);
            RectF vis = new RectF(-tx / scale, -ty / scale, (W - tx) / scale, (H - ty) / scale);
            Renderer.drawPage(c, doc.pages.get(0), paperColor, rc, vis, scale, excluded);
            c.restore();
        } else {
            float gTop = -ty / scale, gBot = (H - ty) / scale;
            float gLeft = -tx / scale, gRight = (W - tx) / scale;
            float sh = 3 * density;
            for (int i = 0; i < doc.pages.size(); i++) {
                Page p = doc.pages.get(i);
                float top = pageTop[i], left = pageLeft[i];
                if (top > gBot || top + p.h < gTop) continue;
                float sl = tx + left * scale, st = ty + top * scale;
                c.drawRect(sl + sh, st + sh, sl + p.w * scale + sh, st + p.h * scale + sh, shadowPaint);
                c.save();
                c.translate(tx, ty);
                c.scale(scale, scale);
                c.translate(left, top);
                RectF vis = new RectF(gLeft - left, gTop - top, gRight - left, gBot - top);
                Renderer.drawPage(c, p, paperColor, rc, vis, scale, i == pageIndex ? excluded : null);
                c.restore();
            }
        }
        sceneScale = scale;
        sceneTx = tx;
        sceneTy = ty;
        sceneDirty = false;
        lastRenderMs = SystemClock.uptimeMillis() - t0;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (doc == null || scene == null) return;
        boolean moving = gestureActive || !scroller.isFinished();
        if (moving && !isInfinite() && sceneDirty && (pinching || lastRenderMs > 12)) {
            drawFromPageCache(canvas);
        } else {
            if (sceneDirty) renderScene();
            drawScene(canvas);
        }
        drawOverlays(canvas);
    }

    /** 각 페이지를 낮은 해상도로 캐시해 두었다가 스크롤·확대 중에는 그대로 늘려 그린다 */
    private void drawFromPageCache(Canvas canvas) {
        canvas.drawRect(0, 0, getWidth(), getHeight(), deskPaint);
        float gTop = -ty / scale, gBot = (getHeight() - ty) / scale;
        float sh = 3 * density;
        for (int i = 0; i < doc.pages.size(); i++) {
            Page p = doc.pages.get(i);
            float top = pageTop[i], left = pageLeft[i];
            if (top > gBot || top + p.h < gTop) continue;
            Bitmap b = pageCache.get(p);
            if (b == null || b.isRecycled()) {
                float cs = Math.min(1.6f, 1500f / Math.max(1f, p.w));
                try {
                    b = Bitmap.createBitmap(Math.max(1, Math.round(p.w * cs)), Math.max(1, Math.round(p.h * cs)), Bitmap.Config.RGB_565);
                } catch (OutOfMemoryError oom) {
                    invalidateContent();
                    return;
                }
                Canvas c = new Canvas(b);
                c.scale(cs, cs);
                Renderer.drawPage(c, p, paperColor, rc, new RectF(0, 0, p.w, p.h), cs, null);
                pageCache.put(p, b);
            }
            float sl = tx + left * scale, st = ty + top * scale;
            canvas.drawRect(sl + sh, st + sh, sl + p.w * scale + sh, st + p.h * scale + sh, shadowPaint);
            canvas.drawBitmap(b, null, new RectF(sl, st, sl + p.w * scale, st + p.h * scale), bitmapPaint);
        }
    }

    private void drawScene(Canvas canvas) {
        boolean same = sceneScale == scale && sceneTx == tx && sceneTy == ty;
        if (same) {
            canvas.drawBitmap(scene, 0, 0, null);
        } else {
            canvas.drawRect(0, 0, getWidth(), getHeight(), deskPaint);
            float k = scale / sceneScale;
            tmpMatrix.setScale(k, k);
            tmpMatrix.postTranslate(tx - k * sceneTx, ty - k * sceneTy);
            canvas.drawBitmap(scene, tmpMatrix, bitmapPaint);
        }
    }

    private void drawOverlays(Canvas canvas) {
        canvas.save();
        canvas.translate(tx, ty);
        canvas.scale(scale, scale);
        canvas.translate(ox(), oy());
        Page p = page();
        if (!p.isInfinite()) canvas.clipRect(0, 0, p.w, p.h);
        if (current != null) current.draw(canvas, rc);
        if (previewElem != null) previewElem.draw(canvas, rc);
        if (!selection.isEmpty()) {
            ArrayList<Element> list = selPreview != null ? selPreview : selection;
            for (Element e : list) e.draw(canvas, rc);
        }
        canvas.restore();

        if (inputKind == IN_LASSO && !lassoPts.isEmpty() && lassoRect) {
            float[] a = lassoPts.get(0), b = lassoPts.get(lassoPts.size() - 1);
            canvas.drawRect(Math.min(sxOf(a[0]), sxOf(b[0])), Math.min(syOf(a[1]), syOf(b[1])),
                    Math.max(sxOf(a[0]), sxOf(b[0])), Math.max(syOf(a[1]), syOf(b[1])), lassoPaint);
        } else if (inputKind == IN_LASSO && !lassoPts.isEmpty()) {
            lassoPath.reset();
            for (int i = 0; i < lassoPts.size(); i++) {
                float[] q = lassoPts.get(i);
                float sx = sxOf(q[0]), sy = syOf(q[1]);
                if (i == 0) lassoPath.moveTo(sx, sy);
                else lassoPath.lineTo(sx, sy);
            }
            canvas.drawPath(lassoPath, lassoPaint);
        }
        if (selBounds != null) {
            RectF b = screenSelBounds();
            canvas.drawRect(b, selPaint);
            canvas.drawCircle(b.right, b.bottom, 9 * density, handlePaint);
        }
        if ((tool == TOOL_ERASER || inputKind == IN_ERASE) && eraserSx >= 0) {
            canvas.drawCircle(eraserSx, eraserSy, eraserSize / 2f, cursorPaint);
        } else if (hoverX >= 0 && inputKind == IN_NONE && (tool == TOOL_PEN || tool == TOOL_HIGHLIGHTER || tool == TOOL_SHAPE)) {
            // 펜을 화면 가까이 댔을 때 굵기만큼 원 표시
            float w = tool == TOOL_HIGHLIGHTER ? hlWidth : penWidth;
            float r = Math.max(2 * density, w * scale / 2f);
            cursorPaint.setColor(tool == TOOL_HIGHLIGHTER ? (hlColor & 0x00FFFFFF) | 0xCC000000 : 0xAA555555);
            canvas.drawCircle(hoverX, hoverY, r, cursorPaint);
            cursorPaint.setColor(0xAA555555);
        }
    }

    private RectF screenSelBounds() {
        RectF b = new RectF(selBounds);
        if (selPreview != null) {
            b.left = selBounds.left + previewDx;
            b.top = selBounds.top + previewDy;
            b.right = (selBounds.right - selBounds.left) * previewScale + selBounds.left + previewDx;
            b.bottom = (selBounds.bottom - selBounds.top) * previewScale + selBounds.top + previewDy;
        }
        RectF s = new RectF(sxOf(b.left), syOf(b.top), sxOf(b.right), syOf(b.bottom));
        s.inset(-6 * density, -6 * density);
        return s;
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            tx = scroller.getCurrX();
            ty = scroller.getCurrY();
            sceneDirty = true;
            updatePageFromViewport();
            postInvalidateOnAnimation();
            if (scroller.isFinished()) postInvalidate();
        }
    }

    // ================= 입력 =================

    @Override
    public boolean onHoverEvent(MotionEvent e) {
        int tt = e.getToolType(0);
        if (tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER) {
            lastHoverTime = SystemClock.uptimeMillis();
            if (e.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT) {
                hoverX = hoverY = -1;
            } else {
                hoverX = e.getX();
                hoverY = e.getY();
            }
            invalidate();
            if (tool == TOOL_ERASER) {
                if (e.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT) {
                    eraserSx = -1;
                } else {
                    eraserSx = e.getX();
                    eraserSy = e.getY();
                }
                invalidate();
            }
        }
        return super.onHoverEvent(e);
    }

    private static boolean isStylus(MotionEvent e, int idx) {
        int tt = e.getToolType(idx);
        return tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (doc == null) return false;
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (velocity != null) velocity.recycle();
            velocity = VelocityTracker.obtain();
            if (!scroller.isFinished()) {
                scroller.forceFinished(true);
                sceneDirty = true;
            }
        }
        if (velocity != null) velocity.addMovement(e);
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                requestUnbufferedDispatch(e);
                boolean stylus = isStylus(e, 0);
                downSx = e.getX();
                downSy = e.getY();
                downTime = e.getEventTime();
                movedFar = false;
                hoverX = -1;
                firstPointerId = e.getPointerId(0);
                if (eyedropper) {
                    pickColor(e.getX(), e.getY());
                    mode = M_IGNORE;
                    break;
                }
                if (tool == TOOL_HAND) {
                    mode = M_GESTURE;
                    startGesture(e, -1);
                    scheduleLongPress(e.getX(), e.getY());
                } else if (stylus || fingerDraw) {
                    mode = M_DRAW;
                    drawPointerId = e.getPointerId(0);
                    drawIsStylus = stylus;
                    boolean erase = e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
                            || (stylus && (e.getButtonState() & (MotionEvent.BUTTON_STYLUS_PRIMARY
                            | MotionEvent.BUTTON_STYLUS_SECONDARY | MotionEvent.BUTTON_SECONDARY)) != 0);
                    beginInput(e, 0, erase);
                } else if (SystemClock.uptimeMillis() - lastHoverTime < 600) {
                    mode = M_PALM; // 펜이 근처에 있으면 손바닥으로 간주
                } else {
                    mode = M_GESTURE;
                    startGesture(e, -1);
                    scheduleLongPress(e.getX(), e.getY());
                }
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                handler.removeCallbacks(longPressRunnable);
                int ai = e.getActionIndex();
                if (isStylus(e, ai) && mode != M_DRAW && tool != TOOL_HAND) {
                    if (gestureActive) endGesture(false);
                    mode = M_DRAW;
                    drawPointerId = e.getPointerId(ai);
                    drawIsStylus = true;
                    downSx = e.getX(ai);
                    downSy = e.getY(ai);
                    downTime = e.getEventTime();
                    movedFar = false;
                    beginInput(e, ai, (e.getButtonState() & MotionEvent.BUTTON_STYLUS_PRIMARY) != 0);
                } else if (mode == M_DRAW) {
                    if (!drawIsStylus && e.getEventTime() - downTime < 300) {
                        cancelInput();
                        mode = M_GESTURE;
                        startGesture(e, -1);
                    }
                } else if (mode == M_GESTURE) {
                    startGesture(e, -1);
                } else if (mode == M_PALM && !isStylus(e, ai)) {
                    mode = M_GESTURE;
                    startGesture(e, -1);
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mode == M_DRAW) {
                    int idx = e.findPointerIndex(drawPointerId);
                    if (idx >= 0) moveInput(e, idx);
                } else if (mode == M_GESTURE) {
                    moveGesture(e);
                    if (movedFar) handler.removeCallbacks(longPressRunnable);
                }
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                int ai = e.getActionIndex();
                if (mode == M_DRAW) {
                    if (e.getPointerId(ai) == drawPointerId) {
                        endInput(e, ai);
                        mode = M_IGNORE;
                    }
                } else if (mode == M_GESTURE) {
                    startGesture(e, ai);
                }
                break;
            }
            case MotionEvent.ACTION_UP: {
                handler.removeCallbacks(longPressRunnable);
                if (mode == M_DRAW) {
                    int idx = e.findPointerIndex(drawPointerId);
                    endInput(e, Math.max(idx, 0));
                } else if (mode == M_GESTURE) {
                    boolean tap = !movedFar && e.getEventTime() - downTime < 300 && gMaxPointers == 1;
                    endGesture(!tap);
                    if (tap) fingerTap(e.getX(), e.getY());
                }
                mode = M_NONE;
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                handler.removeCallbacks(longPressRunnable);
                cancelInput();
                if (gestureActive) endGesture(false);
                mode = M_NONE;
                break;
            }
            default:
                break;
        }
        return true;
    }

    private void fingerTap(float sx, float sy) {
        if (!tapeWriteMode) {
            switchToPageAt(sx, sy);
            TapeElem tp = tapeAt(wx(sx), wy(sy));
            if (tp != null) {
                toggleTape(tp);
                return;
            }
        }
        if (tool == TOOL_TEXT) {
            switchToPageAt(sx, sy);
            textTap(wx(sx), wy(sy));
        } else if (hasSelection()) {
            RectF b = screenSelBounds();
            if (!b.contains(sx, sy)) clearSelection();
        }
    }

    // ---------- 필기 입력 ----------

    private float widthFor(float pressure, boolean stylus) {
        float p = (stylus && pressureEnabled) ? pressure : 0.55f;
        p = Math.max(0.02f, Math.min(1f, p));
        float f = (float) Math.pow(p, pressureGamma);
        return penWidthFor(penType, penWidth, f);
    }

    /** 펜 종류별 필압 → 굵기 (f = 0~1) */
    public static float penWidthFor(int type, float base, float f) {
        switch (type) {
            case StrokeElem.FOUNTAIN: return base * (0.15f + 1.35f * f);
            case StrokeElem.BRUSH: return base * (0.05f + 2.4f * f);
            case StrokeElem.PENCIL: return base * (0.45f + 0.6f * f);
            case StrokeElem.MARKER: return base * 1.4f;
            case StrokeElem.AIRBRUSH: return base * (0.8f + 1.2f * f);
            case StrokeElem.WATERCOLOR: return base * (0.2f + 2.0f * f);
            default: return base * (0.55f + 0.7f * f);
        }
    }

    private void beginInput(MotionEvent e, int idx, boolean erase) {
        float sx = e.getX(idx), sy = e.getY(idx);
        int t = erase ? TOOL_ERASER : tool;
        boolean onSelection = false;
        if (t == TOOL_LASSO && selBounds != null) {
            RectF b = screenSelBounds();
            onSelection = b.contains(sx, sy) || Math.hypot(sx - b.right, sy - b.bottom) < 28 * density;
        }
        if (!onSelection) switchToPageAt(sx, sy);
        float x = wx(sx), y = wy(sy);
        // 테이프 터치 → 잠깐 투명하게 / 다시 가리기 (테이프 쓰기 모드가 꺼져 있을 때)
        if (!tapeWriteMode && (t == TOOL_PEN || t == TOOL_HIGHLIGHTER || t == TOOL_TEXT || t == TOOL_SHAPE || t == TOOL_MEMO)) {
            TapeElem tp = tapeAt(x, y);
            if (tp != null) {
                tapTarget = tp;
                inputKind = IN_TAPE_TAP;
                return;
            }
        }
        lastSx = sx;
        lastSy = sy;
        if (t != TOOL_LASSO && t != TOOL_TEXT && t != TOOL_ERASER) clearSelection();
        switch (t) {
            case TOOL_PEN: {
                inputKind = IN_STROKE;
                snapped = false;
                current = new StrokeElem(penType, penColor, penSquare);
                lastWidth = widthFor(e.getPressure(idx), drawIsStylus);
                snapType = -1;
                scheduleLongPress(sx, sy);
                current.add(x, y, lastWidth);
                holdAnchorX = sx;
                holdAnchorY = sy;
                scheduleHold();
                break;
            }
            case TOOL_HIGHLIGHTER: {
                inputKind = IN_STROKE;
                snapped = false;
                current = new StrokeElem(StrokeElem.HIGHLIGHTER, hlColor, hlSquare);
                current.add(x, y, hlWidth);
                snapType = -1;
                scheduleLongPress(sx, sy);
                holdAnchorX = sx;
                holdAnchorY = sy;
                scheduleHold();
                break;
            }
            case TOOL_ERASER: {
                inputKind = IN_ERASE;
                eraseBefore = new ArrayList<>(activeLayer().elems);
                eraseChanged = false;
                eraserSx = sx;
                eraserSy = sy;
                lastEraseX = x;
                lastEraseY = y;
                eraseAt(x, y);
                break;
            }
            case TOOL_LASSO: {
                if (selBounds != null) {
                    RectF b = screenSelBounds();
                    if (Math.hypot(sx - b.right, sy - b.bottom) < 28 * density) {
                        inputKind = IN_SCALE;
                        dragStartX = x;
                        dragStartY = y;
                        return;
                    }
                    if (b.contains(sx, sy)) {
                        inputKind = IN_MOVE;
                        dragStartX = x;
                        dragStartY = y;
                        return;
                    }
                    clearSelection();
                }
                inputKind = IN_LASSO;
                lassoPts.clear();
                lassoPts.add(new float[]{x, y});
                break;
            }
            case TOOL_SHAPE: {
                inputKind = IN_SHAPE;
                shapeX0 = x;
                shapeY0 = y;
                current = null;
                break;
            }
            case TOOL_TEXT: {
                inputKind = IN_TAP;
                scheduleLongPress(sx, sy);
                break;
            }
            case TOOL_TAPE: {
                inputKind = IN_TAPE;
                tapeX0 = x;
                tapeY0 = y;
                current = null;
                break;
            }
            case TOOL_MEMO: {
                inputKind = IN_MEMO;
                shapeX0 = x;
                shapeY0 = y;
                break;
            }
            default:
                inputKind = IN_NONE;
        }
        invalidate();
    }

    private void moveInput(MotionEvent e, int idx) {
        float sx = e.getX(idx), sy = e.getY(idx);
        if (Math.hypot(sx - downSx, sy - downSy) > 12 * density) movedFar = true;
        switch (inputKind) {
            case IN_STROKE: {
                if (Math.hypot(sx - lpSx, sy - lpSy) > 10 * density) handler.removeCallbacks(longPressRunnable);
                if (snapped && current != null && snapBase != null) {
                    editSnapped(wx(sx), wy(sy));
                    return;
                }
                if (snapped || current == null) return;
                int hs = e.getHistorySize();
                for (int h = 0; h < hs; h++) {
                    addStrokePoint(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h), e.getHistoricalPressure(idx, h));
                }
                addStrokePoint(sx, sy, e.getPressure(idx));
                if (Math.hypot(sx - holdAnchorX, sy - holdAnchorY) > 6 * density) {
                    holdAnchorX = sx;
                    holdAnchorY = sy;
                    scheduleHold();
                }
                invalidate();
                break;
            }
            case IN_ERASE: {
                int hs = e.getHistorySize();
                for (int h = 0; h < hs; h++) eraseTo(wx(e.getHistoricalX(idx, h)), wy(e.getHistoricalY(idx, h)));
                eraseTo(wx(sx), wy(sy));
                eraserSx = sx;
                eraserSy = sy;
                invalidate();
                break;
            }
            case IN_LASSO: {
                lassoPts.add(new float[]{wx(sx), wy(sy)});
                invalidate();
                break;
            }
            case IN_TAP: {
                if (Math.hypot(sx - lpSx, sy - lpSy) > 10 * density) handler.removeCallbacks(longPressRunnable);
                break;
            }
            case IN_MOVE: {
                previewDx = wx(sx) - dragStartX;
                previewDy = wy(sy) - dragStartY;
                previewScale = 1f;
                buildPreview();
                invalidate();
                break;
            }
            case IN_SCALE: {
                float px = selBounds.left, py = selBounds.top;
                float d0 = (float) Math.hypot(dragStartX - px, dragStartY - py);
                float d1 = (float) Math.hypot(wx(sx) - px, wy(sy) - py);
                previewScale = Math.max(0.1f, d0 < 1 ? 1 : d1 / d0);
                previewDx = 0;
                previewDy = 0;
                buildPreview();
                invalidate();
                break;
            }
            case IN_TAPE: {
                float x = wx(sx), y = wy(sy);
                double ang = Math.toDegrees(Math.atan2(y - tapeY0, x - tapeX0));
                if (Math.abs(ang) < 6 || Math.abs(Math.abs(ang) - 180) < 6) y = tapeY0;      // 수평 맞춤
                else if (Math.abs(Math.abs(ang) - 90) < 6) x = tapeX0;                       // 수직 맞춤
                previewElem = new TapeElem(tapeX0, tapeY0, x, y, tapeWidth, tapeColor, tapePattern);
                invalidate();
                break;
            }
            case IN_MEMO: {
                float x = wx(sx), y = wy(sy);
                previewElem = new MemoElem(Math.min(shapeX0, x), Math.min(shapeY0, y), Math.abs(x - shapeX0), Math.abs(y - shapeY0), memoColor, memoPattern);
                invalidate();
                break;
            }
            case IN_SHAPE: {
                current = ShapeRecognizer.build(shapeType, shapeX0, shapeY0, wx(sx), wy(sy),
                        new StrokeElem(penType, penColor, penSquare), penWidth);
                invalidate();
                break;
            }
            default:
                break;
        }
    }

    private void addStrokePoint(float sx, float sy, float pressure) {
        if (Math.hypot(sx - lastSx, sy - lastSy) < 0.8f) return;
        lastSx = sx;
        lastSy = sy;
        float w;
        if (current.pen == StrokeElem.HIGHLIGHTER) {
            w = hlWidth;
        } else {
            float target = widthFor(pressure, drawIsStylus);
            w = lastWidth * 0.55f + target * 0.45f;
            lastWidth = w;
        }
        current.add(wx(sx), wy(sy), w);
        if (current.square) current.invalidatePath();
    }

    private void endInput(MotionEvent e, int idx) {
        handler.removeCallbacks(holdRunnable);
        switch (inputKind) {
            case IN_STROKE: {
                handler.removeCallbacks(longPressRunnable);
                if (current != null && current.n > 0) {
                    if (!snapped && scribbleErase && current.pen != StrokeElem.HIGHLIGHTER && scribbleErase(current)) {
                        current = null;
                        snapBase = null;
                        break;
                    }
                    Element done = snapped ? current : current.smoothResampled(Math.max(1.5f, 3f / scale));
                    joinContainer(done, current.pts[0], current.pts[1]);
                    commitElement(done);
                }
                current = null;
                snapBase = null;
                break;
            }
            case IN_ERASE: {
                if (eraseChanged) pushElems(activeLayer(), eraseBefore);
                eraseBefore = null;
                if (!drawIsStylus || tool != TOOL_ERASER) eraserSx = -1;
                if (eraseChanged) changed();
                break;
            }
            case IN_LASSO: {
                finishLasso();
                lassoPts.clear();
                break;
            }
            case IN_MOVE:
            case IN_SCALE: {
                if (selPreview != null && (previewDx != 0 || previewDy != 0 || previewScale != 1f)) {
                    final float dx = previewDx, dy = previewDy, s = previewScale;
                    final float px = selBounds.left, py = selBounds.top;
                    selPreview = null;
                    replaceSelection(el -> el.transformedKeepWidth(dx, dy, s, px, py));
                }
                selPreview = null;
                previewDx = previewDy = 0;
                previewScale = 1f;
                break;
            }
            case IN_SHAPE: {
                if (current != null) {
                    RectF b = current.bounds();
                    if (b.width() * scale > 6 || b.height() * scale > 6) {
                        joinContainer(current, shapeX0, shapeY0);
                        commitElement(current);
                    }
                }
                current = null;
                break;
            }
            case IN_TAP: {
                handler.removeCallbacks(longPressRunnable);
                if (!movedFar) textTap(wx(e.getX(idx)), wy(e.getY(idx)));
                break;
            }
            case IN_TAPE_TAP: {
                if (!movedFar && tapTarget != null) toggleTape(tapTarget);
                tapTarget = null;
                break;
            }
            case IN_TAPE: {
                if (previewElem instanceof TapeElem) {
                    TapeElem tp = (TapeElem) previewElem;
                    if (Math.hypot(tp.x1 - tp.x0, tp.y1 - tp.y0) * scale > 16 * density) {
                        commitContainer(tp);
                        if (tapeAutoAttach) attachTape(tp);
                    }
                }
                previewElem = null;
                break;
            }
            case IN_MEMO: {
                MemoElem m = previewElem instanceof MemoElem ? (MemoElem) previewElem : null;
                if (m == null || m.w * scale < 30 * density || m.h * scale < 30 * density) {
                    m = new MemoElem(shapeX0, shapeY0, 360, 320, memoColor, memoPattern); // 탭하면 기본 크기
                }
                m.group = newGroup();
                commitContainer(m);
                previewElem = null;
                break;
            }
            default:
                break;
        }
        inputKind = IN_NONE;
        invalidate();
    }

    private void cancelInput() {
        previewElem = null;
        tapTarget = null;
        handler.removeCallbacks(holdRunnable);
        handler.removeCallbacks(longPressRunnable);
        snapBase = null;
        if (inputKind == IN_ERASE && eraseChanged && eraseBefore != null) {
            activeLayer().elems = eraseBefore;
            invalidateScene();
        }
        current = null;
        selPreview = null;
        lassoPts.clear();
        inputKind = IN_NONE;
        invalidate();
    }

    private void commitElement(Element el) {
        Layer l = activeLayer();
        ArrayList<Element> before = new ArrayList<>(l.elems);
        l.elems.add(el);
        pushElems(l, before);
        boolean top = true;
        Page p = page();
        for (int i = activeLayer + 1; i < p.layers.size(); i++) {
            if (p.layers.get(i).visible && !p.layers.get(i).elems.isEmpty()) top = false;
        }
        if (top && l.alpha == 255 && l.visible && !sceneDirty && scene != null
                && sceneScale == scale && sceneTx == tx && sceneTy == ty) {
            Canvas c = new Canvas(scene);
            c.translate(tx, ty);
            c.scale(scale, scale);
            c.translate(ox(), oy());
            if (!p.isInfinite()) c.clipRect(0, 0, p.w, p.h);
            el.draw(c, rc);
        } else {
            sceneDirty = true;
        }
        invalidate();
        if (listener != null) {
            listener.onHistoryChanged();
            listener.onDocChanged();
        }
    }

    private void scheduleHold() {
        handler.removeCallbacks(holdRunnable);
        if (holdToShape) handler.postDelayed(holdRunnable, 650);
    }

    private void onHold() {
        if (inputKind != IN_STROKE || current == null || snapped) return;
        float penX = current.lastX(), penY = current.lastY();
        float firstX = current.pts[0], firstY = current.pts[1];
        StrokeElem shape = ShapeRecognizer.recognize(current);
        int type = shape != null ? ShapeRecognizer.lastType : -1;
        if (shape == null && current.n >= 8 && current.length() * scale > 60 * density) {
            shape = current.smoothedCurve(); // 곡선 보정
            type = -1;
        }
        if (shape != null) {
            // 계속 누른 채 움직이면 크기·길이·방향을 바꿀 수 있도록 기준 저장
            snapBase = shape;
            snapType = type;
            snapPx = penX;
            snapPy = penY;
            if (type == ShapeRecognizer.LINE || type == -1) {
                snapAx = firstX;
                snapAy = firstY;
            } else {
                RectF b = shape.bounds();
                snapAx = Math.abs(penX - b.left) > Math.abs(penX - b.right) ? b.left : b.right;
                snapAy = Math.abs(penY - b.top) > Math.abs(penY - b.bottom) ? b.top : b.bottom;
            }
            current = shape;
            snapped = true;
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            invalidate();
        }
    }

    /** 보정된 도형을 펜 위치에 맞춰 늘이기 / 돌리기 */
    private void editSnapped(float x, float y) {
        if (snapType == ShapeRecognizer.LINE || snapType == ShapeRecognizer.ARROW || snapType == -1) {
            // 기준점(시작점) 기준으로 회전 + 길이 조절
            double a0 = Math.atan2(snapPy - snapAy, snapPx - snapAx), a1 = Math.atan2(y - snapAy, x - snapAx);
            float d0 = (float) Math.hypot(snapPx - snapAx, snapPy - snapAy), d1 = (float) Math.hypot(x - snapAx, y - snapAy);
            if (d0 < 1) return;
            float k = d1 / d0;
            double rot = a1 - a0;
            float cos = (float) Math.cos(rot), sin = (float) Math.sin(rot);
            StrokeElem out = snapBase.blank();
            for (int i = 0; i < snapBase.n; i++) {
                float px = snapBase.pts[3 * i] - snapAx, py = snapBase.pts[3 * i + 1] - snapAy;
                out.add(snapAx + (px * cos - py * sin) * k, snapAy + (px * sin + py * cos) * k, snapBase.pts[3 * i + 2]);
            }
            current = out;
        } else {
            float dx0 = snapPx - snapAx, dy0 = snapPy - snapAy;
            float sx = Math.abs(dx0) < 2 ? 1 : (x - snapAx) / dx0;
            float sy = Math.abs(dy0) < 2 ? 1 : (y - snapAy) / dy0;
            current = snapBase.stretched(snapAx, snapAy, sx, sy);
        }
        invalidate();
    }

    // ---------- 꾹 누르기 선택 ----------

    private void scheduleLongPress(float sx, float sy) {
        lpSx = sx;
        lpSy = sy;
        handler.removeCallbacks(longPressRunnable);
        handler.postDelayed(longPressRunnable, 500);
    }

    /** 텍스트·이미지 (그리고 고정된 요소) 위를 꾹 누르면 선택 */
    private void onLongPress() {
        if (doc == null || movedFar) return;
        if (inputKind == IN_STROKE && current != null && current.length() * scale > 12 * density) return;
        switchToPageAt(lpSx, lpSy);
        float x = wx(lpSx), y = wy(lpSy);
        Page p = page();
        for (int li = p.layers.size() - 1; li >= 0; li--) {
            Layer l = p.layers.get(li);
            if (!l.visible) continue;
            for (int i = l.elems.size() - 1; i >= 0; i--) {
                Element e = l.elems.get(i);
                boolean target = e instanceof TextElem || e instanceof ImageElem || e instanceof MemoElem
                        || e instanceof TapeElem || e.locked;
                if (!target || !e.hit(x, y, 6 / scale)) continue;
                handler.removeCallbacks(holdRunnable);
                current = null;
                snapBase = null;
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                if (e.locked) {
                    inputKind = IN_NONE;
                    mode = M_IGNORE;
                    if (listener != null) listener.onLockedPressed(e);
                    invalidate();
                    return;
                }
                activeLayer = li;
                select(java.util.Collections.singletonList(e));
                if (listener != null) listener.onLongPressSelected();
                // 손을 떼지 않고 바로 끌어서 옮길 수 있게
                inputKind = IN_MOVE;
                dragStartX = x;
                dragStartY = y;
                if (mode == M_GESTURE) {
                    gestureActive = false;
                    pinching = false;
                    mode = M_DRAW;
                    drawPointerId = firstPointerId;
                    drawIsStylus = false;
                }
                invalidate();
                return;
            }
        }
    }

    private TapeElem tapeAt(float x, float y) {
        Page p = page();
        for (int li = p.layers.size() - 1; li >= 0; li--) {
            Layer l = p.layers.get(li);
            if (!l.visible) continue;
            for (int i = l.elems.size() - 1; i >= 0; i--) {
                Element e = l.elems.get(i);
                if (e instanceof TapeElem && e.hit(x, y, 2 / scale)) return (TapeElem) e;
            }
        }
        return null;
    }

    private void toggleTape(TapeElem t) {
        t.revealed = !t.revealed;
        invalidateScene();
    }

    /** 페이지의 모든 테이프 한꺼번에 가리기/보이기 */
    public void setAllTapes(boolean revealed) {
        for (Layer l : page().layers) for (Element e : l.elems) if (e instanceof TapeElem) ((TapeElem) e).revealed = revealed;
        invalidateScene();
    }

    private void commitContainer(Element el) {
        Layer l = activeLayer();
        ArrayList<Element> before = new ArrayList<>(l.elems);
        l.elems.add(el);
        pushElems(l, before);
        changed();
    }

    // ---------- 낙서해서 지우기 ----------

    /** 지그재그로 마구 그은 획이면 그 아래 획들을 지우고 true */
    private boolean scribbleErase(StrokeElem s) {
        if (s.n < 12) return false;
        RectF b = s.bounds();
        float diag = (float) Math.hypot(b.width(), b.height());
        if (diag * scale < 20 * density) return false;
        float len = s.length();
        if (len < diag * 3.2f) return false;
        // 진행 방향이 크게 꺾이는(되돌아가는) 횟수
        int reversals = 0;
        float pdx = 0, pdy = 0;
        int step = Math.max(1, s.n / 60);
        for (int i = step; i < s.n; i += step) {
            float dx = s.pts[3 * i] - s.pts[3 * (i - step)], dy = s.pts[3 * i + 1] - s.pts[3 * (i - step) + 1];
            if (Math.hypot(dx, dy) < 1.5f) continue;
            if (pdx * dx + pdy * dy < -0.3f * Math.hypot(pdx, pdy) * Math.hypot(dx, dy)) reversals++;
            pdx = dx;
            pdy = dy;
        }
        if (reversals < 5) return false;
        Layer l = activeLayer();
        ArrayList<Element> keep = new ArrayList<>();
        int removed = 0;
        for (Element e : l.elems) {
            boolean kill = false;
            if (!e.locked && e instanceof StrokeElem && RectF.intersects(b, e.bounds())) {
                StrokeElem t = (StrokeElem) e;
                int hit = 0, tot = 0;
                int st = Math.max(1, t.n / 30);
                for (int i = 0; i < t.n; i += st) {
                    tot++;
                    if (s.hit(t.pts[3 * i], t.pts[3 * i + 1], s.avgWidth() * 1.5f + 2)) hit++;
                }
                kill = tot > 0 && hit >= Math.max(1, tot * 0.35f);
            }
            if (kill) removed++;
            else keep.add(e);
        }
        if (removed == 0) return false;
        ArrayList<Element> before = new ArrayList<>(l.elems);
        l.elems = keep;
        pushElems(l, before);
        changed();
        return true;
    }

    // ---------- 스포이드 ----------

    private void pickColor(float sx, float sy) {
        eyedropper = false;
        if (scene == null) return;
        if (sceneDirty) renderScene();
        int x = Math.max(0, Math.min(scene.getWidth() - 1, Math.round(sx)));
        int y = Math.max(0, Math.min(scene.getHeight() - 1, Math.round(sy)));
        int c = scene.getPixel(x, y) | 0xFF000000;
        if (listener != null) listener.onColorPicked(c);
    }

    // ---------- 지우개 ----------

    private void eraseTo(float x, float y) {
        float r = eraserSize / 2f / scale;
        float dx = x - lastEraseX, dy = y - lastEraseY;
        float d = (float) Math.hypot(dx, dy);
        int steps = (int) Math.ceil(d / Math.max(r * 0.5f, 0.5f));
        for (int i = 1; i <= steps; i++) {
            eraseAt(lastEraseX + dx * i / steps, lastEraseY + dy * i / steps);
        }
        lastEraseX = x;
        lastEraseY = y;
    }

    private void eraseAt(float x, float y) {
        float r = eraserSize / 2f / scale;
        Layer l = activeLayer();
        ArrayList<Element> out = null;
        for (int i = 0; i < l.elems.size(); i++) {
            Element e = l.elems.get(i);
            boolean hit;
            ArrayList<StrokeElem> pieces = null;
            if (e.locked || (eraserHlOnly && !(e instanceof StrokeElem && ((StrokeElem) e).isHighlighter()))) {
                hit = false;
            } else if (e instanceof StrokeElem) {
                if (eraserPartial) {
                    pieces = ((StrokeElem) e).eraseSplit(x, y, r);
                    hit = pieces != null;
                } else {
                    hit = e.hit(x, y, r);
                }
            } else {
                hit = !eraserPartial && e.hit(x, y, 0);
            }
            if (hit) {
                if (out == null) {
                    out = new ArrayList<>(l.elems.size());
                    for (int j = 0; j < i; j++) out.add(l.elems.get(j));
                }
                if (pieces != null) {
                    for (StrokeElem pc : pieces) pc.group = e.group;
                    out.addAll(pieces);
                }
            } else if (out != null) {
                out.add(e);
            }
        }
        if (out != null) {
            l.elems = out;
            eraseChanged = true;
            sceneDirty = true;
            dropPageCache(page());
        }
    }

    // ---------- 올가미 ----------

    private boolean passFilter(Element e) {
        if (e.locked) return false;
        switch (lassoFilter) {
            case 1: return e instanceof StrokeElem && !((StrokeElem) e).isHighlighter();
            case 2: return e instanceof StrokeElem && ((StrokeElem) e).isHighlighter();
            case 3: return e instanceof ImageElem;
            case 4: return e instanceof TextElem;
            case 5: return e instanceof TapeElem;
            case 6: return e instanceof MemoElem;
            default: return true;
        }
    }

    private void finishLasso() {
        float[] xs, ys;
        if (lassoRect) {
            if (lassoPts.size() < 2) return;
            float[] a = lassoPts.get(0), b = lassoPts.get(lassoPts.size() - 1);
            float l = Math.min(a[0], b[0]), r = Math.max(a[0], b[0]), t = Math.min(a[1], b[1]), bt = Math.max(a[1], b[1]);
            if ((r - l) * scale < 8 || (bt - t) * scale < 8) return;
            xs = new float[]{l, r, r, l};
            ys = new float[]{t, t, bt, bt};
        } else {
            if (lassoPts.size() < 3) return;
            int m = lassoPts.size();
            xs = new float[m];
            ys = new float[m];
            for (int i = 0; i < m; i++) {
                xs[i] = lassoPts.get(i)[0];
                ys[i] = lassoPts.get(i)[1];
            }
        }
        ArrayList<Element> sel = new ArrayList<>();
        for (Element e : activeLayer().elems) {
            if (!passFilter(e)) continue;
            if (e instanceof StrokeElem) {
                StrokeElem s = (StrokeElem) e;
                int inside = 0;
                for (int i = 0; i < s.n; i++) if (pip(s.pts[3 * i], s.pts[3 * i + 1], xs, ys)) inside++;
                if (s.n > 0 && inside >= Math.max(1, s.n * 0.6f)) sel.add(e);
            } else {
                if (pip(e.centerX(), e.centerY(), xs, ys)) sel.add(e);
            }
        }
        if (!sel.isEmpty()) select(sel);
    }

    private static boolean pip(float x, float y, float[] xs, float[] ys) {
        boolean in = false;
        for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
            if (((ys[i] > y) != (ys[j] > y)) && (x < (xs[j] - xs[i]) * (y - ys[i]) / (ys[j] - ys[i]) + xs[i])) {
                in = !in;
            }
        }
        return in;
    }

    private void buildPreview() {
        ArrayList<Element> list = new ArrayList<>();
        float px = selBounds.left, py = selBounds.top;
        for (Element e : selection) list.add(e.transformedKeepWidth(previewDx, previewDy, previewScale, px, py));
        selPreview = list;
    }

    // ---------- 텍스트 ----------

    private void textTap(float x, float y) {
        TextElem hitText = null;
        Layer l = activeLayer();
        for (int i = l.elems.size() - 1; i >= 0; i--) {
            Element e = l.elems.get(i);
            if (e instanceof TextElem && !e.locked && e.hit(x, y, 4)) {
                hitText = (TextElem) e;
                break;
            }
        }
        if (listener != null) listener.onTextRequest(x, y, hitText);
    }

    // ---------- 제스처 (스크롤/확대) ----------

    private void startGesture(MotionEvent e, int skip) {
        float cx = 0, cy = 0;
        int cnt = 0;
        float[] px = new float[2], py = new float[2];
        for (int i = 0; i < e.getPointerCount() && cnt < 2; i++) {
            if (i == skip) continue;
            px[cnt] = e.getX(i);
            py[cnt] = e.getY(i);
            cx += px[cnt];
            cy += py[cnt];
            cnt++;
        }
        if (cnt == 0) return;
        cx /= cnt;
        cy /= cnt;
        gLastCx = cx;
        gLastCy = cy;
        gLastDist = cnt >= 2 ? (float) Math.hypot(px[0] - px[1], py[0] - py[1]) : 0;
        if (!gestureActive) {
            gestureActive = true;
            gMaxPointers = cnt;
            pinchedThisGesture = false;
            axisLock = 0;
        } else {
            gMaxPointers = Math.max(gMaxPointers, cnt);
        }
        pinching = cnt >= 2;
    }

    private void moveGesture(MotionEvent e) {
        int cnt = Math.min(2, e.getPointerCount());
        float cx = 0, cy = 0;
        for (int i = 0; i < cnt; i++) {
            cx += e.getX(i);
            cy += e.getY(i);
        }
        cx /= cnt;
        cy /= cnt;
        float dx = cx - gLastCx, dy = cy - gLastCy;
        if (Math.hypot(cx - downSx, cy - downSy) > 12 * density) movedFar = true;
        if (cnt < 2 && !isInfinite()) {
            // 세로 스크롤 중에는 좌우로 흔들리지 않게 방향 고정
            if (axisLock == 0 && movedFar) {
                float tdx = Math.abs(cx - downSx), tdy = Math.abs(cy - downSy);
                axisLock = tdy > tdx * 1.2f ? 1 : tdx > tdy * 1.2f ? 2 : 3;
            }
            if (axisLock == 0 || axisLock == 1) dx = 0;
            else if (axisLock == 2) dy = 0;
            if (maxPageW * scale <= getWidth()) dx = 0; // 페이지가 화면 폭 안이면 좌우 이동 없음
        }
        tx += dx;
        ty += dy;
        if (cnt >= 2) {
            float dist = (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
            if (gLastDist > 0 && dist > 0) {
                float ns = clampScale(scale * dist / gLastDist);
                float f = ns / scale;
                tx = cx - (cx - tx) * f;
                ty = cy - (cy - ty) * f;
                scale = ns;
            }
            gLastDist = dist;
            pinching = true;
            pinchedThisGesture = true;
        } else {
            pinching = false;
            softClamp();
        }
        gLastCx = cx;
        gLastCy = cy;
        sceneDirty = true;
        updatePageFromViewport();
        invalidate();
        if (listener != null) listener.onZoomChanged(scale);
    }

    private float clampScale(float s) {
        if (isInfinite()) return Math.max(0.08f, Math.min(8f, s));
        float fit = baseScale();
        return Math.max(fit * 0.4f, Math.min(fit * 8f, s));
    }

    private void endGesture(boolean allowFling) {
        gestureActive = false;
        pinching = false;
        boolean flung = false;
        if (allowFling && !pinchedThisGesture && velocity != null) {
            velocity.computeCurrentVelocity(1000, maxFling);
            float vx = velocity.getXVelocity(), vy = velocity.getYVelocity();
            if (!isInfinite()) {
                if (axisLock == 1 || axisLock == 0 || maxPageW * scale <= getWidth()) vx = 0;
                else if (axisLock == 2) vy = 0;
            }
            if (Math.hypot(vx, vy) > minFling * 2) {
                int[] b = bounds();
                scroller.fling(Math.round(tx), Math.round(ty), Math.round(vx), Math.round(vy), b[0], b[1], b[2], b[3]);
                flung = true;
                postInvalidateOnAnimation();
            }
        }
        if (!flung) clampTransform();
        sceneDirty = true;
        updatePageFromViewport();
        invalidate();
    }

    /** {minX, maxX, minY, maxY} 이동 가능 범위 */
    private int[] bounds() {
        if (isInfinite()) return new int[]{-100000000, 100000000, -100000000, 100000000};
        float m = 24 * density;
        float pw = maxPageW * scale, ph = totalH * scale;
        int minX, maxX, minY, maxY;
        if (pw <= getWidth()) {
            minX = maxX = Math.round((getWidth() - pw) / 2f);
        } else {
            minX = Math.round(getWidth() - pw - m);
            maxX = Math.round(m);
        }
        if (ph + 2 * m <= getHeight()) {
            minY = maxY = Math.round(m);
        } else {
            minY = Math.round(getHeight() - ph - m);
            maxY = Math.round(m);
        }
        return new int[]{minX, maxX, minY, maxY};
    }

    /** 끌기 중에는 범위 밖으로 조금만 넘어가게 */
    private void softClamp() {
        if (isInfinite()) return;
        int[] b = bounds();
        float over = 80 * density;
        tx = Math.max(b[0] - over, Math.min(b[1] + over, tx));
        ty = Math.max(b[2] - over, Math.min(b[3] + over, ty));
    }

    private void clampTransform() {
        if (isInfinite()) return;
        int[] b = bounds();
        tx = Math.max(b[0], Math.min(b[1], tx));
        ty = Math.max(b[2], Math.min(b[3], ty));
    }

    public RectF contentBounds() {
        return Renderer.contentBounds(page());
    }
}
