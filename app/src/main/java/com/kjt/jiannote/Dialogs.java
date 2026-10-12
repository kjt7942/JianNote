package com.kjt.jiannote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.kjt.jiannote.model.NotebookMeta;
import com.kjt.jiannote.render.Paper;

import java.util.ArrayList;

public class Dialogs {

    public interface MetaCb {
        void on(NotebookMeta m);
    }

    public interface IntCb {
        void on(int v);
    }

    /** 속지 미리보기 칩 */
    public static class TemplateChip extends View {
        final int template;
        int paper = 0xFFFFFFFF;
        boolean landscape;
        boolean chosen;
        boolean locked;
        String label;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        public TemplateChip(Context c, int template, boolean locked) {
            super(c);
            this.template = template;
            this.locked = locked;
        }

        @Override
        protected void onDraw(Canvas c) {
            float d = getResources().getDisplayMetrics().density;
            float pw = landscape ? 1754 : 1240, ph = landscape ? 1240 : 1754;
            float avW = getWidth() - 8 * d, avH = getHeight() - 26 * d;
            float s = Math.min(avW / pw, avH / ph);
            float ox = (getWidth() - pw * s) / 2f, oy = 4 * d;
            if (chosen) {
                p.setColor(Ui.ACCENT);
                p.setStyle(Paint.Style.FILL);
                c.drawRoundRect(ox - 3 * d, oy - 3 * d, ox + pw * s + 3 * d, oy + ph * s + 3 * d, 6 * d, 6 * d, p);
            }
            c.save();
            c.translate(ox, oy);
            c.scale(s, s);
            Paper.drawPage(c, template, paper, pw, ph, null, s * 3);
            c.restore();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(d);
            p.setColor(0x33000000);
            c.drawRect(ox, oy, ox + pw * s, oy + ph * s, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(chosen ? Ui.ACCENT : Ui.TEXT);
            p.setTextSize(11 * d);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(chosen ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            c.drawText((locked ? "👑 " : "") + (label != null ? label : Paper.NAMES[template]), getWidth() / 2f, getHeight() - 6 * d, p);
        }
    }

    /** 새 노트 만들기 */
    public static void newNotebook(Activity a, Premium premium, MetaCb cb) {
        final NotebookMeta m = new NotebookMeta();
        m.title = "";
        m.coverColor = Ui.COVER_COLORS[(int) (Math.random() * Ui.COVER_COLORS.length)];
        m.paperColor = Paper.PAPER_COLORS[0];
        m.template = Paper.LINED;

        int pad = Ui.dp(a, 20);
        LinearLayout root = Ui.col(a);
        root.setPadding(pad, Ui.dp(a, 8), pad, 0);

        EditText title = new EditText(a);
        title.setHint("노트 제목");
        title.setSingleLine(true);
        root.addView(title);

        root.addView(section(a, "노트 종류"));
        RadioGroup type = new RadioGroup(a);
        type.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rbPaged = new RadioButton(a);
        rbPaged.setText("일반 노트 (A4 페이지)");
        rbPaged.setId(View.generateViewId());
        RadioButton rbInf = new RadioButton(a);
        rbInf.setText("무한 캔버스");
        rbInf.setId(View.generateViewId());
        type.addView(rbPaged);
        type.addView(rbInf);
        rbPaged.setChecked(true);
        root.addView(type);

        root.addView(section(a, "방향"));
        RadioGroup ori = new RadioGroup(a);
        ori.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rbPort = new RadioButton(a);
        rbPort.setText("세로");
        rbPort.setId(View.generateViewId());
        RadioButton rbLand = new RadioButton(a);
        rbLand.setText("가로");
        rbLand.setId(View.generateViewId());
        ori.addView(rbPort);
        ori.addView(rbLand);
        rbPort.setChecked(true);
        root.addView(ori);

        root.addView(section(a, "속지"));
        HorizontalScrollView hs = new HorizontalScrollView(a);
        LinearLayout chips = Ui.row(a);
        hs.addView(chips);
        root.addView(hs);
        final ArrayList<TemplateChip> chipList = new ArrayList<>();
        for (int t = 0; t < Paper.COUNT; t++) {
            boolean locked = Paper.isPremium(t) && !premium.isPremium();
            TemplateChip chip = new TemplateChip(a, t, locked);
            chip.chosen = t == m.template;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(a, 92), Ui.dp(a, 140));
            lp.setMargins(Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4));
            chips.addView(chip, lp);
            chipList.add(chip);
            final int tt = t;
            chip.setOnClickListener(v -> {
                if (Paper.isPremium(tt) && !premium.isPremium()) {
                    Toast.makeText(a, "프리미엄 전용 속지입니다", Toast.LENGTH_SHORT).show();
                    return;
                }
                m.template = tt;
                for (TemplateChip ch : chipList) {
                    ch.chosen = ch.template == tt;
                    ch.invalidate();
                }
            });
        }

