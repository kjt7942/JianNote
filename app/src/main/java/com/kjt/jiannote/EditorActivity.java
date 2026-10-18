package com.kjt.jiannote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.kjt.jiannote.model.Document;
import com.kjt.jiannote.model.Layer;
import com.kjt.jiannote.model.NotebookMeta;
import com.kjt.jiannote.model.Page;
import com.kjt.jiannote.model.StrokeElem;
import com.kjt.jiannote.model.TextElem;
import com.kjt.jiannote.render.Fonts;
import com.kjt.jiannote.render.Paper;
import com.kjt.jiannote.render.RenderContext;
import com.kjt.jiannote.render.Renderer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/** 노트 편집 화면 */
public class EditorActivity extends Activity implements CanvasView.Listener, PagePanel.Host, PenPresets.Host {
    private static final int REQ_IMAGE = 11, REQ_PAGE_IMAGE = 12, REQ_STICKER_IMAGE = 13, REQ_TPL_IMAGE = 14, REQ_TPL_PDF = 15, REQ_PAGE_PDF = 16;
    /** 올가미 편집 1회 후 돌아갈 도구 (-1 = 없음) */
    private int returnTool = -1;

    private Store store;
    private Premium premium;
    private Prefs prefs;
    private NotebookMeta meta;
    private Document doc;
    private RenderContext rc;
    private CanvasView canvas;
    private PagePanel pagePanel;
    private boolean dirty;

    private final TextView[] toolBtns = new TextView[9];
    private TextView attachBtn, detachBtn, memoPatBtn, homeBtn;
    private android.widget.HorizontalScrollView selWrap;
    private TextView undoBtn, redoBtn, pageLabel, zoomLabel, fingerBtn, layerBtn, editTextBtn;
    private LinearLayout selBar;
    private Ui.Dot widthDot;
    private final Ui.Swatch[] quick = new Ui.Swatch[5];
    private final int[] quickColors = {0xFF1A1A1A, 0xFF2563EB, 0xFFDC2626, 0xFF16A34A, 0xFFF97316};
    private PopupWindow popup;
    private PopupWindow layerPopup;
    private LinearLayout layerList;

