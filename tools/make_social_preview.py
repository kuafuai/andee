#!/usr/bin/env python3
"""GitHub 社交预览图（1280×640）、README 封面（1200×630）与方形组织头像（512×512）。

## 为什么不是"截个图标再放大"

图标里那颗球在 `ic_launcher_foreground` 里只有 221px 宽。社交预览要 430px 以上，
直接放大是 2 倍插值 —— 小尺寸下看还行，铺满 1280 宽的卡片就是糊的。

但这个仓库的球本来就不是"一张图"，是 `tools/ball_shot.mjs` 从实验室页面渲出来的。
所以这里**不放大，重新渲一张大的**：同一个渲染路径、同一个 look、同一份几何，
只是把边长从 432 提到 1000。放大倍数是 0.44 —— 是缩小，不是放大。

## 素材全部来自仓库自己，没有一个外编

  * 球体      `tools/ball_shot.mjs --look cream --mood LISTENING`（图标用的就是这一只：
              奶油身体 + 深色豆豆眼 + 深色描边 + 立耳）
  * 星空底    `tools/cosmic_backdrop.py` —— 和启动图标背景层是同一个生成器，
              所以社交预览的底片和图标是同一片星空，不是"看起来像"
  * 文案      README.md 自己的句子（tagline 就是文件里那句 h3）

## 两件不猜的事

1. **球体的几何是量出来的。** 球身直径、球心在渲染图里的位置，都从 alpha 通
   道实测（球身最宽行 ±20 行的平均宽度），不是"看着差不多"。落位再由这两个
   数反推，所以换 look、换 size 都不用重调偏移。
2. **文字不许压到球。** 文字层渲完之后扫一遍球体左缘之前的区域，找近白像素，
   报出文字最右缘和到球的距离；越界就打印告警。这条挡的是"标语长了 20px 就悄
   悄压到球上"这类只有肉眼才看得见的错。

## 用法

    python3 tools/make_social_preview.py                    # 三张全出
    python3 tools/make_social_preview.py --ball-src /tmp/ball.png   # 复用已渲好的球

需要 Pillow 与 numpy（`cosmic_backdrop` 要 numpy）。球体那一步要 node + 一个
能跑 headless WebGL 的 Chromium —— 见 `tools/ball_shot.mjs` 的文件头。
"""
import argparse
import html
import os
import random
import subprocess
import sys
from pathlib import Path

from PIL import Image

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
sys.path.insert(0, str(HERE))
import cosmic_backdrop  # noqa: E402

OUT_DIR = REPO / "images"
CHROME = os.environ.get(
    "CHROME_PATH",
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
)
NODE = os.environ.get("NODE", "node")

W, H = 1280, 640          # GitHub 社交预览：1280×640 是官方推荐的最优尺寸
COVER = (1200, 630)       # README 顶部封面：沿用 OG 图的常规比例

# 画布上球体的落位（球身中心）
BALL_X, BALL_Y = 966.0, 340.0
BALL_BODY = 440.0         # 球身显示直径

# 文字基线
Y_EYEBROW, Y_TITLE, Y_TAG = 202, 332, 394
Y_RULE, Y_FOOT, Y_FOOT2 = 430, 466, 496

STAR_SEED = cosmic_backdrop.SEED   # 和图标底片同一颗种子


# ---------------------------------------------------------------- 球体
def render_ball(tmp: Path, size: int = 1000) -> Path:
    """调 tools/ball_shot.mjs 渲一颗透明底的大球。"""
    out = tmp / "ball.png"
    alpha = tmp / "ball.alpha.png"
    if alpha.exists():
        return alpha
    cmd = [
        NODE, str(HERE / "ball_shot.mjs"),
        "--look", "cream", "--mood", "LISTENING",
        "--size", str(size), "--alpha", "--out", str(out),
    ]
    r = subprocess.run(cmd, cwd=str(REPO), capture_output=True, text=True, timeout=600)
    if not alpha.exists():
        print(r.stdout[-1500:], r.stderr[-1500:], sep="\n")
        raise SystemExit("球体渲染失败。可以先在别处渲好，再用 --ball-src 传进来。")
    return alpha


