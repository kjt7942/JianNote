package com.kjt.jiannote.model;

import java.util.Locale;

/** 페이지 크기 (mm). 내부 좌표는 A4 너비 210mm = 1240 단위 (약 150dpi) */
public class PageSize {
    public static final float PX_PER_MM = 1240f / 210f;

    public static final String[] NAMES = {"A4", "A5", "B5", "A3", "B4", "레터", "리갈", "정사각형", "태블릿 화면 (16:10)"};
    public static final float[][] MM = {
            {210, 297}, {148, 210}, {182, 257}, {297, 420}, {257, 364}, {216, 279}, {216, 356}, {210, 210}, {210, 336}
    };

    public static float px(float mm) {
        return mm * PX_PER_MM;
    }

    public static float mm(float px) {
        return px / PX_PER_MM;
    }

    /** 세로 기준 mm 크기에 맞는 이름 (없으면 "직접 입력 W×H mm") */
    public static String label(float wMm, float hMm) {
        float a = Math.min(wMm, hMm), b = Math.max(wMm, hMm);
        for (int i = 0; i < MM.length; i++) {
            if (Math.abs(MM[i][0] - a) < 1.5f && Math.abs(MM[i][1] - b) < 1.5f) return NAMES[i];
        }
        return String.format(Locale.KOREA, "%.0f×%.0fmm", wMm, hMm);
    }
}