    private static final int[] TOOL_ICONS = {
            R.drawable.ic_ink_pen, R.drawable.ic_ink_highlighter, R.drawable.ic_ink_eraser, R.drawable.ic_lasso_select,
            R.drawable.ic_shapes, R.drawable.ic_text_fields, R.drawable.ic_pan_tool, R.drawable.ic_rectangle, R.drawable.ic_sticky_note_2
    };
    private static final String[] TOOL_NAMES = {"펜", "형광펜", "지우개", "올가미 선택", "도형", "텍스트", "이동(스크롤)", "암기 테이프", "메모지"};
    private LinearLayout strip;
    private LinearLayout topHolder, bottomHolder, stripEnd;
    private boolean compactUi;
    private int builtWidthDp;
    private final android.os.Handler autosave = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable autosaveTick = new Runnable() {
        @Override
        public void run() {
            if (dirty) save();
            autosave.postDelayed(this, 30000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Fonts.init(getAssets());
        store = new Store(this);
        premium = new Premium(this);
        prefs = new Prefs(this);
        String id = getIntent().getStringExtra("id");
        try {
            meta = store.loadMeta(id);
            doc = store.loadDoc(id);
        } catch (Exception e) {
            Toast.makeText(this, "노트를 열 수 없습니다: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (doc.pages.isEmpty()) doc.pages.add(Page.create(meta.pageW(), meta.pageH(), meta.template));
        rc = new RenderContext(store.dir(id));

        for (int i = 0; i < 5; i++) quickColors[i] = prefs.i("quick" + i, quickColors[i]);

        canvas = new CanvasView(this);
        canvas.setListener(this);
        loadToolPrefs();
        canvas.setDocument(doc, rc, meta.paperColor);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.BG);
        topHolder = Ui.col(this);
        root.addView(topHolder);

        FrameLayout body = new FrameLayout(this);
        body.addView(canvas, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        selBar = buildSelectionBar();
        selWrap = new android.widget.HorizontalScrollView(this);
        selWrap.setHorizontalScrollBarEnabled(false);
        selWrap.addView(selBar);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        slp.topMargin = Ui.dp(this, 12);
        slp.leftMargin = slp.rightMargin = Ui.dp(this, 12);
        body.addView(selWrap, slp);
        selWrap.setVisibility(View.GONE);
        pagePanel = new PagePanel(this, canvas, rc, this);
        body.addView(pagePanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        bottomHolder = Ui.col(this);
        root.addView(bottomHolder);
        buildChrome();
        setContentView(root);
        Ui.edgeToEdge(this, root);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }

        selectTool(canvas.tool);
        updateHistory();
        onPageChanged();
    }

    /** 화면 폭에 맞춰 위·아래 막대를 만든다 (좁은 폰은 두 줄 배치, 태블릿은 한 줄) */
    private void buildChrome() {
        compactUi = Ui.isCompact(this);
        builtWidthDp = Ui.widthDp(this);
        topHolder.removeAllViews();
        topHolder.addView(compactUi ? buildCompactTopBar() : buildTopBar());
        topHolder.addView(Ui.divider(this, false));
        topHolder.addView(buildStrip());
        topHolder.addView(Ui.divider(this, false));
        bottomHolder.removeAllViews();
        bottomHolder.addView(Ui.divider(this, false));
        bottomHolder.addView(buildBottomBar());
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (topHolder == null || canvas == null) return;
        // 접는 폰을 펴거나 회전·화면 분할로 폭이 바뀌면 도구 배치를 다시 맞춘다
        if (Ui.isCompact(this) == compactUi && (!compactUi || Ui.widthDp(this) == builtWidthDp)) return;
        if (popup != null) popup.dismiss();
        if (layerPopup != null) layerPopup.dismiss();
        buildChrome();
        for (int i = 0; i < toolBtns.length; i++) Ui.setSelected(toolBtns[i], i == canvas.tool);
        updateDot();
        updateStrip();
        updateHistory();
        onPageChanged();
        onZoomChanged(canvas.getScale());
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    private void handleBack() {
        if (pagePanel != null && pagePanel.isOpen()) {
            pagePanel.hide();
        } else if (canvas != null && canvas.hasSelection()) {
            canvas.clearSelection();
        } else {
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        autosave.postDelayed(autosaveTick, 30000);
    }

    private void loadToolPrefs() {
        canvas.tool = prefs.i("tool", CanvasView.TOOL_PEN);
        if (canvas.tool == CanvasView.TOOL_TEXT || canvas.tool == CanvasView.TOOL_LASSO) canvas.tool = CanvasView.TOOL_PEN;
        canvas.penType = prefs.i("penType", StrokeElem.PEN);
        canvas.penColor = prefs.i("penColor", 0xFF1A1A1A);
        canvas.penWidth = prefs.f("penWidth", 4f);
        canvas.hlColor = prefs.i("hlColor", 0xFFFFEB3B);
        canvas.hlWidth = prefs.f("hlWidth", 26f);
        canvas.eraserSize = prefs.f("eraserSize", 24f);
        canvas.eraserPartial = prefs.b("eraserPartial", true);
        canvas.shapeType = prefs.i("shapeType", ShapeRecognizer.RECT);
        canvas.fingerDraw = prefs.b("fingerDraw", false);
        canvas.pressureEnabled = prefs.b("pressure", true);
        canvas.pressureGamma = prefs.f("gamma", 1f);
        canvas.holdToShape = prefs.b("holdToShape", true);
        canvas.penSquare = prefs.b("penSquare", false);
        canvas.hlSquare = prefs.b("hlSquare", false);
        canvas.scribbleErase = prefs.b("scribbleErase", true);
        canvas.eraserHlOnly = prefs.b("eraserHlOnly", false);
        canvas.lassoRect = prefs.b("lassoRect", false);
        canvas.lassoFilter = prefs.i("lassoFilter", 0);
        canvas.tapeWidth = prefs.f("tapeWidth", 48f);
        canvas.tapeColor = prefs.i("tapeColor", 0xFFFF8A65);
        canvas.tapePattern = prefs.i("tapePattern", 1);
        canvas.tapeWriteMode = prefs.b("tapeWriteMode", false);
        canvas.tapeAutoAttach = prefs.b("tapeAutoAttach", true);
        canvas.memoColor = prefs.i("memoColor", 0xFFFFF59D);
        canvas.memoPattern = prefs.i("memoPattern", 1);
        TextElem.Style ts = canvas.textStyle;
        ts.font = prefs.i("txFont", 1);
        ts.size = prefs.f("textSize", 40f);
        ts.color = prefs.i("txColor", 0xFF1A1A1A);
        ts.flags = prefs.i("txFlags", 0);
        ts.bg = prefs.i("txBg", 0);
        ts.align = prefs.i("txAlign", 0);
    }

    private void saveToolPrefs() {
        prefs.put("tool", canvas.tool);
        prefs.put("penType", canvas.penType);
        prefs.put("penColor", canvas.penColor);
        prefs.put("penWidth", canvas.penWidth);
        prefs.put("hlColor", canvas.hlColor);
        prefs.put("hlWidth", canvas.hlWidth);
        prefs.put("eraserSize", canvas.eraserSize);
        prefs.put("eraserPartial", canvas.eraserPartial);
        prefs.put("shapeType", canvas.shapeType);
        prefs.put("fingerDraw", canvas.fingerDraw);
        prefs.put("pressure", canvas.pressureEnabled);
        prefs.put("gamma", canvas.pressureGamma);
        prefs.put("holdToShape", canvas.holdToShape);
        prefs.put("penSquare", canvas.penSquare);
        prefs.put("hlSquare", canvas.hlSquare);
        prefs.put("scribbleErase", canvas.scribbleErase);
        prefs.put("eraserHlOnly", canvas.eraserHlOnly);
        prefs.put("lassoRect", canvas.lassoRect);
        prefs.put("lassoFilter", canvas.lassoFilter);
        prefs.put("tapeWidth", canvas.tapeWidth);
        prefs.put("tapeColor", canvas.tapeColor);
        prefs.put("tapePattern", canvas.tapePattern);
        prefs.put("tapeWriteMode", canvas.tapeWriteMode);
        prefs.put("tapeAutoAttach", canvas.tapeAutoAttach);
        prefs.put("memoColor", canvas.memoColor);
        prefs.put("memoPattern", canvas.memoPattern);
        TextElem.Style ts = canvas.textStyle;
        prefs.put("txFont", ts.font);
        prefs.put("textSize", ts.size);
        prefs.put("txColor", ts.color);
        prefs.put("txFlags", ts.flags);
        prefs.put("txBg", ts.bg);
        prefs.put("txAlign", ts.align);
    }

    // =============== 상단 도구 막대 ===============

    private TextView icon(int res, String tip) {
        TextView t = Ui.iconButton(this, res, null);
        t.setTooltipText(tip);
        t.setContentDescription(tip);
        if (compactUi) {
            // 좁은 화면: 버튼을 조금 촘촘하게
            t.setMinWidth(Ui.dp(this, 42));
            t.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 3));
            t.getLayoutParams().height = Ui.dp(this, 44);
        }
        return t;
    }

    private View buildTopBar() {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setFillViewport(true);
        hs.setBackgroundColor(Ui.SURFACE);
        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4));
        hs.addView(bar, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 왼쪽: 뒤로 · 제목 · 실행취소/다시실행
        bar.addView(backButton());
        TextView title = titleView();
        title.setMaxWidth(Ui.dp(this, 150));
        bar.addView(title);
        makeHistoryButtons();
        bar.addView(undoBtn);
        bar.addView(redoBtn);
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        // 가운데: 그리기 도구
        makeToolButtons();
        for (int i = 0; i < toolBtns.length; i++) if (i != CanvasView.TOOL_HAND) bar.addView(toolBtns[i]);
        bar.addView(imageButton());
        bar.addView(stickerButton());
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        // 오른쪽: 레이어 · 손가락 · 이동 · 더보기
        bar.addView(layerButton());
        bar.addView(fingerButton());
        bar.addView(toolBtns[CanvasView.TOOL_HAND]);
        updateFinger();
        bar.addView(moreButton());
        return hs;
    }

    /** 일반 스마트폰용: 첫 줄은 뒤로·제목·실행취소·레이어·더보기, 아래는 모든 도구가 화면 폭에 맞춰 줄바꿈 */
    private View buildCompactTopBar() {
        LinearLayout box = Ui.col(this);
        box.setBackgroundColor(Ui.SURFACE);
        LinearLayout row = Ui.row(this);
        row.setPadding(Ui.dp(this, 4), Ui.dp(this, 2), Ui.dp(this, 4), 0);
        row.addView(backButton());
        row.addView(titleView(), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        makeHistoryButtons();
        row.addView(undoBtn);
        row.addView(redoBtn);
        row.addView(layerButton());
        row.addView(moreButton());
        box.addView(row);

        Ui.FlowLayout tools = new Ui.FlowLayout(this);
        tools.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), Ui.dp(this, 2));
        makeToolButtons();
        for (int i = 0; i < toolBtns.length; i++) if (i != CanvasView.TOOL_HAND) tools.addView(toolBtns[i]);
        tools.addView(imageButton());
        tools.addView(stickerButton());
        tools.addView(toolBtns[CanvasView.TOOL_HAND]);
        tools.addView(fingerButton());
        updateFinger();
        box.addView(tools, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private TextView backButton() {
        TextView back = icon(R.drawable.ic_arrow_back, "서재로");
        back.setOnClickListener(v -> finish());
        return back;
    }

    private TextView titleView() {
        TextView title = Ui.label(this, meta.title, 15, Ui.TEXT, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 6), 0);
        title.setOnClickListener(v -> Dialogs.input(this, "노트 이름", meta.title, false, s -> {
            if (s.trim().isEmpty()) return;
            meta.title = s.trim();
            title.setText(meta.title);
            dirty = true;
        }));
        return title;
    }

    private void makeHistoryButtons() {
        undoBtn = icon(R.drawable.ic_undo, "실행취소");
        undoBtn.setOnClickListener(v -> canvas.undo());
        redoBtn = icon(R.drawable.ic_redo, "다시실행");
        redoBtn.setOnClickListener(v -> canvas.redo());
    }

    private void makeToolButtons() {
        for (int i = 0; i < TOOL_ICONS.length; i++) {
            TextView t = icon(TOOL_ICONS[i], TOOL_NAMES[i]);
            final int tool = i;
            t.setOnClickListener(v -> {
                if (canvas.tool == tool && tool != CanvasView.TOOL_HAND) showToolOptions(v);
                else selectTool(tool);
            });
            toolBtns[i] = t;
        }
    }

    private TextView imageButton() {
        TextView img = icon(R.drawable.ic_image, "이미지 넣기");
        img.setOnClickListener(v -> pickImage());
        return img;
    }

    private TextView stickerButton() {
        TextView st = icon(R.drawable.ic_add_reaction, "스티커");
        st.setOnClickListener(v -> showStickers());
        return st;
    }

    private TextView layerButton() {
        layerBtn = icon(R.drawable.ic_layers, "레이어");
        layerBtn.setOnClickListener(this::showLayers);
        return layerBtn;
    }

    private TextView fingerButton() {
        fingerBtn = icon(R.drawable.ic_touch_app, "손가락으로 그리기");
        fingerBtn.setOnClickListener(v -> {
            canvas.fingerDraw = !canvas.fingerDraw;
            updateFinger();
            Toast.makeText(this, canvas.fingerDraw ? "손가락 필기 켜짐 (두 손가락으로 스크롤)" : "S펜으로만 필기 (손가락은 스크롤/확대)", Toast.LENGTH_SHORT).show();
        });
        return fingerBtn;
    }

    private TextView moreButton() {
        TextView more = icon(R.drawable.ic_more_vert, "더보기");
        more.setOnClickListener(this::showMenu);
        return more;
    }

    /** 도구 아래의 빠른 설정 줄: 즐겨찾는 색 5개 + 굵기 3단계 + 상세 설정 */
    private View buildStrip() {
        strip = Ui.row(this);
        strip.setGravity(Gravity.CENTER);
        strip.setPadding(Ui.dp(this, 10), Ui.dp(this, 2), Ui.dp(this, 10), Ui.dp(this, 2));
        if (compactUi) strip.setPadding(Ui.dp(this, 4), Ui.dp(this, 2), Ui.dp(this, 4), Ui.dp(this, 2));
        LinearLayout wrap = Ui.row(this);
        wrap.setGravity(Gravity.CENTER);
        wrap.addView(strip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 46)));
        // 화면보다 길면 옆으로 밀어서 볼 수 있게 (짧으면 가운데 정렬)
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setFillViewport(true);
        hs.setBackgroundColor(0xFFFAF9F6);
        hs.addView(wrap, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 좁은 화면: 상세 설정 버튼은 밀려서 안 보이지 않도록 오른쪽 끝에 고정
        LinearLayout outer = Ui.row(this);
        outer.setBackgroundColor(0xFFFAF9F6);
        outer.addView(hs, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        stripEnd = Ui.row(this);
        outer.addView(stripEnd);
        for (int i = 0; i < 5; i++) quick[i] = makeQuick(i, quickColors[i]);
        widthDot = new Ui.Dot(this, 8, canvas.penColor);
        widthDot.setOnClickListener(this::showToolOptions);
        widthDot.setTooltipText("굵기·색상 상세 설정");
        return outer;
    }

    private static final float[][] WIDTH_PRESETS = {
            {2, 4, 8}, {16, 26, 44}, {14, 28, 56}, {}, {2, 4, 8}, {28, 40, 64}, {}, {32, 48, 72}, {}
    };

    private void updateStrip() {
        if (strip == null) return;
        strip.removeAllViews();
        stripEnd.removeAllViews();
        int tool = canvas.tool;
        if (tool == CanvasView.TOOL_LASSO) {
            String[] modes = {"자유 올가미", "사각형 올가미"};
            for (int i = 0; i < 2; i++) {
                final boolean rect = i == 1;
                TextView t = stripChip(modes[i], canvas.lassoRect == rect);
                t.setOnClickListener(v -> {
                    canvas.lassoRect = rect;
                    saveToolPrefs();
                    updateStrip();
                });
                strip.addView(t);
            }
            strip.addView(Ui.divider(this, true));
            String[] filters = {"전체", "펜만", "형광펜만", "이미지만", "텍스트만", "테이프만", "메모지만"};
            for (int i = 0; i < filters.length; i++) {
                final int f = i;
                TextView t = stripChip(filters[i], canvas.lassoFilter == i);
                t.setOnClickListener(v -> {
                    canvas.lassoFilter = f;
                    saveToolPrefs();
                    updateStrip();
                });
                strip.addView(t);
            }
            return;
        }
        if (tool == CanvasView.TOOL_MEMO) {
            for (int c : com.kjt.jiannote.model.MemoElem.COLORS) {
                Ui.Swatch sw = new Ui.Swatch(this, c);
                sw.setChosen(c == canvas.memoColor);
                sw.setOnClickListener(v -> {
                    canvas.memoColor = c;
                    updateStrip();
                });
                strip.addView(sw, stripCell(36));
            }
            strip.addView(Ui.divider(this, true));
            for (int i = 0; i < com.kjt.jiannote.model.MemoElem.PATTERN_NAMES.length; i++) {
                final int pt = i;
                TextView t = stripChip(com.kjt.jiannote.model.MemoElem.PATTERN_NAMES[i], canvas.memoPattern == i);
                t.setOnClickListener(v -> {
                    canvas.memoPattern = pt;
                    updateStrip();
                });
                strip.addView(t);
            }
            strip.addView(Ui.divider(this, true));
            strip.addView(Ui.label(this, "탭 또는 끌어서 붙이기", 12, Ui.SUBTEXT, false));
            return;
        }
        if (tool == CanvasView.TOOL_TAPE) {
            for (int i = 0; i < com.kjt.jiannote.model.TapeElem.PATTERN_NAMES.length; i++) {
                final int pt = i;
                TextView t = stripChip(com.kjt.jiannote.model.TapeElem.PATTERN_NAMES[i], canvas.tapePattern == i);
                t.setOnClickListener(v -> {
                    canvas.tapePattern = pt;
                    updateStrip();
                });
                strip.addView(t);
            }
            strip.addView(Ui.divider(this, true));
            TextView wm = stripChip(canvas.tapeWriteMode ? "테이프 위에 쓰기: 켜짐" : "테이프 위에 쓰기: 꺼짐", canvas.tapeWriteMode);
            wm.setOnClickListener(v -> {
                canvas.tapeWriteMode = !canvas.tapeWriteMode;
                Toast.makeText(this, canvas.tapeWriteMode ? "테이프 위에 글씨를 쓸 수 있어요" : "테이프를 터치하면 보이기/가리기", Toast.LENGTH_SHORT).show();
                updateStrip();
            });
            strip.addView(wm);
            TextView show = stripChip("모두 보이기", false);
            show.setOnClickListener(v -> canvas.setAllTapes(true));
            strip.addView(show);
            TextView hide = stripChip("모두 가리기", false);
            hide.setOnClickListener(v -> canvas.setAllTapes(false));
            strip.addView(hide);
            strip.addView(Ui.divider(this, true));
        }
        if (tool == CanvasView.TOOL_HAND) {
            strip.addView(Ui.label(this, compactUi ? "스크롤 · 두 손가락 확대/축소 · 꾹 눌러 편집" : "손가락·펜으로 페이지를 스크롤하고 두 손가락으로 확대/축소 · 텍스트·이미지를 꾹 누르면 편집", 13, Ui.SUBTEXT, false));
            return;
        }
        if (tool == CanvasView.TOOL_ERASER) {
            String[] modes = {"부분", "획", "형광펜만"};
            int cur = canvas.eraserHlOnly ? 2 : canvas.eraserPartial ? 0 : 1;
            for (int i = 0; i < 3; i++) {
                final int m = i;
                TextView t = stripChip(modes[i], cur == i);
                t.setOnClickListener(v -> {
                    setEraserMode(m);
                    saveToolPrefs();
                    updateStrip();
                });
                strip.addView(t);
            }
            strip.addView(Ui.divider(this, true));
        }
        if (tool == CanvasView.TOOL_TAPE) {
            // 테이프 전용 색 팔레트 (펜 색과 따로)
            for (int c : TAPE_COLORS) {
                Ui.Swatch sw = new Ui.Swatch(this, c);
                sw.setChosen((c | 0xFF000000) == (canvas.tapeColor | 0xFF000000));
                sw.setOnClickListener(v -> {
                    canvas.tapeColor = c;
                    saveToolPrefs();
                    updateStrip();
                });
                strip.addView(sw, stripCell(34));
            }
            TextView pal = icon(R.drawable.ic_palette, "테이프 색 직접 고르기");
            pal.setOnClickListener(v -> ColorPicker.show(this, canvas.tapeColor, c -> {
                canvas.tapeColor = c;
                saveToolPrefs();
                updateStrip();
            }));
            strip.addView(pal);
            strip.addView(Ui.divider(this, true));
            String[] names = {"얇게", "보통", "굵게"};
            float[] ws = WIDTH_PRESETS[tool];
            for (int i = 0; i < ws.length; i++) {
                final float w = ws[i];
                TextView t = stripChip(names[i], Math.abs(canvas.tapeWidth - w) < 0.5f);
                t.setOnClickListener(v -> {
                    canvas.tapeWidth = w;
                    saveToolPrefs();
                    updateStrip();
                });
                strip.addView(t);
            }
            if (!compactUi) strip.addView(Ui.divider(this, true));
            addSettings("테이프 설정");
            return;
        }
        if (tool != CanvasView.TOOL_ERASER) {
            for (Ui.Swatch q : quick) {
                if (q.getParent() != null) ((ViewGroup) q.getParent()).removeView(q);
                strip.addView(q, stripCell(36));
            }
            TextView pal = icon(R.drawable.ic_palette, "색상 선택기");
            pal.setOnClickListener(v -> ColorPicker.show(this, currentColor(), this::applyColor));
            strip.addView(pal);
            TextView eye = icon(R.drawable.ic_colorize, "스포이드 (화면에서 색 집기)");
            Ui.setSelected(eye, canvas.eyedropper);
            eye.setOnClickListener(v -> {
                canvas.eyedropper = !canvas.eyedropper;
                Ui.setSelected(eye, canvas.eyedropper);
                if (canvas.eyedropper) Toast.makeText(this, "색을 가져올 곳을 탭하세요", Toast.LENGTH_SHORT).show();
            });
            strip.addView(eye);
            strip.addView(Ui.divider(this, true));
        }
        float[] presets = WIDTH_PRESETS[tool];
        for (float w : presets) {
            Ui.Dot d = new Ui.Dot(this, 0, 0);
            float shown = tool == CanvasView.TOOL_HIGHLIGHTER ? w * 0.5f : tool == CanvasView.TOOL_ERASER ? w * 0.45f
                    : tool == CanvasView.TOOL_TEXT ? w * 0.35f : w * 1.6f + 2;
            int col = tool == CanvasView.TOOL_ERASER ? 0xFFBDBDBD : currentColor();
            d.set(shown * getResources().getDisplayMetrics().density / 1.5f, col);
            d.setChosen(Math.abs(currentWidth() - w) < 0.5f);
            d.setOnClickListener(v -> {
                setCurrentWidth(w);
                updateStrip();
            });
            strip.addView(d, stripCell(40));
        }
        if (widthDot.getParent() != null) ((ViewGroup) widthDot.getParent()).removeView(widthDot);
        if (!compactUi) strip.addView(Ui.divider(this, true));
        addSettings(TOOL_NAMES[tool] + " 설정");
    }

    /** '펜 설정' 같은 상세 설정 버튼 (좁은 화면에서는 오른쪽 끝에 고정된 아이콘) */
    private void addSettings(String text) {
        if (compactUi) {
            TextView opts = icon(R.drawable.ic_settings, text);
            Ui.tintIcon(opts, Ui.ACCENT);
            opts.setOnClickListener(this::showToolOptions);
            stripEnd.addView(Ui.divider(this, true));
            stripEnd.addView(opts);
            return;
        }
        TextView label = Ui.label(this, text, 13, Ui.ACCENT, true);
        label.setPadding(Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8));
        label.setOnClickListener(this::showToolOptions);
        strip.addView(label);
    }

    /** 빠른 설정 줄의 색 견본·굵기 점 크기 (좁은 화면에서는 조금 작게) */
    private LinearLayout.LayoutParams stripCell(int dp) {
        int sz = Ui.dp(this, compactUi ? dp - 6 : dp);
        return new LinearLayout.LayoutParams(sz, sz);
    }

    private TextView stripChip(String text, boolean on) {
        TextView t = Ui.pill(this, text, on);
        t.setTextSize(13);
        t.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
        t.setLayoutParams(lp);
        return t;
    }

    /** 0=부분 1=획 2=형광펜만 */
    private void setEraserMode(int m) {
        canvas.eraserHlOnly = m == 2;
        canvas.eraserPartial = m != 1;
    }

    private static final int[] TAPE_COLORS = {0xFFFF8A65, 0xFFFFD54F, 0xFFA5D6A7, 0xFF81D4FA, 0xFFF48FB1, 0xFFB39DDB, 0xFFB0BEC5, 0xFF616161};

    private float currentWidth() {
        switch (canvas.tool) {
            case CanvasView.TOOL_HIGHLIGHTER: return canvas.hlWidth;
            case CanvasView.TOOL_ERASER: return canvas.eraserSize;
            case CanvasView.TOOL_TEXT: return canvas.textStyle.size;
            case CanvasView.TOOL_TAPE: return canvas.tapeWidth;
            default: return canvas.penWidth;
        }
    }

    private void setCurrentWidth(float w) {
        switch (canvas.tool) {
            case CanvasView.TOOL_HIGHLIGHTER: canvas.hlWidth = w; break;
            case CanvasView.TOOL_ERASER: canvas.eraserSize = w; break;
            case CanvasView.TOOL_TEXT: canvas.textStyle.size = w; break;
            case CanvasView.TOOL_TAPE: canvas.tapeWidth = w; break;
            default: canvas.penWidth = w; break;
        }
        updateDot();
    }

    private Ui.Swatch makeQuick(int slot, int color) {
        Ui.Swatch s = new Ui.Swatch(this, color);
        s.setOnClickListener(v -> applyColor(quickColors[slot]));
        s.setOnLongClickListener(v -> {
            ColorPicker.show(this, quickColors[slot], c -> {
                quickColors[slot] = c;
                prefs.put("quick" + slot, c);
                rebuildQuick(slot, c);
                applyColor(c);
            });
            return true;
        });
        return s;
    }

    private void rebuildQuick(int slot, int color) {
        quick[slot] = makeQuick(slot, color);
        updateStrip();
        updateDot();
    }

    private void updateFinger() {
        Ui.setSelected(fingerBtn, canvas.fingerDraw);
    }

    private void selectTool(int tool) {
        selectTool(tool, true);
    }

    private void selectTool(int tool, boolean byUser) {
        if (byUser) {
            // 다른 도구에서 올가미로 바꾸면, 편집 한 번 뒤 원래 도구로 돌아간다
            returnTool = tool == CanvasView.TOOL_LASSO && canvas.tool != CanvasView.TOOL_LASSO ? canvas.tool : -1;
        }
        canvas.eyedropper = false;
        canvas.tool = tool;
        if (tool != CanvasView.TOOL_LASSO) canvas.clearSelection();
        for (int i = 0; i < toolBtns.length; i++) Ui.setSelected(toolBtns[i], i == tool);
        updateDot();
        updateStrip();
        canvas.invalidate();
    }

    private int currentColor() {
        switch (canvas.tool) {
            case CanvasView.TOOL_HIGHLIGHTER: return canvas.hlColor;
            case CanvasView.TOOL_TEXT: return canvas.textStyle.color;
            case CanvasView.TOOL_TAPE: return canvas.tapeColor;
            case CanvasView.TOOL_MEMO: return canvas.memoColor;
            default: return canvas.penColor;
        }
    }

    private void applyColor(int c) {
        if (canvas.hasSelection()) {
            canvas.recolorSelection(c);
            return;
        }
        if (canvas.tool == CanvasView.TOOL_HIGHLIGHTER) {
            canvas.hlColor = c;
        } else if (canvas.tool == CanvasView.TOOL_TEXT) {
            canvas.textStyle.color = c;
        } else if (canvas.tool == CanvasView.TOOL_TAPE) {
            canvas.tapeColor = c;
        } else if (canvas.tool == CanvasView.TOOL_MEMO) {
            canvas.memoColor = c | 0xFF000000;
        } else {
            canvas.penColor = c;
            if (canvas.tool == CanvasView.TOOL_ERASER || canvas.tool == CanvasView.TOOL_HAND || canvas.tool == CanvasView.TOOL_LASSO) {
                selectTool(CanvasView.TOOL_PEN);
            }
        }
        updateDot();
        updateStrip();
    }

    private void updateDot() {
        float d = getResources().getDisplayMetrics().density;
        float w;
        switch (canvas.tool) {
            case CanvasView.TOOL_HIGHLIGHTER: w = canvas.hlWidth * 0.6f; break;
            case CanvasView.TOOL_ERASER: w = canvas.eraserSize; break;
            case CanvasView.TOOL_TEXT: w = canvas.textStyle.size * 0.4f; break;
            default: w = canvas.penWidth * 1.5f + 2; break;
        }
        widthDot.set(Math.min(w, 30 * d), canvas.tool == CanvasView.TOOL_ERASER ? 0xFFBDBDBD : currentColor());
        for (int i = 0; i < quick.length; i++) {
            if (quick[i] != null) quick[i].setChosen(quickColors[i] == currentColor() && canvas.tool != CanvasView.TOOL_ERASER);
        }
    }

    // =============== 선택 막대 ===============

    private LinearLayout buildSelectionBar() {
        LinearLayout bar = Ui.row(this);
        bar.setBackground(Ui.round(0xFF2B2B33, Ui.dp(this, 24)));
        bar.setElevation(Ui.dp(this, 6));
        bar.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4));
        int[] icons = {R.drawable.ic_edit, R.drawable.ic_delete, R.drawable.ic_content_copy, R.drawable.ic_palette, R.drawable.ic_layers,
                R.drawable.ic_lock, R.drawable.ic_link, R.drawable.ic_link_off, R.drawable.ic_grid_view,
                R.drawable.ic_add_reaction, R.drawable.ic_crop_square, R.drawable.ic_close};
        String[] labels = {"글자편집", "삭제", "복제", "색상", "레이어로", "고정", "붙이기", "떼기", "양식", "스티커로", "캡처", "해제"};
        for (int i = 0; i < icons.length; i++) {
            TextView t = Ui.iconButton(this, icons[i], labels[i]);
            Ui.tintIcon(t, 0xFFFFFFFF);
            final int k = i;
            t.setOnClickListener(v -> {
                switch (k) {
                    case 0: editSelectedText(); break;
                    case 1: canvas.deleteSelection(); break;
                    case 2: canvas.duplicateSelection(); break;
                    case 3: ColorPicker.show(this, canvas.penColor, canvas::recolorSelection); break;
                    case 4: chooseLayerForSelection(); break;
                    case 5:
                        canvas.lockSelection();
                        Toast.makeText(this, "고정했습니다. 꾹 누르면 고정을 풀 수 있어요", Toast.LENGTH_SHORT).show();
                        break;
                    case 6: {
                        int n = canvas.attachSelectedTapes(true);
                        Toast.makeText(this, n > 0 ? "테이프를 붙였습니다. 이제 아래 내용과 함께 움직여요" : "테이프 아래에 붙일 내용이 없습니다", Toast.LENGTH_SHORT).show();
                        break;
                    }
                    case 7:
                        canvas.attachSelectedTapes(false);
                        Toast.makeText(this, "테이프를 뗐습니다. 이제 따로 움직여요", Toast.LENGTH_SHORT).show();
                        break;
                    case 8: chooseMemoPattern(); break;
                    case 9: saveSelectionAsSticker(); break;
                    case 10: captureSelection(); break;
                    default: canvas.clearSelection(); break;
                }
            });
            if (i == 0) editTextBtn = t;
            if (i == 6) attachBtn = t;
            if (i == 7) detachBtn = t;
            if (i == 8) memoPatBtn = t;
            bar.addView(t);
        }
        return bar;
    }

