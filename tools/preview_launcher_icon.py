#!/usr/bin/env python3
"""把生成好的图标拼成一张预览图 —— 在**真实像素尺寸**下看,不是放大看。

为什么预览要单独做一步、而不是直接读那张 432px 的源:
  * 图标最终是**在 48px 上被看见**的,432px 上好看不代表 48px 上能认。
  * 系统会给自适应图标套遮罩(圆/方/圆角),套上之后会不会切到主体,只有
    套一遍才知道。
  * 主题图标(单色层)与美术层是不是同一位置,要在**小尺寸**上对比才看得出来
    —— 差 1px 在 432 上看不见,在 108 上一眼就歪。

所以这张图上有三行:真实尺寸、套遮罩、单色层;每行都同时给出 192/144/96/72/48
五档,末尾把 48px 放大 6 倍(最近邻)供核对细节。

用法:
  python tools/preview_launcher_icon.py --res app/src/main/res --out /tmp/preview.png
"""
import argparse
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

SIZES = [192, 144, 96, 72, 48]
CHECKER = 8
MASK_KINDS = ["circle", "squircle", "square"]

# 标签是中文,必须有中文字体。PIL 的默认位图字体只有 ASCII,不给字体的话
# 打出来是一串豆腐块 —— 预览图就白做了。
FONT_CANDIDATES = [
    "/System/Library/Fonts/Hiragino Sans GB.ttc",
    "/System/Library/Fonts/STHeiti Medium.ttc",
    "/System/Library/Fonts/Supplemental/Songti.ttc",
    "/Library/Fonts/Arial Unicode.ttf",
]


def load_font(size=15):
    from PIL import ImageFont
    for p in FONT_CANDIDATES:
        if Path(p).exists():
            try:
                return ImageFont.truetype(p, size)
            except Exception:
                continue
    return ImageFont.load_default()


