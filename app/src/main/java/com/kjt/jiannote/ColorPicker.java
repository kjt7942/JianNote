package com.kjt.jiannote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;

/** 채도·명도 사각형 + 색상 막대 색상 선택기, 그리고 저장되는 "내 팔레트" */
public class ColorPicker {

    // ================= 내 팔레트 저장 =================
    public static class Palette {
        private static final int MAX = 32;

        public static ArrayList<Integer> load(Context c) {
            ArrayList<Integer> out = new ArrayList<>();
            String s = new Prefs(c).s("palette", "");
            if (s.isEmpty()) return out;
            for (String part : s.split(",")) {
                try {
                    out.add((int) Long.parseLong(part.trim(), 16));
                } catch (Exception ignored) {
                }
            }
            return out;
        }

        private static void save(Context c, ArrayList<Integer> list) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(Long.toHexString(list.get(i) & 0xFFFFFFFFL));
            }
            new Prefs(c).put("palette", sb.toString());
        }

        public static void add(Context c, int color) {
            ArrayList<Integer> l = load(c);
            l.remove(Integer.valueOf(color));
            l.add(0, color);
            while (l.size() > MAX) l.remove(l.size() - 1);
            save(c, l);
        }

        public static void remove(Context c, int color) {
            ArrayList<Integer> l = load(c);
            l.remove(Integer.valueOf(color));
            save(c, l);
        }
    }

    // ================= 선택기 뷰 =================
    public static class PickerView extends View {
        public interface Listener {
            void onColor(int color);
        }

        private final float[] hsv = new float[3];
        private Listener listener;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF sv = new RectF(), hue = new RectF();
        private int dragging = 0; // 1 = 사각형, 2 = 색상막대
        private final float d;
        private static final int[] HUES = {0xFFFF0000, 0xFFFF00FF, 0xFF0000FF, 0xFF00FFFF, 0xFF00FF00, 0xFFFFFF00, 0xFFFF0000};

        public PickerView(Context c, int color) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            Color.colorToHSV(color, hsv);
        }

        public void setListener(Listener l) { listener = l; }

        public int color() { return Color.HSVToColor(hsv); }

        public void setColor(int c) {
            Color.colorToHSV(c, hsv);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float hueW = 44 * d, gap = 6 * d;
            sv.set(0, 0, getWidth() - hueW - gap, getHeight());
            hue.set(getWidth() - hueW, 0, getWidth(), getHeight());
            int pure = Color.HSVToColor(new float[]{hsv[0], 1f, 1f});
            float r = 8 * d;
            // 채도/명도 사각형
            p.setShader(new LinearGradient(sv.left, 0, sv.right, 0, Color.WHITE, pure, Shader.TileMode.CLAMP));
            c.drawRoundRect(sv, r, r, p);
            p.setShader(new LinearGradient(0, sv.top, 0, sv.bottom, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
            c.drawRoundRect(sv, r, r, p);
            // 색상 막대 (위:빨강 → 자홍 → 파랑 → 청록 → 초록 → 노랑 → 아래:빨강)
            p.setShader(new LinearGradient(0, hue.top, 0, hue.bottom, HUES, null, Shader.TileMode.CLAMP));
            c.drawRoundRect(hue, r, r, p);
            p.setShader(null);

            // 사각형 손잡이
            float sx = sv.left + hsv[1] * sv.width();
            float sy = sv.top + (1 - hsv[2]) * sv.height();
            thumb(c, sx, sy, color());
            // 색상 막대 손잡이
            float hy = hue.top + (1 - hsv[0] / 360f) * hue.height();
            thumb(c, hue.centerX(), hy, pure);
        }

        private void thumb(Canvas c, float x, float y, int fill) {
            float rad = 18 * d;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x40000000);
            c.drawCircle(x, y + 1.5f * d, rad + 1.5f * d, p);
            p.setColor(fill);
            c.drawCircle(x, y, rad, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3 * d);
            p.setColor(Color.WHITE);
            c.drawCircle(x, y, rad, p);
            p.setStyle(Paint.Style.FILL);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    dragging = x >= hue.left - 10 * d ? 2 : 1;
                    update(x, y);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    update(x, y);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = 0;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                default:
                    return true;
            }
        }

        private void update(float x, float y) {
            if (dragging == 1) {
                hsv[1] = clamp((x - sv.left) / sv.width());
                hsv[2] = 1 - clamp((y - sv.top) / sv.height());
            } else if (dragging == 2) {
                hsv[0] = (1 - clamp((y - hue.top) / hue.height())) * 360f;
                if (hsv[0] >= 360f) hsv[0] = 359.9f;
            }
            invalidate();
            if (listener != null) listener.onColor(color());
        }

        private static float clamp(float v) {
            return Math.max(0f, Math.min(1f, v));
        }
    }

    public static String hex(int c) {
        return String.format(Locale.ROOT, "#%06X", c & 0xFFFFFF);
    }

    // ================= 대화상자 =================

    /** 색상 선택 대화상자. 선택한 색은 자동으로 "내 팔레트"에 저장된다. */
    public static void show(Activity a, int initial, Ui.IntCb cb) {
        LinearLayout box = Ui.col(a);
        int pad = Ui.dp(a, 18);
        box.setPadding(pad, Ui.dp(a, 6), pad, 0);

        PickerView picker = new PickerView(a, initial);
        box.addView(picker, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 300)));

        LinearLayout row = Ui.row(a);
        row.setPadding(0, Ui.dp(a, 14), 0, Ui.dp(a, 4));
        View preview = new View(a);
        preview.setBackground(Ui.round(initial, Ui.dp(a, 8)));
        row.addView(preview, new LinearLayout.LayoutParams(0, Ui.dp(a, 46), 1f));
        EditText hexField = new EditText(a);
        hexField.setText(hex(initial));
        hexField.setSingleLine(true);
        hexField.setGravity(Gravity.CENTER);
        hexField.setTextSize(16);
        hexField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7), new InputFilter.AllCaps()});
        hexField.setBackground(Ui.roundStroke(0xFFFFFFFF, Ui.dp(a, 8), 0x22000000, Ui.dp(a, 1)));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(Ui.dp(a, 140), Ui.dp(a, 46));
        hlp.setMargins(Ui.dp(a, 14), 0, 0, 0);
        row.addView(hexField, hlp);
        box.addView(row);

        final boolean[] fromHex = {false};
        picker.setListener(c -> {
            preview.setBackground(Ui.round(c, Ui.dp(a, 8)));
            if (!fromHex[0]) {
                fromHex[0] = true;
                hexField.setText(hex(c));
                fromHex[0] = false;
            }
        });
        hexField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (fromHex[0]) return;
                String t = s.toString().replace("#", "");
                if (t.length() == 6) {
                    try {
                        int col = 0xFF000000 | Integer.parseInt(t, 16);
                        fromHex[0] = true;
                        picker.setColor(col);
                        preview.setBackground(Ui.round(col, Ui.dp(a, 8)));
                        fromHex[0] = false;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        });

        // 내 팔레트
        TextView pl = Ui.label(a, "내 팔레트  (길게 누르면 삭제)", 12, Ui.SUBTEXT, true);
        pl.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 4));
        box.addView(pl);
        GridLayout saved = new GridLayout(a);
        saved.setColumnCount(9);
        box.addView(saved);
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            saved.removeAllViews();
            ArrayList<Integer> list = Palette.load(a);
            if (list.isEmpty()) {
                TextView empty = Ui.label(a, "선택한 색이 여기에 저장됩니다", 12, Ui.SUBTEXT, false);
                saved.addView(empty);
            }
            for (int col : list) {
                Ui.Swatch s = new Ui.Swatch(a, col);
                s.setOnClickListener(v -> {
                    picker.setColor(col);
                    preview.setBackground(Ui.round(col, Ui.dp(a, 8)));
                    hexField.setText(hex(col));
                });
                s.setOnLongClickListener(v -> {
                    Palette.remove(a, col);
                    refresh[0].run();
                    return true;
                });
                GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
                lp.width = Ui.dp(a, 38);
                lp.height = Ui.dp(a, 38);
                saved.addView(s, lp);
            }
        };
        refresh[0].run();

        TextView bl = Ui.label(a, "기본 색", 12, Ui.SUBTEXT, true);
        bl.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 4));
        box.addView(bl);
        GridLayout basics = new GridLayout(a);
        basics.setColumnCount(9);
        for (int col : Ui.PEN_COLORS) {
            Ui.Swatch s = new Ui.Swatch(a, col);
            s.setOnClickListener(v -> {
                picker.setColor(col);
                preview.setBackground(Ui.round(col, Ui.dp(a, 8)));
                hexField.setText(hex(col));
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = Ui.dp(a, 38);
            lp.height = Ui.dp(a, 38);
            basics.addView(s, lp);
        }
        box.addView(basics);

        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        new AlertDialog.Builder(a)
                .setTitle("색상 선택")
                .setView(sv)
                .setNegativeButton("취소", null)
                .setPositiveButton("선택", (dlg, w) -> {
                    int c = picker.color();
                    Palette.add(a, c);
                    cb.on(c);
                })
                .show();
    }

    /**
     * 프리셋 색 + 내 팔레트 + [＋ 색 만들기] 를 포함한 색 견본 격자.
     * 도구 옵션 창에서 사용.
     */
    public static View grid(Activity a, int[] presets, int current, Ui.IntCb cb) {
        LinearLayout box = Ui.col(a);
        int sz = Ui.dp(a, 38);
        ArrayList<Ui.Swatch> all = new ArrayList<>();

        GridLayout g = new GridLayout(a);
        g.setColumnCount(8);
        for (int col : presets) {
            Ui.Swatch s = new Ui.Swatch(a, col);
            s.setChosen(col == current);
            all.add(s);
            s.setOnClickListener(v -> {
                for (Ui.Swatch o : all) o.setChosen(o == s);
                cb.on(col);
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = sz;
            lp.height = sz;
            g.addView(s, lp);
        }
        box.addView(g);

        TextView pl = Ui.label(a, "내 팔레트", 12, Ui.SUBTEXT, true);
        pl.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 2));
        box.addView(pl);
        GridLayout pg = new GridLayout(a);
        pg.setColumnCount(8);
        box.addView(pg);
        Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            pg.removeAllViews();
            for (int col : Palette.load(a)) {
                Ui.Swatch s = new Ui.Swatch(a, col);
                s.setChosen(col == current);
                all.add(s);
                s.setOnClickListener(v -> {
                    for (Ui.Swatch o : all) o.setChosen(o == s);
                    cb.on(col);
                });
                s.setOnLongClickListener(v -> {
                    new AlertDialog.Builder(a).setTitle("팔레트에서 삭제할까요?")
                            .setNegativeButton("취소", null)
                            .setPositiveButton("삭제", (d, w) -> {
                                Palette.remove(a, col);
                                fill[0].run();
                            }).show();
                    return true;
                });
                GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
                lp.width = sz;
                lp.height = sz;
                pg.addView(s, lp);
            }
            TextView add = new TextView(a);
            add.setText("＋");
            add.setTextSize(20);
            add.setGravity(Gravity.CENTER);
            add.setTextColor(Ui.SUBTEXT);
            add.setBackground(Ui.roundStroke(0xFFFFFFFF, sz / 2f, 0x33000000, Ui.dp(a, 1)));
            add.setOnClickListener(v -> show(a, current, c -> {
                cb.on(c);
                fill[0].run();
                for (Ui.Swatch o : all) o.setChosen(o.color() == c);
            }));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = sz - Ui.dp(a, 6);
            lp.height = sz - Ui.dp(a, 6);
            lp.setMargins(Ui.dp(a, 3), Ui.dp(a, 3), Ui.dp(a, 3), Ui.dp(a, 3));
            pg.addView(add, lp);
        };
        fill[0].run();
        return box;
    }

    static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }
}