    private void showStickers() {
        Stickers.show(this, rc, elems -> canvas.insertElements(elems), () -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            startActivityForResult(i, REQ_STICKER_IMAGE);
        });
    }

    /** 올가미로 고른 영역을 그림 파일로 저장 */
    private void captureSelection() {
        android.graphics.RectF r = canvas.selectionBounds();
        if (r == null) return;
        try {
            Uri u = Exporter.exportRegion(this, meta, canvas.page(), rc, r);
            new AlertDialog.Builder(this).setTitle("캡처 저장 완료")
                    .setMessage("사진/JianNote 폴더에 저장했습니다.")
                    .setNegativeButton("확인", null)
                    .setPositiveButton("공유", (d, w) -> Exporter.share(this, u, "image/png"))
                    .show();
        } catch (Exception e) {
            Toast.makeText(this, "캡처 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void saveSelectionAsSticker() {
        try {
            Stickers.addClip(this, canvas.selectionCopy(), rc);
            Toast.makeText(this, "내 스티커에 저장했습니다", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "스티커 저장 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void chooseMemoPattern() {
        com.kjt.jiannote.model.MemoElem m = canvas.singleSelectedMemo();
        if (m == null) return;
        new AlertDialog.Builder(this).setTitle("메모지 양식")
                .setItems(com.kjt.jiannote.model.MemoElem.PATTERN_NAMES, (d, w) -> canvas.setMemoPattern(m, w))
                .show();
    }

    private void editSelectedText() {
        TextElem t = canvas.singleSelectedText();
        if (t == null) return;
        TextTools.edit(this, t.text, t.style, true, (text, st) -> canvas.editSelectedText(t, text, st));
    }

    private void chooseLayerForSelection() {
        Page p = canvas.page();
        String[] names = new String[p.layers.size()];
        for (int i = 0; i < names.length; i++) {
            int li = p.layers.size() - 1 - i;
            names[i] = p.layers.get(li).name + (li == canvas.activeLayerIndex() ? "  (현재)" : "");
        }
        new AlertDialog.Builder(this).setTitle("선택 항목을 이동할 레이어")
                .setItems(names, (d, w) -> canvas.moveSelectionToLayer(p.layers.size() - 1 - w))
                .show();
    }

    // =============== 하단 막대 ===============

    private View buildBottomBar() {
        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, compactUi ? 2 : 8), Ui.dp(this, 2), Ui.dp(this, compactUi ? 2 : 8), Ui.dp(this, 2));
        bar.setGravity(Gravity.CENTER);
        TextView prev = icon(R.drawable.ic_keyboard_arrow_up, "이전 페이지");
        prev.setOnClickListener(v -> canvas.goToPage(canvas.pageIndex() - 1));
        bar.addView(prev);
        pageLabel = Ui.label(this, "1 / 1", 14, Ui.TEXT, true);
        int lp = Ui.dp(this, compactUi ? 4 : 10);
        pageLabel.setPadding(lp, Ui.dp(this, 10), lp, Ui.dp(this, 10));
        pageLabel.setOnClickListener(v -> togglePanel());
        bar.addView(pageLabel);
        TextView next = icon(R.drawable.ic_keyboard_arrow_down, "다음 페이지");
        next.setOnClickListener(v -> canvas.goToPage(canvas.pageIndex() + 1));
        bar.addView(next);
        bar.addView(Ui.divider(this, true));
        TextView add = bottomButton(R.drawable.ic_add, "페이지 추가");
        add.setOnClickListener(this::showAddPageMenu);
        bar.addView(add);
        TextView panel = bottomButton(R.drawable.ic_view_agenda, "페이지 보기");
        panel.setOnClickListener(v -> togglePanel());
        bar.addView(panel);
        TextView pm = bottomButton(R.drawable.ic_more_vert, "페이지 메뉴");
        pm.setOnClickListener(this::showPageMenu);
        bar.addView(pm);
        // 무한 캔버스 페이지를 보고 있을 때만 보이는 버튼
        homeBtn = bottomButton(R.drawable.ic_my_location, "처음 위치로");
        homeBtn.setOnClickListener(v -> canvas.resetInfiniteView());
        bar.addView(homeBtn);
        bar.addView(Ui.divider(this, true));
        zoomLabel = Ui.label(this, "100%", 13, Ui.SUBTEXT, false);
        zoomLabel.setPadding(Ui.dp(this, compactUi ? 4 : 8), Ui.dp(this, 10), Ui.dp(this, compactUi ? 4 : 8), Ui.dp(this, 10));
        zoomLabel.setOnClickListener(v -> canvas.fitPage());
        bar.addView(zoomLabel);
        // 아주 좁은 화면에서도 잘리지 않게 옆으로 밀 수 있게
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setFillViewport(true);
        hs.setBackgroundColor(Ui.SURFACE);
        hs.addView(bar, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return hs;
    }

    /** 하단 막대 버튼: 넓은 화면은 아이콘+글자, 좁은 화면은 아이콘만 */
    private TextView bottomButton(int res, String text) {
        return compactUi ? icon(res, text) : Ui.iconText(this, res, text, Ui.TEXT);
    }

    private void togglePanel() {
        if (pagePanel.isOpen()) pagePanel.hide();
        else pagePanel.show();
    }

    /** 페이지 추가: 일반 페이지 / 무한 캔버스 / 사진·PDF */
    private void showAddPageMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        Page cur = canvas.page();
        String size = com.kjt.jiannote.model.PageSize.label(meta.sizeWmm, meta.sizeHmm);
        pm.getMenu().add(0, 1, 0, cur.isInfinite() ? "일반 페이지 (" + size + ")" : "일반 페이지 (지금 페이지와 같은 크기·속지)");
        pm.getMenu().add(0, 2, 1, "무한 캔버스 페이지");
        pm.getMenu().add(0, 3, 2, "사진으로 페이지 추가");
        pm.getMenu().add(0, 4, 3, "PDF로 페이지 추가");
        pm.setOnMenuItemClickListener(item -> {
            int at = canvas.pageIndex() + 1;
            switch (item.getItemId()) {
                case 1: addPage(); break;
                case 2:
                    canvas.addPage(Page.create(0, 0, meta.template), at);
                    Toast.makeText(this, "무한 캔버스 페이지를 추가했습니다", Toast.LENGTH_SHORT).show();
                    break;
                case 3: pickPageImage(); break;
                case 4: pickTemplateFile("application/pdf", REQ_PAGE_PDF); break;
                default: break;
            }
            if (pagePanel.isOpen()) pagePanel.rebuild();
            return true;
        });
        pm.show();
    }

    private void addPage() {
        Page cur = canvas.page();
        if (cur.isInfinite()) {
            canvas.addPage(Page.create(meta.pagedW(), meta.pagedH(), meta.template), canvas.pageIndex() + 1);
            if (pagePanel.isOpen()) pagePanel.rebuild();
            return;
        }
        canvas.addPage(cur.blankLike(), canvas.pageIndex() + 1);
        if (pagePanel.isOpen()) pagePanel.rebuild();
    }

    private void showPageMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        Page cur = canvas.page();
        pm.getMenu().add(0, 1, 0, "현재 페이지 뒤에 새 페이지");
        pm.getMenu().add(0, 2, 0, "현재 페이지 복제");
        pm.getMenu().add(0, 3, 0, cur.bookmarked ? "북마크 해제" : "🔖 북마크");
        pm.getMenu().add(0, 4, 0, "페이지 위로 이동");
        pm.getMenu().add(0, 5, 0, "페이지 아래로 이동");
        pm.getMenu().add(0, 6, 0, "속지 변경");
        if (!cur.isInfinite()) {
            pm.getMenu().add(0, 10, 0, "페이지 크기 변경 (현재 " + com.kjt.jiannote.model.PageSize.label(
                    com.kjt.jiannote.model.PageSize.mm(cur.w), com.kjt.jiannote.model.PageSize.mm(cur.h)) + ")");
        }
        pm.getMenu().add(0, 8, 0, "이미지로 새 페이지 추가");
        pm.getMenu().add(0, 9, 0, "이 페이지의 고정 모두 해제");
        pm.getMenu().add(0, 7, 0, "현재 페이지 삭제");
        pm.setOnMenuItemClickListener(item -> {
            int i = canvas.pageIndex();
            switch (item.getItemId()) {
                case 1: addPage(); break;
                case 2: canvas.addPage(canvas.page().copy(), i + 1); break;
                case 3:
                    cur.bookmarked = !cur.bookmarked;
                    dirty = true;
                    Toast.makeText(this, cur.bookmarked ? "북마크했습니다" : "북마크 해제", Toast.LENGTH_SHORT).show();
                    break;
                case 4: canvas.movePage(i, i - 1); break;
                case 5: canvas.movePage(i, i + 1); break;
                case 6: chooseTemplate(); break;
                case 7: confirmDeletePage(i); break;
                case 8: pickPageImage(); break;
                case 10: changePageSize(); break;
                case 9: {
                    int n = canvas.unlockAllOnPage();
                    Toast.makeText(this, n > 0 ? n + "개 항목의 고정을 해제했습니다" : "고정된 항목이 없습니다", Toast.LENGTH_SHORT).show();
                    break;
                }
                default: break;
            }
            if (pagePanel.isOpen()) pagePanel.rebuild();
            return true;
        });
        pm.show();
    }

    private void confirmDeletePage(int i) {
        if (canvas.pageCount() <= 1) {
            Toast.makeText(this, "마지막 페이지는 삭제할 수 없습니다", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("페이지 삭제")
                .setMessage((i + 1) + "페이지를 삭제할까요?")
                .setNegativeButton("취소", null)
                .setPositiveButton("삭제", (d, w) -> {
                    canvas.deletePage(i);
                    if (pagePanel.isOpen()) pagePanel.rebuild();
                })
                .show();
    }

    /** 속지를 이미지로 보면서 선택 */
    private void chooseTemplate() {
        Page cur = canvas.page();
        if (cur.isInfinite()) {
            chooseBasicTemplate();
            return;
        }
        java.util.ArrayList<String> items = new java.util.ArrayList<>();
        items.add("기본 속지 고르기 (줄·모눈·도트 등)");
        items.add("사진으로 속지 만들기");
        items.add("PDF로 속지 만들기");
        if (cur.bgImage != null) items.add("사진·PDF 배경 지우기 (빈 속지로)");
        new AlertDialog.Builder(this).setTitle("속지 변경")
                .setItems(items.toArray(new String[0]), (d, w) -> {
                    switch (w) {
                        case 0: chooseBasicTemplate(); break;
                        case 1: pickTemplateFile("image/*", REQ_TPL_IMAGE); break;
                        case 2: pickTemplateFile("application/pdf", REQ_TPL_PDF); break;
                        default:
                            askScope("사진·PDF 속지 지우기", all -> {
                                for (Page p : all ? doc.pages : java.util.Collections.singletonList(canvas.page())) {
                                    p.setBackground(null, null, 0);
                                }
                                templateChanged();
                            });
                    }
                }).show();
    }

    /** 페이지 크기 바꾸기 (각 페이지의 가로/세로 방향은 유지, 내용 위치는 그대로) */
    private void changePageSize() {
        Page cur = canvas.page();
        Dialogs.pageSizePicker(this, com.kjt.jiannote.model.PageSize.mm(cur.w), com.kjt.jiannote.model.PageSize.mm(cur.h), (wMm, hMm) ->
                askScope("페이지 크기를 바꿀 페이지", all -> {
                    float a = com.kjt.jiannote.model.PageSize.px(Math.min(wMm, hMm));
                    float b = com.kjt.jiannote.model.PageSize.px(Math.max(wMm, hMm));
                    for (Page p : all ? doc.pages : java.util.Collections.singletonList(canvas.page())) {
                        if (p.isInfinite()) continue;
                        boolean land = p.w > p.h;
                        p.w = land ? b : a;
                        p.h = land ? a : b;
                    }
                    if (all) {
                        meta.sizeWmm = Math.min(wMm, hMm);
                        meta.sizeHmm = Math.max(wMm, hMm);
                    }
                    canvas.pagesChanged();
                    canvas.fitPage();
                    dirty = true;
                    if (pagePanel.isOpen()) pagePanel.rebuild();
                    Toast.makeText(this, "페이지 크기를 " + com.kjt.jiannote.model.PageSize.label(wMm, hMm) + "(으)로 바꿨습니다", Toast.LENGTH_SHORT).show();
                }));
    }

    private void pickTemplateFile(String mime, int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        try {
            startActivityForResult(i, req);
        } catch (Exception e) {
            Toast.makeText(this, "파일을 열 수 없습니다", Toast.LENGTH_SHORT).show();
        }
    }

    private interface ScopeCb {
        void on(boolean allPages);
    }

    /** 이 페이지만 / 모든 페이지 */
    private void askScope(String title, ScopeCb cb) {
        if (canvas.pageCount() <= 1) {
            cb.on(false);
            return;
        }
        new AlertDialog.Builder(this).setTitle(title)
                .setItems(new String[]{"이 페이지만", "모든 페이지"}, (d, w) -> cb.on(w == 1))
                .show();
    }

    private boolean confirmedBgReplace;

    private void applyImageTemplate(String file) {
        applyImageTemplate(new String[]{file, null}, 0);
    }

    /** tpl = {속지 이미지, 원본 PDF(없으면 null)}, pdfPage = 원본 PDF 의 페이지 */
    private void applyImageTemplate(String[] tpl, int pdfPage) {
        askScope("속지를 적용할 페이지", all -> {
            for (Page p : all ? doc.pages : java.util.Collections.singletonList(canvas.page())) p.setBackground(tpl[0], tpl[1], pdfPage);
            templateChanged();
            Toast.makeText(this, "속지를 바꿨습니다. 새로 추가하는 페이지에도 같은 속지가 들어가요", Toast.LENGTH_LONG).show();
        });
    }

    private void templateChanged() {
        dirty = true;
        canvas.invalidateScene();
        if (pagePanel.isOpen()) pagePanel.rebuild();
    }

    private void chooseBasicTemplate() {
        Page cur = canvas.page();
        if (cur.bgImage != null && !cur.hasImageTemplate() && !confirmedBgReplace) {
            new AlertDialog.Builder(this).setTitle("속지 변경")
                    .setMessage("이 페이지는 사진·PDF로 만든 페이지예요. 기본 속지로 바꾸면 사진·PDF 배경은 사라지고, 그 위에 쓴 필기는 그대로 남아요.")
                    .setNegativeButton("취소", null)
                    .setPositiveButton("바꾸기", (d, w) -> {
                        confirmedBgReplace = true;
                        chooseBasicTemplate();
                    }).show();
            return;
        }
        confirmedBgReplace = false;
        boolean land = !cur.isInfinite() && cur.w > cur.h;
        Dialogs.templatePicker(this, premium, meta.paperColor, land, cur.template, !cur.isInfinite() && canvas.pageCount() > 1,
                (t, all) -> {
                    if (all) {
                        for (Page p : doc.pages) {
                            p.template = t;
                            p.setBackground(null, null, 0);
                        }
                    } else {
                        cur.template = t;
                        cur.setBackground(null, null, 0);
                    }
                    meta.template = t;
                    dirty = true;
                    canvas.invalidateScene();
                    if (pagePanel.isOpen()) pagePanel.rebuild();
                });
    }

    /** 종이 색을 이미지로 보면서 선택 */
    private void choosePaper() {
        Page cur = canvas.page();
        boolean land = !cur.isInfinite() && cur.w > cur.h;
        Dialogs.paperPicker(this, cur.template, land, meta.paperColor, c -> {
            meta.paperColor = c;
            canvas.setPaperColor(c);
            dirty = true;
            if (pagePanel.isOpen()) pagePanel.rebuild();
        });
    }

    // =============== 더보기 메뉴 ===============

    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "📄 PDF로 내보내기");
        pm.getMenu().add(0, 2, 0, "🖼️ 현재 페이지를 이미지로 저장");
        pm.getMenu().add(0, 3, 0, "📃 속지 변경");
        pm.getMenu().add(0, 4, 0, "🎨 종이 색 변경");
        pm.getMenu().add(0, 5, 0, "✒️ 필압 / 펜 설정");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: exportPdf(); break;
                case 2: exportPng(); break;
                case 3: chooseTemplate(); break;
                case 4: choosePaper(); break;
                case 5: selectTool(CanvasView.TOOL_PEN); showToolOptions(toolBtns[0]); break;
                default: break;
            }
            return true;
        });
        pm.show();
    }

    private void exportPdf() {
        save();
        // 원본 PDF 배경을 고해상도로 다시 그리느라 오래 걸릴 수 있어 진행 상황 표시
        LinearLayout box = Ui.row(this);
        int pad = Ui.dp(this, 24);
        box.setPadding(pad, pad, pad, pad);
        box.addView(new android.widget.ProgressBar(this));
        TextView msg = Ui.label(this, "PDF로 내보내는 중…", 15, Ui.TEXT, false);
        msg.setPadding(Ui.dp(this, 16), 0, 0, 0);
        box.addView(msg);
        AlertDialog pd = new AlertDialog.Builder(this).setView(box).setCancelable(false).create();
        pd.show();
        Exporter.exportPdf(this, meta, doc, rc, false,
                (d, n) -> msg.setText("PDF로 내보내는 중… " + d + " / " + n + "페이지"),
                new ImportExport.Done<Uri>() {
                    @Override
                    public void ok(Uri u) {
                        if (isDestroyed()) return;
                        pd.dismiss();
                        new AlertDialog.Builder(EditorActivity.this).setTitle("PDF 저장 완료")
                                .setMessage("다운로드/JianNote 폴더에 저장했습니다.")
                                .setNegativeButton("확인", null)
                                .setPositiveButton("공유", (d, w) -> Exporter.share(EditorActivity.this, u, "application/pdf"))
                                .show();
                    }

                    @Override
                    public void fail(String m) {
                        if (isDestroyed()) return;
                        pd.dismiss();
                        Toast.makeText(EditorActivity.this, "PDF 저장 실패: " + m, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void exportPng() {
        try {
            Uri u = Exporter.exportPng(this, meta, canvas.page(), rc);
            new AlertDialog.Builder(this).setTitle("이미지 저장 완료")
                    .setMessage("사진/JianNote 폴더에 저장했습니다.")
                    .setNegativeButton("확인", null)
                    .setPositiveButton("공유", (d, w) -> Exporter.share(this, u, "image/png"))
                    .show();
        } catch (Exception e) {
            Toast.makeText(this, "이미지 저장 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // =============== 도구 옵션 팝업 ===============

    private void showToolOptions(View anchor) {
        if (popup != null) popup.dismiss();
        LinearLayout box = Ui.col(this);
        int p = Ui.dp(this, 16);
        box.setPadding(p, p, p, p);
        int width = 380;
        switch (canvas.tool) {
            case CanvasView.TOOL_PEN:
                box.addView(title("내 펜 목록"));
                box.addView(PenPresets.list(this, PenPresets.KIND_PEN, this, () -> showToolOptions(anchor)));
                penOptions(box);
                break;
            case CanvasView.TOOL_HIGHLIGHTER:
                box.addView(title("내 형광펜 목록"));
                box.addView(PenPresets.list(this, PenPresets.KIND_HL, this, () -> showToolOptions(anchor)));
                hlOptions(box);
                break;
            case CanvasView.TOOL_ERASER:
                box.addView(title("내 지우개 목록"));
                box.addView(PenPresets.list(this, PenPresets.KIND_ERASER, this, () -> showToolOptions(anchor)));
                eraserOptions(box);
                break;
            case CanvasView.TOOL_SHAPE: shapeOptions(box); break;
            case CanvasView.TOOL_TEXT:
                width = 440;
                box.addView(Ui.label(this, "텍스트를 넣을 곳을 탭하세요. 기존 텍스트를 탭하면 수정합니다.\n아래 서식은 새로 입력하는 텍스트에 적용됩니다.", 13, Ui.TEXT, false));
                box.addView(TextTools.controls(this, canvas.textStyle, this::updateDot));
                break;
            case CanvasView.TOOL_TAPE:
                box.addView(Ui.label(this, "암기 테이프: 가릴 곳 위로 끌어서 붙이세요.\n• 테이프를 터치하면 잠깐 투명해지고, 다시 터치하면 가려져요\n• '테이프 위에 쓰기'를 켜면 테이프 위에 글씨를 쓸 수 있어요\n• 붙일 때 아래 글씨·텍스트·이미지와 자동으로 묶여 함께 움직여요 (올가미로 선택 → 붙이기/떼기)", 13, Ui.TEXT, false));
                {
                    CheckBox auto = new CheckBox(this);
                    auto.setText("붙일 때 아래 내용과 자동으로 묶기");
                    auto.setChecked(canvas.tapeAutoAttach);
                    auto.setOnCheckedChangeListener((b, c) -> canvas.tapeAutoAttach = c);
                    box.addView(auto);
                    TextView wl = title("두께: " + fmt(canvas.tapeWidth));
                    box.addView(wl);
                    box.addView(Ui.seek(this, 100, (int) canvas.tapeWidth - 16, v -> {
                        canvas.tapeWidth = v + 16;
                        wl.setText("두께: " + fmt(canvas.tapeWidth));
                    }));
                    box.addView(title("색상"));
                    box.addView(ColorPicker.grid(this, new int[]{0xFFFF8A65, 0xFF81D4FA, 0xFFA5D6A7, 0xFFF48FB1, 0xFFFFE082, 0xFFB0BEC5, 0xFF9575CD, 0xFF4E342E},
                            canvas.tapeColor, c -> canvas.tapeColor = c));
                }
                break;
            case CanvasView.TOOL_MEMO:
                box.addView(Ui.label(this, "메모지: 탭하면 기본 크기로, 끌면 원하는 크기로 붙어요.\n• 메모지 위에 쓴 손글씨·텍스트는 메모지와 함께 움직여요\n• 메모지를 꾹 누르면 바로 옮기거나 양식·색을 바꿀 수 있어요", 13, Ui.TEXT, false));
                box.addView(title("색상"));
                box.addView(ColorPicker.grid(this, com.kjt.jiannote.model.MemoElem.COLORS, canvas.memoColor, c -> canvas.memoColor = c | 0xFF000000));
                break;
            case CanvasView.TOOL_LASSO:
                box.addView(Ui.label(this, "올가미로 영역을 둘러 선택하세요.\n• 선택 상자 안을 끌면 이동\n• 오른쪽 아래 파란 점을 끌면 크기 조절 (선 두께는 그대로)\n• 위쪽 막대에서 글자편집·삭제·복제·색상·레이어 이동·고정\n• 아래 줄에서 자유/사각형 올가미와 선택할 종류(펜만·형광펜만·이미지만·텍스트만)를 고를 수 있어요\n• 다른 도구에서 올가미로 바꿨다면, 편집 한 번 뒤 원래 도구로 돌아갑니다", 14, Ui.TEXT, false));
                break;
            default: return;
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.78f);
        int pw = Ui.popupWidth(this, width);
        popup = new PopupWindow(sv, pw, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        sv.measure(View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        if (sv.getMeasuredHeight() > maxH) popup.setHeight(maxH);
        popup.setElevation(Ui.dp(this, 12));
        popup.setBackgroundDrawable(Ui.round(Ui.SURFACE, Ui.dp(this, 16)));
        popup.setOutsideTouchable(true);
        popup.setOnDismissListener(() -> {
            saveToolPrefs();
            updateStrip();
        });
        popup.showAsDropDown(anchor, 0, Ui.dp(this, 4));
    }

    private TextView title(String s) {
        TextView t = Ui.label(this, s, 13, Ui.SUBTEXT, true);
        t.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
        return t;
    }

    private void penOptions(LinearLayout box) {
        StrokePreview prev = new StrokePreview();
        box.addView(title("펜 종류"));
        android.widget.GridLayout types = new android.widget.GridLayout(this);
        // 팝업 폭(좁은 화면이면 화면 폭)에 들어가는 만큼 한 줄에 놓는다
        float dens = getResources().getDisplayMetrics().density;
        types.setColumnCount(Math.max(2, Math.min(4, (int) ((Ui.popupWidth(this, 380) / dens - 32) / 86))));
        int[] vals = {StrokeElem.PEN, StrokeElem.FOUNTAIN, StrokeElem.BRUSH, StrokeElem.PENCIL,
                StrokeElem.MARKER, StrokeElem.AIRBRUSH, StrokeElem.WATERCOLOR};
        TextView[] btns = new TextView[vals.length];
        for (int i = 0; i < vals.length; i++) {
            TextView t = Ui.pill(this, StrokeElem.PEN_NAMES[vals[i]], false);
            t.setTextSize(12);
            t.setPadding(Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8));
            final int v = vals[i];
            btns[i] = t;
            t.setOnClickListener(x -> {
                canvas.penType = v;
                for (int j = 0; j < vals.length; j++) TextTools.markPill(this, btns[j], vals[j] == v);
                prev.invalidate();
            });
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
            lp.width = Ui.dp(this, 82);
            lp.setMargins(Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2));
            types.addView(t, lp);
            TextTools.markPill(this, t, canvas.penType == v);
        }
        box.addView(types);
        CheckBox sq = new CheckBox(this);
        sq.setText("끝을 네모나게");
        sq.setChecked(canvas.penSquare);
        sq.setOnCheckedChangeListener((b, c) -> {
            canvas.penSquare = c;
            prev.invalidate();
        });
        box.addView(sq);
        box.addView(prev, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 70)));

        TextView wl = title("굵기: " + fmt(canvas.penWidth));
        box.addView(wl);
        box.addView(Ui.seek(this, 39, (int) canvas.penWidth - 1, v -> {
            canvas.penWidth = v + 1;
            wl.setText("굵기: " + fmt(canvas.penWidth));
            prev.invalidate();
            updateDot();
        }));

        box.addView(title("색상"));
        box.addView(ColorPicker.grid(this, Ui.PEN_COLORS, canvas.penColor, c -> {
            canvas.penColor = c;
            prev.invalidate();
            updateDot();
        }));
        box.addView(opacityBar(() -> canvas.penColor, c -> {
            canvas.penColor = c;
            prev.invalidate();
            updateDot();
        }));

        box.addView(title("S펜 필압"));
        CheckBox pr = new CheckBox(this);
        pr.setText("필압 사용 (세게 누를수록 굵게)");
        pr.setChecked(canvas.pressureEnabled);
        pr.setOnCheckedChangeListener((b, c) -> {
            canvas.pressureEnabled = c;
            prev.invalidate();
        });
        box.addView(pr);
        TextView gl = Ui.label(this, gammaText(), 13, Ui.TEXT, false);
        box.addView(gl);
        box.addView(Ui.seek(this, 21, Math.round((canvas.pressureGamma - 0.4f) * 10), v -> {
            canvas.pressureGamma = 0.4f + v / 10f;
            gl.setText(gammaText());
            prev.invalidate();
        }));
        CheckBox hold = new CheckBox(this);
        hold.setText("펜을 그은 끝에서 멈추면 도형으로 보정");
        hold.setChecked(canvas.holdToShape);
        hold.setOnCheckedChangeListener((b, c) -> canvas.holdToShape = c);
        box.addView(hold);
        box.addView(Ui.label(this, "  ↳ 보정된 뒤 펜을 떼지 않고 움직이면 크기·길이·방향을 바꿀 수 있어요", 11, Ui.SUBTEXT, false));
        CheckBox scr = new CheckBox(this);
        scr.setText("낙서해서 지우기 (글씨 위에 지그재그로 마구 그으면 지워짐)");
        scr.setChecked(canvas.scribbleErase);
        scr.setOnCheckedChangeListener((b, c) -> canvas.scribbleErase = c);
        box.addView(scr);
    }

    private interface IntGet {
        int get();
    }

    /** 현재 색의 투명도 조절 막대 */
    private View opacityBar(IntGet get, Ui.IntCb set) {
        LinearLayout box = Ui.col(this);
        int a = android.graphics.Color.alpha(get.get());
        TextView l = title("투명도: " + Math.round(a * 100 / 255f) + "%");
        box.addView(l);
        box.addView(Ui.seek(this, 90, Math.round(a * 100 / 255f) - 10, v -> {
            int na = Math.round((v + 10) * 255 / 100f);
            l.setText("투명도: " + (v + 10) + "%");
            set.on(ColorPicker.withA(get.get(), na));
        }));
        return box;
    }

    private String gammaText() {
        float g = canvas.pressureGamma;
        String feel = g < 0.8f ? "부드럽게 (살짝만 눌러도 굵게)" : g > 1.3f ? "단단하게 (세게 눌러야 굵게)" : "보통";
        return "필압 감도: " + feel;
    }

    private void hlOptions(LinearLayout box) {
        TextView wl = title("굵기: " + fmt(canvas.hlWidth));
        box.addView(wl);
        box.addView(Ui.seek(this, 72, (int) canvas.hlWidth - 8, v -> {
            canvas.hlWidth = v + 8;
            wl.setText("굵기: " + fmt(canvas.hlWidth));
            updateDot();
        }));
        CheckBox sq = new CheckBox(this);
        sq.setText("끝을 네모나게");
        sq.setChecked(canvas.hlSquare);
        sq.setOnCheckedChangeListener((b, c) -> canvas.hlSquare = c);
        box.addView(sq);
        box.addView(title("색상"));
        box.addView(ColorPicker.grid(this, Ui.HL_COLORS, canvas.hlColor, c -> {
            canvas.hlColor = c;
            updateDot();
        }));
        box.addView(opacityBar(() -> canvas.hlColor == (canvas.hlColor | 0xFF000000) ? ColorPicker.withA(canvas.hlColor, 0x70) : canvas.hlColor, c -> {
            canvas.hlColor = c;
            updateDot();
        }));
    }

    private void eraserOptions(LinearLayout box) {
        TextView wl = title("크기: " + fmt(canvas.eraserSize));
        box.addView(wl);
        box.addView(Ui.seek(this, 112, (int) canvas.eraserSize - 8, v -> {
            canvas.eraserSize = v + 8;
            wl.setText("크기: " + fmt(canvas.eraserSize));
            updateDot();
        }));
        box.addView(title("지우개 방식"));
        LinearLayout r = Ui.row(this);
        String[] names = {"부분 지우개", "획 지우개", "형광펜만"};
        TextView[] bs = new TextView[3];
        int cur = canvas.eraserHlOnly ? 2 : canvas.eraserPartial ? 0 : 1;
        for (int i = 0; i < 3; i++) {
            final int m = i;
            bs[i] = Ui.pill(this, names[i], false);
            bs[i].setTextSize(13);
            TextTools.markPill(this, bs[i], cur == i);
            bs[i].setOnClickListener(v -> {
                setEraserMode(m);
                for (int j = 0; j < 3; j++) TextTools.markPill(this, bs[j], j == m);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
            r.addView(bs[i], lp);
        }
        box.addView(r);
        box.addView(Ui.label(this, "획 지우개는 텍스트·이미지도 지웁니다. '형광펜만'은 형광펜 자국만 지우고 글씨는 남깁니다. 고정된 항목은 지워지지 않아요. S펜 측면 버튼을 누른 채 쓰면 언제든 지우개가 됩니다.", 12, Ui.SUBTEXT, false));
        TextView clear = Ui.pill(this, "현재 레이어 전체 지우기", false);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Ui.dp(this, 12);
        box.addView(clear, clp);
        clear.setOnClickListener(v -> {
            if (popup != null) popup.dismiss();
            new AlertDialog.Builder(this).setTitle("레이어 지우기")
                    .setMessage("'" + canvas.activeLayer().name + "'의 모든 내용을 지울까요? (실행취소 가능)")
                    .setNegativeButton("취소", null)
                    .setPositiveButton("지우기", (d, w) -> canvas.clearActiveLayer())
                    .show();
        });
    }

    private void shapeOptions(LinearLayout box) {
        box.addView(title("도형"));
        LinearLayout r = Ui.row(this);
        TextView[] btns = new TextView[ShapeRecognizer.NAMES.length];
        for (int i = 0; i < btns.length; i++) {
            TextView t = Ui.pill(this, ShapeRecognizer.NAMES[i], false);
            t.setTextSize(12);
            t.setPadding(Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8));
            final int s = i;
            btns[i] = t;
            t.setOnClickListener(v -> {
                canvas.shapeType = s;
                for (int j = 0; j < btns.length; j++) TextTools.markPill(this, btns[j], j == s);
            });
            TextTools.markPill(this, t, canvas.shapeType == i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(Ui.dp(this, 2), 0, Ui.dp(this, 2), 0);
            r.addView(t, lp);
        }
        box.addView(r);
        TextView wl = title("선 굵기: " + fmt(canvas.penWidth));
        box.addView(wl);
        box.addView(Ui.seek(this, 39, (int) canvas.penWidth - 1, v -> {
            canvas.penWidth = v + 1;
            wl.setText("선 굵기: " + fmt(canvas.penWidth));
            updateDot();
        }));
        box.addView(title("색상"));
        box.addView(ColorPicker.grid(this, Ui.PEN_COLORS, canvas.penColor, c -> {
            canvas.penColor = c;
            updateDot();
        }));
    }

    private static String fmt(float f) {
        return String.valueOf(Math.round(f));
    }

    /** 펜 미리보기 (필압 변화 시뮬레이션) */
    private class StrokePreview extends View {
        private final Paint bg = new Paint();

        StrokePreview() {
            super(EditorActivity.this);
            bg.setColor(0xFFF7F5F0);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            c.drawRoundRect(0, 0, w, h, 16, 16, bg);
            StrokeElem s = new StrokeElem(canvas.penType, canvas.penColor);
            int steps = 80;
            for (int i = 0; i <= steps; i++) {
                float t = (float) i / steps;
                float x = 20 + t * (w - 40);
                float y = h / 2 + (float) Math.sin(t * Math.PI * 2) * h * 0.25f;
                float pres = canvas.pressureEnabled ? (float) Math.sin(t * Math.PI) : 0.55f;
                pres = Math.max(0.05f, pres);
                float f = (float) Math.pow(pres, canvas.pressureGamma);
                float pw;
                switch (canvas.penType) {
                    case StrokeElem.FOUNTAIN: pw = canvas.penWidth * (0.15f + 1.35f * f); break;
                    case StrokeElem.BRUSH: pw = canvas.penWidth * (0.05f + 2.4f * f); break;
                    default: pw = canvas.penWidth * (0.55f + 0.7f * f); break;
                }
                s.add(x, y, pw);
            }
            s.draw(c, rc);
        }
    }

    // =============== 레이어 패널 ===============

    private void showLayers(View anchor) {
        if (layerPopup != null) layerPopup.dismiss();
        LinearLayout box = Ui.col(this);
        int p = Ui.dp(this, 14);
        box.setPadding(p, p, p, p);
        LinearLayout head = Ui.row(this);
        head.addView(Ui.label(this, "레이어 · " + (canvas.pageIndex() + 1) + "페이지", 16, Ui.TEXT, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView add = Ui.pill(this, "＋ 추가", true);
        add.setTextSize(13);
        add.setOnClickListener(v -> {
            if (canvas.addLayer(premium.layerLimit())) refreshLayers();
        });
        head.addView(add);
        box.addView(head);
        layerList = Ui.col(this);
        box.addView(layerList);
        refreshLayers();
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        layerPopup = new PopupWindow(sv, Ui.popupWidth(this, 360), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        sv.measure(View.MeasureSpec.makeMeasureSpec(Ui.popupWidth(this, 360), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.7f);
        if (sv.getMeasuredHeight() > maxH) layerPopup.setHeight(maxH);
        layerPopup.setElevation(Ui.dp(this, 12));
        layerPopup.setBackgroundDrawable(Ui.round(Ui.SURFACE, Ui.dp(this, 16)));
        layerPopup.setOutsideTouchable(true);
        layerPopup.showAsDropDown(anchor, 0, Ui.dp(this, 4));
    }

    private void refreshLayers() {
        if (layerList == null) return;
        layerList.removeAllViews();
        Page pg = canvas.page();
        for (int idx = pg.layers.size() - 1; idx >= 0; idx--) {
            final int i = idx;
            Layer l = pg.layers.get(i);
            boolean active = i == canvas.activeLayerIndex();
            LinearLayout card = Ui.col(this);
            card.setBackground(Ui.roundStroke(active ? 0xFFEEF1FF : 0xFFFAFAF7, Ui.dp(this, 10),
                    active ? Ui.ACCENT : 0x14000000, Ui.dp(this, active ? 2 : 1)));
            card.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 4));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.topMargin = Ui.dp(this, 8);

            LinearLayout r = Ui.row(this);
            TextView eye = icon(l.visible ? R.drawable.ic_visibility : R.drawable.ic_visibility_off, "보이기/숨기기");
            eye.setOnClickListener(v -> {
                l.visible = !l.visible;
                dirty = true;
                canvas.invalidateScene();
                refreshLayers();
            });
            r.addView(eye);
            TextView name = Ui.label(this, l.name + "  (" + l.elems.size() + ")", 14, active ? Ui.ACCENT : Ui.TEXT, active);
            name.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10));
            name.setOnClickListener(v -> {
                canvas.setActiveLayer(i);
                refreshLayers();
            });
            r.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView up = icon(R.drawable.ic_keyboard_arrow_up, "위로");
            up.setOnClickListener(v -> {
                canvas.moveLayer(i, 1);
                refreshLayers();
            });
            r.addView(up);
            TextView down = icon(R.drawable.ic_keyboard_arrow_down, "아래로");
            down.setOnClickListener(v -> {
                canvas.moveLayer(i, -1);
                refreshLayers();
            });
            r.addView(down);
            TextView menu = icon(R.drawable.ic_more_vert, "레이어 메뉴");
            menu.setOnClickListener(v -> layerMenu(v, i));
            r.addView(menu);
            card.addView(r);

            LinearLayout op = Ui.row(this);
            op.addView(Ui.label(this, "불투명도", 11, Ui.SUBTEXT, false));
            SeekBar sb = Ui.seek(this, 255, l.alpha, v -> {
                l.alpha = v;
                dirty = true;
                canvas.invalidateScene();
            });
            op.addView(sb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            card.addView(op);
            layerList.addView(card, clp);
        }
    }

    private void layerMenu(View anchor, int i) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "이름 바꾸기");
        pm.getMenu().add(0, 2, 0, "아래 레이어와 병합");
        pm.getMenu().add(0, 3, 0, "레이어 내용 지우기");
        pm.getMenu().add(0, 4, 0, "레이어 삭제");
        pm.setOnMenuItemClickListener(item -> {
            Page pg = canvas.page();
            switch (item.getItemId()) {
                case 1:
                    Dialogs.input(this, "레이어 이름", pg.layers.get(i).name, false, s -> {
                        if (!s.trim().isEmpty()) {
                            pg.layers.get(i).name = s.trim();
                            dirty = true;
                            refreshLayers();
                        }
                    });
                    break;
                case 2:
                    if (i == 0) Toast.makeText(this, "가장 아래 레이어입니다", Toast.LENGTH_SHORT).show();
                    else canvas.mergeLayerDown(i);
                    refreshLayers();
                    break;
                case 3:
                    canvas.setActiveLayer(i);
                    canvas.clearActiveLayer();
                    refreshLayers();
                    break;
                case 4:
                    if (pg.layers.size() <= 1) {
                        Toast.makeText(this, "레이어는 최소 1개 필요합니다", Toast.LENGTH_SHORT).show();
                    } else {
                        canvas.deleteLayer(i);
                        refreshLayers();
                    }
                    break;
                default: break;
            }
            return true;
        });
        pm.show();
    }

    // =============== 이미지 ===============

    private void pickPageImage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_PAGE_IMAGE);
        } catch (Exception e) {
            Toast.makeText(this, "사진을 열 수 없습니다", Toast.LENGTH_SHORT).show();
        }
    }

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        try {
            startActivityForResult(i, REQ_IMAGE);
        } catch (Exception e) {
            Toast.makeText(this, "사진을 열 수 없습니다", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PAGE_PDF && res == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            int at = canvas.pageIndex() + 1;
            try {
                java.util.List<Page> pages = ImportExport.pdfToPages(this, uri, rc.imagesDir());
                int n = pages.size();
                for (int i = 0; i < n; i++) canvas.addPage(pages.get(i), at + i);
                canvas.goToPage(at);
                Toast.makeText(this, "PDF " + n + "페이지를 추가했습니다", Toast.LENGTH_SHORT).show();
                if (pagePanel.isOpen()) pagePanel.rebuild();
            } catch (Exception e) {
                Toast.makeText(this, "PDF 페이지 추가 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (req == REQ_TPL_IMAGE && res == RESULT_OK && data != null && data.getData() != null) {
            try {
                applyImageTemplate(ImportExport.templateFromImage(this, data.getData(), rc.imagesDir()));
            } catch (Exception e) {
                Toast.makeText(this, "속지 만들기 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (req == REQ_TPL_PDF && res == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                int n = ImportExport.pdfPageCount(this, uri);
                if (n <= 1) {
                    applyImageTemplate(ImportExport.templateFromPdf(this, uri, 0, rc.imagesDir()), 0);
                } else {
                    String[] pages = new String[n];
                    for (int i = 0; i < n; i++) pages[i] = (i + 1) + "페이지";
                    new AlertDialog.Builder(this).setTitle("속지로 쓸 PDF 페이지")
                            .setItems(pages, (d, w) -> {
                                try {
                                    applyImageTemplate(ImportExport.templateFromPdf(this, uri, w, rc.imagesDir()), w);
                                } catch (Exception e) {
                                    Toast.makeText(this, "속지 만들기 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
                                }
                            }).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "PDF를 열 수 없습니다: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (req == REQ_STICKER_IMAGE && res == RESULT_OK && data != null && data.getData() != null) {
            try {
                Stickers.addImage(this, data.getData());
                Toast.makeText(this, "내 스티커에 추가했습니다", Toast.LENGTH_SHORT).show();
                showStickers();
            } catch (Exception e) {
                Toast.makeText(this, "스티커 만들기 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (req == REQ_PAGE_IMAGE && res == RESULT_OK && data != null) {
            java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            int at = canvas.pageIndex() + 1;
            int added = 0;
            for (Uri u : uris) {
                try {
                    Page pg = ImportExport.imageToPage(this, u, rc.imagesDir());
                    canvas.addPage(pg, at + added);
                    added++;
                } catch (Exception e) {
                    Toast.makeText(this, "이미지 페이지 추가 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
            if (added > 0) {
                canvas.goToPage(at);
                Toast.makeText(this, added + "장의 이미지를 페이지로 추가했습니다", Toast.LENGTH_SHORT).show();
                if (pagePanel.isOpen()) pagePanel.rebuild();
            }
            return;
        }
        if (req != REQ_IMAGE || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / sample > 2400) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            Bitmap bm;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                bm = BitmapFactory.decodeStream(in, null, o2);
            }
            if (bm == null) throw new Exception("이미지 해독 실패");
            boolean alpha = bm.hasAlpha();
            String name = UUID.randomUUID().toString().substring(0, 12) + (alpha ? ".png" : ".jpg");
            File f = new File(rc.imagesDir(), name);
            try (OutputStream os = new FileOutputStream(f)) {
                bm.compress(alpha ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 92, os);
            }
            int w = bm.getWidth(), h = bm.getHeight();
            bm.recycle();
            canvas.addImage(name, w, h);
            selectTool(CanvasView.TOOL_LASSO);
            onSelectionChanged(true);
        } catch (Exception e) {
            Toast.makeText(this, "이미지 추가 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // =============== 저장 ===============

    private void save() {
        if (doc == null) return;
        try {
            store.saveDoc(meta.id, doc);
            meta.pageCount = doc.pages.size();
            if (dirty) meta.modified = System.currentTimeMillis();
            store.saveMeta(meta);
            Bitmap th = Renderer.thumbnail(doc.pages.get(0), meta.paperColor, rc, 360, 500);
            store.saveThumb(meta.id, th);
            th.recycle();
            dirty = false;
        } catch (Exception e) {
            Toast.makeText(this, "저장 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        autosave.removeCallbacks(autosaveTick);
        if (popup != null) popup.dismiss();
        if (layerPopup != null) layerPopup.dismiss();
        if (canvas != null) saveToolPrefs();
        if (dirty) save();
    }

    // =============== PagePanel.Host ===============

    @Override
    public int paperColor() {
        return meta.paperColor;
    }

    @Override
    public void onPagesEdited() {
        dirty = true;
        canvas.invalidateScene();
        updateHistory();
    }

    // =============== CanvasView.Listener ===============

    @Override
    public void onPageChanged() {
        if (homeBtn != null) homeBtn.setVisibility(canvas.isInfinite() ? View.VISIBLE : View.GONE);
        if (pageLabel != null) pageLabel.setText((canvas.pageIndex() + 1) + " / " + canvas.pageCount());
        if (layerPopup != null && layerPopup.isShowing()) refreshLayers();
        if (pagePanel != null) pagePanel.onPageChanged();
        updateHistory();
    }

    @Override
    public void onSelectionChanged(boolean has) {
        selWrap.setVisibility(has ? View.VISIBLE : View.GONE);
        if (editTextBtn != null) editTextBtn.setVisibility(canvas.singleSelectedText() != null ? View.VISIBLE : View.GONE);
        boolean tape = has && canvas.selectionHasTape();
        if (attachBtn != null) attachBtn.setVisibility(tape ? View.VISIBLE : View.GONE);
        if (detachBtn != null) detachBtn.setVisibility(tape ? View.VISIBLE : View.GONE);
        if (memoPatBtn != null) memoPatBtn.setVisibility(has && canvas.singleSelectedMemo() != null ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onHistoryChanged() {
        updateHistory();
    }

    private void updateHistory() {
        undoBtn.setAlpha(canvas.undo.canUndo() ? 1f : 0.35f);
        redoBtn.setAlpha(canvas.undo.canRedo() ? 1f : 0.35f);
        if (pageLabel != null) pageLabel.setText((canvas.pageIndex() + 1) + " / " + canvas.pageCount());
    }

    @Override
    public void onTextRequest(float x, float y, TextElem existing) {
        TextElem.Style base = existing != null ? existing.style : canvas.textStyle;
        TextTools.edit(this, existing == null ? "" : existing.text, base, existing != null, (text, st) -> {
            if (existing == null) {
                canvas.textStyle = st.copy();
                saveToolPrefs();
            }
            canvas.commitText(x, y, existing, text, st);
        });
    }

    @Override
    public void onZoomChanged(float scale) {
        if (zoomLabel == null) return;
        zoomLabel.setText(Math.round(scale / canvas.baseScale() * 100) + "%");
    }

    @Override
    public void onDocChanged() {
        dirty = true;
        if (layerPopup != null && layerPopup.isShowing()) refreshLayers();
        if (pagePanel != null) pagePanel.invalidatePage(canvas.page());
        if (pageLabel != null) pageLabel.setText((canvas.pageIndex() + 1) + " / " + canvas.pageCount());
    }

    // =============== 새 캔버스 콜백 ===============

    @Override
    public void onLongPressSelected() {
        if (canvas.tool != CanvasView.TOOL_LASSO) {
            int prev = canvas.tool;
            selectTool(CanvasView.TOOL_LASSO, false);
            returnTool = prev;
        }
    }

    @Override
    public void onSelectionEdited() {
        if (returnTool < 0) return;
        int t = returnTool;
        returnTool = -1;
        canvas.post(() -> {
            canvas.clearSelection();
            selectTool(t, false);
        });
    }

    @Override
    public void onColorPicked(int color) {
        applyColor(ColorPicker.withA(color, android.graphics.Color.alpha(currentColor())));
        ColorPicker.Palette.add(this, color);
        Toast.makeText(this, "색을 가져왔습니다 " + ColorPicker.hex(color), Toast.LENGTH_SHORT).show();
        updateStrip();
    }

    @Override
    public void onLockedLassoed(java.util.List<com.kjt.jiannote.model.Element> list) {
        new AlertDialog.Builder(this).setTitle("고정된 항목 " + list.size() + "개")
                .setMessage("올가미 안에 고정된 항목만 있습니다. 한 번에 고정을 해제하고 선택할까요?")
                .setNegativeButton("취소", null)
                .setPositiveButton("고정 해제하고 선택", (d, w) -> canvas.unlockAndSelect(list))
                .show();
    }

    @Override
    public void onLockedPressed(com.kjt.jiannote.model.Element e) {
        new AlertDialog.Builder(this).setTitle("고정된 항목")
                .setMessage("이 항목은 고정되어 있어 움직이거나 지울 수 없습니다. 고정을 해제할까요?")
                .setNegativeButton("취소", null)
                .setPositiveButton("고정 해제", (d, w) -> canvas.unlock(e))
                .show();
    }

    // =============== PenPresets.Host ===============

    @Override
    public PenPresets.Preset current(int kind) {
        if (kind == PenPresets.KIND_PEN) {
            return PenPresets.make(StrokeElem.PEN_NAMES[canvas.penType], canvas.penType, canvas.penWidth, canvas.penColor, canvas.penSquare);
        } else if (kind == PenPresets.KIND_HL) {
            return PenPresets.make("형광펜", StrokeElem.HIGHLIGHTER, canvas.hlWidth, canvas.hlColor, canvas.hlSquare);
        }
        int mode = canvas.eraserHlOnly ? 2 : canvas.eraserPartial ? 0 : 1;
        String[] names = {"부분 지우개", "획 지우개", "형광펜만 지우기"};
        return PenPresets.make(names[mode], mode, canvas.eraserSize, 0, false);
    }

    @Override
    public void apply(int kind, PenPresets.Preset p) {
        if (kind == PenPresets.KIND_PEN) {
            canvas.penType = p.type;
            canvas.penWidth = p.width;
            canvas.penColor = p.color;
            canvas.penSquare = p.square;
        } else if (kind == PenPresets.KIND_HL) {
            canvas.hlWidth = p.width;
            canvas.hlColor = p.color;
            canvas.hlSquare = p.square;
        } else {
            canvas.eraserSize = p.width;
            setEraserMode(p.type);
        }
        saveToolPrefs();
        updateDot();
        updateStrip();
    }

    @Override
    public void onLayerLimit() {
        Toast.makeText(this, "레이어를 더 추가할 수 없습니다", Toast.LENGTH_SHORT).show();
    }
}
