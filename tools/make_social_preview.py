#!/usr/bin/env python3
"""README 封面（1200×630）、GitHub 社交预览（1280×640）与方形组织头像（512×512）。

两份素材，各管一件事：

  * 封面/社交预览的右侧主图 = `images/brand/andee-device.png`：一台躺着的手机，
    角色坐在屏幕上 —— 讲的是"住进设备"，不是"一颗悬着的头"。
  * 方形头像 = `images/brand/andee-head.png`：圆形裁切下只放得下头。
    头部抠图和光晕补底在 `andee_head.py`，和启动图标共用同一套。

## 构图

  * 底是纯白（#FDFDFD）。主图自带白边，**不抠图**：按内容框裁掉多余白边，
    再用一圈解析斜坡（`edge_ramp`）把最外 40px 淡成透明。光晕本来就在白边上，
    淡出看不出来；硬切会在光晕里留一条横向接缝（第一版就是这么翻车的，
    原因是"填满整块再模糊"等于没羽化）。
  * 文字深色，左对齐；主图在右下。文字右缘到**角色**左缘（蓝通道差 > 80 的
    最左列）的间距会量出来，小于 40px 就告警。
  * 底部那行五张表情收窄到 512px（原来是 604），否则手机左端会压到最后一格。
  * 头像是圆形裁切（GitHub 组织头像就是圆的）：脚本量角色最远点到圆心的
    距离，越界就告警。

文字用 Chrome 渲 SVG（要 PingFang / SF Pro 之类系统字体，PIL 做不好字距）。

用法:
    python3 tools/make_social_preview.py
"""
import argparse
import html
import os
import subprocess
import sys
from pathlib import Path

import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import andee_head as head  # noqa: E402

REPO = HERE.parent
OUT_DIR = REPO / "images"
CHROME = os.environ.get(
    "CHROME_PATH", "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")

W, H = 1280, 640
COVER = (1200, 630)
AVATAR = 512

# 右侧主图（手机 + 坐在屏幕上的角色）的落位。它自带白边，所以按**内容框**裁，
# DEV_PAD 是留给斜坡的白边宽度：斜坡全落在这圈白里，角色和手机一个像素都不透明。
DEVICE_SRC = REPO / "images" / "brand" / "andee-device.png"
DEV_W = 720                       # 落到画布上的宽度（裁切后）
DEV_RIGHT, DEV_BOTTOM = 1232, 636  # 右下角锚点
DEV_TRIM = 30                     # 判"内容"的阈值：与白底的最大通道差
DEV_PAD = 60                      # 内容框外保留的白边
DEV_RAMP = 40                     # 淡出斜坡的宽度（画到画布上的像素）
DEV_BLUE = 80                     # 角色判据：B − R > 80（手机是中性色，不会被算进来）

# 文字基线（比原来整体上移 60px，给最下面那条表情行让位）
Y_EYEBROW, Y_TITLE, Y_TAG = 118, 244, 304
Y_RULE, Y_FOOT = 388, 430

# 最下面那条「一个形象 + 一个名字」的行：五个桌宠形象，站着的常态（idle），不放动作。
# 宽度锁在左栏里（96 → 608），这样右侧主图（手机左端在 ~518）不会压到最后一格。
# 这五张是桌面端桌宠的立绘（desktop/src/main/resources/pet/<id>-idle.webp，透明底），
# 和封面上的毛绒角色同一套画风。球是圆的、另外四个是站着的，按"等高"排会一个大一个窄，
# 所以每个形象有自己的高度（FLOW_H），**底线对齐**，名字在下面居中。
FLOW_W = 512
FLOW_BASE = 566                    # 所有形象脚底（或球底）落在这一条线上
FLOW_LABEL_DY = 26
FLOW_FONT = 15
FLOW_DIR = REPO / "desktop" / "src" / "main" / "resources" / "pet"
# id → 画出来多高(px)。球 64 够看清脸；站着的角色要高一些才看得清五官。
FLOW_H = {"andee": 78, "girl": 84, "boy": 84, "andee-girl": 84, "cat": 76}

INK = "#10172A"
INK_SOFT = "#3B4768"
INK_FAINT = "#6B7794"
ACCENT = "#2F6BEA"

