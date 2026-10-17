package com.kjt.jiannote.model;

import org.json.JSONException;
import org.json.JSONObject;

public class NotebookMeta {
    public String id;
    public String title;
    public int coverColor;
    public int paperColor;
    public int template;
    public boolean infinite;
    public boolean landscape;
    /** 페이지 크기 (세로 기준 mm). 기본 A4 */
    public float sizeWmm = 210, sizeHmm = 297;
    public boolean favorite;
    public long created;
    public long modified;
    public int pageCount = 1;
    /** 분류(카테고리) 이름, "" = 미분류 */
    public String category = "";
    /** 표지에 사용자 이미지(cover.jpg) 사용 */
    public boolean coverImage;
    /** 가져온 PDF 의 원래 파일 (이름 + 크기) — 다른 앱에서 같은 PDF 를 다시 열면 이 노트를 연다 */
    public String pdfSource = "";

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("title", title);
        o.put("cover", coverColor);
        o.put("paper", paperColor);
        o.put("template", template);
        o.put("infinite", infinite);
        o.put("landscape", landscape);
        o.put("favorite", favorite);
        o.put("created", created);
        o.put("modified", modified);
        o.put("pages", pageCount);
        o.put("sizeW", (double) sizeWmm);
        o.put("sizeH", (double) sizeHmm);
        o.put("category", category);
        o.put("coverImage", coverImage);
        o.put("pdfSource", pdfSource);
        return o;
    }

    public static NotebookMeta fromJson(JSONObject o) {
        NotebookMeta m = new NotebookMeta();
        m.id = o.optString("id");
        m.title = o.optString("title", "제목 없음");
        m.coverColor = o.optInt("cover", 0xFF3D5AFE);
        m.paperColor = o.optInt("paper", 0xFFFFFFFF);
        m.template = o.optInt("template", 0);
        m.infinite = o.optBoolean("infinite", false);
        m.landscape = o.optBoolean("landscape", false);
        m.favorite = o.optBoolean("favorite", false);
        m.created = o.optLong("created", System.currentTimeMillis());
        m.modified = o.optLong("modified", m.created);
        m.pageCount = o.optInt("pages", 1);
        m.sizeWmm = (float) o.optDouble("sizeW", 210);
        m.sizeHmm = (float) o.optDouble("sizeH", 297);
        m.category = o.optString("category", "");
        m.coverImage = o.optBoolean("coverImage", false);
        m.pdfSource = o.optString("pdfSource", "");
        return m;
    }

    /** 새 노트를 무엇으로 시작할지 (저장하지 않음): 0=빈 노트 1=사진 2=PDF */
    public transient int startFrom;

    /** 일반 페이지 크기 (무한 캔버스 노트에 일반 페이지를 추가할 때도 사용) */
    public float pagedW() {
        return PageSize.px(landscape ? Math.max(sizeWmm, sizeHmm) : Math.min(sizeWmm, sizeHmm));
    }

    public float pagedH() {
        return PageSize.px(landscape ? Math.min(sizeWmm, sizeHmm) : Math.max(sizeWmm, sizeHmm));
    }

    public float pageW() {
        if (infinite) return 0;
        return PageSize.px(landscape ? Math.max(sizeWmm, sizeHmm) : Math.min(sizeWmm, sizeHmm));
    }

    public float pageH() {
        if (infinite) return 0;
        return PageSize.px(landscape ? Math.min(sizeWmm, sizeHmm) : Math.max(sizeWmm, sizeHmm));
    }
}
