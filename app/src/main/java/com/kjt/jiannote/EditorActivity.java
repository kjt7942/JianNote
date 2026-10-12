package com.kjt.jiannote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
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
import com.kjt.jiannote.render.Paper;
import com.kjt.jiannote.render.RenderContext;
import com.kjt.jiannote.render.Renderer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/** 노트 편집 화면 */
public class EditorActivity extends Activity implements CanvasView.Listener {
    private static final int REQ_IMAGE = 11;

    private Store store;
    private Premium premium;
    private Prefs prefs;
    private NotebookMeta meta;
    private Document doc;
    private RenderContext rc;
    private CanvasView canvas;
    private boolean dirty;

    private final TextView[] toolBtns = new TextView[7];
    private TextView undoBtn, redoBtn, pageLabel, zoomLabel, fingerBtn, layerBtn;
    private LinearLayout selBar;
    private Ui.Dot widthDot;
    private final Ui.Swatch[] quick = new Ui.Swatch[5];
    private int[] quickColors = {0xFF1A1A1A, 0xFF2563EB, 0xFFDC2626, 0xFF16A34A, 0xFFF97316};
    private PopupWindow popup;
    private PopupWindow layerPopup;
    private LinearLayout layerList;

    private static final String[][] TOOLS = {
            {"✒️", "펜"}, {"🖍️", "형광펜"}, {"🧽", "지우개"}, {"➰", "올가미"},
            {"🔷", "도형"}, {"🔤", "텍스트"}, {"✋", "이동"}
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
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

        FrameLayout body = new FrameLayout(this);
        body.addView(canvas, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        selBar = buildSelectionBar();
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        slp.topMargin = Ui.dp(this, 12);
        body.addView(selBar, slp);
        selBar.setVisibility(View.GONE);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(Ui.divider(this, false));
        root.addView(buildBottomBar());
        setContentView(root);

        selectTool(canvas.tool);
        updateHistory();
        onPageChanged();
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
        canvas.textSize = prefs.f("textSize", 40f);
        canvas.fingerDraw = prefs.b("fingerDraw", false);
        canvas.pressureEnabled = prefs.b("pressure", true);
        canvas.pressureGamma = prefs.f("gamma", 1f);
        canvas.holdToShape = prefs.b("holdToShape", true);
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
        prefs.put("textSize", canvas.textSize);
        prefs.put("fingerDraw", canvas.fingerDraw);
        prefs.put("pressure", canvas.pressureEnabled);
        prefs.put("gamma", canvas.pressureGamma);
        prefs.put("holdToShape", canvas.holdToShape);
    }

    // =============== 상단 도구 막대 ===============

    private View buildTopBar() {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setBackgroundColor(Ui.SURFACE);
        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4));
        hs.addView(bar);

