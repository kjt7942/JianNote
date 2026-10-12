package com.kjt.jiannote;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.kjt.jiannote.model.TextElem;
import com.kjt.jiannote.render.Fonts;

import java.util.ArrayList;

/** 텍스트 서식 도구 (글꼴·크기·스타일·정렬·색·배경) 와 텍스트 편집 창 */
public class TextTools {

    public interface DoneCb {
        void on(String text, TextElem.Style style);
    }

    private static final int[] BG_COLORS = {0, 0x66FFEB3B, 0x66FF8A80, 0x6680D8FF, 0x66A5D6A7, 0x66B39DDB, 0xFFFFFFFF, 0xFF1F1F24};

    /** 서식 조절 컨트롤 묶음. 값이 바뀔 때마다 onChange 호출 */
    public static View controls(Activity a, TextElem.Style st, Runnable onChange) {
        LinearLayout box = Ui.col(a);

        // ---- 글꼴
        box.addView(section(a, "글꼴"));
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout fonts = Ui.row(a);
        hs.addView(fonts);
        box.addView(hs);
        ArrayList<TextView> fontBtns = new ArrayList<>();
        for (int i = 0; i < Fonts.COUNT; i++) {
            TextView t = new TextView(a);
            t.setText("가나다 Abc\n" + Fonts.NAMES[i]);
            t.setTypeface(Fonts.get(i, false));
            t.setTextSize(15);
            t.setGravity(Gravity.CENTER);
            t.setLineSpacing(0, 1.1f);
            t.setPadding(Ui.dp(a, 14), Ui.dp(a, 8), Ui.dp(a, 14), Ui.dp(a, 8));
            final int f = i;
            fontBtns.add(t);
            t.setOnClickListener(v -> {
                st.font = f;
                for (int j = 0; j < fontBtns.size(); j++) markChip(a, fontBtns.get(j), j == f);
                onChange.run();
            });
            markChip(a, t, st.font == i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, Ui.dp(a, 6), 0);
            fonts.addView(t, lp);
        }

        // ---- 크기
        TextView sizeLabel = section(a, "글자 크기: " + Math.round(st.size));
        box.addView(sizeLabel);
        LinearLayout sr = Ui.row(a);
        TextView minus = Ui.pill(a, "−", false);
        TextView plus = Ui.pill(a, "＋", false);
        SeekBar sb = Ui.seek(a, 144, Math.round(st.size) - 12, v -> {
            st.size = v + 12;
            sizeLabel.setText("글자 크기: " + Math.round(st.size));
            onChange.run();
        });
        minus.setOnClickListener(v -> {
            st.size = Math.max(12, Math.round(st.size) - 2);
            sb.setProgress(Math.round(st.size) - 12);
            sizeLabel.setText("글자 크기: " + Math.round(st.size));
            onChange.run();
        });
        plus.setOnClickListener(v -> {
            st.size = Math.min(156, Math.round(st.size) + 2);
            sb.setProgress(Math.round(st.size) - 12);
            sizeLabel.setText("글자 크기: " + Math.round(st.size));
            onChange.run();
        });
        sr.addView(minus);
        sr.addView(sb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sr.addView(plus);
        box.addView(sr);
        LinearLayout presets = Ui.row(a);
        int[] quick = {24, 32, 40, 56, 72, 96};
        for (int q : quick) {
            TextView t = Ui.pill(a, String.valueOf(q), false);
            t.setTextSize(12);
            t.setPadding(Ui.dp(a, 10), Ui.dp(a, 6), Ui.dp(a, 10), Ui.dp(a, 6));
            t.setOnClickListener(v -> {
                st.size = q;
                sb.setProgress(q - 12);
                sizeLabel.setText("글자 크기: " + q);
                onChange.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, Ui.dp(a, 4), Ui.dp(a, 6), 0);
            presets.addView(t, lp);
        }
        box.addView(presets);

        // ---- 스타일 / 정렬
        box.addView(section(a, "스타일 · 정렬"));
        LinearLayout styleRow = Ui.row(a);
        String[] sLabels = {"B", "I", "U", "S"};
        int[] sFlags = {TextElem.BOLD, TextElem.ITALIC, TextElem.UNDERLINE, TextElem.STRIKE};
        for (int i = 0; i < 4; i++) {
            TextView t = Ui.pill(a, sLabels[i], false);
            if (i == 0) t.setTypeface(Typeface.DEFAULT_BOLD);
            if (i == 1) t.setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC));
            if (i == 2) t.setPaintFlags(t.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
            if (i == 3) t.setPaintFlags(t.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            final int flag = sFlags[i];
            markPill(a, t, (st.flags & flag) != 0);
            t.setOnClickListener(v -> {
                st.flags ^= flag;
                markPill(a, t, (st.flags & flag) != 0);
                onChange.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(a, 44), Ui.dp(a, 40));
            lp.setMargins(0, 0, Ui.dp(a, 4), 0);
            styleRow.addView(t, lp);
        }
        View gap = new View(a);
        styleRow.addView(gap, new LinearLayout.LayoutParams(Ui.dp(a, 12), 1));
        String[] aLabels = {"⇤ 왼쪽", "↔ 가운데", "⇥ 오른쪽"};
        TextView[] aBtns = new TextView[3];
        for (int i = 0; i < 3; i++) {
            TextView t = Ui.pill(a, aLabels[i], false);
            t.setTextSize(12);
            t.setPadding(Ui.dp(a, 8), 0, Ui.dp(a, 8), 0);
            final int al = i;
            aBtns[i] = t;
            t.setOnClickListener(v -> {
                st.align = al;
                for (int j = 0; j < 3; j++) markPill(a, aBtns[j], j == al);
                onChange.run();
            });
            markPill(a, t, st.align == i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(a, 40));
            lp.setMargins(0, 0, Ui.dp(a, 4), 0);
            styleRow.addView(t, lp);
        }
        HorizontalScrollView shs = new HorizontalScrollView(a);
        shs.setHorizontalScrollBarEnabled(false);
        shs.addView(styleRow);
        box.addView(shs);

        // ---- 글자 색
        box.addView(section(a, "글자 색"));
        box.addView(ColorPicker.grid(a, Ui.PEN_COLORS, st.color, c -> {
            st.color = c;
            onChange.run();
        }));

        // ---- 배경
        box.addView(section(a, "배경 (형광 강조)"));
        LinearLayout bgRow = Ui.row(a);
        ArrayList<View> bgViews = new ArrayList<>();
        for (int col : BG_COLORS) {
            View v;
            if (col == 0) {
                TextView none = new TextView(a);
                none.setText("없음");
                none.setTextSize(11);
                none.setGravity(Gravity.CENTER);
                none.setTextColor(Ui.SUBTEXT);
                v = none;
            } else {
                v = new Ui.Swatch(a, col | 0xFF000000);
                ((Ui.Swatch) v).setAlpha((col >>> 24) == 0xFF ? 1f : 0.75f);
            }
            bgViews.add(v);
            markBg(a, v, st.bg == col);
            v.setOnClickListener(x -> {
                st.bg = col;
                for (int j = 0; j < bgViews.size(); j++) markBg(a, bgViews.get(j), BG_COLORS[j] == col);
                onChange.run();
            });
            bgRow.addView(v, new LinearLayout.LayoutParams(Ui.dp(a, 40), Ui.dp(a, 40)));
        }
        box.addView(bgRow);
        return box;
    }

    private static void markBg(Activity a, View v, boolean on) {
        if (v instanceof Ui.Swatch) {
            ((Ui.Swatch) v).setChosen(on);
        } else {
            v.setBackground(Ui.roundStroke(on ? 0xFFE3E7FF : 0xFFFFFFFF, Ui.dp(a, 20), on ? Ui.ACCENT : 0x33000000, Ui.dp(a, on ? 2 : 1)));
        }
    }

    private static TextView section(Activity a, String s) {
        TextView t = Ui.label(a, s, 13, Ui.SUBTEXT, true);
        t.setPadding(0, Ui.dp(a, 12), 0, Ui.dp(a, 4));
        return t;
    }

    static void markPill(Activity a, TextView t, boolean on) {
        t.setBackground(on ? Ui.ripple(Ui.ACCENT, Ui.dp(a, 20)) : Ui.ripple(0xFFECE8DF, Ui.dp(a, 20)));
        t.setTextColor(on ? Color.WHITE : Ui.TEXT);
    }

    private static void markChip(Activity a, TextView t, boolean on) {
        t.setBackground(Ui.roundStroke(on ? 0xFFEEF1FF : 0xFFFAFAF7, Ui.dp(a, 12), on ? Ui.ACCENT : 0x22000000, Ui.dp(a, on ? 2 : 1)));
        t.setTextColor(on ? Ui.ACCENT : Ui.TEXT);
    }

    /** 미리보기 EditText에 서식 적용 */
    private static void applyPreview(Activity a, EditText e, TextElem.Style st) {
        boolean bold = (st.flags & TextElem.BOLD) != 0;
        e.setTypeface(Fonts.get(st.font, bold));
        e.getPaint().setFakeBoldText(bold && !Fonts.hasRealBold(st.font));
        e.getPaint().setTextSkewX((st.flags & TextElem.ITALIC) != 0 ? -0.22f : 0f);
        int pf = e.getPaintFlags() & ~(Paint.UNDERLINE_TEXT_FLAG | Paint.STRIKE_THRU_TEXT_FLAG);
        if ((st.flags & TextElem.UNDERLINE) != 0) pf |= Paint.UNDERLINE_TEXT_FLAG;
        if ((st.flags & TextElem.STRIKE) != 0) pf |= Paint.STRIKE_THRU_TEXT_FLAG;
        e.setPaintFlags(pf);
        float px = Math.max(Ui.dp(a, 14), Math.min(Ui.dp(a, 54), st.size * 0.75f * a.getResources().getDisplayMetrics().density / 1.5f));
        e.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        e.setTextColor(st.color);
        int g = st.align == TextElem.ALIGN_CENTER ? Gravity.CENTER_HORIZONTAL : st.align == TextElem.ALIGN_RIGHT ? Gravity.END : Gravity.START;
        e.setGravity(g | Gravity.TOP);
        int bg = st.bg != 0 ? st.bg : 0xFFFFFFFF;
        e.setBackground(Ui.roundStroke(Paper0.compose(bg), Ui.dp(a, 10), 0x22000000, Ui.dp(a, 1)));
        e.invalidate();
    }

    /** 반투명 배경을 흰 종이 위에 합성한 색 */
    static class Paper0 {
        static int compose(int c) {
            int al = c >>> 24;
            if (al == 0xFF) return c;
            float f = al / 255f;
            int r = Math.round(Color.red(c) * f + 255 * (1 - f));
            int g = Math.round(Color.green(c) * f + 255 * (1 - f));
            int b = Math.round(Color.blue(c) * f + 255 * (1 - f));
            return Color.rgb(r, g, b);
        }
    }

    /** 텍스트 입력/수정 창 (서식 포함) */
    public static void edit(Activity a, String initial, TextElem.Style base, boolean existing, DoneCb cb) {
        TextElem.Style st = base.copy();
        LinearLayout box = Ui.col(a);
        int p = Ui.dp(a, 18);
        box.setPadding(p, Ui.dp(a, 6), p, 0);

        EditText e = new EditText(a);
        e.setText(initial);
        e.setHint("내용을 입력하세요");
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setMinLines(2);
        e.setMaxLines(8);
        e.setPadding(Ui.dp(a, 14), Ui.dp(a, 10), Ui.dp(a, 14), Ui.dp(a, 10));
        box.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        applyPreview(a, e, st);
        box.addView(controls(a, st, () -> applyPreview(a, e, st)));
        e.setSelection(e.getText().length());

        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle(existing ? "텍스트 수정" : "텍스트 추가")
                .setView(sv)
                .setNegativeButton("취소", null)
                .setPositiveButton("확인", (d, w) -> cb.on(e.getText().toString(), st));
        if (existing) b.setNeutralButton("삭제", (d, w) -> cb.on("", st));
        AlertDialog d = b.create();
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        d.show();
        e.requestFocus();
    }
}