        root.addView(section(a, "종이 색"));
        LinearLayout paperRow = Ui.row(a);
        root.addView(paperRow);
        final ArrayList<Ui.Swatch> paperSw = new ArrayList<>();
        for (int i = 0; i < Paper.PAPER_COLORS.length; i++) {
            Ui.Swatch s = new Ui.Swatch(a, Paper.PAPER_COLORS[i]);
            s.setChosen(i == 0);
            paperRow.addView(s, new LinearLayout.LayoutParams(Ui.dp(a, 40), Ui.dp(a, 40)));
            paperSw.add(s);
            s.setOnClickListener(v -> {
                m.paperColor = s.color();
                for (Ui.Swatch o : paperSw) o.setChosen(o == s);
                for (TemplateChip ch : chipList) {
                    ch.paper = m.paperColor;
                    ch.invalidate();
                }
            });
        }

        root.addView(section(a, "표지 색"));
        HorizontalScrollView hs2 = new HorizontalScrollView(a);
        LinearLayout coverRow = Ui.row(a);
        hs2.addView(coverRow);
        root.addView(hs2);
        final ArrayList<Ui.Swatch> coverSw = new ArrayList<>();
        for (int col : Ui.COVER_COLORS) {
            Ui.Swatch s = new Ui.Swatch(a, col);
            s.setChosen(col == m.coverColor);
            coverRow.addView(s, new LinearLayout.LayoutParams(Ui.dp(a, 40), Ui.dp(a, 40)));
            coverSw.add(s);
            s.setOnClickListener(v -> {
                m.coverColor = s.color();
                for (Ui.Swatch o : coverSw) o.setChosen(o == s);
            });
        }

        ori.setOnCheckedChangeListener((g, id) -> {
            for (TemplateChip ch : chipList) {
                ch.landscape = id == rbLand.getId();
                ch.invalidate();
            }
        });
        type.setOnCheckedChangeListener((g, id) -> {
            boolean inf = id == rbInf.getId();
            rbPort.setEnabled(!inf);
            rbLand.setEnabled(!inf);
        });

        ScrollView sv = new ScrollView(a);
        sv.addView(root);

        new AlertDialog.Builder(a)
                .setTitle("새 노트")
                .setView(sv)
                .setNegativeButton("취소", null)
                .setPositiveButton("만들기", (dlg, w) -> {
                    String t = title.getText().toString().trim();
                    m.title = t.isEmpty() ? (rbInf.isChecked() ? "무한 캔버스" : "새 노트") : t;
                    m.infinite = rbInf.isChecked();
                    m.landscape = !m.infinite && rbLand.isChecked();
                    cb.on(m);
                })
                .show();
    }


    public interface TemplateCb {
        void on(int template, boolean allPages);
    }