        TextView back = Ui.toolButton(this, "←", null);
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        TextView title = Ui.label(this, meta.title, 15, Ui.TEXT, true);
        title.setMaxWidth(Ui.dp(this, 160));
        title.setSingleLine(true);
        title.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 8), 0);
        title.setOnClickListener(v -> Dialogs.input(this, "노트 이름", meta.title, false, s -> {
            if (s.trim().isEmpty()) return;
            meta.title = s.trim();
            title.setText(meta.title);
            dirty = true;
        }));
        bar.addView(title);
        bar.addView(Ui.divider(this, true));

        for (int i = 0; i < TOOLS.length; i++) {
            TextView t = Ui.toolButton(this, TOOLS[i][0], TOOLS[i][1]);
            final int tool = i;
            t.setOnClickListener(v -> {
                if (canvas.tool == tool && tool != CanvasView.TOOL_HAND) showToolOptions(v);
                else selectTool(tool);
            });
            toolBtns[i] = t;
            bar.addView(t);
        }
        TextView img = Ui.toolButton(this, "🖼️", "이미지");
        img.setOnClickListener(v -> pickImage());
        bar.addView(img);
        bar.addView(Ui.divider(this, true));

        for (int i = 0; i < 5; i++) {
            Ui.Swatch s = new Ui.Swatch(this, quickColors[i]);
            final int slot = i;
            s.setOnClickListener(v -> applyColor(quickColors[slot]));
            s.setOnLongClickListener(v -> {
                colorPicker(c -> {
                    quickColors[slot] = c;
                    prefs.put("quick" + slot, c);
                    rebuildQuick(slot, c);
                    applyColor(c);
                });
                return true;
            });
            quick[i] = s;
            bar.addView(s, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));
        }
        widthDot = new Ui.Dot(this, 8, canvas.penColor);
        widthDot.setOnClickListener(this::showToolOptions);
        bar.addView(widthDot, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        bar.addView(Ui.divider(this, true));

        undoBtn = Ui.toolButton(this, "↶", "실행취소");
        undoBtn.setOnClickListener(v -> canvas.undo());
        bar.addView(undoBtn);
        redoBtn = Ui.toolButton(this, "↷", "다시실행");
        redoBtn.setOnClickListener(v -> canvas.redo());
        bar.addView(redoBtn);
        bar.addView(Ui.divider(this, true));

        layerBtn = Ui.toolButton(this, "📚", "레이어");
        layerBtn.setOnClickListener(this::showLayers);
        bar.addView(layerBtn);
        fingerBtn = Ui.toolButton(this, "☝️", "손가락");
        fingerBtn.setOnClickListener(v -> {
            canvas.fingerDraw = !canvas.fingerDraw;
            updateFinger();
            Toast.makeText(this, canvas.fingerDraw ? "손가락 필기 켜짐 (두 손가락으로 이동)" : "S펜으로만 필기 (손가락은 이동/확대)", Toast.LENGTH_SHORT).show();
        });
        bar.addView(fingerBtn);
        updateFinger();
        TextView more = Ui.toolButton(this, "⋯", "더보기");
        more.setOnClickListener(this::showMenu);
        bar.addView(more);
        return hs;
    }

    private void rebuildQuick(int slot, int color) {
        Ui.Swatch old = quick[slot];
        ViewGroup parent = (ViewGroup) old.getParent();
        int idx = parent.indexOfChild(old);
        Ui.Swatch s = new Ui.Swatch(this, color);
        s.setOnClickListener(v -> applyColor(quickColors[slot]));
        s.setOnLongClickListener(v -> {
            colorPicker(c -> {
                quickColors[slot] = c;
                prefs.put("quick" + slot, c);
                rebuildQuick(slot, c);
                applyColor(c);
            });
            return true;
        });
        parent.removeViewAt(idx);
        parent.addView(s, idx, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));
        quick[slot] = s;
    }

    private void updateFinger() {
        Ui.setSelected(fingerBtn, canvas.fingerDraw);
    }

    private void selectTool(int tool) {
        canvas.tool = tool;
        if (tool != CanvasView.TOOL_LASSO) canvas.clearSelection();
        for (int i = 0; i < toolBtns.length; i++) Ui.setSelected(toolBtns[i], i == tool);
        updateDot();
        canvas.invalidate();
    }

    private int currentColor() {
        return canvas.tool == CanvasView.TOOL_HIGHLIGHTER ? canvas.hlColor : canvas.penColor;
    }

    private void applyColor(int c) {
        if (canvas.hasSelection()) {
            canvas.recolorSelection(c);
            return;
        }
        if (canvas.tool == CanvasView.TOOL_HIGHLIGHTER) canvas.hlColor = c;
        else {
            canvas.penColor = c;
            if (canvas.tool == CanvasView.TOOL_ERASER || canvas.tool == CanvasView.TOOL_HAND || canvas.tool == CanvasView.TOOL_LASSO) {
                selectTool(CanvasView.TOOL_PEN);
            }
        }
        updateDot();
    }

    private void updateDot() {
        float d = getResources().getDisplayMetrics().density;
        float w;
        switch (canvas.tool) {
            case CanvasView.TOOL_HIGHLIGHTER: w = canvas.hlWidth * 0.6f; break;
            case CanvasView.TOOL_ERASER: w = canvas.eraserSize; break;
            case CanvasView.TOOL_TEXT: w = canvas.textSize * 0.4f; break;
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
        String[][] items = {{"🗑️", "삭제"}, {"📄", "복제"}, {"🎨", "색상"}, {"📚", "레이어로"}, {"✖", "해제"}};
        for (int i = 0; i < items.length; i++) {
            TextView t = Ui.toolButton(this, items[i][0], items[i][1]);
            t.setTextColor(Color.WHITE);
            final int k = i;
            t.setOnClickListener(v -> {
                switch (k) {
                    case 0: canvas.deleteSelection(); break;
                    case 1: canvas.duplicateSelection(); break;
                    case 2: colorPicker(c -> canvas.recolorSelection(c)); break;
                    case 3: chooseLayerForSelection(); break;
                    default: canvas.clearSelection(); break;
                }
            });
            bar.addView(t);
        }
        return bar;
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
            TextView home = Ui.toolButton(this, "🎯 처음 위치로", null);
            home.setTextSize(14);
            home.setOnClickListener(v -> canvas.resetInfiniteView());
            bar.addView(home);
            TextView tip = Ui.label(this, "   ∞ 무한 캔버스 — 한 손가락으로 이동, 두 손가락으로 확대/축소   ", 13, Ui.SUBTEXT, false);
            bar.addView(tip);
        } else {
            TextView prev = Ui.toolButton(this, "◀", null);
            prev.setOnClickListener(v -> canvas.goToPage(canvas.pageIndex() - 1));
            bar.addView(prev);
            pageLabel = Ui.label(this, "1 / 1", 14, Ui.TEXT, true);
            pageLabel.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
            pageLabel.setOnClickListener(v -> showPageGrid());
            bar.addView(pageLabel);
            TextView next = Ui.toolButton(this, "▶", null);
            next.setOnClickListener(v -> {
                if (canvas.pageIndex() == canvas.pageCount() - 1) addPage();
                else canvas.goToPage(canvas.pageIndex() + 1);
            });
            bar.addView(next);
            bar.addView(Ui.divider(this, true));
            TextView add = Ui.toolButton(this, "＋ 페이지", null);
            add.setTextSize(14);
            add.setOnClickListener(v -> addPage());
            bar.addView(add);
            TextView grid = Ui.toolButton(this, "📑 페이지 목록", null);
            grid.setTextSize(14);
            grid.setOnClickListener(v -> showPageGrid());
            bar.addView(grid);
            TextView pm = Ui.toolButton(this, "⋯ 페이지", null);
            pm.setTextSize(14);
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

    private void addPage() {
        Page cur = canvas.page();
        Page p = Page.create(cur.w, cur.h, cur.template);
        canvas.addPage(p, canvas.pageIndex() + 1);
    }

    private void showPageMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "현재 페이지 뒤에 새 페이지");
        pm.getMenu().add(0, 2, 0, "현재 페이지 복제");
        pm.getMenu().add(0, 3, 0, "페이지 앞으로 이동");
        pm.getMenu().add(0, 4, 0, "페이지 뒤로 이동");
        pm.getMenu().add(0, 5, 0, "이 페이지 속지 변경");
        pm.getMenu().add(0, 6, 0, "현재 페이지 삭제");
        pm.setOnMenuItemClickListener(item -> {
            int i = canvas.pageIndex();
            switch (item.getItemId()) {
                case 1: addPage(); break;
                case 2: canvas.addPage(canvas.page().copy(), i + 1); break;
                case 3: canvas.movePage(i, i - 1); break;
                case 4: canvas.movePage(i, i + 1); break;
                case 5: chooseTemplateForPage(); break;
                case 6: confirmDeletePage(i); break;
                default: break;
            }
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
                .setPositiveButton("삭제", (d, w) -> canvas.deletePage(i))
                .show();
    }

    private void chooseTemplateForPage() {
        String[] names = new String[Paper.COUNT];
        for (int i = 0; i < Paper.COUNT; i++) {
            names[i] = (Paper.isPremium(i) && !premium.isPremium() ? "👑 " : "") + Paper.NAMES[i];
        }
        new AlertDialog.Builder(this).setTitle("속지 선택")
                .setItems(names, (d, w) -> {
                    if (Paper.isPremium(w) && !premium.isPremium()) {
                        Dialogs.limit(this, premium, "'" + Paper.NAMES[w] + "' 속지는 프리미엄 전용입니다.", null);
                        return;
                    }
                    canvas.page().template = w;
                    dirty = true;
                    canvas.invalidateScene();
                })
                .show();
    }

    private void showPageGrid() {
        GridView g = new GridView(this);
        g.setNumColumns(GridView.AUTO_FIT);
        g.setColumnWidth(Ui.dp(this, 130));
        g.setVerticalSpacing(Ui.dp(this, 12));
        g.setHorizontalSpacing(Ui.dp(this, 12));
        g.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        final java.util.HashMap<Integer, Bitmap> cache = new java.util.HashMap<>();
        AlertDialog dlg = new AlertDialog.Builder(this).setTitle("페이지 (" + canvas.pageCount() + ")")
                .setView(g).setNegativeButton("닫기", null).create();
        g.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() { return canvas.pageCount(); }

            @Override
            public Object getItem(int i) { return i; }

            @Override
            public long getItemId(int i) { return i; }

            @Override
            public View getView(int pos, View convert, ViewGroup parent) {
                LinearLayout box = Ui.col(EditorActivity.this);
                box.setGravity(Gravity.CENTER_HORIZONTAL);
                ImageView iv = new ImageView(EditorActivity.this);
                Bitmap bm = cache.get(pos);
                if (bm == null) {
                    bm = Renderer.thumbnail(doc.pages.get(pos), meta.paperColor, rc, 240, 340);
                    cache.put(pos, bm);
                }
                iv.setImageBitmap(bm);
                iv.setAdjustViewBounds(true);
                int pad = Ui.dp(EditorActivity.this, 3);
                iv.setPadding(pad, pad, pad, pad);
                iv.setBackground(Ui.round(pos == canvas.pageIndex() ? Ui.ACCENT : 0x22000000, Ui.dp(EditorActivity.this, 4)));
                box.addView(iv, new LinearLayout.LayoutParams(Ui.dp(EditorActivity.this, 120), ViewGroup.LayoutParams.WRAP_CONTENT));
                box.addView(Ui.label(EditorActivity.this, String.valueOf(pos + 1), 12, Ui.SUBTEXT, false));
                return box;
            }
        });
        g.setOnItemClickListener((p, v, pos, id) -> {
            canvas.goToPage(pos);
            dlg.dismiss();
        });
        dlg.show();
    }

    // =============== 더보기 메뉴 ===============

    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "📄 PDF로 내보내기" + (premium.isPremium() ? "" : " (워터마크)"));
        pm.getMenu().add(0, 2, 0, "🖼️ 현재 페이지를 이미지로 저장");
        pm.getMenu().add(0, 3, 0, "📃 이 페이지 속지 변경");
        pm.getMenu().add(0, 4, 0, "🎨 종이 색 변경");
        pm.getMenu().add(0, 5, 0, "✒️ 필압 / 펜 설정");
        pm.getMenu().add(0, 6, 0, "👑 프리미엄");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: exportPdf(); break;
                case 2: exportPng(); break;
                case 3: chooseTemplateForPage(); break;
                case 4: choosePaper(); break;
                case 5: selectTool(CanvasView.TOOL_PEN); showToolOptions(toolBtns[0]); break;
                case 6: Dialogs.premium(this, premium, null); break;
                default: break;
            }
            return true;
        });
        pm.show();
    }

    private void choosePaper() {
        new AlertDialog.Builder(this).setTitle("종이 색")
                .setItems(Paper.PAPER_NAMES, (d, w) -> {
                    meta.paperColor = Paper.PAPER_COLORS[w];
                    canvas.setPaperColor(meta.paperColor);
                    dirty = true;
                }).show();
    }

    private void exportPdf() {
        save();
        try {
            Uri u = Exporter.exportPdf(this, meta, doc, rc, !premium.isPremium());
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
        box.setBackground(Ui.round(Ui.SURFACE, Ui.dp(this, 16)));
        switch (canvas.tool) {
            case CanvasView.TOOL_PEN: penOptions(box); break;
            case CanvasView.TOOL_HIGHLIGHTER: hlOptions(box); break;
            case CanvasView.TOOL_ERASER: eraserOptions(box); break;
            case CanvasView.TOOL_SHAPE: shapeOptions(box); break;
            case CanvasView.TOOL_TEXT: textOptions(box); break;
            case CanvasView.TOOL_LASSO:
                box.addView(Ui.label(this, "올가미로 영역을 둘러 선택하세요.\n• 선택 상자 안을 끌면 이동\n• 오른쪽 아래 파란 점을 끌면 크기 조절\n• 위쪽 막대에서 삭제·복제·색상·레이어 이동", 14, Ui.TEXT, false));
                break;
            default: return;
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        popup = new PopupWindow(sv, Ui.dp(this, 360), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setElevation(Ui.dp(this, 12));
        popup.setBackgroundDrawable(Ui.round(Ui.SURFACE, Ui.dp(this, 16)));
        popup.setOutsideTouchable(true);
        popup.setOnDismissListener(this::saveToolPrefs);
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
                for (int j = 0; j < 3; j++) markPill(btns[j], vals[j] == v);
                prev.invalidate();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
            types.addView(t, lp);
            markPill(t, canvas.penType == v);
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
        box.addView(colorGrid(Ui.PEN_COLORS, c -> {
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
        box.addView(colorGrid(Ui.HL_COLORS, c -> {
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
        markPill(part, canvas.eraserPartial);
        markPill(whole, !canvas.eraserPartial);
        part.setOnClickListener(v -> {
            canvas.eraserPartial = true;
            markPill(part, true);
            markPill(whole, false);
        });
        whole.setOnClickListener(v -> {
            canvas.eraserPartial = false;
            markPill(part, false);
            markPill(whole, true);
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
                for (int j = 0; j < btns.length; j++) markPill(btns[j], j == s);
            });
            markPill(t, canvas.shapeType == i);
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
        box.addView(colorGrid(Ui.PEN_COLORS, c -> {
            canvas.penColor = c;
            updateDot();
        }));
    }

    private void textOptions(LinearLayout box) {
        box.addView(Ui.label(this, "텍스트를 넣을 위치를 탭하세요. 기존 텍스트를 탭하면 수정합니다.", 13, Ui.TEXT, false));
        TextView wl = title("글자 크기: " + fmt(canvas.textSize));
        box.addView(wl);
        box.addView(Ui.seek(this, 104, (int) canvas.textSize - 16, v -> {
            canvas.textSize = v + 16;
            wl.setText("글자 크기: " + fmt(canvas.textSize));
            updateDot();
        }));
        box.addView(title("색상"));
        box.addView(colorGrid(Ui.PEN_COLORS, c -> {
            canvas.penColor = c;
            updateDot();
        }));
    }

    private static String fmt(float f) {
        return String.valueOf(Math.round(f));
    }

    private void markPill(TextView t, boolean on) {
        t.setBackground(on ? Ui.ripple(Ui.ACCENT, Ui.dp(this, 22)) : Ui.ripple(0xFFECE8DF, Ui.dp(this, 22)));
        t.setTextColor(on ? Color.WHITE : Ui.TEXT);
    }

    private View colorGrid(int[] colors, Ui.IntCb cb) {
        GridLayout g = new GridLayout(this);
        g.setColumnCount(8);
        int sz = Ui.dp(this, 38);
        final Ui.Swatch[] sws = new Ui.Swatch[colors.length + 1];
        int cur = currentColor();
        for (int i = 0; i < colors.length; i++) {
            Ui.Swatch s = new Ui.Swatch(this, colors[i]);
            s.setChosen(colors[i] == cur);
            sws[i] = s;
            final int c = colors[i];
            s.setOnClickListener(v -> {
                for (Ui.Swatch o : sws) if (o != null) o.setChosen(o == s);
                cb.on(c);
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = sz;
            lp.height = sz;
            g.addView(s, lp);
        }
        TextView custom = Ui.label(this, "＋", 20, Ui.SUBTEXT, true);
        custom.setGravity(Gravity.CENTER);
        custom.setOnClickListener(v -> colorPicker(c -> {
            cb.on(c);
            for (Ui.Swatch o : sws) if (o != null) o.setChosen(false);
        }));
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = sz;
        lp.height = sz;
        g.addView(custom, lp);
        return g;
    }

    /** RGB 색상 선택 */
    private void colorPicker(Ui.IntCb cb) {
        final int[] rgb = {Color.red(currentColor()), Color.green(currentColor()), Color.blue(currentColor())};
        LinearLayout box = Ui.col(this);
        int p = Ui.dp(this, 20);
        box.setPadding(p, p, p, 0);
        View preview = new View(this);
        preview.setBackground(Ui.round(Color.rgb(rgb[0], rgb[1], rgb[2]), Ui.dp(this, 12)));
        box.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56)));
        GridLayout presets = new GridLayout(this);
        presets.setColumnCount(8);
        String[] names = {"빨강 R", "초록 G", "파랑 B"};
        SeekBar[] bars = new SeekBar[3];
        for (int i = 0; i < 3; i++) {
            box.addView(title(names[i]));
            final int k = i;
            bars[i] = Ui.seek(this, 255, rgb[i], v -> {
                rgb[k] = v;
                preview.setBackground(Ui.round(Color.rgb(rgb[0], rgb[1], rgb[2]), Ui.dp(this, 12)));
            });
            box.addView(bars[i]);
        }
        box.addView(title("기본 색"));
        for (int c : Ui.PEN_COLORS) {
            Ui.Swatch s = new Ui.Swatch(this, c);
            s.setOnClickListener(v -> {
                rgb[0] = Color.red(c);
                rgb[1] = Color.green(c);
                rgb[2] = Color.blue(c);
                for (int i = 0; i < 3; i++) bars[i].setProgress(rgb[i]);
                preview.setBackground(Ui.round(c, Ui.dp(this, 12)));
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = Ui.dp(this, 36);
            lp.height = Ui.dp(this, 36);
            presets.addView(s, lp);
        }
        box.addView(presets);
        new AlertDialog.Builder(this).setTitle("색상 선택").setView(box)
                .setNegativeButton("취소", null)
                .setPositiveButton("확인", (d, w) -> cb.on(Color.rgb(rgb[0], rgb[1], rgb[2])))
                .show();
    }

    /** 펜 미리보기 (필압 변화 시뮬레이션) */
    private class StrokePreview extends View {
        StrokePreview() {
            super(EditorActivity.this);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            Paint bg = new Paint();
            bg.setColor(0xFFF7F5F0);
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
        head.addView(Ui.label(this, "레이어", 16, Ui.TEXT, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView add = Ui.pill(this, "＋ 추가", true);
        add.setTextSize(13);
        add.setOnClickListener(v -> {
            if (canvas.addLayer(premium.layerLimit())) refreshLayers();
        });
        head.addView(add);
        box.addView(head);
        if (!premium.isPremium()) {
            box.addView(Ui.label(this, "무료: 페이지당 레이어 " + Premium.FREE_LAYERS + "개 · 👑 프리미엄: " + Premium.MAX_LAYERS + "개", 11, Ui.SUBTEXT, false));
        }
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
            TextView eye = Ui.toolButton(this, l.visible ? "👁" : "🚫", null);
            eye.setTextSize(16);
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
            TextView up = Ui.toolButton(this, "▲", null);
            up.setTextSize(13);
            up.setOnClickListener(v -> {
                canvas.moveLayer(i, 1);
                refreshLayers();
            });
            r.addView(up);
            TextView down = Ui.toolButton(this, "▼", null);
            down.setTextSize(13);
            down.setOnClickListener(v -> {
                canvas.moveLayer(i, -1);
                refreshLayers();
            });
            r.addView(down);
            TextView menu = Ui.toolButton(this, "⋯", null);
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
        if (popup != null) popup.dismiss();
        if (layerPopup != null) layerPopup.dismiss();
        if (canvas != null) saveToolPrefs();
        if (dirty) save();
    }

    // =============== CanvasView.Listener ===============

    @Override
    public void onPageChanged() {
        if (pageLabel != null) pageLabel.setText((canvas.pageIndex() + 1) + " / " + canvas.pageCount());
        refreshLayers();
        updateHistory();
    }

    @Override
    public void onSelectionChanged(boolean has) {
        selBar.setVisibility(has ? View.VISIBLE : View.GONE);
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
        Dialogs.input(this, existing == null ? "텍스트 입력" : "텍스트 수정 (비우면 삭제)",
                existing == null ? "" : existing.text, true, s -> canvas.commitText(x, y, existing, s));
    }

    @Override
    public void onZoomChanged(float scale) {
        if (zoomLabel == null) return;
        float base = meta.infinite ? 1f : (canvas.getWidth() - 32 * getResources().getDisplayMetrics().density) / canvas.page().w;
        zoomLabel.setText(Math.round(scale / base * 100) + "%");
    }

    @Override
    public void onDocChanged() {
        dirty = true;
        if (layerPopup != null && layerPopup.isShowing()) refreshLayers();
    }

    @Override
    public void onLayerLimit() {
        Dialogs.limit(this, premium, "무료 버전에서는 페이지당 레이어를 " + Premium.FREE_LAYERS + "개까지 사용할 수 있습니다.", null);
    }
}
