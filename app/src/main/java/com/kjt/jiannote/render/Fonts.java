package com.kjt.jiannote.render;

import android.content.res.AssetManager;
import android.graphics.Typeface;

/** 글꼴 목록. assets/fonts 의 한글 OFL 글꼴 + 시스템 글꼴 */
public class Fonts {
    public static final String[] NAMES = {
            "기본", "나눔고딕", "나눔명조", "나눔손글씨 펜", "개구쟁이", "도현", "주아", "푸어스토리", "싱글데이", "고정폭"
    };
    private static final String[] REGULAR = {
            null, "NanumGothic-Regular.ttf", "NanumMyeongjo-Regular.ttf", "NanumPenScript-Regular.ttf",
            "Gaegu-Regular.ttf", "DoHyeon-Regular.ttf", "Jua-Regular.ttf", "PoorStory-Regular.ttf",
            "SingleDay-Regular.ttf", null
    };
    private static final String[] BOLD = {
            null, "NanumGothic-Bold.ttf", "NanumMyeongjo-Bold.ttf", null,
            "Gaegu-Bold.ttf", null, null, null, null, null
    };
    public static final int COUNT = NAMES.length;

    private static AssetManager assets;
    private static final Typeface[] regular = new Typeface[COUNT];
    private static final Typeface[] bold = new Typeface[COUNT];

    public static void init(AssetManager am) {
        if (assets == null) assets = am;
    }

    /** 굵게 파일이 있으면 true (없으면 가짜 굵게 사용) */
    public static boolean hasRealBold(int font) {
        if (font < 0 || font >= COUNT) return true;
        return REGULAR[font] == null || BOLD[font] != null;
    }

    public static Typeface get(int font, boolean wantBold) {
        if (font < 0 || font >= COUNT) font = 0;
        if (REGULAR[font] == null) {
            String fam = font == COUNT - 1 ? "monospace" : "sans-serif";
            return Typeface.create(fam, wantBold ? Typeface.BOLD : Typeface.NORMAL);
        }
        Typeface[] cache = wantBold && BOLD[font] != null ? bold : regular;
        String file = wantBold && BOLD[font] != null ? BOLD[font] : REGULAR[font];
        if (cache[font] == null && assets != null) {
            try {
                cache[font] = Typeface.createFromAsset(assets, "fonts/" + file);
            } catch (Exception e) {
                cache[font] = Typeface.DEFAULT;
            }
        }
        return cache[font] != null ? cache[font] : Typeface.DEFAULT;
    }
}