    /** 속지를 미리보기 이미지로 고르는 창 */
    public static void templatePicker(Activity a, Premium premium, int paperColor, boolean landscape, int current,
                                      boolean showApplyAll, TemplateCb cb) {
        LinearLayout root = Ui.col(a);
        int pad = Ui.dp(a, 16);
        root.setPadding(pad, Ui.dp(a, 8), pad, 0);
        android.widget.GridLayout grid = new android.widget.GridLayout(a);
        grid.setColumnCount(4);
        root.addView(grid);
        final int[] chosen = {current};
        final ArrayList<TemplateChip> chips = new ArrayList<>();
        for (int t = 0; t < Paper.COUNT; t++) {
            boolean locked = Paper.isPremium(t) && !premium.isPremium();
            TemplateChip chip = new TemplateChip(a, t, locked);
            chip.paper = paperColor;
            chip.landscape = landscape;
            chip.chosen = t == current;
            chips.add(chip);
            final int tt = t;
            chip.setOnClickListener(v -> {
                if (Paper.isPremium(tt) && !premium.isPremium()) {
                    limit(a, premium, "'" + Paper.NAMES[tt] + "' 속지는 프리미엄 전용입니다.", null);
                    return;
                }
                chosen[0] = tt;
                for (TemplateChip ch : chips) {
                    ch.chosen = ch.template == tt;
                    ch.invalidate();
                }
            });
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
            lp.width = Ui.dp(a, landscape ? 150 : 120);
            lp.height = Ui.dp(a, landscape ? 130 : 180);
            lp.setMargins(Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 6));
            grid.addView(chip, lp);
        }
        android.widget.CheckBox all = new android.widget.CheckBox(a);
        all.setText("모든 페이지에 적용");
        if (showApplyAll) root.addView(all);
        ScrollView sv = new ScrollView(a);
        sv.addView(root);
        new AlertDialog.Builder(a).setTitle("속지 선택").setView(sv)
                .setNegativeButton("취소", null)
                .setPositiveButton("적용", (d, w) -> cb.on(chosen[0], all.isChecked()))
                .show();
    }

    /** 종이 색을 미리보기 이미지로 고르는 창 (사용자 지정 색 포함) */
    public static void paperPicker(Activity a, int template, boolean landscape, int current, IntCb cb) {
        LinearLayout root = Ui.col(a);
        int pad = Ui.dp(a, 16);
        root.setPadding(pad, Ui.dp(a, 8), pad, 0);
        android.widget.GridLayout grid = new android.widget.GridLayout(a);
        grid.setColumnCount(4);
        root.addView(grid);
        ArrayList<Integer> colors = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        for (int i = 0; i < Paper.PAPER_COLORS.length; i++) {
            colors.add(Paper.PAPER_COLORS[i]);
            names.add(Paper.PAPER_NAMES[i]);
        }
        int[] extra = {0xFFFDF6E3, 0xFFFCE4EC, 0xFFEDE7F6, 0xFF37474F, 0xFF1B2A1E};
        String[] extraNames = {"아이보리", "분홍", "라벤더", "차콜", "칠판"};
        for (int i = 0; i < extra.length; i++) {
            colors.add(extra[i]);
            names.add(extraNames[i]);
        }
        for (int c : ColorPicker.Palette.load(a)) {
            if (!colors.contains(c)) {
                colors.add(c);
                names.add("내 색 " + ColorPicker.hex(c));
            }
        }
        if (!colors.contains(current)) {
            colors.add(current);
            names.add("현재 색");
        }
        final int[] chosen = {current};
        final ArrayList<TemplateChip> chips = new ArrayList<>();
        final AlertDialog[] dlg = new AlertDialog[1];
        for (int i = 0; i < colors.size(); i++) {
            TemplateChip chip = new TemplateChip(a, template, false);
            int col = colors.get(i);
            chip.paper = col;
            chip.landscape = landscape;
            chip.label = names.get(i);
            chip.chosen = col == current;
            chips.add(chip);
            chip.setOnClickListener(v -> {
                chosen[0] = col;
                for (TemplateChip ch : chips) {
                    ch.chosen = ch.paper == col;
                    ch.invalidate();
                }
            });
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
            lp.width = Ui.dp(a, landscape ? 150 : 110);
            lp.height = Ui.dp(a, landscape ? 130 : 165);
            lp.setMargins(Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 6));
            grid.addView(chip, lp);
        }
        TextView custom = Ui.pill(a, "🎨 직접 색 만들기", false);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Ui.dp(a, 8);
        root.addView(custom, clp);
        custom.setOnClickListener(v -> ColorPicker.show(a, chosen[0], c -> {
            if (dlg[0] != null) dlg[0].dismiss();
            cb.on(c);
        }));
        ScrollView sv = new ScrollView(a);
        sv.addView(root);
        dlg[0] = new AlertDialog.Builder(a).setTitle("종이 색").setView(sv)
                .setNegativeButton("취소", null)
                .setPositiveButton("적용", (d, w) -> cb.on(chosen[0]))
                .show();
    }

    private static TextView section(Context c, String s) {
        TextView t = Ui.label(c, s, 13, Ui.SUBTEXT, true);
        t.setPadding(0, Ui.dp(c, 14), 0, Ui.dp(c, 4));
        return t;
    }

    /** 프리미엄 안내 및 코드 등록 */
    public static void premium(Activity a, Premium premium, Runnable onChange) {
        int pad = Ui.dp(a, 20);
        LinearLayout root = Ui.col(a);
        root.setPadding(pad, Ui.dp(a, 8), pad, 0);

        boolean isP = premium.isPremium();
        TextView status = Ui.label(a, isP ? "👑 프리미엄 사용 중입니다" : "현재 무료 버전을 사용 중입니다", 16, isP ? Ui.GOLD : Ui.TEXT, true);
        root.addView(status);

        String table =
                "                    무료            프리미엄\n" +
                "노트 수          3권              무제한\n" +
                "페이지          무제한          무제한\n" +
                "레이어          페이지당 2개   페이지당 20개\n" +
                "속지              기본 4종        전체 8종\n" +
                "PDF 내보내기  워터마크        워터마크 없음\n" +
                "무한 캔버스   ✔                 ✔\n" +
                "S펜 필압       ✔                 ✔";
        TextView tv = Ui.label(a, table, 13, Ui.TEXT, false);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setPadding(0, Ui.dp(a, 12), 0, Ui.dp(a, 12));
        root.addView(tv);

        TextView dev = Ui.label(a, "기기 코드: " + premium.deviceCode(), 15, Ui.ACCENT, true);
        dev.setTextIsSelectable(true);
        root.addView(dev);
        root.addView(Ui.label(a, "기기 코드를 개발자에게 알려주면 프리미엄 해제 코드를 받을 수 있습니다.", 12, Ui.SUBTEXT, false));

        EditText code = new EditText(a);
        code.setHint("해제 코드 (XXXX-XXXX-XXXX)");
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        code.setSingleLine(true);
        if (!isP) root.addView(code);

        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle("JianNote")
                .setView(root)
                .setNegativeButton("닫기", null);
        if (!isP) {
            b.setPositiveButton("등록", (d, w) -> {
                if (premium.activate(code.getText().toString())) {
                    Toast.makeText(a, "👑 프리미엄이 활성화되었습니다!", Toast.LENGTH_LONG).show();
                    if (onChange != null) onChange.run();
                } else {
                    Toast.makeText(a, "코드가 올바르지 않습니다", Toast.LENGTH_LONG).show();
                }
            });
        } else {
            b.setNeutralButton("프리미엄 해제", (d, w) -> {
                premium.deactivate();
                if (onChange != null) onChange.run();
            });
        }
        b.show();
    }

    /** 프리미엄 제한 안내 */
    public static void limit(Activity a, Premium premium, String msg, Runnable onChange) {
        new AlertDialog.Builder(a)
                .setTitle("👑 프리미엄 기능")
                .setMessage(msg + "\n\n프리미엄으로 업그레이드하면 제한 없이 사용할 수 있습니다.")
                .setNegativeButton("나중에", null)
                .setPositiveButton("업그레이드", (d, w) -> premium(a, premium, onChange))
                .show();
    }

    public interface TextCb {
        void on(String s);
    }

    public static void input(Activity a, String title, String initial, boolean multiline, TextCb cb) {
        EditText e = new EditText(a);
        e.setText(initial);
        if (multiline) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            e.setMinLines(3);
            e.setGravity(Gravity.TOP | Gravity.START);
        } else {
            e.setSingleLine(true);
        }
        e.setSelection(e.getText().length());
        LinearLayout box = Ui.col(a);
        int p = Ui.dp(a, 20);
        box.setPadding(p, Ui.dp(a, 8), p, 0);
        box.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle(title)
                .setView(box)
                .setNegativeButton("취소", null)
                .setPositiveButton("확인", (dd, w) -> cb.on(e.getText().toString()))
                .create();
        d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
        e.requestFocus();
    }
}
