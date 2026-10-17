package com.kjt.jiannote.model;

import java.util.ArrayList;

public class Page {
    /** 0이면 무한 캔버스 */
    public float w, h;
    public int template;
    public boolean bookmarked;
    /** PDF 가져오기 등 배경 이미지 파일명 (images/ 폴더), 없으면 null */
    public String bgImage;
    /** 배경의 원본 PDF 파일명 (images/ 폴더)과 페이지 번호(0부터). 있으면 확대할 때 원본에서 선명하게 다시 그림 */
    public String bgPdf;
    public int bgPdfPage;
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

    /** 사진·PDF 로 만든 속지 이미지인지 (tpl_ 로 시작) */
    public boolean hasImageTemplate() {
        return bgImage != null && bgImage.startsWith("tpl_");
    }

    /** 사진·PDF 배경 설정 (pdf 가 null 이면 원본 PDF 없음), image 가 null 이면 배경 지우기 */
    public void setBackground(String image, String pdf, int pdfPage) {
        bgImage = image;
        bgPdf = image == null ? null : pdf;
        bgPdfPage = bgPdf == null ? 0 : pdfPage;
    }

    /** 같은 크기·속지(사진/PDF 속지 포함)의 빈 페이지 */
    public Page blankLike() {
        Page p = create(w, h, template);
        if (hasImageTemplate()) p.setBackground(bgImage, bgPdf, bgPdfPage);
        return p;
    }

    public boolean isInfinite() {
        return w <= 0 || h <= 0;
    }

    public Page copy() {
        Page p = new Page(w, h, template);
        p.bookmarked = bookmarked;
        p.setBackground(bgImage, bgPdf, bgPdfPage);
        for (Layer l : layers) p.layers.add(l.copy());
        return p;
    }

    public boolean isEmpty() {
        for (Layer l : layers) if (!l.elems.isEmpty()) return false;
        return true;
    }
}
