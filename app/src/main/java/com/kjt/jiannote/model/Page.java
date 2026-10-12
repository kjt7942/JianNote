package com.kjt.jiannote.model;

import java.util.ArrayList;

public class Page {
    /** 0이면 무한 캔버스 */
    public float w, h;
    public int template;
    public ArrayList<Layer> layers = new ArrayList<>();

    public Page(float w, float h, int template) {
        this.w = w;
        this.h = h;
        this.template = template;
    }

    public static Page create(float w, float h, int template) {
        Page p = new Page(w, h, template);
        p.layers.add(new Layer("레이어 1"));
        return p;
    }

    public boolean isInfinite() {
        return w <= 0 || h <= 0;
    }

    public Page copy() {
        Page p = new Page(w, h, template);
        for (Layer l : layers) p.layers.add(l.copy());
        return p;
    }

    public boolean isEmpty() {
        for (Layer l : layers) if (!l.elems.isEmpty()) return false;
        return true;
    }
}
