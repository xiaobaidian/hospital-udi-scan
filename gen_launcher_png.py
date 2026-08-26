#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成扫码枪启动图标 PNG（mdpi~xxxhdpi），覆盖 API<26 设备。
白枪 + 琥珀扫描光 + 蓝底，与自适应图标视觉一致。4x 超采样抗锯齿。"""
import math
import os
from PIL import Image, ImageDraw

BASE = "#0D47A1"
GUN = "#FFFFFF"
LENS = "#BBDEFB"
BEAM = "#FFB300"

SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

OUT_DIR = os.path.join(os.path.dirname(__file__), "app", "src", "main", "res")


def round_rect(d, x0, y0, x1, y1, r, fill):
    d.rounded_rectangle([x0, y0, x1, y1], radius=r, fill=fill)


def draw_gun(d, S):
    # 机身（圆角矩形，右侧）
    round_rect(d, 36 * S, 12 * S, 88 * S, 52 * S, 9 * S, GUN)
    # 斜手柄（平行四边形）
    d.polygon(
        [(16 * S, 82 * S), (40 * S, 58 * S), (50 * S, 68 * S), (26 * S, 92 * S)],
        fill=GUN,
    )
    # 扫描光（琥珀三角）
    d.polygon(
        [(60 * S, 42 * S), (48 * S, 58 * S), (72 * S, 58 * S)],
        fill=BEAM,
    )
    # 镜头（浅蓝圆）
    cx, cy, r = 60 * S, 32 * S, 9 * S
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=LENS)


def gen(size_px):
    SS = 4  # 超采样
    big = size_px * SS
    img = Image.new("RGBA", (big, big), BASE)
    d = ImageDraw.Draw(img)
    S = big / 100.0
    draw_gun(d, S)
    img = img.resize((size_px, size_px), Image.LANCZOS)
    return img


def main():
    for folder, px in SIZES.items():
        d = os.path.join(OUT_DIR, folder)
        os.makedirs(d, exist_ok=True)
        p = os.path.join(d, "ic_launcher.png")
        gen(px).save(p, "PNG")
        print("wrote", p, px, "px")


if __name__ == "__main__":
    main()
