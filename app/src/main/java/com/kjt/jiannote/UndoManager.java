package com.kjt.jiannote;

import com.kjt.jiannote.model.Element;
import com.kjt.jiannote.model.Layer;
import com.kjt.jiannote.model.Page;

import java.util.ArrayList;

public class UndoManager {
    public interface Cmd {
        void undo();

        void redo();

        /** 이 명령이 속한 페이지 인덱스 (-1 = 문서 수준) */
        int page();
    }

    /** 레이어의 요소 목록 스냅샷 교체 */
    public static class ElemsCmd implements Cmd {
        final Layer layer;
        final ArrayList<Element> before, after;
        final int page;

        public ElemsCmd(int page, Layer layer, ArrayList<Element> before, ArrayList<Element> after) {
            this.page = page;
            this.layer = layer;
            this.before = before;
            this.after = after;
        }

        public void undo() { layer.elems = new ArrayList<>(before); }

        public void redo() { layer.elems = new ArrayList<>(after); }

        public int page() { return page; }
    }

    /** 페이지의 레이어 목록 스냅샷 교체 */
    public static class LayersCmd implements Cmd {
        final Page pg;
        final ArrayList<Layer> before, after;
        final int page;

        public LayersCmd(int page, Page pg, ArrayList<Layer> before, ArrayList<Layer> after) {
            this.page = page;
            this.pg = pg;
            this.before = before;
            this.after = after;
        }

        public void undo() { pg.layers = new ArrayList<>(before); }

        public void redo() { pg.layers = new ArrayList<>(after); }

        public int page() { return page; }
    }

    private final ArrayList<Cmd> undo = new ArrayList<>();
    private final ArrayList<Cmd> redo = new ArrayList<>();

    public void push(Cmd c) {
        undo.add(c);
        if (undo.size() > 150) undo.remove(0);
        redo.clear();
    }

    public boolean canUndo() { return !undo.isEmpty(); }

    public boolean canRedo() { return !redo.isEmpty(); }

    public Cmd undo() {
        if (undo.isEmpty()) return null;
        Cmd c = undo.remove(undo.size() - 1);
        c.undo();
        redo.add(c);
        return c;
    }

    public Cmd redo() {
        if (redo.isEmpty()) return null;
        Cmd c = redo.remove(redo.size() - 1);
        c.redo();
        undo.add(c);
        return c;
    }

    public void clear() {
        undo.clear();
        redo.clear();
    }
}