EN = {
    "eyebrow": "ANDROID · AI AGENT",
    "title": "Andee",
    "tagline": ["It becomes the soul", "of the device."],
    "foot": ["Nineteen moods. Its own notebook.", "It reviews itself when nobody watches."],
    "flow": [("andee", "Andee"), ("girl", "Cream"), ("boy", "Cream short"),
             ("andee-girl", "Andee girl"), ("cat", "Kitten")],
    "font": "'SF Pro Display','Helvetica Neue',Helvetica,Arial,sans-serif",
}
ZH = {
    "eyebrow": "安卓 · AI 智能体",
    "title": "Andee",
    "tagline": ["他会成为这台设备的", "主人。"],
    "foot": ["十九种情绪、一本只有他自己能读的笔记本。", "没人看着的时候，他会自己复盘。"],
    "flow": [("andee", "Andee"), ("girl", "奶油金"), ("boy", "短发"),
             ("andee-girl", "Andee娘"), ("cat", "小猫")],
    "font": "'PingFang SC','Hiragino Sans GB','Heiti SC',sans-serif",
}


def content_box(rgb: np.ndarray):
    """与白底差得够多的最外一圈像素（光晕也算内容，所以框左边通常是光晕而非手机）。"""
    d = (255 - rgb).max(axis=2)
    ys, xs = np.nonzero(d > DEV_TRIM)
    return int(xs.min()), int(ys.min()), int(xs.max()), int(ys.max())


def edge_ramp(w: int, h: int, ramp: int) -> Image.Image:
    """四边向内 ramp 像素的平滑斜坡：边上 alpha=0，ramp 以内 alpha=255。

    必须写成"从边算距离"，不能写成"填满再高斯模糊" —— 填满之后模糊出来还是
    一张全白的掩膜，等于没羽化，光晕会被画布边缘硬切出一条接缝。
    """
    x = np.arange(w)[None, :]
    y = np.arange(h)[:, None]
    d = np.minimum(np.minimum(x, w - 1 - x), np.minimum(y, h - 1 - y)).astype(np.float64)
    t = np.clip(d / max(1, ramp), 0, 1)
    return Image.fromarray(((t * t * (3 - 2 * t)) * 255).astype(np.uint8), "L")


def device_layer():
    """右侧主图：裁掉多余白边 → 缩到 DEV_W → 四边淡出。返回 (图, 角色左缘x)。"""
    src = Image.open(DEVICE_SRC).convert("RGB")
    rgb = np.asarray(src).astype(np.int16)
    x0, y0, x1, y1 = content_box(rgb)
    char_x0 = int(np.nonzero(((rgb[:, :, 2] - rgb[:, :, 0]) > DEV_BLUE).any(axis=0))[0].min())
    box = (max(0, x0 - DEV_PAD), max(0, y0 - DEV_PAD),
           min(src.size[0], x1 + DEV_PAD), min(src.size[1], y1 + DEV_PAD))
    art = src.crop(box)
    s = DEV_W / art.size[0]
    art = art.resize((int(round(art.size[0] * s)), int(round(art.size[1] * s))), Image.LANCZOS)
    out = art.convert("RGBA")
    out.putalpha(edge_ramp(*art.size, max(2, int(round(DEV_RAMP * s)))))
    return out, (char_x0 - box[0]) * s


def build_svg(copy: dict) -> str:
    f = copy["font"]
    tag = "\n  ".join(
        f'<text x="96" y="{Y_TAG + i * 44}" class="tag" fill="{INK_SOFT}">{html.escape(l)}</text>'
        for i, l in enumerate(copy["tagline"]))
    foot = "\n  ".join(
        f'<text x="96" y="{Y_FOOT + i * 32}" class="foot" fill="{INK_FAINT}">{html.escape(l)}</text>'
        for i, l in enumerate(copy["foot"]))
    cell_w = FLOW_W / len(copy["flow"])
    flow = "\n  ".join(
        f'<text x="{96 + i * cell_w + cell_w / 2:.1f}" y="{FLOW_BASE + FLOW_LABEL_DY}"'
        f' text-anchor="middle" class="flow" fill="{INK_SOFT}">{html.escape(label)}</text>'
        for i, (_, label) in enumerate(copy["flow"]))
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">
  <defs>
    <style>
      .eyebrow {{ font-family: {f}; font-size: 18px; font-weight: 600; letter-spacing: 5px; }}
      .wordmark {{ font-family: {f}; font-size: 130px; font-weight: 700; letter-spacing: -3px; }}
      .tag {{ font-family: {f}; font-size: 33px; font-weight: 500; letter-spacing: -0.2px; }}
      .foot {{ font-family: {f}; font-size: 22px; font-weight: 400; letter-spacing: 0.1px; }}
      .flow {{ font-family: {f}; font-size: {FLOW_FONT}px; font-weight: 500; letter-spacing: 0.2px; }}
    </style>
  </defs>
  <text x="96" y="{Y_EYEBROW}" class="eyebrow" fill="{ACCENT}">{html.escape(copy["eyebrow"])}</text>
  <text x="92" y="{Y_TITLE}" class="wordmark" fill="{INK}">{html.escape(copy["title"])}</text>
  {tag}
  <rect x="96" y="{Y_RULE}" width="88" height="3" rx="1.5" fill="{ACCENT}"/>
  {foot}
  {flow}