def ball_geometry(src: Path):
    """量球身：用最宽行 ±20 行的平均宽度，消掉单行噪声。返回 (直径, 中心x, 中心y)。"""
    im = Image.open(src).convert("RGBA")
    px = im.split()[3].load()
    w, h = im.size
    rows = []
    for y in range(h):
        xs = [x for x in range(w) if px[x, y] > 8]
        if xs:
            rows.append((y, min(xs), max(xs), max(xs) - min(xs) + 1))
    if not rows:
        raise SystemExit("球体源图整张透明，什么也没量到。")
    widest = max(rows, key=lambda r: r[3])
    near = [r[3] for r in rows if abs(r[0] - widest[0]) <= 20]
    dia = sum(near) / len(near)
    cx = (widest[1] + widest[2]) / 2.0
    return dia, cx, float(widest[0])


def ball_layer(src: Path):
    dia, cx, cy = ball_geometry(src)
    s = BALL_BODY / dia
    im = Image.open(src).convert("RGBA")
    side = max(1, int(round(im.size[0] * s)))
    im = im.resize((side, side), Image.LANCZOS)
    left = int(round(BALL_X - cx * s))
    top = int(round(BALL_Y - cy * s))
    return im, (left, top), (dia, cx, cy, s)


# ---------------------------------------------------------------- 星空底
def backdrop() -> Image.Image:
    """拿图标那块底片的同一个生成器，渲一大张再裁成 16:9。

    辉光要垫在球正后方，所以在**方形画布**里传球心坐标：裁切掉上半 320px，
    方形坐标里的球心就是 (BALL_X, BALL_Y + 320)。
    """
    sq = cosmic_backdrop.render(
        W,
        ball_cx=BALL_X,
        ball_cy=BALL_Y + (W - H) / 2.0,
        ball_r=BALL_BODY / 2.0,
        seed=STAR_SEED,
    )
    top = int((W - H) / 2)
    return sq.crop((0, top, W, top + H)).convert("RGB")

    # 曾经在这里叠过一层"文字侧压暗"（0.45 的软渐变）。撤掉了：实测底片上
    # 亮度 >150 的像素有 282 个，但**距文字 8px 以内的只有 2 个**，而且是颗
    # 柔和灰星（191），既没压到笔画也不影响阅读。压暗 0.45 换来的是一条肉眼
    # 可见的竖直明暗分界，把整片星空的连贯性弄没了 —— 代价远大于收益。


def ball_layer(src: Path, body: float = None, cx: float = None, cy: float = None):
    """把球按 `body` 的显示直径摆到 (cx, cy)。默认用横图那套落位参数。"""
    body = BALL_BODY if body is None else body
    cx = BALL_X if cx is None else cx
    cy = BALL_Y if cy is None else cy
    dia, scx, scy = ball_geometry(src)
    s = body / dia
    im = Image.open(src).convert("RGBA")
    side = max(1, int(round(im.size[0] * s)))
    im = im.resize((side, side), Image.LANCZOS)
    left = int(round(cx - scx * s))
    top = int(round(cy - scy * s))
    return im, (left, top), (dia, scx, scy, s)


# ---------------------------------------------------------------- 方形头像
AVATAR = 512
# 球身占方图边长的比例。上限是被**圆形**裁切卡的：这个角色的耳朵往斜上方伸，
# 从球心到耳尖的距离是球半径的 1.28 倍（实测），所以球半径不能超过 边长/2/1.28。
# 0.68 给圆形裁切留出 33px 余量。
AVATAR_RATIO = 0.68


def make_avatar(tmp: Path, ball_src: Path) -> Path:
    c = (AVATAR - 1) / 2.0
    body = AVATAR * AVATAR_RATIO
    base = cosmic_backdrop.render(
        AVATAR, ball_cx=c, ball_cy=c, ball_r=body / 2.0, seed=STAR_SEED
    ).convert("RGBA")
    layer, pos, _ = ball_layer(ball_src, body=body, cx=c, cy=c)
    base.alpha_composite(layer, pos)
    final = base.convert("RGB")

    # 自检：角色离圆心最远的那一点在哪。圆形裁切（GitHub 头像就是圆的）的半径
    # 是 边长/2，越界就是耳朵被啃掉一角 —— 这类事小尺寸下看不出来。
    a = layer.split()[3].load()
    lw, lh = layer.size
    far = 0.0
    for y in range(lh):
        for x in range(lw):
            if a[x, y] > 8:
                d = ((x + pos[0] - c) ** 2 + (y + pos[1] - c) ** 2) ** 0.5
                if d > far:
                    far = d
    limit = AVATAR / 2.0
    print(f"  角色最远点 {far:.0f}px / 圆形裁切半径 {limit:.0f}px / 余量 {limit - far:.0f}px"
          + ("   ⚠ 会被裁到" if far > limit - 8 else ""))

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    out = OUT_DIR / "avatar-org.png"
    final.save(out, "PNG", optimize=True)
    print(f"  -> {out.relative_to(REPO)}  {final.size[0]}x{final.size[1]}  "
          f"{out.stat().st_size / 1024:.0f}KB")
    return out


