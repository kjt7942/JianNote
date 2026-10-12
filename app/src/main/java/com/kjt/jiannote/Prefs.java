package com.kjt.jiannote;

import android.content.Context;
import android.content.SharedPreferences;

/** 도구 설정 저장 */
public class Prefs {
    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public int i(String k, int d) { return sp.getInt(k, d); }

    public float f(String k, float d) { return sp.getFloat(k, d); }

    public boolean b(String k, boolean d) { return sp.getBoolean(k, d); }

    public void put(String k, int v) { sp.edit().putInt(k, v).apply(); }

    public void put(String k, float v) { sp.edit().putFloat(k, v).apply(); }

    public void put(String k, boolean v) { sp.edit().putBoolean(k, v).apply(); }

    public String s(String k, String d) { return sp.getString(k, d); }

    public void put(String k, String v) { sp.edit().putString(k, v).apply(); }
}
