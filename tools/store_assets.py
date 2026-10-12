#!/usr/bin/env python3
"""플레이 스토어용 앱 아이콘(512)과 그래픽 이미지(1024x500) 생성"""
import math, sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter

FONT = "app/src/main/assets/fonts/NanumGothic-Bold.ttf"
OUT = sys.argv[1] if len(sys.argv) > 1 else "store"

def grad(w, h, c1, c2):
    im = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(im)
    for i in range(w + h):
        t = i / (w + h)
        c = tuple(int(c1[k] + (c2[k] - c1[k]) * t) for k in range(3))
        d.line([(i, 0), (i - h, h)], fill=c, width=2)
    return im

def notebook(d, x, y, s, stroke_col=(61, 90, 254)):
    """108단위 아이콘 좌표계를 (x,y) 기준 s배로 그림"""
    P = lambda a, b: (x + a * s, y + b * s)
    d.rounded_rectangle([P(30, 26), P(76, 82)], radius=5 * s, fill=(255, 255, 255))
    d.rectangle([P(30, 26), P(37, 82)], fill=(255, 213, 79))
    for yy in (42, 50, 58):
        d.line([P(43, yy), P(68, yy)], fill=(197, 202, 233), width=max(1, int(1.8 * s)))
    pts = []
    for i in range(41):
        t = i / 40
        xx = 43 + 24 * t
        yy = 69 - 5 * math.sin(t * math.pi * 2.2) - 2 * t
        pts.append(P(xx, yy))
    d.line(pts, fill=stroke_col, width=int(3.4 * s), joint="curve")

def icon(size=512):
    S = 4
    W = size * S
    im = grad(W, W, (61, 90, 254), (124, 77, 255))
    d = ImageDraw.Draw(im)
    s = W / 108
    # 그림자
    sh = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    sd = ImageDraw.Draw(sh)
    sd.rounded_rectangle([(31 * s, 28.5 * s), (77 * s, 84.5 * s)], radius=5 * s, fill=(0, 0, 0, 70))
    sh = sh.filter(ImageFilter.GaussianBlur(3 * s))
    im.paste(sh, (0, 0), sh)
    notebook(d, 0, 0, s)
    return im.resize((size, size), Image.LANCZOS)

def feature():
    S = 2
    W, H = 1024 * S, 500 * S
    im = grad(W, H, (49, 70, 220), (124, 77, 255)).convert("RGBA")
    d = ImageDraw.Draw(im)
    big = ImageFont.truetype(FONT, 92 * S)
    mid = ImageFont.truetype(FONT, 30 * S)
    small = ImageFont.truetype(FONT, 22 * S)
    d.text((64 * S, 120 * S), "JianNote", font=big, fill=(255, 255, 255))
    d.text((68 * S, 240 * S), "S펜으로 쓰는 진짜 같은 손글씨 노트", font=mid, fill=(255, 255, 255))
    chips = ["필압 감지", "레이어", "무한 캔버스", "PDF 필기"]
    x = 68 * S
    ov = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    od = ImageDraw.Draw(ov)
    pos = []
    for c in chips:
        tw = d.textlength(c, font=small)
        od.rounded_rectangle([(x, 300 * S), (x + tw + 32 * S, 344 * S)], radius=22 * S, fill=(255, 255, 255, 60))
        pos.append((x, c))
        x += tw + 44 * S
    im = Image.alpha_composite(im, ov)
    d = ImageDraw.Draw(im)
    for x0, c in pos:
        d.text((x0 + 16 * S, 309 * S), c, font=small, fill=(255, 255, 255))
    # 태블릿
    tx, ty, tw_, th_ = 600 * S, 70 * S, 380 * S, 380 * S
    shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle([(tx + 10 * S, ty + 16 * S), (tx + tw_ + 10 * S, ty + th_ + 16 * S)], radius=34 * S, fill=(0, 0, 0, 90))
    shadow = shadow.filter(ImageFilter.GaussianBlur(14 * S))
    im = Image.alpha_composite(im, shadow)
    d = ImageDraw.Draw(im)
    d.rounded_rectangle([(tx, ty), (tx + tw_, ty + th_)], radius=34 * S, fill=(30, 30, 36))
    px, py, pw, ph = tx + 18 * S, ty + 18 * S, tw_ - 36 * S, th_ - 36 * S
    d.rounded_rectangle([(px, py), (px + pw, py + ph)], radius=18 * S, fill=(255, 253, 248))
    for i in range(1, 12):
        yy = py + i * 28 * S
        d.line([(px + 20 * S, yy), (px + pw - 20 * S, yy)], fill=(214, 220, 232), width=2 * S)
    # 손글씨 획
    def scribble(y0, length, amp, col, w):
        pts = []
        for i in range(120):
            t = i / 119
            pts.append((px + 34 * S + length * t, y0 - amp * abs(math.sin(t * 18)) + 4 * S * math.sin(t * 5)))
        d.line(pts, fill=col, width=w, joint="curve")
    scribble(py + 52 * S, 220 * S, 14 * S, (26, 26, 26), 4 * S)
    scribble(py + 108 * S, 260 * S, 12 * S, (26, 26, 26), 3 * S)
    d.rectangle([(px + 30 * S, py + 128 * S), (px + 230 * S, py + 146 * S)], fill=(255, 235, 59, 140))
    scribble(py + 164 * S, 180 * S, 12 * S, (37, 99, 235), 4 * S)
    d.ellipse([(px + 200 * S, py + 200 * S), (px + 310 * S, py + 290 * S)], outline=(220, 38, 38), width=4 * S)
    # 펜
    pen = [(tx + tw_ - 40 * S, ty + th_ + 10 * S), (tx + tw_ + 20 * S, ty + th_ - 120 * S)]
    d.line(pen, fill=(60, 60, 70), width=16 * S)
    d.line([(tx + tw_ - 40 * S, ty + th_ + 10 * S), (tx + tw_ - 47 * S, ty + th_ + 26 * S)], fill=(20, 20, 20), width=6 * S)
    return im.convert("RGB").resize((1024, 500), Image.LANCZOS)

icon(512).save(f"{OUT}/icon_512.png")
feature().save(f"{OUT}/feature_graphic_1024x500.png")
print("done")
