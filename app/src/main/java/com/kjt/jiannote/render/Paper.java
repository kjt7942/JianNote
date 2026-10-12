package com.kjt.jiannote.render;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

/** 종이 템플릿(속지) 그리기. 모든 좌표는 월드 좌표. */
public class Paper {
    public static final int BLANK = 0;
    public static final int LINED = 1;
    public static final int GRID = 2;
    public static final int DOT = 3;
    public static final int SMALL_GRID = 4;
    public static final int CORNELL = 5;
    public static final int MUSIC = 6;
    public static final int TODO = 7;

    public static final String[] NAMES = {"무지", "줄노트", "모눈", "도트", "촘촘한 모눈", "코넬노트", "오선지", "체크리스트"};
    public static final int COUNT = NAMES.length;

    public static boolean isPremium(int t) {
        return t >= SMALL_GRID;
    }

    public static final int[] PAPER_COLORS = {0xFFFFFFFF, 0xFFFFF8E7, 0xFFF1F8E9, 0xFFE3F2FD, 0xFF2B2B2E};
    public static final String[] PAPER_NAMES = {"흰색", "크림", "연두", "하늘", "다크"};

    private static final Paint LINE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint ACCENT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint FILL = new Paint(Paint.ANTI_ALIAS_FLAG);

    public static boolean isDark(int color) {
        return (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000 < 110;
    }

    /** 페이지(유한)의 배경을 그린다. */
    public static void drawPage(Canvas c, int template, int paperColor, float pw, float ph, RectF vis, float scale) {
        FILL.setColor(paperColor);
        FILL.setStyle(Paint.Style.FILL);
        c.drawRect(0, 0, pw, ph, FILL);
        RectF r = new RectF(0, 0, pw, ph);
        if (vis != null && !r.intersect(vis)) return;
        setup(paperColor, scale);
        float top = 150;
        switch (template) {
            case LINED: {
                hLines(c, r, top, ph - 60, 50, 0, pw);
                ACCENT.setColor(0x66E57373);
                c.drawLine(120, 0, 120, ph, ACCENT);
                break;
            }
            case GRID: grid(c, r, 50, scale); break;
            case SMALL_GRID: grid(c, r, 25, scale); break;
            case DOT: dots(c, r, 50, scale); break;
            case CORNELL: {
                float cue = pw * 0.3f, sum = ph - 350;
                hLines(c, r, top, sum - 10, 50, cue, pw);
                ACCENT.setColor(LINE.getColor());
                ACCENT.setStrokeWidth(2.5f);
                c.drawLine(0, top - 10, pw, top - 10, ACCENT);
                c.drawLine(cue, top - 10, cue, sum, ACCENT);
                c.drawLine(0, sum, pw, sum, ACCENT);
                ACCENT.setStrokeWidth(1.5f);
                break;
            }
            case MUSIC: {
                float y = top;
                while (y + 64 < ph - 60) {
                    for (int i = 0; i < 5; i++) {
                        float ly = y + i * 16;
                        if (ly >= r.top - 2 && ly <= r.bottom + 2) c.drawLine(80, ly, pw - 80, ly, LINE);
                    }
                    y += 150;
                }
                break;
            }
            case TODO: {
                for (float y = top; y < ph - 60; y += 60) {
                    if (y < r.top - 60 || y > r.bottom + 60) continue;
                    c.drawLine(140, y, pw - 80, y, LINE);
                    c.drawRect(90, y - 38, 120, y - 8, LINE);
                }
                break;
            }
            default: break;
        }
    }

    /** 무한 캔버스 배경 (보이는 영역만). */
    public static void drawInfinite(Canvas c, int template, int paperColor, RectF vis, float scale) {
        FILL.setColor(paperColor);
        FILL.setStyle(Paint.Style.FILL);
        c.drawRect(vis, FILL);
        setup(paperColor, scale);
        switch (template) {
            case LINED:
            case CORNELL:
            case TODO:
                if (50 * scale >= 5) {
                    hLines(c, vis, (float) Math.floor(vis.top / 50) * 50, vis.bottom, 50, vis.left, vis.right);
                }
                break;
            case GRID: grid(c, vis, 50, scale); break;
            case SMALL_GRID: grid(c, vis, 25, scale); break;
            case DOT: dots(c, vis, 50, scale); break;
            case MUSIC: {
                float start = (float) Math.floor(vis.top / 150) * 150;
                for (float y = start; y < vis.bottom; y += 150) {
                    for (int i = 0; i < 5; i++) c.drawLine(vis.left, y + i * 16, vis.right, y + i * 16, LINE);
                }
                break;
            }
            default: break;
        }
    }

    private static void setup(int paperColor, float scale) {
        boolean dark = isDark(paperColor);
        LINE.setColor(dark ? 0x44FFFFFF : 0x40607D8B);
        LINE.setStyle(Paint.Style.STROKE);
        LINE.setStrokeWidth(Math.max(1.5f, 1f / Math.max(scale, 0.01f)));
        ACCENT.setStyle(Paint.Style.STROKE);
        ACCENT.setStrokeWidth(Math.max(1.5f, 1f / Math.max(scale, 0.01f)));
    }

    private static void hLines(Canvas c, RectF r, float from, float to, float sp, float x0, float x1) {
        float start = r.top > from ? from + (float) Math.ceil((r.top - from) / sp) * sp : from;
        float end = Math.min(to, r.bottom);
        for (float y = start; y <= end; y += sp) {
            c.drawLine(Math.max(x0, r.left), y, Math.min(x1, r.right), y, LINE);
        }
    }

    private static void grid(Canvas c, RectF r, float sp, float scale) {
        if (sp * scale < 5) return;
        float sx = (float) Math.floor(r.left / sp) * sp;
        float sy = (float) Math.floor(r.top / sp) * sp;
        for (float x = sx; x <= r.right; x += sp) if (x >= r.left) c.drawLine(x, r.top, x, r.bottom, LINE);
        for (float y = sy; y <= r.bottom; y += sp) if (y >= r.top) c.drawLine(r.left, y, r.right, y, LINE);
    }

    private static void dots(Canvas c, RectF r, float sp, float scale) {
        if (sp * scale < 8) return;
        FILL.setColor(LINE.getColor() | 0x30000000);
        float rad = Math.max(2.2f, 1.6f / scale);
        float sx = (float) Math.floor(r.left / sp) * sp;
        float sy = (float) Math.floor(r.top / sp) * sp;
        for (float x = sx; x <= r.right; x += sp) {
            if (x < r.left) continue;
            for (float y = sy; y <= r.bottom; y += sp) {
                if (y < r.top) continue;
                c.drawCircle(x, y, rad, FILL);
            }
        }
    }
}