def checkerboard(w, h, a=(58, 58, 64), b=(78, 78, 86)):
    img = Image.new("RGB", (w, h), a)
    d = ImageDraw.Draw(img)
    for y in range(0, h, CHECKER):
        for x in range(0, w, CHECKER):
            if ((x // CHECKER) + (y // CHECKER)) % 2:
                d.rectangle([x, y, x + CHECKER - 1, y + CHECKER - 1], fill=b)
    return img


def mask_for(kind, n, ss=4):
    """在 4 倍上画遮罩再降采样 —— ImageDraw 没有抗锯齿。"""
    big = n * ss
    m = Image.new("L", (big, big), 0)
    d = ImageDraw.Draw(m)
    if kind == "circle":
        d.ellipse([0, 0, big - 1, big - 1], fill=255)
    elif kind == "squircle":
        # 圆角方形,半径取边长的 ~22% —— 各家 launcher 的常见值
        r = int(big * 0.22)
        d.rounded_rectangle([0, 0, big - 1, big - 1], radius=r, fill=255)
    else:
        d.rectangle([0, 0, big - 1, big - 1], fill=255)
    return m.resize((n, n), Image.LANCZOS)


def compose(res: Path, density: str, size: int) -> Image.Image:
    """按系统的方式合成:**中央 72/108 铺满图标位**。

    这一步最容易算错。系统不是把整张 108dp 画布缩到图标位,而是把画布的中央
    72dp 铺满图标位、外面裁掉 —— 也就是画布要按 slot × 1.5 渲染。
    """
    fg = Image.open(res / f"mipmap-{density}/ic_launcher_foreground.webp").convert("RGBA")
    bg = Image.open(res / f"mipmap-{density}/ic_launcher_background.webp").convert("RGB")
    cvs = fg.size[0]
    full = bg.convert("RGBA")
    full.alpha_composite(fg)
    big = int(round(size * 108.0 / 72.0))
    big = max(big, 1)
    s = full.resize((big, big), Image.LANCZOS)
    c = big // 2
    return s.crop((c - size // 2, c - size // 2, c + size // 2, c + size // 2))


def legacy(res: Path, density: str, name: str) -> Image.Image:
    return Image.open(res / f"mipmap-{density}/{name}.webp").convert("RGBA")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--res", default="app/src/main/res")
    ap.add_argument("--out", default="/tmp/launcher-preview.png")
    ap.add_argument("--density", default="xxxhdpi")
    args = ap.parse_args()
    res = Path(args.res)

    ZOOM = 6
    PAD = 18
    row_h = 192 + 46
    labels = ["自适应层 · 真实尺寸(按中央 72/108 铺满)", "套圆形遮罩", "套圆角方形遮罩",
              "传统光栅图(满幅,系统再裁)", "主题图标(单色层)"]
    W = PAD + sum(s + PAD for s in SIZES) + 48 + 48 * ZOOM + PAD
    # ⚠️ 这里是 len(labels),不是写死的 4 —— 写死的时候第五行被画到了画布
    # 外面,单色层在预览里整个不见了,而数据其实是好的(差点去查错的方向)。
    H = PAD + row_h * len(labels) + PAD
    canvas = checkerboard(W, H)
    d = ImageDraw.Draw(canvas)
    font = load_font(15)

    y = PAD
    for label in labels:
        d.text((PAD, y - 16), label, fill=(240, 240, 245), font=font)
        x = PAD
        for sz in SIZES:
            base = Image.new("RGBA", (192, 192), (0, 0, 0, 0))
            if label.startswith("自适应层"):
                tile = compose(res, args.density, sz).convert("RGBA")
            elif label.startswith("传统"):
                tile = legacy(res, args.density, "ic_launcher")
                tile = tile.resize((sz, sz), Image.LANCZOS)
            elif label.startswith("主题"):
                m = legacy(res, args.density, "ic_launcher_monochrome")
                # 单色层的语义是"黑=实体、透明=镂空",在深色底上要反相才看得见
                arr = np.asarray(m).astype(np.uint8).copy()
                arr[:, :, :3] = 235
                m = Image.fromarray(arr, "RGBA")
                tile = m.resize((sz, sz), Image.LANCZOS)
            else:
                kind = "circle" if "圆形" in label else "squircle"
                tile = compose(res, args.density, sz).convert("RGBA")
                tile.putalpha(mask_for(kind, sz))
            base.alpha_composite(tile, (0, 0))
            canvas.paste(base, (x, y), base)
            d.rectangle([x, y, x + 191, y + 191], outline=(96, 96, 106))
            d.text((x, y + 198), f"{sz}px", fill=(200, 200, 210), font=font)
            x += sz + PAD
        # 最后一列:48px 放大 ZOOM 倍,最近邻,用来核对边缘细节
        x += 30
        big = Image.new("RGBA", (48, 48), (0, 0, 0, 0))
        if label.startswith("自适应层"):
            t = compose(res, args.density, 48).convert("RGBA")
        elif label.startswith("传统"):
            t = legacy(res, args.density, "ic_launcher").resize((48, 48), Image.LANCZOS)
        elif label.startswith("主题"):
            m = legacy(res, args.density, "ic_launcher_monochrome")
            arr = np.asarray(m).astype(np.uint8).copy()
            arr[:, :, :3] = 235
            t = Image.fromarray(arr, "RGBA").resize((48, 48), Image.LANCZOS)
        else:
            kind = "circle" if "圆形" in label else "squircle"
            t = compose(res, args.density, 48).convert("RGBA")
            t.putalpha(mask_for(kind, 48))
        big.alpha_composite(t)
        z = big.resize((48 * ZOOM, 48 * ZOOM), Image.NEAREST)
        canvas.paste(z, (x, y), z)
        d.text((x, y + 198), f"48px ×{ZOOM}(最近邻)", fill=(200, 200, 210), font=font)
        y += row_h

    canvas.save(args.out)
    print(f"预览 -> {args.out}  ({canvas.size[0]}×{canvas.size[1]})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
