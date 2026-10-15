package com.kjt.jiannote;

import android.content.Context;

/** 모든 기능을 제한 없이 제공 (무료/프리미엄 구분 없음) */
public class Premium {
    public static final int MAX_LAYERS = 50;

    public Premium(Context ctx) {
    }

    public boolean isPremium() {
        return true;
    }

    public int notebookLimit() {
        return Integer.MAX_VALUE;
    }

    public int layerLimit() {
        return MAX_LAYERS;
    }
}
