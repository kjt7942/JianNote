package com.kjt.jiannote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.kjt.jiannote.model.StrokeElem;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/** 펜·형광펜·지우개 목록 (선 미리보기 + 이름 + 굵기) */
public class PenPresets {
    public static final int KIND_PEN = 0, KIND_HL = 1, KIND_ERASER = 2;
    private static final String[] KEYS = {"presets_pen", "presets_hl", "presets_eraser"};

    public static class Preset {
        public String name;
        public int type;       // 펜 종류 (지우개는 0=부분 1=획 2=형광펜만)
        public float width;
        public int color;
        public boolean square;

        Preset(String name, int type, float width, int color, boolean square) {
            this.name = name;
            this.type = type;
            this.width = width;
            this.color = color;
            this.square = square;
        }

        JSONObject json() throws Exception {
            JSONObject o = new JSONObject();
            o.put("n", name);
            o.put("t", type);
            o.put("w", (double) width);
            o.put("c", color);
            o.put("s", square);
            return o;
        }

        static Preset from(JSONObject o) {
            return new Preset(o.optString("n"), o.optInt("t"), (float) o.optDouble("w", 4), o.optInt("c", 0xFF1A1A1A), o.optBoolean("s"));
        }
    }

    public static Preset make(String name, int type, float width, int color, boolean square) {
        return new Preset(name, type, width, color, square);
    }

    public static ArrayList<Preset> defaults(int kind) {
        ArrayList<Preset> l = new ArrayList<>();
        if (kind == KIND_PEN) {
            l.add(new Preset("볼펜", StrokeElem.PEN, 4, 0xFF1A1A1A, false));
            l.add(new Preset("만년필", StrokeElem.FOUNTAIN, 5, 0xFF1E3A8A, false));
            l.add(new Preset("붓펜", StrokeElem.BRUSH, 6, 0xFF1A1A1A, false));
            l.add(new Preset("연필", StrokeElem.PENCIL, 4, 0xFF4A4A4A, false));
            l.add(new Preset("마커 (네모 끝)", StrokeElem.MARKER, 6, 0xFF2563EB, true));
            l.add(new Preset("에어브러시", StrokeElem.AIRBRUSH, 14, 0xFFDB2777, false));
            l.add(new Preset("붓/수채", StrokeElem.WATERCOLOR, 16, 0xFF06B6D4, false));
        } else if (kind == KIND_HL) {
            l.add(new Preset("형광펜", StrokeElem.HIGHLIGHTER, 26, 0xFFFFEB3B, false));
            l.add(new Preset("형광펜 (네모 끝)", StrokeElem.HIGHLIGHTER, 26, 0xFFA5D6A7, true));
            l.add(new Preset("굵은 형광펜", StrokeElem.HIGHLIGHTER, 44, 0xFFF48FB1, true));
        } else {
            l.add(new Preset("부분 지우개", 0, 24, 0, false));
            l.add(new Preset("큰 부분 지우개", 0, 64, 0, false));
            l.add(new Preset("획 지우개", 1, 24, 0, false));
            l.add(new Preset("형광펜만 지우기", 2, 32, 0, false));
        }
        return l;
    }