</svg>
'''


def render_text_layer(svg: str, tmp: Path) -> Path:
    page = tmp / "text.html"
    page.write_text(
        '<!doctype html><html><head><meta charset="utf-8">'
        '<style>html,body{margin:0;padding:0;background:transparent;overflow:hidden}'
        'svg{display:block}</style></head><body>' + svg + "</body></html>",
        encoding="utf-8")
    shot = tmp / "text.png"
    if shot.exists():
        shot.unlink()
    subprocess.run(
        [CHROME, "--headless=new", "--no-sandbox", "--disable-dev-shm-usage",
         "--disable-gpu", "--hide-scrollbars", "--default-background-color=00000000",
         "--force-device-scale-factor=1", f"--user-data-dir={tmp}/chrome-profile",
         f"--screenshot={shot}", f"--window-size={W},{H}", f"file://{page}"],
        capture_output=True, text=True, timeout=180, check=False)
    if not shot.exists():
        raise SystemExit("文字层截图失败（Chrome 没起来？）。")
    return shot


def text_right_edge(text_layer: Path) -> int:
    alpha = np.asarray(Image.open(text_layer).convert("RGBA").split()[3])
    cols = np.where((alpha[150:520] > 120).any(axis=0))[0]
    return int(cols.max()) if len(cols) else 0


def _flood(mask, seed):
    """8 连通泛洪，返回该连通块的布尔掩码。"""
    h, w = mask.shape
    seen = np.zeros_like(mask)
    stack = [seed]
    seen[seed] = True
    while stack:
        y, x = stack.pop()
        for dy in (-1, 0, 1):
            yy = y + dy
            if yy < 0 or yy >= h:
                continue
            for dx in (-1, 0, 1):
                xx = x + dx
                if 0 <= xx < w and mask[yy, xx] and not seen[yy, xx]:
                    seen[yy, xx] = True
                    stack.append((yy, xx))
    return seen


def _drop_edge_fragments(im):
    """抹掉贴着画布边缘的连通块，返回 (新图, 抹掉多少像素)。

    这套插画是从一张拼图上切出来的，边上会把**隔壁那只**的一小条一起带进来：
    `protect` 左侧 x=0..30 有 2226px、`rest` 左侧 x=0..41 有 2960px。

    ⚠️ 判据是「**被画布的左边或上边切开**」（`x0 == 0 or y0 == 0`），有两个坑：
    1. **不能按"连不连着球"删** —— `wave` / `wink` 里那几笔橙色动效线本来就脱离主体
       （3979px / 2158px），是画的一部分，按连通删会把它们一起删掉。
    2. **不能写成"贴任意一条边就删"** —— `protect` 的**主体**包围盒是 x=88..512，
       它自己就被画布右边切了；按"贴边"删会把整个主体删掉（第 4 格会变空）。
       真正的规律是：这套图是从拼图上**从左往右**切的，溢出来的邻居只会落在**左边缘**
       （`protect` x=0..30、`rest` x=0..41 都是），所以只认左边和上边。
    """
    a = np.asarray(im).copy()
    mask = a[:, :, 3] > 16
    ys, xs = np.nonzero(mask)
    done = np.zeros_like(mask)
    removed = 0
    for k in range(0, len(ys), 8):       # 抽样做种子就够：连通块比 8px 大得多
        seed = (int(ys[k]), int(xs[k]))
        if done[seed]:
            continue
        comp = _flood(mask, seed)
        done |= comp
        cy, cx = np.nonzero(comp)
        if cx.min() == 0 or cy.min() == 0:
            a[comp, 3] = 0
            removed += int(comp.sum())
    return Image.fromarray(a), removed


def flow_layer(copy: dict):
    """五个形象的立绘：裁到人物的包围盒，各按自己的高度缩，脚底落在同一条线上，横向居中在各自的格子里。

    这和旧的"整张方图一起缩"相反，原因也相反：旧的是同一套球，球的大小本来就一致，裁边会把带道具的
    撑宽；现在是一个球加四个站着的人，宽高比差很多，只能裁到人物本身、再按各自的高度调。
    """
    cell_w = FLOW_W / len(copy["flow"])
    for i, (key, _) in enumerate(copy["flow"]):
        im = Image.open(FLOW_DIR / f"{key}-idle.webp").convert("RGBA")
        im = im.crop(im.getbbox())
        h = FLOW_H[key]
        im = im.resize((max(1, round(im.size[0] * h / im.size[1])), h), Image.LANCZOS)
        x = round(96 + i * cell_w + (cell_w - im.size[0]) / 2)
        yield im, (x, FLOW_BASE - h)


def compose(copy, name, tmp, size=None):
    base = Image.new("RGB", (W, H), (253, 253, 253)).convert("RGBA")
    layer, char_left = device_layer()
    left, top = DEV_RIGHT - layer.size[0], DEV_BOTTOM - layer.size[1]
    base.alpha_composite(layer, (left, top))
    for im, xy in flow_layer(copy):
        base.alpha_composite(im, xy)
    text_layer = render_text_layer(build_svg(copy), tmp)
    base.alpha_composite(Image.open(text_layer).convert("RGBA"))
    final = base.convert("RGB")

    char_x = left + char_left          # 角色最左列在画布上的 x
    over = text_right_edge(text_layer)
    gap = char_x - over
    print(f"  文字右缘 {over} / 角色左缘 {char_x:.0f} / 间距 {gap:.0f}px"
          + ("   ⚠ 太挤" if gap < 40 else ""))

    if size and size != (W, H):
        l, t = (W - size[0]) // 2, (H - size[1]) // 2
        final = final.crop((l, t, l + size[0], t + size[1]))
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    out = OUT_DIR / name
    final.save(out, "PNG", optimize=True)
    print(f"  -> {out.relative_to(REPO)}  {final.size[0]}x{final.size[1]}  {out.stat().st_size / 1024:.0f}KB")


def make_avatar():
    """方形头像：角色整图居中，圆形裁切下角（耳朵）不越界。"""
    rgba, alpha, hard, a = head.character_rgba()
    r, dy = head.circumcircle(alpha)
    # 外接圆（相对源图中心，已含最优下移）占圆形裁切半径的比例。
    ratio = 0.80
    scale = (AVATAR / 2.0) * ratio / r
    side = int(round(1254 * scale))
    art = Image.open(head.SRC).convert("RGB").resize((side, side), Image.LANCZOS)
    base = Image.new("RGB", (AVATAR, AVATAR), (253, 253, 253))
    ox = int(round(AVATAR / 2.0 - 626.5 * scale))
    oy = int(round(AVATAR / 2.0 - 626.5 * scale + dy * scale))
    base.paste(art, (ox, oy))
    far = r * scale
    print(f"  角色最远点 {far:.0f}px / 圆形裁切半径 {AVATAR / 2:.0f}px / 余量 {AVATAR / 2 - far:.0f}px")
    out = OUT_DIR / "avatar-org.png"
    base.save(out, "PNG", optimize=True)
    print(f"  -> {out.relative_to(REPO)}  {AVATAR}x{AVATAR}  {out.stat().st_size / 1024:.0f}KB")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tmp", default="/tmp/andee-social-preview")
    args = ap.parse_args()
    tmp = Path(args.tmp)
    tmp.mkdir(parents=True, exist_ok=True)

    print("英文版 1280×640")
    compose(EN, "social-preview.png", tmp)
    print("中文版 1280×640")
    compose(ZH, "social-preview.zh-CN.png", tmp)
    print("README 封面 1200×630（英文）")
    compose(EN, "cover.png", tmp, size=COVER)
    print("README 封面 1200×630（中文）")
    compose(ZH, "cover.zh-CN.png", tmp, size=COVER)
    print(f"方形头像 {AVATAR}×{AVATAR}（GitHub 组织头像）")
    make_avatar()


if __name__ == "__main__":
    main()
