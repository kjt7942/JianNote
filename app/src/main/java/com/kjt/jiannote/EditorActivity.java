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
public class EditorActivity extends Activity implements CanvasView.Listener, PagePanel.Host {
    private static final int REQ_IMAGE = 11;

    private Store store;
    private Premium premium;
    private Prefs prefs;
    private NotebookMeta meta;
    private Document doc;
    private RenderContext rc;
    private CanvasView canvas;
    private PagePanel pagePanel;
    private boolean dirty;

    private final TextView[] toolBtns = new TextView[7];
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
            R.drawable.ic_shapes, R.drawable.ic_text_fields, R.drawable.ic_pan_tool
    };
    private static final String[] TOOL_NAMES = {"펜", "형광펜", "지우개", "올가미 선택", "도형", "텍스트", "이동(스크롤)"};
    private LinearLayout strip;
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
        root.addView(buildTopBar());
        root.addView(Ui.divider(this, false));
        root.addView(buildStrip());
        root.addView(Ui.divider(this, false));

        FrameLayout body = new FrameLayout(this);
        body.addView(canvas, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        selBar = buildSelectionBar();
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        slp.topMargin = Ui.dp(this, 12);
        body.addView(selBar, slp);
        selBar.setVisibility(View.GONE);
        pagePanel = new PagePanel(this, canvas, rc, this);
        body.addView(pagePanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(Ui.divider(this, false));
        root.addView(buildBottomBar());
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
        TextView back = icon(R.drawable.ic_arrow_back, "서재로");
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        TextView title = Ui.label(this, meta.title, 15, Ui.TEXT, true);
        title.setMaxWidth(Ui.dp(this, 150));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 6), 0);
        title.setOnClickListener(v -> Dialogs.input(this, "노트 이름", meta.title, false, s -> {
            if (s.trim().isEmpty()) return;
            meta.title = s.trim();
            title.setText(meta.title);
            dirty = true;
        }));
        bar.addView(title);
        undoBtn = icon(R.drawable.ic_undo, "실행취소");
        undoBtn.setOnClickListener(v -> canvas.undo());
        bar.addView(undoBtn);
        redoBtn = icon(R.drawable.ic_redo, "다시실행");
        redoBtn.setOnClickListener(v -> canvas.redo());
        bar.addView(redoBtn);
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        // 가운데: 그리기 도구
        for (int i = 0; i < TOOL_ICONS.length; i++) {
            TextView t = icon(TOOL_ICONS[i], TOOL_NAMES[i]);
            final int tool = i;
            t.setOnClickListener(v -> {
                if (canvas.tool == tool && tool != CanvasView.TOOL_HAND) showToolOptions(v);
                else selectTool(tool);
            });
            toolBtns[i] = t;
            if (i != CanvasView.TOOL_HAND) bar.addView(t);
        }
        TextView img = icon(R.drawable.ic_image, "이미지 넣기");
        img.setOnClickListener(v -> pickImage());
        bar.addView(img);
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        // 오른쪽: 레이어 · 손가락 · 이동 · 더보기
        layerBtn = icon(R.drawable.ic_layers, "레이어");
        layerBtn.setOnClickListener(this::showLayers);
        bar.addView(layerBtn);
        fingerBtn = icon(R.drawable.ic_touch_app, "손가락으로 그리기");
        fingerBtn.setOnClickListener(v -> {
            canvas.fingerDraw = !canvas.fingerDraw;
            updateFinger();
            Toast.makeText(this, canvas.fingerDraw ? "손가락 필기 켜짐 (두 손가락으로 스크롤)" : "S펜으로만 필기 (손가락은 스크롤/확대)", Toast.LENGTH_SHORT).show();
        });
        bar.addView(fingerBtn);
        bar.addView(toolBtns[CanvasView.TOOL_HAND]);
        updateFinger();
        TextView more = icon(R.drawable.ic_more_vert, "더보기");
        more.setOnClickListener(this::showMenu);
        bar.addView(more);
        return hs;
    }

    /** 도구 아래의 빠른 설정 줄: 즐겨찾는 색 5개 + 굵기 3단계 + 상세 설정 */
    private View buildStrip() {
        strip = Ui.row(this);
        strip.setGravity(Gravity.CENTER);
        strip.setPadding(Ui.dp(this, 10), Ui.dp(this, 2), Ui.dp(this, 10), Ui.dp(this, 2));
        LinearLayout wrap = Ui.row(this);
        wrap.setGravity(Gravity.CENTER);
        wrap.setBackgroundColor(0xFFFAF9F6);
        wrap.addView(strip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 46)));
        for (int i = 0; i < 5; i++) quick[i] = makeQuick(i, quickColors[i]);
        widthDot = new Ui.Dot(this, 8, canvas.penColor);
        widthDot.setOnClickListener(this::showToolOptions);
        widthDot.setTooltipText("굵기·색상 상세 설정");
        return wrap;
    }

    private static final float[][] WIDTH_PRESETS = {
            {2, 4, 8}, {16, 26, 44}, {14, 28, 56}, {}, {2, 4, 8}, {28, 40, 64}, {}
    };

    private void updateStrip() {
        if (strip == null) return;
        strip.removeAllViews();
        int tool = canvas.tool;
        if (tool == CanvasView.TOOL_LASSO || tool == CanvasView.TOOL_HAND) {
            String hint = tool == CanvasView.TOOL_LASSO
                    ? "펜으로 영역을 둘러 선택 → 끌어서 이동, 오른쪽 아래 점으로 크기 조절"
                    : "손가락·펜으로 페이지를 스크롤하고 두 손가락으로 확대/축소";
            strip.addView(Ui.label(this, hint, 13, Ui.SUBTEXT, false));
            return;
        }
        if (tool != CanvasView.TOOL_ERASER) {
            for (Ui.Swatch q : quick) {
                if (q.getParent() != null) ((ViewGroup) q.getParent()).removeView(q);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36));
                strip.addView(q, lp);
            }
            TextView pal = icon(R.drawable.ic_palette, "색상 선택기");
            pal.setOnClickListener(v -> ColorPicker.show(this, currentColor(), this::applyColor));
            strip.addView(pal);
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
            strip.addView(d, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        }
        if (widthDot.getParent() != null) ((ViewGroup) widthDot.getParent()).removeView(widthDot);
        TextView opts = icon(R.drawable.ic_more_vert, "상세 설정");
        opts.setOnClickListener(this::showToolOptions);
        strip.addView(Ui.divider(this, true));
        TextView label = Ui.label(this, TOOL_NAMES[tool] + " 설정", 13, Ui.ACCENT, true);
        label.setPadding(Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8));
        label.setOnClickListener(this::showToolOptions);
        strip.addView(label);
    }

    private float currentWidth() {
        switch (canvas.tool) {
            case CanvasView.TOOL_HIGHLIGHTER: return canvas.hlWidth;
            case CanvasView.TOOL_ERASER: return canvas.eraserSize;
            case CanvasView.TOOL_TEXT: return canvas.textStyle.size;
            default: return canvas.penWidth;
        }
    }

    private void setCurrentWidth(float w) {
        switch (canvas.tool) {
            case CanvasView.TOOL_HIGHLIGHTER: canvas.hlWidth = w; break;
            case CanvasView.TOOL_ERASER: canvas.eraserSize = w; break;
            case CanvasView.TOOL_TEXT: canvas.textStyle.size = w; break;
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
        int[] icons = {R.drawable.ic_edit, R.drawable.ic_delete, R.drawable.ic_content_copy, R.drawable.ic_palette, R.drawable.ic_layers, R.drawable.ic_close};
        String[] labels = {"글자편집", "삭제", "복제", "색상", "레이어로", "해제"};
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
                    default: canvas.clearSelection(); break;
                }
            });
            if (i == 0) editTextBtn = t;
            bar.addView(t);
        }
        return bar;
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
        bar.setBackgroundColor(Ui.SURFACE);
        bar.setPadding(Ui.dp(this, 8), Ui.dp(this, 2), Ui.dp(this, 8), Ui.dp(this, 2));
        bar.setGravity(Gravity.CENTER);
        if (meta.infinite) {
            TextView home = Ui.iconText(this, R.drawable.ic_my_location, "처음 위치로", Ui.TEXT);
            home.setOnClickListener(v -> canvas.resetInfiniteView());
            bar.addView(home);
            bar.addView(Ui.label(this, "   ∞ 무한 캔버스 — 한 손가락으로 이동, 두 손가락으로 확대/축소   ", 13, Ui.SUBTEXT, false));
        } else {
            TextView prev = icon(R.drawable.ic_keyboard_arrow_up, "이전 페이지");
            prev.setOnClickListener(v -> canvas.goToPage(canvas.pageIndex() - 1));
            bar.addView(prev);
            pageLabel = Ui.label(this, "1 / 1", 14, Ui.TEXT, true);
            pageLabel.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
            pageLabel.setOnClickListener(v -> togglePanel());
            bar.addView(pageLabel);
            TextView next = icon(R.drawable.ic_keyboard_arrow_down, "다음 페이지");
            next.setOnClickListener(v -> canvas.goToPage(canvas.pageIndex() + 1));
            bar.addView(next);
            bar.addView(Ui.divider(this, true));
            TextView add = Ui.iconText(this, R.drawable.ic_add, "페이지 추가", Ui.TEXT);
            add.setOnClickListener(v -> addPage());
            bar.addView(add);
            TextView panel = Ui.iconText(this, R.drawable.ic_view_agenda, "페이지 보기", Ui.TEXT);
            panel.setOnClickListener(v -> togglePanel());
            bar.addView(panel);
            TextView pm = Ui.iconText(this, R.drawable.ic_more_vert, "페이지 메뉴", Ui.TEXT);
            pm.setOnClickListener(this::showPageMenu);
            bar.addView(pm);
        }
        bar.addView(Ui.divider(this, true));
        zoomLabel = Ui.label(this, "100%", 13, Ui.SUBTEXT, false);
        zoomLabel.setPadding(Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10));
        zoomLabel.setOnClickListener(v -> canvas.fitPage());
        bar.addView(zoomLabel);
        return bar;
    }

    private void togglePanel() {
        if (pagePanel.isOpen()) pagePanel.hide();
        else pagePanel.show();
    }

    private void addPage() {
        Page cur = canvas.page();
        canvas.addPage(Page.create(cur.w, cur.h, cur.template), canvas.pageIndex() + 1);
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
        boolean land = !cur.isInfinite() && cur.w > cur.h;
        Dialogs.templatePicker(this, premium, meta.paperColor, land, cur.template, !meta.infinite && canvas.pageCount() > 1,
                (t, all) -> {
                    if (all) {
                        for (Page p : doc.pages) p.template = t;
                    } else {
                        cur.template = t;
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
        try {
            Uri u = Exporter.exportPdf(this, meta, doc, rc, false);
            new AlertDialog.Builder(this).setTitle("PDF 저장 완료")
                    .setMessage("다운로드/JianNote 폴더에 저장했습니다.")
                    .setNegativeButton("확인", null)
                    .setPositiveButton("공유", (d, w) -> Exporter.share(this, u, "application/pdf"))
                    .show();
        } catch (Exception e) {
            Toast.makeText(this, "PDF 저장 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
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
            case CanvasView.TOOL_PEN: penOptions(box); break;
            case CanvasView.TOOL_HIGHLIGHTER: hlOptions(box); break;
            case CanvasView.TOOL_ERASER: eraserOptions(box); break;
            case CanvasView.TOOL_SHAPE: shapeOptions(box); break;
            case CanvasView.TOOL_TEXT:
                width = 440;
                box.addView(Ui.label(this, "텍스트를 넣을 곳을 탭하세요. 기존 텍스트를 탭하면 수정합니다.\n아래 서식은 새로 입력하는 텍스트에 적용됩니다.", 13, Ui.TEXT, false));
                box.addView(TextTools.controls(this, canvas.textStyle, this::updateDot));
                break;
            case CanvasView.TOOL_LASSO:
                box.addView(Ui.label(this, "올가미로 영역을 둘러 선택하세요.\n• 선택 상자 안을 끌면 이동\n• 오른쪽 아래 파란 점을 끌면 크기 조절\n• 위쪽 막대에서 글자편집·삭제·복제·색상·레이어 이동", 14, Ui.TEXT, false));
                break;
            default: return;
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.78f);
        popup = new PopupWindow(sv, Ui.dp(this, width), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        sv.measure(View.MeasureSpec.makeMeasureSpec(Ui.dp(this, width), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
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
        LinearLayout types = Ui.row(this);
        String[] names = {"볼펜", "만년필", "붓펜"};
        int[] vals = {StrokeElem.PEN, StrokeElem.FOUNTAIN, StrokeElem.BRUSH};
        TextView[] btns = new TextView[3];
        for (int i = 0; i < 3; i++) {
            TextView t = Ui.pill(this, names[i], false);
            final int v = vals[i];
            btns[i] = t;
            t.setOnClickListener(x -> {
                canvas.penType = v;
                for (int j = 0; j < 3; j++) TextTools.markPill(this, btns[j], vals[j] == v);
                prev.invalidate();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
            types.addView(t, lp);
            TextTools.markPill(this, t, canvas.penType == v);
        }
        box.addView(types);
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
        box.addView(title("색상"));
        box.addView(ColorPicker.grid(this, Ui.HL_COLORS, canvas.hlColor, c -> {
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
        TextView part = Ui.pill(this, "부분 지우개", false);
        TextView whole = Ui.pill(this, "획 지우개", false);
        TextTools.markPill(this, part, canvas.eraserPartial);
        TextTools.markPill(this, whole, !canvas.eraserPartial);
        part.setOnClickListener(v -> {
            canvas.eraserPartial = true;
            TextTools.markPill(this, part, true);
            TextTools.markPill(this, whole, false);
        });
        whole.setOnClickListener(v -> {
            canvas.eraserPartial = false;
            TextTools.markPill(this, part, false);
            TextTools.markPill(this, whole, true);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
        r.addView(part, lp);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp2.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
        r.addView(whole, lp2);
        box.addView(r);
        box.addView(Ui.label(this, "획 지우개는 텍스트·이미지도 지웁니다. S펜 측면 버튼을 누른 채 쓰면 언제든 지우개가 됩니다.", 12, Ui.SUBTEXT, false));
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
        layerPopup = new PopupWindow(sv, Ui.dp(this, 360), ViewGroup.LayoutParams.WRAP_CONTENT, true);
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
        if (pageLabel != null) pageLabel.setText((canvas.pageIndex() + 1) + " / " + canvas.pageCount());
        if (layerPopup != null && layerPopup.isShowing()) refreshLayers();
        if (pagePanel != null) pagePanel.onPageChanged();
        updateHistory();
    }

    @Override
    public void onSelectionChanged(boolean has) {
        selBar.setVisibility(has ? View.VISIBLE : View.GONE);
        if (editTextBtn != null) editTextBtn.setVisibility(canvas.singleSelectedText() != null ? View.VISIBLE : View.GONE);
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

    @Override
    public void onLayerLimit() {
        Dialogs.limit(this, premium, "레이어는 페이지당 " + Premium.MAX_LAYERS + "개까지 만들 수 있습니다.", null);
    }
}
