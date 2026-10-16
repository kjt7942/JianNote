package com.kjt.jiannote.model;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

/** 노트북 하나의 전체 내용 (페이지 → 레이어 → 요소). 바이너리로 저장. */
public class Document {
    private static final int MAGIC = 0x494E4B53; // INKS
    private static final int VERSION = 5;

    public ArrayList<Page> pages = new ArrayList<>();

    public void write(File f) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16))) {
            o.writeInt(MAGIC);
            o.writeInt(VERSION);
            o.writeInt(pages.size());
            for (Page p : pages) {
                o.writeFloat(p.w);
                o.writeFloat(p.h);
                o.writeInt(p.template);
                o.writeBoolean(p.bookmarked);
                o.writeUTF(p.bgImage == null ? "" : p.bgImage);
                o.writeInt(p.layers.size());
                for (Layer l : p.layers) {
                    o.writeUTF(l.name);
                    o.writeBoolean(l.visible);
                    o.writeInt(l.alpha);
                    o.writeInt(l.elems.size());
                    for (Element e : l.elems) {
                        o.writeByte(e.locked ? 1 : 0);
                        o.writeInt(e.group);
                        e.write(o);
                    }
                }
            }
        }
        // 직전 저장본을 .bak 으로 보관해 파일 손상에 대비
        File bak = new File(f.getPath() + ".bak");
        if (f.exists()) {
            if (bak.exists()) bak.delete();
            if (!f.renameTo(bak)) f.delete();
        }
        if (!tmp.renameTo(f)) throw new IOException("rename failed");
    }

    /** 요소 목록만 저장/읽기 (스티커용) */
    public static void writeElems(DataOutputStream o, java.util.List<Element> elems) throws IOException {
        o.writeInt(MAGIC);
        o.writeInt(VERSION);
        o.writeInt(elems.size());
        for (Element e : elems) {
            o.writeByte(e.locked ? 1 : 0);
            o.writeInt(e.group);
            e.write(o);
        }
    }

    public static ArrayList<Element> readElems(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC) throw new IOException("not an InkShelf clip");
        in.readInt();
        int n = in.readInt();
        ArrayList<Element> out = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            boolean locked = (in.readByte() & 1) != 0;
            int group = in.readInt();
            Element e = readElem(in);
            e.locked = locked;
            e.group = group;
            out.add(e);
        }
        return out;
    }

    private static Element readElem(DataInputStream in) throws IOException {
        byte t = in.readByte();
        switch (t) {
            case Element.T_STROKE: return StrokeElem.read(in);
            case Element.T_TEXT: return TextElem.readV1(in);
            case Element.T_IMAGE: return ImageElem.read(in);
            case Element.T_TEXT2: return TextElem.read(in);
            case Element.T_TAPE: return TapeElem.read(in);
            case Element.T_MEMO: return MemoElem.read(in);
            default: throw new IOException("unknown element " + t);
        }
    }

    public static Document read(File f) throws IOException {
        Document d = new Document();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 1 << 16))) {
            if (in.readInt() != MAGIC) throw new IOException("not an InkShelf file");
            int ver = in.readInt();
            int pc = in.readInt();
            for (int i = 0; i < pc; i++) {
                Page p = new Page(in.readFloat(), in.readFloat(), in.readInt());
                if (ver >= 2) p.bookmarked = in.readBoolean();
                if (ver >= 3) {
                    String bg = in.readUTF();
                    p.bgImage = bg.isEmpty() ? null : bg;
                }
                int lc = in.readInt();
                for (int j = 0; j < lc; j++) {
                    Layer l = new Layer(in.readUTF());
                    l.visible = in.readBoolean();
                    l.alpha = in.readInt();
                    int ec = in.readInt();
                    for (int k = 0; k < ec; k++) {
                        boolean locked = ver >= 4 && (in.readByte() & 1) != 0;
                        int group = ver >= 5 ? in.readInt() : 0;
                        byte t = in.readByte();
                        switch (t) {
                            case Element.T_STROKE: l.elems.add(StrokeElem.read(in)); break;
                            case Element.T_TEXT: l.elems.add(TextElem.readV1(in)); break;
                            case Element.T_TEXT2: l.elems.add(TextElem.read(in)); break;
                            case Element.T_TAPE: l.elems.add(TapeElem.read(in)); break;
                            case Element.T_MEMO: l.elems.add(MemoElem.read(in)); break;
                            case Element.T_IMAGE: l.elems.add(ImageElem.read(in)); break;
                            default: throw new IOException("unknown element " + t);
                        }
                        l.elems.get(l.elems.size() - 1).locked = locked;
                        l.elems.get(l.elems.size() - 1).group = group;
                    }
                    p.layers.add(l);
                }
                if (p.layers.isEmpty()) p.layers.add(new Layer("레이어 1"));
                d.pages.add(p);
            }
        }
        return d;
    }
}