# ---------------------------------------------------------------- 文字层
def _stars_svg():
    """文字层自带的一层细星。

    底片那套星点是按 432px 画布设计的（半径 ~1.1px），在 1280 上放大到 ~3.3px，
    偏大偏疏；这里的细星只负责"近处的小颗粒"，和底片的星云叠在一起。
    """
    rnd = random.Random(STAR_SEED + 7)
    parts = []
    for _ in range(150):
        x, y = rnd.uniform(0, W), rnd.uniform(0, H)
        r = rnd.choice([0.4, 0.5, 0.6, 0.7, 0.9, 1.1])
        o = rnd.uniform(0.08, 0.55)
        if x < 745 and 140 < y < 525:      # 文字区压暗，别干扰阅读
            o *= 0.45
        parts.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{r:.2f}" fill="#fff" opacity="{o:.2f}"/>')
    return "\n    ".join(parts)


def build_svg(copy: dict) -> str:
    foot = copy["foot"] if isinstance(copy["foot"], list) else [copy["foot"]]
    foot_svg = "\n  ".join(
        f'<text x="96" y="{Y_FOOT + i * 30}" class="foot" fill="#b3a3d4">{html.escape(l)}</text>'
        for i, l in enumerate(foot)
    )
    f = copy["font"]
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">
  <defs>
    <style>
      .eyebrow {{ font-family: {f}; font-size: 18px; font-weight: 600; letter-spacing: 5px; }}
      .wordmark {{ font-family: {f}; font-size: 126px; font-weight: 700; letter-spacing: -2.5px; }}
      .tag {{ font-family: {f}; font-size: 33px; font-weight: 400; letter-spacing: -0.2px; }}
      .foot {{ font-family: {f}; font-size: 22px; font-weight: 400; letter-spacing: 0.1px; }}
    </style>
  </defs>
  <g>
    {_stars_svg()}
  </g>
  <text x="96" y="{Y_EYEBROW}" class="eyebrow" fill="#c9a4ef">{html.escape(copy["eyebrow"])}</text>
  <text x="94" y="{Y_TITLE}" class="wordmark" fill="#ffffff">{html.escape(copy["title"])}</text>
  <text x="96" y="{Y_TAG}" class="tag" fill="#e2d7f7">{html.escape(copy["tagline"])}</text>
  <rect x="96" y="{Y_RULE}" width="88" height="2" fill="#7c58a8"/>
  {foot_svg}
