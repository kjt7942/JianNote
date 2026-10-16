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
    public boolean favorite;
    public long created;
    public long modified;
    public int pageCount = 1;
    /** 분류(카테고리) 이름, "" = 미분류 */
    public String category = "";
    /** 표지에 사용자 이미지(cover.jpg) 사용 */
    public boolean coverImage;

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
        o.put("category", category);
        o.put("coverImage", coverImage);
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
        m.category = o.optString("category", "");
        m.coverImage = o.optBoolean("coverImage", false);
        return m;
    }

    public float pageW() {
        if (infinite) return 0;
        return landscape ? 1754 : 1240;
    }

    public float pageH() {
        if (infinite) return 0;
        return landscape ? 1240 : 1754;
    }
}
