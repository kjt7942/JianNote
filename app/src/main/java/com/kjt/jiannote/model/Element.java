package com.kjt.jiannote.model;

import android.graphics.Canvas;
import android.graphics.RectF;

import com.kjt.jiannote.render.RenderContext;

import java.io.DataOutputStream;
import java.io.IOException;

/** 페이지 위의 모든 객체(획, 텍스트, 이미지)의 공통 부모. 요소는 불변으로 취급한다(변형 시 복사본 생성). */
public abstract class Element {
    public static final byte T_STROKE = 1;
    public static final byte T_TEXT = 2;
    public static final byte T_IMAGE = 3;

    public abstract byte type();

    public abstract RectF bounds();

    public abstract void draw(Canvas c, RenderContext rc);

    public abstract boolean hit(float x, float y, float r);

    /** p' = (p - pivot) * s + pivot + (dx, dy) 로 변형된 복사본 */
    public abstract Element transformed(float dx, float dy, float s, float px, float py);

    public abstract void write(DataOutputStream o) throws IOException;

    public Element withColor(int color) {
        return this;
    }

    public float centerX() {
        RectF b = bounds();
        return b.centerX();
    }

    public float centerY() {
        RectF b = bounds();
        return b.centerY();
    }
}