    public static ArrayList<Preset> load(Context c, int kind) {
        String s = new Prefs(c).s(KEYS[kind], "");
        if (s.isEmpty()) return defaults(kind);
        ArrayList<Preset> l = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) l.add(Preset.from(a.getJSONObject(i)));
        } catch (Exception e) {
            return defaults(kind);
        }
        return l;
    }

    public static void save(Context c, int kind, ArrayList<Preset> l) {
        JSONArray a = new JSONArray();
        try {
            for (Preset p : l) a.put(p.json());
        } catch (Exception ignored) {
        }
        new Prefs(c).put(KEYS[kind], a.toString());
    }

    public interface Host {
        /** 현재 도구 설정을 Preset 으로 */
        Preset current(int kind);

        void apply(int kind, Preset p);
    }

    /** 미리보기 선 */
    static class Sample extends View {
        final Preset p;
        final int kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Sample(Context c, Preset p, int kind) {
            super(c);
            this.p = p;
            this.kind = kind;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float d = getResources().getDisplayMetrics().density;
            if (kind == KIND_ERASER) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.5f * d);
                paint.setColor(0xFF9E9E9E);
                float r = Math.min(h / 2 - 2 * d, Math.max(4 * d, p.width * d / 3f));
                c.drawCircle(h / 2 + 4 * d, h / 2, r, paint);
                return;
            }
            float shown = kind == KIND_HL ? Math.min(h * 0.6f, p.width * d / 2.2f) : Math.min(h * 0.32f, p.width * d / 1.6f + d);
            StrokeElem s = new StrokeElem(kind == KIND_HL ? StrokeElem.HIGHLIGHTER : p.type, p.color, p.square);
            int steps = 60;
            for (int i = 0; i <= steps; i++) {
                float t = (float) i / steps;
                float x = 10 * d + t * (w - 20 * d);
                float y = h / 2 + (float) Math.sin(t * Math.PI * 1.6) * h * 0.18f;
                float f = kind == KIND_HL ? 1f : 0.35f + 0.65f * (float) Math.sin(t * Math.PI);
                float base = kind == KIND_HL ? shown : CanvasView.penWidthFor(p.type, shown / 1.3f, f);
                s.add(x, y, base);
            }
            s.draw(c, null);
        }
    }

    /** 목록 UI (도구 옵션 창 맨 위에 넣음) */
    public static View list(Activity a, int kind, Host host, Runnable onChanged) {
        LinearLayout box = Ui.col(a);
        ArrayList<Preset> list = load(a, kind);
        Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            box.removeAllViews();
            Preset cur = host.current(kind);
            for (int i = 0; i < list.size(); i++) {
                Preset p = list.get(i);
                final int idx = i;
                boolean on = same(p, cur, kind);
                LinearLayout row = Ui.row(a);
                row.setBackground(Ui.roundStroke(on ? 0xFFEEF1FF : 0xFFFFFFFF, Ui.dp(a, 10), on ? Ui.ACCENT : 0x14000000, Ui.dp(a, on ? 2 : 1)));
                row.setPadding(Ui.dp(a, 6), Ui.dp(a, 2), Ui.dp(a, 10), Ui.dp(a, 2));
                LinearLayout txt = Ui.col(a);
                Sample sample = new Sample(a, p, kind);
                txt.addView(sample, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 30)));
                TextView name = Ui.label(a, p.name, 12, on ? Ui.ACCENT : Ui.SUBTEXT, on);
                name.setPadding(Ui.dp(a, 10), 0, 0, Ui.dp(a, 2));
                txt.addView(name);
                row.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                TextView size = Ui.label(a, String.valueOf(Math.round(p.width * 10) / 10f), 13, Ui.TEXT, true);
                size.setGravity(Gravity.END);
                row.addView(size);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = Ui.dp(a, 6);
                box.addView(row, lp);
                row.setOnClickListener(v -> {
                    host.apply(kind, p);
                    rebuild[0].run();
                    if (onChanged != null) onChanged.run();
                });
                row.setOnLongClickListener(v -> {
                    String[] items = {"이름 바꾸기", "현재 설정으로 덮어쓰기", "목록에서 삭제"};
                    new AlertDialog.Builder(a).setTitle(p.name).setItems(items, (dd, w) -> {
                        if (w == 0) {
                            Dialogs.input(a, "이름", p.name, false, s -> {
                                if (s.trim().isEmpty()) return;
                                p.name = s.trim();
                                save(a, kind, list);
                                rebuild[0].run();
                            });
                        } else if (w == 1) {
                            Preset c = host.current(kind);
                            c.name = p.name;
                            list.set(idx, c);
                            save(a, kind, list);
                            rebuild[0].run();
                        } else {
                            list.remove(idx);
                            save(a, kind, list);
                            rebuild[0].run();
                        }
                    }).show();
                    return true;
                });
            }
            TextView add = Ui.pill(a, "＋ 지금 설정을 목록에 추가", false);
            add.setTextSize(13);
            add.setOnClickListener(v -> {
                Preset c = host.current(kind);
                Dialogs.input(a, "목록에 추가할 이름", c.name, false, s -> {
                    c.name = s.trim().isEmpty() ? c.name : s.trim();
                    list.add(c);
                    save(a, kind, list);
                    rebuild[0].run();
                });
            });
            box.addView(add, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView hint = Ui.label(a, "항목을 길게 누르면 이름 바꾸기·덮어쓰기·삭제", 11, Ui.SUBTEXT, false);
            hint.setPadding(0, Ui.dp(a, 4), 0, 0);
            box.addView(hint);
        };
        rebuild[0].run();
        return box;
    }

    private static boolean same(Preset p, Preset c, int kind) {
        if (kind == KIND_ERASER) return p.type == c.type && Math.abs(p.width - c.width) < 0.5f;
        return p.type == c.type && Math.abs(p.width - c.width) < 0.05f && p.color == c.color && p.square == c.square;
    }
}
