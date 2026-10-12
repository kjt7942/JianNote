package com.kjt.jiannote;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 무료/프리미엄 관리.
 * 무료: 노트북 3권, 페이지당 레이어 2개, 기본 속지 4종, PDF 워터마크.
 * 프리미엄: 노트북·레이어 무제한, 프리미엄 속지, 워터마크 없는 PDF.
 *
 * 판매용 구매는 Google Play 결제(Billing)로 처리하고,
 * 개발자/테스트용 해제 코드 = SHA-256(SECRET + ":" + 기기코드) 앞 12자리 (tools/keygen.py).
 */
public class Premium {
    public static final int FREE_NOTEBOOKS = 3;
    public static final int FREE_LAYERS = 2;
    public static final int MAX_LAYERS = 20;
    static final String SECRET = "JianNote-2026-kjt7942-premium";

    private final SharedPreferences sp;
    private final String deviceCode;

    @SuppressLint("HardwareIds")
    public Premium(Context ctx) {
        sp = ctx.getSharedPreferences("premium", Context.MODE_PRIVATE);
        String aid = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (aid == null) aid = "unknown";
        deviceCode = sha256("dev:" + aid).substring(0, 8).toUpperCase();
    }

    public String deviceCode() {
        return deviceCode;
    }

    public boolean isPremium() {
        return sp.getBoolean("play", false) || devUnlocked();
    }

    /** 개발자/테스트용 해제 코드로 활성화된 경우 */
    public boolean devUnlocked() {
        String c = sp.getString("code", null);
        return c != null && c.equals(expectedCode());
    }

    public boolean activate(String code) {
        String norm = normalize(code);
        if (norm.equals(expectedCode())) {
            sp.edit().putString("code", norm).apply();
            return true;
        }
        return false;
    }

    public void deactivate() {
        sp.edit().remove("code").apply();
    }

    public int notebookLimit() {
        return isPremium() ? Integer.MAX_VALUE : FREE_NOTEBOOKS;
    }

    public int layerLimit() {
        return isPremium() ? MAX_LAYERS : FREE_LAYERS;
    }

    private String expectedCode() {
        String h = sha256(SECRET + ":" + deviceCode).substring(0, 12).toUpperCase();
        return h.substring(0, 4) + "-" + h.substring(4, 8) + "-" + h.substring(8, 12);
    }

    private static String normalize(String code) {
        String s = code.trim().toUpperCase().replaceAll("[^0-9A-F]", "");
        if (s.length() != 12) return "";
        return s.substring(0, 4) + "-" + s.substring(4, 8) + "-" + s.substring(8, 12);
    }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "000000000000000000000000";
        }
    }
}