</svg>
'''


def render_text_layer(svg: str, tmp: Path) -> Path:
    page = tmp / "text.html"
    page.write_text(
        '<!doctype html><html><head><meta charset="utf-8">'
        '<style>html,body{margin:0;padding:0;background:transparent;overflow:hidden}'
        'svg{display:block}</style></head><body>' + svg + "</body></html>",
        encoding="utf-8",
    )
    shot = tmp / "text.png"
    subprocess.run(
        [CHROME, "--headless=new", "--no-sandbox", "--disable-dev-shm-usage",
         "--disable-gpu", "--hide-scrollbars",
         "--default-background-color=00000000",
         "--force-device-scale-factor=1",
         f"--user-data-dir={tmp}/chrome-profile",
         f"--screenshot={shot}", f"--window-size={W},{H}", f"file://{page}"],
        capture_output=True, text=True, timeout=180, check=False,
    )
    if not shot.exists():
        raise SystemExit("文字层截图失败（Chrome 没起来？）。")
    return shot


# ---------------------------------------------------------------- 自检
def text_clearance(text_layer: Path):
    """文字最右缘，以及它到球体左缘还剩多少。

    量的是**文字层自己的 alpha**，不是成品图：成品图上有底片的星（其中一批是
    淡紫和淡粉的，亮度能到 240+），拿"近白像素"当判据会被它们骗过去 —— 第一版
    就是这么误报的，把一颗粉星读成了"文字伸到 x=691"。

    文字层里也有星，但它们都压在 0.25 透明度以下（alpha ≤ 63），文字是 255，
    阈值取 120 就把两者干净地分开了。
    """
    alpha = Image.open(text_layer).convert("RGBA").split()[3]
    ball_left = BALL_X - BALL_BODY / 2.0
    x0, x1 = 560, int(ball_left) - 3
    y0, y1 = 150, 515          # 落在"文字区压暗"的范围内，避开未压暗的星
    band = alpha.crop((x0, y0, x1, y1))
    px = band.load()
    worst = -1
    for y in range(band.height):
        for x in range(band.width):
            if px[x, y] > 120:
                worst = max(worst, x + x0)
    return worst, ball_left


# ---------------------------------------------------------------- 出图
EN = {
    "eyebrow": "ANDROID · AI AGENT",
    "title": "Andee",
    "tagline": "Turn any Android phone into an AI phone.",
    "foot": "An individual that lives on your phone. No root. One APK.",
    "font": "'SF Pro Display','Helvetica Neue',Helvetica,Arial,sans-serif",
}
ZH = {
    "eyebrow": "安卓 · AI 智能体",
    "title": "Andee",
    "tagline": "把任何一部安卓手机，变成 AI 手机。",
    "foot": ["它不是跑在手机上的工具，而是住在手机里的个体。", "免 root，一个 APK。"],
    "font": "'PingFang SC','Hiragino Sans GB','Heiti SC',sans-serif",
}


def compose(copy, name, tmp, ball_src, size=None):
    base = backdrop().convert("RGBA")
    text_layer = render_text_layer(build_svg(copy), tmp)
    base.alpha_composite(Image.open(text_layer).convert("RGBA"))
    layer, pos, geo = ball_layer(ball_src)
    base.alpha_composite(layer, pos)
    final = base.convert("RGB")

    over, ball_left = text_clearance(text_layer)
    gap = ball_left - over
    print(f"  文字右缘 {over} / 球体左缘 {ball_left:.0f} / 间距 {gap:.0f}px"
          + ("   ⚠ 太挤" if gap < 20 else ""))

    if size and size != (W, H):
        l, t = (W - size[0]) // 2, (H - size[1]) // 2
        final = final.crop((l, t, l + size[0], t + size[1]))
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    out = OUT_DIR / name
    final.save(out, "PNG", optimize=True)
    print(f"  -> {out.relative_to(REPO)}  {final.size[0]}x{final.size[1]}  "
          f"{out.stat().st_size / 1024:.0f}KB")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ball-src", help="复用已渲好的透明底球体（.alpha.png）")
    ap.add_argument("--tmp", default="/tmp/andee-social-preview")
    args = ap.parse_args()

    tmp = Path(args.tmp)
    tmp.mkdir(parents=True, exist_ok=True)
    ball_src = Path(args.ball_src) if args.ball_src else render_ball(tmp)
    dia, cx, cy = ball_geometry(ball_src)
    print(f"球体源图 {ball_src.name}: 球身直径 {dia:.0f}px，中心 ({cx:.0f},{cy:.0f})"
          f" -> 显示 {BALL_BODY:.0f}px（{BALL_BODY / dia:.2f}x）")

    print("英文版 1280×640")
    compose(EN, "social-preview.png", tmp, ball_src)
    print("中文版 1280×640")
    compose(ZH, "social-preview.zh-CN.png", tmp, ball_src)
    print("README 封面 1200×630（英文）")
    compose(EN, "cover.png", tmp, ball_src, size=COVER)
    print("README 封面 1200×630（中文）")
    compose(ZH, "cover.zh-CN.png", tmp, ball_src, size=COVER)
    print(f"方形头像 {AVATAR}×{AVATAR}（GitHub 组织头像）")
    make_avatar(tmp, ball_src)


if __name__ == "__main__":
    main()
