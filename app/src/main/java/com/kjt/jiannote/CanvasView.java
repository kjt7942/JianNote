package com.kjt.jiannote;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import com.kjt.jiannote.model.Document;
import com.kjt.jiannote.model.Element;
import com.kjt.jiannote.model.ImageElem;
import com.kjt.jiannote.model.Layer;
import com.kjt.jiannote.model.Page;
import com.kjt.jiannote.model.StrokeElem;
import com.kjt.jiannote.model.TextElem;
import com.kjt.jiannote.render.RenderContext;
import com.kjt.jiannote.render.Renderer;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * 필기 캔버스.
 * - S펜(스타일러스): 필기 / 측면 버튼 누르고 쓰면 지우개
 * - 손가락: 이동·확대(두 손가락), 손가락 필기 옵션
 * - 펜이 화면 근처(호버)에 있을 때 손바닥 터치 무시
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
    }

    public static final int TOOL_PEN = 0, TOOL_HIGHLIGHTER = 1, TOOL_ERASER = 2, TOOL_LASSO = 3,
            TOOL_SHAPE = 4, TOOL_TEXT = 5, TOOL_HAND = 6;

    private static final int M_NONE = 0, M_DRAW = 1, M_GESTURE = 2, M_IGNORE = 3, M_PALM = 4;
    private static final int IN_NONE = 0, IN_STROKE = 1, IN_ERASE = 2, IN_LASSO = 3, IN_MOVE = 4,
            IN_SCALE = 5, IN_SHAPE = 6, IN_TAP = 7;

    // ---- 문서 상태
    private Document doc;
    private RenderContext rc;
    private int paperColor = Color.WHITE;
    private int pageIndex = 0;
    private int activeLayer = 0;
    private Listener listener;
    public final UndoManager undo = new UndoManager();

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
    public float textSize = 40f;
    public boolean fingerDraw = false;
    public boolean pressureEnabled = true;
    public float pressureGamma = 1.0f;
    public boolean holdToShape = true;

    // ---- 변환 (screen = world * scale + t)
    private float scale = 1f, tx = 0f, ty = 0f;

    // ---- 장면 캐시
    private Bitmap scene;
    private boolean sceneDirty = true;
    private float sceneScale = 1f, sceneTx = 0f, sceneTy = 0f;
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

    // 제스처
    private boolean gestureActive;
    private float gLastCx, gLastCy, gLastDist;
    private int gMaxPointers;
    private float gTotalDx, gTotalDy;
    private float gStartScale;
    private long gStartTime;

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
        deskPaint.setColor(0xFFE9E6DF);
        shadowPaint.setColor(0x22000000);
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
        post(this::fitPage);
    }

    public Document doc() { return doc; }

    public int pageIndex() { return pageIndex; }

    public int pageCount() { return doc == null ? 0 : doc.pages.size(); }

    public Page page() { return doc.pages.get(pageIndex); }

    public int activeLayerIndex() { return activeLayer; }

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

    public void setPaperColor(int c) {
        paperColor = c;
        invalidateScene();
    }

    public void goToPage(int i) {
        if (doc == null) return;
        i = Math.max(0, Math.min(i, doc.pages.size() - 1));
        clearSelection();
        pageIndex = i;
        activeLayer = page().layers.size() - 1;
        fitPage();
        if (listener != null) listener.onPageChanged();
    }

    public void invalidateScene() {
        sceneDirty = true;
        invalidate();
    }

    public void fitPage() {
        if (doc == null || getWidth() == 0) return;
        Page p = page();
        if (p.isInfinite()) {
            if (scale <= 0) scale = 1;
            if (tx == 0 && ty == 0) {
                scale = 1;
                tx = 40 * density;
                ty = 40 * density;
            }
        } else {
            float margin = 16 * density;
            scale = (getWidth() - 2 * margin) / p.w;
            tx = (getWidth() - p.w * scale) / 2f;
            ty = margin;
            if (p.h * scale < getHeight()) ty = Math.max(margin, (getHeight() - p.h * scale) / 2f);
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

    private float fitScale() {
        Page p = page();
        if (p.isInfinite()) return 1f;
        return (getWidth() - 32 * density) / p.w;
    }

    public void undo() {
        clearSelection();
        UndoManager.Cmd c = undo.undo();
        afterHistory(c);
    }

    public void redo() {
        clearSelection();
        UndoManager.Cmd c = undo.redo();
        afterHistory(c);
    }

    private void afterHistory(UndoManager.Cmd c) {
        if (c != null && c.page() >= 0 && c.page() != pageIndex && c.page() < doc.pages.size()) {
            pageIndex = c.page();
            fitPage();
            if (listener != null) listener.onPageChanged();
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
        undo.clear();
        goToPage(at);
        changed();
    }

    public void deletePage(int i) {
        if (doc.pages.size() <= 1) return;
        doc.pages.remove(i);
        undo.clear();
        goToPage(Math.min(i, doc.pages.size() - 1));
        changed();
    }

    public void movePage(int from, int to) {
        if (to < 0 || to >= doc.pages.size()) return;
        Page p = doc.pages.remove(from);
        doc.pages.add(to, p);
        undo.clear();
        goToPage(to);
        changed();
    }

    // ---- 텍스트/이미지
    public void commitText(float x, float y, TextElem existing, String text) {
        Layer l = activeLayer();
        ArrayList<Element> before = new ArrayList<>(l.elems);
        int idx = existing == null ? -1 : l.elems.indexOf(existing);
        if (text == null || text.trim().isEmpty()) {
            if (idx >= 0) l.elems.remove(idx);
            else return;
        } else if (idx >= 0) {
            l.elems.set(idx, new TextElem(existing.x, existing.y, text, existing.size, existing.color, existing.maxWidth));
        } else {
            l.elems.add(new TextElem(x, y, text, textSize, penColor, 0));
        }
        pushElems(l, before);
        changed();
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
    }

    public void duplicateSelection() {
        if (selection.isEmpty()) return;
        Layer l = activeLayer();
        ArrayList<Element> before = new ArrayList<>(l.elems);
        ArrayList<Element> copies = new ArrayList<>();
        for (Element e : selection) copies.add(e.transformed(30, 30, 1, 0, 0));
        l.elems.addAll(copies);
        pushElems(l, before);
        changed();
        select(copies);
    }

    public void recolorSelection(int color) {
        if (selection.isEmpty()) return;
        replaceSelection(e -> e.withColor(color));
    }

    /** 선택 영역을 다른 레이어로 이동 */
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
            if (idx >= 0) l.elems.set(idx, ne);
            newSel.add(ne);
        }
        pushElems(l, before);
        changed();
        select(newSel);
    }

    // ================= 좌표 =================

    private float wx(float sx) { return (sx - tx) / scale; }

    private float wy(float sy) { return (sy - ty) / scale; }

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
        if (doc != null) {
            if (ow == 0 || !page().isInfinite()) fitPage();
        }
        sceneDirty = true;
    }

    private void renderScene() {
        if (scene == null || doc == null) return;
        Canvas c = new Canvas(scene);
        c.drawRect(0, 0, scene.getWidth(), scene.getHeight(), deskPaint);
        Page p = page();
        if (!p.isInfinite()) {
            float sh = 4 * density;
            c.drawRect(tx + sh, ty + sh, tx + p.w * scale + sh, ty + p.h * scale + sh, shadowPaint);
        }
        c.save();
        c.translate(tx, ty);
        c.scale(scale, scale);
        Renderer.drawPage(c, p, paperColor, rc, visibleWorld(), scale, excluded);
        c.restore();
        sceneScale = scale;
        sceneTx = tx;
        sceneTy = ty;
        sceneDirty = false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (doc == null || scene == null) return;
        if (sceneDirty && !gestureActive) renderScene();
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

        canvas.save();
        canvas.translate(tx, ty);
        canvas.scale(scale, scale);
        Page p = page();
        if (!p.isInfinite()) canvas.clipRect(0, 0, p.w, p.h);
        if (current != null) current.draw(canvas, rc);
        // 선택된 요소 (이동/확대 미리보기 포함)
        if (!selection.isEmpty()) {
            ArrayList<Element> list = selPreview != null ? selPreview : selection;
            for (Element e : list) e.draw(canvas, rc);
        }
        canvas.restore();

        // 올가미
        if (inputKind == IN_LASSO && !lassoPts.isEmpty()) {
            lassoPath.reset();
            for (int i = 0; i < lassoPts.size(); i++) {
                float[] q = lassoPts.get(i);
                float sx = q[0] * scale + tx, sy = q[1] * scale + ty;
                if (i == 0) lassoPath.moveTo(sx, sy);
                else lassoPath.lineTo(sx, sy);
            }
            canvas.drawPath(lassoPath, lassoPaint);
        }
        // 선택 상자
        if (selBounds != null) {
            RectF b = screenSelBounds();
            canvas.drawRect(b, selPaint);
            canvas.drawCircle(b.right, b.bottom, 9 * density, handlePaint);
        }
        // 지우개 커서
        if ((tool == TOOL_ERASER || inputKind == IN_ERASE) && eraserSx >= 0) {
            canvas.drawCircle(eraserSx, eraserSy, eraserSize / 2f, cursorPaint);
        }
    }

    private RectF screenSelBounds() {
        RectF b = new RectF(selBounds);
        if (selPreview != null) {
            b.left = (b.left - selBounds.left) * previewScale + selBounds.left + previewDx;
            b.top = (b.top - selBounds.top) * previewScale + selBounds.top + previewDy;
            b.right = (selBounds.right - selBounds.left) * previewScale + selBounds.left + previewDx;
            b.bottom = (selBounds.bottom - selBounds.top) * previewScale + selBounds.top + previewDy;
        }
        RectF s = new RectF(b.left * scale + tx, b.top * scale + ty, b.right * scale + tx, b.bottom * scale + ty);
        s.inset(-6 * density, -6 * density);
        return s;
    }

    // ================= 입력 =================

    @Override
    public boolean onHoverEvent(MotionEvent e) {
        int tt = e.getToolType(0);
        if (tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER) {
            lastHoverTime = SystemClock.uptimeMillis();
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
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                requestUnbufferedDispatch(e);
                boolean stylus = isStylus(e, 0);
                downSx = e.getX();
                downSy = e.getY();
                downTime = e.getEventTime();
                movedFar = false;
                if (tool == TOOL_HAND) {
                    mode = M_GESTURE;
                    startGesture(e, -1);
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
                }
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                int ai = e.getActionIndex();
                if (isStylus(e, ai) && mode != M_DRAW && tool != TOOL_HAND) {
                    // 손바닥이 먼저 닿은 뒤 펜이 들어온 경우
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
                if (mode == M_DRAW) {
                    int idx = e.findPointerIndex(drawPointerId);
                    endInput(e, Math.max(idx, 0));
                } else if (mode == M_GESTURE) {
                    boolean tap = !movedFar && e.getEventTime() - downTime < 300 && gMaxPointers == 1;
                    endGesture(true);
                    if (tap) fingerTap(e.getX(), e.getY());
                }
                mode = M_NONE;
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
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

    /** 손가락 탭: 텍스트 도구면 텍스트 추가, 선택 중이면 해제 */
    private void fingerTap(float sx, float sy) {
        if (tool == TOOL_TEXT) {
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
        switch (penType) {
            case StrokeElem.FOUNTAIN: return penWidth * (0.15f + 1.35f * f);
            case StrokeElem.BRUSH: return penWidth * (0.05f + 2.4f * f);
            default: return penWidth * (0.55f + 0.7f * f);
        }
    }

    private void beginInput(MotionEvent e, int idx, boolean erase) {
        float sx = e.getX(idx), sy = e.getY(idx);
        float x = wx(sx), y = wy(sy);
        lastSx = sx;
        lastSy = sy;
        int t = erase ? TOOL_ERASER : tool;
        if (t != TOOL_LASSO && t != TOOL_TEXT && t != TOOL_ERASER) clearSelection();
        switch (t) {
            case TOOL_PEN: {
                inputKind = IN_STROKE;
                snapped = false;
                current = new StrokeElem(penType, penColor);
                lastWidth = widthFor(e.getPressure(idx), drawIsStylus);
                current.add(x, y, lastWidth);
                holdAnchorX = sx;
                holdAnchorY = sy;
                scheduleHold();
                break;
            }
            case TOOL_HIGHLIGHTER: {
                inputKind = IN_STROKE;
                snapped = false;
                current = new StrokeElem(StrokeElem.HIGHLIGHTER, hlColor);
                current.add(x, y, hlWidth);
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
            case IN_SHAPE: {
                float x = wx(sx), y = wy(sy);
                current = ShapeRecognizer.build(shapeType, shapeX0, shapeY0, x, y, penColor, penWidth);
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
            w = lastWidth * 0.55f + target * 0.45f; // 필압 떨림 완화
            lastWidth = w;
        }
        current.add(wx(sx), wy(sy), w);
    }

    private void endInput(MotionEvent e, int idx) {
        handler.removeCallbacks(holdRunnable);
        switch (inputKind) {
            case IN_STROKE: {
                if (current != null && current.n > 0) {
                    commitElement(current);
                }
                current = null;
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
                    replaceSelection(el -> el.transformed(dx, dy, s, px, py));
                } else if (!movedFar && inputKind == IN_MOVE) {
                    // 선택 영역 탭: 그대로 유지
                }
                selPreview = null;
                previewDx = previewDy = 0;
                previewScale = 1f;
                break;
            }
            case IN_SHAPE: {
                if (current != null) {
                    RectF b = current.bounds();
                    if (b.width() * scale > 6 || b.height() * scale > 6) commitElement(current);
                }
                current = null;
                break;
            }
            case IN_TAP: {
                if (!movedFar) textTap(wx(e.getX(idx)), wy(e.getY(idx)));
                break;
            }
            default:
                break;
        }
        inputKind = IN_NONE;
        invalidate();
    }

    private void cancelInput() {
        handler.removeCallbacks(holdRunnable);
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
        // 빠른 경로: 맨 위 레이어면 장면 비트맵에 바로 그리기
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
        StrokeElem shape = ShapeRecognizer.recognize(current);
        if (shape != null) {
            if (current.pen == StrokeElem.HIGHLIGHTER) {
                StrokeElem h = new StrokeElem(StrokeElem.HIGHLIGHTER, current.color);
                for (int i = 0; i < shape.n; i++) h.add(shape.pts[3 * i], shape.pts[3 * i + 1], hlWidth);
                shape = h;
            }
            current = shape;
            snapped = true;
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            invalidate();
        }
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
            if (e instanceof StrokeElem) {
                if (eraserPartial) {
                    pieces = ((StrokeElem) e).eraseSplit(x, y, r);
                    hit = pieces != null;
                } else {
                    hit = e.hit(x, y, r);
                }
            } else {
                // 텍스트/이미지는 획 지우개 모드에서만 지운다
                hit = !eraserPartial && e.hit(x, y, 0);
            }
            if (hit) {
                if (out == null) {
                    out = new ArrayList<>(l.elems.size());
                    for (int j = 0; j < i; j++) out.add(l.elems.get(j));
                }
                if (pieces != null) out.addAll(pieces);
            } else if (out != null) {
                out.add(e);
            }
        }
        if (out != null) {
            l.elems = out;
            eraseChanged = true;
            sceneDirty = true;
        }
    }

    // ---------- 올가미 ----------

    private void finishLasso() {
        if (lassoPts.size() < 3) return;
        int m = lassoPts.size();
        float[] xs = new float[m], ys = new float[m];
        for (int i = 0; i < m; i++) {
            xs[i] = lassoPts.get(i)[0];
            ys[i] = lassoPts.get(i)[1];
        }
        ArrayList<Element> sel = new ArrayList<>();
        for (Element e : activeLayer().elems) {
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
        for (Element e : selection) list.add(e.transformed(previewDx, previewDy, previewScale, px, py));
        selPreview = list;
    }

    // ---------- 텍스트 ----------

    private void textTap(float x, float y) {
        TextElem hitText = null;
        Layer l = activeLayer();
        for (int i = l.elems.size() - 1; i >= 0; i--) {
            Element e = l.elems.get(i);
            if (e instanceof TextElem && e.hit(x, y, 4)) {
                hitText = (TextElem) e;
                break;
            }
        }
        if (listener != null) listener.onTextRequest(x, y, hitText);
    }

    // ---------- 제스처 (이동/확대) ----------

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
            gTotalDx = 0;
            gTotalDy = 0;
            gStartScale = scale;
            gStartTime = e.getEventTime();
        } else {
            gMaxPointers = Math.max(gMaxPointers, cnt);
        }
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
        tx += dx;
        ty += dy;
        gTotalDx += dx;
        gTotalDy += dy;
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
        }
        gLastCx = cx;
        gLastCy = cy;
        invalidate();
        if (listener != null) listener.onZoomChanged(scale);
    }

    private float clampScale(float s) {
        Page p = page();
        if (p.isInfinite()) return Math.max(0.08f, Math.min(8f, s));
        float fit = fitScale();
        return Math.max(fit * 0.4f, Math.min(fit * 8f, s));
    }

    private void endGesture(boolean allowFlip) {
        gestureActive = false;
        Page p = page();
        if (allowFlip && !p.isInfinite() && gMaxPointers == 1) {
            float fit = fitScale();
            boolean atFit = scale <= fit * 1.05f;
            boolean fast = gestureDuration() < 500;
            if (atFit && Math.abs(gTotalDx) > getWidth() * 0.18f && Math.abs(gTotalDx) > Math.abs(gTotalDy) * 1.5f && fast) {
                int dir = gTotalDx < 0 ? 1 : -1;
                int target = pageIndex + dir;
                if (target >= 0 && target < doc.pages.size()) {
                    goToPage(target);
                    return;
                }
            }
        }
        clampTransform();
        sceneDirty = true;
        invalidate();
    }

    private long gestureDuration() {
        return SystemClock.uptimeMillis() - gStartTime;
    }

    private void clampTransform() {
        Page p = page();
        if (p.isInfinite()) return;
        float pw = p.w * scale, ph = p.h * scale;
        float m = 24 * density;
        if (pw <= getWidth()) tx = (getWidth() - pw) / 2f;
        else tx = Math.max(getWidth() - pw - m, Math.min(m, tx));
        if (ph <= getHeight()) ty = Math.max(m * 0.5f, Math.min(ty, getHeight() - ph - m * 0.5f));
        else ty = Math.max(getHeight() - ph - m, Math.min(m, ty));
    }

    /** 무한 캔버스 내보내기용 */
    public RectF contentBounds() {
        return Renderer.contentBounds(page());
    }
}
