package com.kjt.jiannote;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** 코드로 UI를 만들기 위한 도우미 */
public class Ui {
    public static final int ACCENT = 0xFF3D5AFE;
    public static final int BG = 0xFFF4F1EA;
    public static final int SURFACE = 0xFFFFFFFF;
    public static final int TEXT = 0xFF1F1F24;
    public static final int SUBTEXT = 0xFF6B6B75;
    public static final int GOLD = 0xFFF5A623;

    public static final int[] PEN_COLORS = {
            0xFF1A1A1A, 0xFF5F6368, 0xFFFFFFFF, 0xFF1E3A8A, 0xFF2563EB, 0xFF06B6D4,
            0xFF16A34A, 0xFF84CC16, 0xFFFACC15, 0xFFF97316, 0xFFDC2626, 0xFFDB2777,
            0xFF9333EA, 0xFF7C2D12, 0xFFA16207, 0xFF0F766E
    };
    public static final int[] HL_COLORS = {
            0xFFFFEB3B, 0xFFFFC107, 0xFFFF8A80, 0xFFF48FB1, 0xFFB39DDB, 0xFF80D8FF, 0xFFA5D6A7, 0xFFCCFF90
    };
    public static final int[] COVER_COLORS = {
            0xFF3D5AFE, 0xFF26A69A, 0xFFEF5350, 0xFFFFA726, 0xFF8D6E63, 0xFF5C6BC0, 0xFF263238, 0xFFEC407A,
            0xFF7E57C2, 0xFF66BB6A
    };

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    public static GradientDrawable roundStroke(int color, float radiusPx, int strokeColor, int strokePx) {
        GradientDrawable d = round(color, radiusPx);
        d.setStroke(strokePx, strokeColor);
        return d;
    }

    public static RippleDrawable ripple(int bg, float radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x22000000), round(bg, radius), round(Color.WHITE, radius));
    }

    /** 아이콘(이모지) + 작은 글씨 버튼 */
    public static TextView toolButton(Context c, String icon, String label) {
        TextView t = new TextView(c);
        t.setText(label == null ? icon : icon + "\n" + label);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(TEXT);
        t.setTextSize(label == null ? 18 : 11);
        t.setLineSpacing(0, 0.95f);
        t.setMinWidth(dp(c, 48));
        t.setPadding(dp(c, 8), dp(c, 4), dp(c, 8), dp(c, 4));
        t.setBackground(ripple(Color.TRANSPARENT, dp(c, 10)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(c, 48));
        lp.setMargins(dp(c, 2), 0, dp(c, 2), 0);
        t.setLayoutParams(lp);
        return t;
    }

    public static void setSelected(View v, boolean sel) {
        Context c = v.getContext();
        v.setBackground(sel ? ripple(0xFFE3E7FF, dp(c, 10)) : ripple(Color.TRANSPARENT, dp(c, 10)));
        if (v instanceof TextView) ((TextView) v).setTextColor(sel ? ACCENT : TEXT);
    }

    public static TextView pill(Context c, String text, boolean primary) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(primary ? Color.WHITE : TEXT);
        t.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));
        t.setBackground(primary ? ripple(ACCENT, dp(c, 22)) : ripple(0xFFECE8DF, dp(c, 22)));
        return t;
    }

    public static TextView label(Context c, String text, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    public static View divider(Context c, boolean vertical) {
        View v = new View(c);
        v.setBackgroundColor(0x1F000000);
        if (vertical) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 1), dp(c, 28));
            lp.gravity = Gravity.CENTER_VERTICAL;
            lp.setMargins(dp(c, 6), 0, dp(c, 6), 0);
            v.setLayoutParams(lp);
        } else {
            v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 1)));
        }
        return v;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public interface IntCb {
        void on(int v);
    }

    public static SeekBar seek(Context c, int max, int value, IntCb cb) {
        SeekBar s = new SeekBar(c);
        s.setMax(max);
        s.setProgress(value);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) cb.on(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        return s;
    }

    /** 원형 색상 견본 */
    public static class Swatch extends View {
        private final int color;
        private boolean selected;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        public Swatch(Context c, int color) {
            super(c);
            this.color = color;
        }

        public int color() {
            return color;
        }

        public void setChosen(boolean s) {
            selected = s;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) - dp(getContext(), 3);
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            c.drawCircle(cx, cy, r, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(getContext(), 1));
            p.setColor(0x33000000);
            c.drawCircle(cx, cy, r, p);
            if (selected) {
                p.setStrokeWidth(dp(getContext(), 2.5f));
                p.setColor(ACCENT);
                c.drawCircle(cx, cy, r + dp(getContext(), 2), p);
            }
        }
    }

    /** 굵기 미리보기 점 */
    public static class Dot extends View {
        private float size;
        private int color;
        private boolean selected;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        public Dot(Context c, float sizePx, int color) {
            super(c);
            this.size = sizePx;
            this.color = color;
        }

        public void set(float sizePx, int color) {
            this.size = sizePx;
            this.color = color;
            invalidate();
        }

        public void setChosen(boolean s) {
            selected = s;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            if (selected) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFE3E7FF);
                c.drawCircle(cx, cy, Math.min(cx, cy) - 1, p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            c.drawCircle(cx, cy, Math.max(1.5f, Math.min(size / 2f, Math.min(cx, cy) - 4)), p);
        }
    }
}
