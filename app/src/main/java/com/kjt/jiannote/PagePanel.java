package com.kjt.jiannote;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.kjt.jiannote.model.Page;
import com.kjt.jiannote.render.RenderContext;
import com.kjt.jiannote.render.Renderer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;

/** 오른쪽에서 열리는 페이지 미리보기 패널 (모든 페이지 / 북마크 페이지) */
public class PagePanel extends FrameLayout {
    public interface Host {
        int paperColor();

        void onPagesEdited();
    }

    private final EditorActivity act;
    private final CanvasView canvas;
    private final RenderContext rc;
    private final Host host;
    private final LinearLayout panel;
    private final LinearLayout list;
    private final ScrollView scroll;
    private final TextView tabAll, tabMarked;
    private boolean bookmarksOnly;
    private final IdentityHashMap<Page, Bitmap> thumbs = new IdentityHashMap<>();
    private final HashMap<Integer, FrameLayout> cards = new HashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<Page> renderQueue = new ArrayList<>();
    private final int panelW;

    public PagePanel(EditorActivity a, CanvasView canvas, RenderContext rc, Host host) {
        super(a);
        this.act = a;
        this.canvas = canvas;
        this.rc = rc;
        this.host = host;
        panelW = Ui.dp(a, 330);

        View dim = new View(a);
        dim.setBackgroundColor(0x55000000);
        dim.setOnClickListener(v -> hide());
        addView(dim, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        panel = Ui.col(a);
        panel.setBackgroundColor(0xFFFAFAFC);
        panel.setElevation(Ui.dp(a, 16));
        panel.setClickable(true);
        LayoutParams plp = new LayoutParams(panelW, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        addView(panel, plp);

        // 헤더
        LinearLayout head = Ui.row(a);
        head.setPadding(Ui.dp(a, 8), Ui.dp(a, 10), Ui.dp(a, 12), Ui.dp(a, 6));
        TextView close = Ui.toolButton(a, "✕", null);
        close.setOnClickListener(v -> hide());
        head.addView(close);
        TextView title = Ui.label(a, "페이지", 22, Ui.TEXT, true);
        title.setPadding(Ui.dp(a, 6), 0, 0, 0);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView add = Ui.toolButton(a, "＋", null);
        add.setOnClickListener(v -> {
            Page cur = canvas.page();
            canvas.addPage(Page.create(cur.w, cur.h, cur.template), canvas.pageIndex() + 1);
            host.onPagesEdited();
            rebuild();
        });
        head.addView(add);
        panel.addView(head);

        // 탭
        LinearLayout tabs = Ui.row(a);
        tabs.setBackground(Ui.roundStroke(0xFFFFFFFF, Ui.dp(a, 24), 0x55000000, Ui.dp(a, 1)));
        tabAll = tab("모든 페이지");
        tabMarked = tab("북마크 페이지");
        tabs.addView(tabAll, new LinearLayout.LayoutParams(0, Ui.dp(a, 46), 1f));
        tabs.addView(tabMarked, new LinearLayout.LayoutParams(0, Ui.dp(a, 46), 1f));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 48));
        tlp.setMargins(Ui.dp(a, 24), Ui.dp(a, 6), Ui.dp(a, 24), Ui.dp(a, 10));
        panel.addView(tabs, tlp);
        tabAll.setOnClickListener(v -> {
            bookmarksOnly = false;
            rebuild();
        });
        tabMarked.setOnClickListener(v -> {
            bookmarksOnly = true;
            rebuild();
        });

        scroll = new ScrollView(a);
        list = Ui.col(a);
        list.setGravity(Gravity.CENTER_HORIZONTAL);
        list.setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 30));
        scroll.addView(list);
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setVisibility(GONE);
    }

    private TextView tab(String s) {
        TextView t = Ui.label(act, s, 14, Ui.TEXT, false);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    public boolean isOpen() {
        return getVisibility() == VISIBLE;
    }

    public void show() {
        setVisibility(VISIBLE);
        rebuild();
        panel.setTranslationX(panelW);
        panel.animate().translationX(0).setDuration(200).start();
        scrollToCurrent();
    }

    public void hide() {
        panel.animate().translationX(panelW).setDuration(180).withEndAction(() -> setVisibility(GONE)).start();
    }

    /** 페이지 내용이 바뀌면 썸네일 무효화 */
    public void invalidatePage(Page p) {
        thumbs.remove(p);
        if (isOpen()) {
            handler.removeCallbacks(refreshCurrent);
            handler.postDelayed(refreshCurrent, 700);
        }
    }

    private final Runnable refreshCurrent = () -> {
        int i = canvas.pageIndex();
        Page p = canvas.page();
        FrameLayout card = cards.get(i);
        if (card != null) {
            Bitmap b = render(p);
            ImageView iv = (ImageView) card.getTag();
            iv.setImageBitmap(b);
        }
    };

    public void onPageChanged() {
        if (!isOpen()) return;
        for (java.util.Map.Entry<Integer, FrameLayout> en : cards.entrySet()) {
            styleCard(en.getValue(), en.getKey() == canvas.pageIndex());
        }
    }

    private void scrollToCurrent() {
        FrameLayout c = cards.get(canvas.pageIndex());
        if (c != null) scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, c.getTop() - Ui.dp(act, 40))));
    }

    private Bitmap render(Page p) {
        Bitmap b = thumbs.get(p);
        if (b == null) {
            int w = Ui.dp(act, 230);
            float ratio = p.isInfinite() ? 0.75f : p.h / p.w;
            b = Renderer.thumbnail(p, host.paperColor(), rc, w, Math.round(w * ratio));
            thumbs.put(p, b);
        }
        return b;
    }

    public void rebuild() {
        TextTools.markPill(act, tabAll, !bookmarksOnly);
        TextTools.markPill(act, tabMarked, bookmarksOnly);
        tabAll.setBackground(Ui.round(!bookmarksOnly ? 0xFFE3E7FF : 0x00000000, Ui.dp(act, 24)));
        tabMarked.setBackground(Ui.round(bookmarksOnly ? 0xFFE3E7FF : 0x00000000, Ui.dp(act, 24)));
        tabAll.setTextColor(Ui.TEXT);
        tabMarked.setTextColor(Ui.TEXT);

        list.removeAllViews();
        cards.clear();
        renderQueue.clear();
        handler.removeCallbacks(renderNext);
        int count = 0;
        for (int i = 0; i < canvas.pageCount(); i++) {
            Page p = canvas.doc().pages.get(i);
            if (bookmarksOnly && !p.bookmarked) continue;
            count++;
            list.addView(card(i, p));
        }
        if (count == 0) {
            TextView empty = Ui.label(act, bookmarksOnly ? "북마크한 페이지가 없습니다.\n썸네일 오른쪽 위 리본을 눌러 북마크하세요." : "페이지가 없습니다", 13, Ui.SUBTEXT, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, Ui.dp(act, 40), 0, 0);
            list.addView(empty);
        }
        handler.post(renderNext);
    }

    private final Runnable renderNext = new Runnable() {
        @Override
        public void run() {
            if (renderQueue.isEmpty()) return;
            Page p = renderQueue.remove(0);
            int idx = canvas.doc().pages.indexOf(p);
            FrameLayout card = cards.get(idx);
            if (card != null) ((ImageView) card.getTag()).setImageBitmap(render(p));
            if (!renderQueue.isEmpty()) handler.post(this);
        }
    };

    private FrameLayout card(int i, Page p) {
        FrameLayout card = new FrameLayout(act);
        int pad = Ui.dp(act, 4);
        card.setPadding(pad, pad, pad, pad);
        ImageView iv = new ImageView(act);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setBackgroundColor(host.paperColor());
        int w = Ui.dp(act, 230);
        float ratio = p.isInfinite() ? 0.75f : p.h / p.w;
        iv.setMinimumHeight(Math.round(w * ratio));
        Bitmap cached = thumbs.get(p);
        if (cached != null) iv.setImageBitmap(cached);
        else renderQueue.add(p);
        card.addView(iv, new LayoutParams(w, Math.round(w * ratio)));
        card.setTag(iv);

        TextView num = Ui.label(act, String.valueOf(i + 1), 13, Ui.TEXT, false);
        LayoutParams nlp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        nlp.setMargins(Ui.dp(act, 10), 0, 0, Ui.dp(act, 6));
        card.addView(num, nlp);

        TextView ribbon = new TextView(act);
        ribbon.setText("🔖");
        ribbon.setTextSize(20);
        ribbon.setGravity(Gravity.CENTER);
        ribbon.setAlpha(p.bookmarked ? 1f : 0.25f);
        LayoutParams rlp = new LayoutParams(Ui.dp(act, 40), Ui.dp(act, 40), Gravity.TOP | Gravity.END);
        card.addView(ribbon, rlp);
        ribbon.setOnClickListener(v -> {
            p.bookmarked = !p.bookmarked;
            ribbon.setAlpha(p.bookmarked ? 1f : 0.25f);
            host.onPagesEdited();
            Toast.makeText(act, p.bookmarked ? (i + 1) + "페이지 북마크" : "북마크 해제", Toast.LENGTH_SHORT).show();
            if (bookmarksOnly) rebuild();
        });

        styleCard(card, i == canvas.pageIndex());
        card.setOnClickListener(v -> {
            canvas.goToPage(i);
            onPageChanged();
        });
        card.setOnLongClickListener(v -> {
            pageMenu(v, i);
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.setMargins(0, Ui.dp(act, 10), 0, Ui.dp(act, 10));
        card.setLayoutParams(lp);
        cards.put(i, card);
        return card;
    }

    private void styleCard(FrameLayout card, boolean current) {
        card.setBackground(Ui.roundStroke(Color.WHITE, Ui.dp(act, 8), current ? Ui.ACCENT : 0x22000000,
                Ui.dp(act, current ? 3 : 1)));
    }

    private void pageMenu(View anchor, int i) {
        PopupMenu pm = new PopupMenu(act, anchor);
        Page p = canvas.doc().pages.get(i);
        pm.getMenu().add(0, 1, 0, p.bookmarked ? "북마크 해제" : "북마크");
        pm.getMenu().add(0, 2, 0, "다음에 새 페이지");
        pm.getMenu().add(0, 3, 0, "복제");
        pm.getMenu().add(0, 4, 0, "위로 이동");
        pm.getMenu().add(0, 5, 0, "아래로 이동");
        pm.getMenu().add(0, 6, 0, "삭제");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: p.bookmarked = !p.bookmarked; break;
                case 2: canvas.addPage(Page.create(p.w, p.h, p.template), i + 1); break;
                case 3: canvas.addPage(p.copy(), i + 1); break;
                case 4: canvas.movePage(i, i - 1); break;
                case 5: canvas.movePage(i, i + 1); break;
                case 6:
                    if (canvas.pageCount() <= 1) {
                        Toast.makeText(act, "마지막 페이지는 삭제할 수 없습니다", Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    new AlertDialog.Builder(act).setTitle("페이지 삭제")
                            .setMessage((i + 1) + "페이지를 삭제할까요?")
                            .setNegativeButton("취소", null)
                            .setPositiveButton("삭제", (d, w) -> {
                                canvas.deletePage(i);
                                host.onPagesEdited();
                                rebuild();
                            }).show();
                    return true;
                default: break;
            }
            host.onPagesEdited();
            rebuild();
            return true;
        });
        pm.show();
    }
}
