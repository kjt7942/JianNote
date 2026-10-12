package com.kjt.jiannote.model;

import java.util.ArrayList;

public class Layer {
    public String name;
    public boolean visible = true;
    public int alpha = 255;
    public ArrayList<Element> elems = new ArrayList<>();

    public Layer(String name) {
        this.name = name;
    }

    public Layer copy() {
        Layer l = new Layer(name);
        l.visible = visible;
        l.alpha = alpha;
        l.elems = new ArrayList<>(elems);
        return l;
    }
}
