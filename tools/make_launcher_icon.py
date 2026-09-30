#!/usr/bin/env python3
"""从一张**透明底的角色渲染图**生成整套 Android 启动图标。

## 和「从美术稿做图标」那套流程的区别

那套流程的对象是别人画好的一张**不透明**方图,难点全在"把主体从背景里抠
出来":背景色是猜的、几何圆遮罩会糊边、五官要找亮度阈值。这一版不一样 ——
源图由 `tools/ball_shot.mjs` 从实验室那一页渲出来,**自带一路 alpha**:

  * **不用抠图**。alpha 就是剪影,边界就是美术自己的边界,没有"几何圆 vs
    按背景抠"这个取舍,也不会有 1px 的羽化假边。
  * **不用猜背景色**。背景层是单独填的纯色,源图里根本没有背景。
  * **五官的阈值有干净的峰**。实测球内亮度直方图是三个分离的峰:眼睛 33±2
    (21070px)、嘴 78±2(2905px)、主体 120–215,峰与峰之间是空的。阈值落在
    空隙里是量出来的,不是试出来的。

## 几何怎么定(没有一个是拍的)

**构图下移。** 角色的外接圆是歪的:耳朵在斜上方把它撑开,而**可见圆是以画布
中心为圆心的**。把角色整体下移,让"到画布中心的最大距离"最小 —— 实测最优
下移 43px,最大半径 357.9 → 323.6,**白赚 10.6%**。不做这一步,球还要再小
10%。

**球体尺寸不是自由变量。** 外接圆半径 / 球半径 = 1.154,因为耳朵伸在球外。
要让**整个角色**落进保证可见的 72dp 圆里,球就只能是角色高度的一个固定比例。
这是几何结论,不是缩水的选择 —— 肉眼看到的是"整个角色",而角色占可见圆的
比例几乎没变(95.8% → 96.5%)。

**两层用两套占比,不能互相缩。** 自适应层沿用被替换那套的 CONTENT=276/432
(先量旧图再沿用,换图才不会改变桌面上的视觉大小);传统光栅图**单独构建**
—— 从自适应画布缩下来会把"为遮罩预留的死环"一起烘进去,48px 下就是贴纸。
传统图按满幅惯例,角色占 88%(实测旧图 169/192 = 88.0%)。

## 单色层的语义

**黑 = 实体,透明 = 镂空**,不是"画一个白色剪影"。眼睛和嘴是 alpha=0 的洞。

### 剪影绝不二值化(这条踩过,而且错得不明显)

第一版写的是 `sil = alpha > 128`,结果单色层的剪影和美术层的 IoU 只有
**0.8611**,单色层还比美术层宽 16px(@432)。原因是把剪影当成了"一个实心形状",
而它其实不是 —— 球最外那一圈描边(`rimDark`)的 alpha 是一条**斜坡**:

    球心行剖面,从最外沿往内:
      202 168 109 86 65 53 44 37 30 24 20 16 14 11 9 8 7 | 118 231 233 ...

最外沿 202,向内一路衰减到 **7**,然后球体本体的 231 才开始。也就是说描边那一圈
(18px @1152)的 alpha 从 79% 掉到 3% —— 描边是靠最外那 1~2px 撑着的。

任何阈值都会把这条斜坡切成两段:阈值以上留一个细环、阈值以下丢掉整条斜坡,
中间断开。所以**单色层的 alpha 直接沿用源图的 alpha**,不二值化 ——
alpha 本来就是这个像素"被角色覆盖了多少",那正是 mask 要的语义。

眼睛和嘴**必须用 bbox 拟合椭圆 / 真实形状,不能用阈值 mask**:

  * 眼球里有一个亮度 252–255 的高光白点,阈值 mask 会在洞里留一个疙瘩。
  * 嘴是两端收细的弧,矩形框要么切掉嘴角,要么把背景一起拖走。
  * **耳朵会把嘴的阈值一起命中的坑**:耳朵亮度中位数 90,和嘴的 78 只差 12,
    光靠亮度分不开。分开它们靠**位置** —— 耳朵整片都在球心上方。所以判据是
    `y > 球心` 而不是调阈值。

## 一次变换,不是每层各缩一次

所有层都从**同一张源图**、用**同一个 scale 和同一个整数 offset** 变换过去。
每层各缩一次(尤其遮罩单独缩)会让单色层挖掉的眼睛和美术层的眼睛差 1px ——
432px 上看不出来,108px 上一眼就歪。所以这里先在源图分辨率上把要合成的东西
合成完,再整张变换一次。

用法:
  python tools/make_launcher_icon.py --src a.alpha.png --check-only
  python tools/make_launcher_icon.py --src a.alpha.png --res app/src/main/res \\
      --dump-masks /tmp/masks.png
"""
import argparse
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

import cosmic_backdrop

# ---------------------------------------------------------------- 常量
DENSITIES = [("mdpi", 1.0), ("hdpi", 1.5), ("xhdpi", 2.0), ("xxhdpi", 3.0), ("xxxhdpi", 4.0)]
ADAPTIVE_DP = 108.0
LEGACY_DP = 48.0

# 保证可见圆:系统把画布中央 72/108 铺满图标位,外面那圈是给遮罩预留的。
VISIBLE_FRACTION = 72.0 / 108.0

# 角色到画布中心的最大半径,占可见圆半径的比例。**基准** 139/144 = 96.5%
# (沿用被替换那套的视觉大小,留的余量是给锐化的 —— 锐化会让主体略微外扩)。
#
# ⚠️ 这个 96.5% 有一个副作用,加了装饰之后才暴露出来:**装饰是加在球周围的**,
# 球占满可见圆的时候,底片只剩一圈细边,等于白加。所以 `--content` 是一个乘在
# 这个基准上的因子,用来给配景让出可见空间。缩小时**必须连传统光栅图一起缩**
# (见下),否则同一个设备上桌面图标和圆形图标会显示成两种大小。
CONTENT_OF_VISIBLE = 139.0 / 144.0

# 传统光栅图上"角色"占方形的比例。实测旧图 169/192 = 88.0%。
# 同样乘 `--content` —— 这两条路的角色大小在视觉上必须一致(见上)。
LEGACY_CONTENT = 0.88

BG = (14, 23, 43)  # 实测旧图标 background 的纯色 #0E172B

# 源图上的阈值,落在实测的峰之间的空隙里。**空隙在哪要量,不能看着像就定。**
EYE_LUM = 60.0     # 眼睛 33±2,下一个峰在 76 → 60 落在空隙里
# ⚠️ 嘴这里踩过一次:原来写 120,而**球体自身的暗部底就是 120.6**(下半部亮度
# 分位 5%=78.9、10%=120.6、50%=124.1)。阈值贴着球体暗部底的结果是把它一起吞
# 进来当嘴:阈值 115→120 时"嘴"的像素从 589 涨到 725、被挖空覆盖从 89.3% 掉到
# 72.6%;120→125 更是从 725 暴涨到 6131。真正的谷底在 79 和 120 之间,取 100。
MOUTH_LUM = 100.0  # 嘴 78±2,球体暗部底 120.6 → 100 落在谷底正中
BALL_INNER = 0.90  # 只在球体半径的这个比例内找五官,把耳根挡在外面

SHARPEN_RADIUS = 1.0
SHARPEN_PERCENT = 60

# ---- 装饰:星尘宇宙 ----
# 球缘那圈紫粉边缘光,让它"接住"背景的辉光。宽度按球半径取比例(不是固定像素),
# 否则 432 画布上调好的宽度到 108 画布上就成了一整块糊。
RIM_COLOR = (188, 96, 226)
RIM_OF_R = 0.030
RIM_STRENGTH = 0.50


def rim_tint(im: Image.Image, color, ball_r: float, of_r: float) -> Image.Image:
    """把剪影最外那薄薄一圈染向 color —— 模拟环境光打在边缘。

    **只改 RGB,不动 alpha。** 这既是"剪影绝不能碰"的老规矩,也是这次能加装饰
    的前提:边缘光在剪影内部,所以可见圆的预算一点没动(前景层的最大半径不变)。

    做法:`alpha>200` 腐蚀掉 of_r*ball_r 得到内芯,`实心 & ~内芯` 就是贴着边界的
    那圈;再高斯模糊出柔和过渡。模糊会让它溢到剪影外,但那里 alpha=0,看不见。
    """
    a = np.asarray(im).astype(np.float64)
    # ⚠️ 必须转成 Python float —— ball_r 一路从 numpy 运算下来是 np.float64,
    # 而 PIL 的 GaussianBlur 里要 `if xy == (0, 0)`,np.float64 和元组比较会返回
    # 一个数组,于是抛 "truth value of an array is ambiguous"。报错点还落在 PIL 里,
    # 看着像是 PIL 的毛病。
    ball_r = float(ball_r)
    solid = a[:, :, 3] > 200
    core = solid.copy()
    for _ in range(max(1, int(round(ball_r * of_r)))):
        core = (core & np.roll(core, 1, 0) & np.roll(core, -1, 0)
                & np.roll(core, 1, 1) & np.roll(core, -1, 1))
    ring = solid & ~core
    w = Image.fromarray((ring * 255).astype(np.uint8), "L").filter(
        ImageFilter.GaussianBlur(max(0.6, ball_r * of_r * 0.55)))
    ww = np.asarray(w).astype(np.float64) / 255.0 * RIM_STRENGTH
    out = a.copy()
    for i in range(3):
        out[:, :, i] = a[:, :, i] * (1.0 - ww) + color[i] * ww
    return Image.fromarray(np.clip(out, 0, 255).astype(np.uint8), "RGBA")


def backdrop(size: int, ball_cx: float, ball_cy: float, ball_r: float, decor: str):
    """底片。`none` 就是原来的纯色 —— 老样式要能原样复现,不能只有新样式。"""
    if decor == "cosmic":
        return cosmic_backdrop.render(size, ball_cx, ball_cy, ball_r)
    return Image.new("RGB", (size, size), BG)


# ---------------------------------------------------------------- 工具
def resize_rgba(im: Image.Image, size: int) -> Image.Image:
    """缩放 RGBA。**必须走预乘**,否则透明像素的黑会渗进半透明边缘变成暗边。

    这不是保险起见:源图是"深色描边 + 抗锯齿过渡",直通缩放时插值会把透明的
    黑混进边缘像素,48px 上就是一圈脏边。
    """
    a = np.asarray(im).astype(np.float64)
    al = a[:, :, 3:4] / 255.0
    pm = Image.fromarray(np.dstack([a[:, :, :3] * al, a[:, :, 3]]).astype(np.uint8), "RGBA")
    b = np.asarray(pm.resize((size, size), Image.LANCZOS)).astype(np.float64)
    al2 = b[:, :, 3:4] / 255.0
    rgb = np.where(al2 > 1e-6, b[:, :, :3] / np.maximum(al2, 1e-6), 0.0)
    return Image.fromarray(np.dstack([np.clip(rgb, 0, 255), b[:, :, 3]]).astype(np.uint8), "RGBA")


def resize_mask(im: Image.Image, size: int) -> Image.Image:
    """缩放灰度遮罩。用 BOX(面积平均)—— 遮罩是"覆盖了多少",面积平均才是它的
    定义;LANCZOS 会在细笔画两侧振铃,把 5px 宽的嘴拆成一串亮点。"""
    return im.resize((size, size), Image.BOX)


def sharpen_rgb(im: Image.Image) -> Image.Image:
    """只锐化 RGB,**绝不碰 alpha** —— 碰 alpha 会把剪影弄毛,而剪影是整图对比度
    最高的地方,一锐就是一圈亮边。"""
    r, g, b, a = im.split()
    rgb = Image.merge("RGB", (r, g, b)).filter(
        ImageFilter.UnsharpMask(radius=SHARPEN_RADIUS, percent=SHARPEN_PERCENT, threshold=0)
    )
    r2, g2, b2 = rgb.split()
    return Image.merge("RGBA", (r2, g2, b2, a))


def find_ball(alpha: np.ndarray):
    """从 alpha 求球体的圆心与半径。

    球是最大的圆,且耳朵**不超出球在水平方向上的宽度**(耳朵往上长、往外撇,
    但撇得没球宽),所以"最宽那一行的宽度"就是球直径。球底就是整张图的最下沿
    —— 耳朵只往上长。
    """
    ys, xs = np.where(alpha > 8)
    if len(ys) == 0:
        raise SystemExit("源图是空的 —— 全是透明像素")
    best = (0, None)
    for y in range(ys.min(), ys.max() + 1):
        xr = np.where(alpha[y] > 8)[0]
        if len(xr):
            w = xr.max() - xr.min() + 1
            if w > best[0]:
                best = (w, (int(xr.min()), int(xr.max())))
    d, (x0, x1) = best
    r = d / 2.0
    return (x0 + x1) / 2.0, float(ys.max()) - r, r, (
        int(ys.min()), int(ys.max()), int(xs.min()), int(xs.max()))


def best_shift(ys, xs, H, W, search=140):
    """扫出那个让"到画布中心的最大距离"最小的下移量。

    可见圆以画布中心为圆心,所以要最小化的就是"角色到画布中心的最大距离"
    —— 不是到球心的距离,也不是 bbox 的居中。
    """
    ccx, ccy = (W - 1) / 2.0, (H - 1) / 2.0
    base = float(np.hypot(xs - ccx, ys - ccy).max())
    best = (base, 0)
    for dy in range(-search, search + 1):
        d = float(np.hypot(xs - ccx, (ys + dy) - ccy).max())
        if d < best[0]:
            best = (d, dy)
    return base, best[1], best[0]


def _components(mask: np.ndarray, min_px: int, limit: int = 6):
    """4-邻域连通域。不引 scipy —— 这个仓库的图标链路只依赖 numpy + PIL。"""
    H, W = mask.shape
    lab = np.zeros(mask.shape, np.int32)
    out = []
    cur = 0
    for sy, sx in zip(*np.where(mask)):
        if lab[sy, sx]:
            continue
        cur += 1
        stack = [(sy, sx)]
        lab[sy, sx] = cur
        pix = []
        while stack:
            y, x = stack.pop()
            pix.append((y, x))
            for ny, nx in ((y - 1, x), (y + 1, x), (y, x - 1), (y, x + 1)):
                if 0 <= ny < H and 0 <= nx < W and mask[ny, nx] and not lab[ny, nx]:
                    lab[ny, nx] = cur
                    stack.append((ny, nx))
        if len(pix) >= min_px:
            out.append(np.array(pix))
    out.sort(key=len, reverse=True)
    return out[:limit]


def ellipse_mask(shape, box, grow=1.0):
    x0, y0, x1, y1 = box
    cxe, cye = (x0 + x1) / 2.0, (y0 + y1) / 2.0
    axe, aye = (x1 - x0 + 1) / 2.0 * grow, (y1 - y0 + 1) / 2.0 * grow
    H, W = shape
    yy, xx = np.mgrid[0:H, 0:W]
    return ((xx - cxe) / axe) ** 2 + ((yy - cye) / aye) ** 2 <= 1.0


def close_mask(mask, iters=2):
    """形态学闭运算(先膨胀再腐蚀),把嘴的暗像素连成一片 —— 嘴是弧,内部亮度
    并不完全均匀,直接取阈值会得到几段。"""
    m = mask
    for _ in range(iters):
        m = m | np.roll(m, 1, 0) | np.roll(m, -1, 0) | np.roll(m, 1, 1) | np.roll(m, -1, 1)
    for _ in range(iters):
        m = m & np.roll(m, 1, 0) & np.roll(m, -1, 0) & np.roll(m, 1, 1) & np.roll(m, -1, 1)
    return m


def blit(dst: Image.Image, src: Image.Image, ox: int, oy: int):
    """把 src 贴到 dst 的 (ox, oy),负坐标也正确裁剪。

    **所有层都必须走这一个函数**,这是"各层共用同一次变换"在结构上的保证:
    只要 offset 是同一组整数,单色层挖掉的眼睛就不可能和美术层的眼睛差
    1px。每个调用点各写一遍 paste(或各自 round 一次),那个 1px 的偏差就会
    回来 —— 432px 上看不出来,108px 上一眼就歪。
    """
    dw, dh = dst.size
    sw, sh = src.size
    x0, y0 = max(0, ox), max(0, oy)
    x1, y1 = min(dw, ox + sw), min(dh, oy + sh)
    if x1 <= x0 or y1 <= y0:
        return
    patch = src.crop((x0 - ox, y0 - oy, x1 - ox, y1 - oy))
    dst.paste(patch, (x0, y0), patch)


def analyse(arr, quiet=False):
    """量出球体、最优下移、五官遮罩。返回一个 dict。"""
    alpha = arr[:, :, 3]
    H, W = alpha.shape
    cx, cy, r, bbox = find_ball(alpha)
    ys, xs = np.where(alpha > 8)
    base, dy, opt = best_shift(ys, xs, H, W)

    rgb = arr[:, :, :3]
    lum = 0.2126 * rgb[:, :, 0] + 0.7152 * rgb[:, :, 1] + 0.0722 * rgb[:, :, 2]
    yy, xx = np.mgrid[0:H, 0:W]
    d2c = np.hypot(xx - cx, yy - cy)
    inside = (alpha > 200) & (d2c < r * BALL_INNER)

    eye_hits = _components(inside & (yy < cy) & (lum < EYE_LUM), int(r * r * 0.004))
    eyes = sorted(
        [(int(p[:, 1].min()), int(p[:, 0].min()), int(p[:, 1].max()), int(p[:, 0].max()))
         for p in eye_hits[:2]],
        key=lambda e: (e[0] + e[2]) / 2,
    )
    # 嘴:靠**位置**和耳朵分开,不是靠亮度 —— 耳朵中位亮度 90 vs 嘴 78,阈值分不开;
    # 而耳朵整片在球心上方。
    mouth_hits = _components(inside & (yy > cy) & (lum < MOUTH_LUM), int(r * r * 0.004))
    mouth = mouth_hits[0] if mouth_hits else None

    eye_masks = [ellipse_mask((H, W), e, grow=1.06) for e in eyes]
    if mouth is not None:
        m = (lum < MOUTH_LUM) & (yy > cy) & (d2c < r * BALL_INNER)
        mouth_mask = close_mask(m, 2)
    else:
        mouth_mask = None

    if not quiet:
        print(f"  球体 球心 ({cx:.1f},{cy:.1f}) 半径 {r:.1f} 直径 {2*r:.0f}")
        print(f"  bbox y[{bbox[0]},{bbox[1]}] x[{bbox[2]},{bbox[3]}]"
              f"  宽 {bbox[3]-bbox[2]+1} 高 {bbox[1]-bbox[0]+1}")
        print(f"  构图 画布中心最大半径 {base:.1f} → 下移 {dy}px 后 {opt:.1f}"
              f"  (白赚 {(base-opt)/opt*100:.1f}%)")
        print(f"  外接圆/球半径 = {opt/r:.3f} —— 球在图标里的尺寸上限"
              f"(耳朵伸在球外,占掉这部分)")
        print(f"  五官 (眼<{EYE_LUM:.0f} / 嘴<{MOUTH_LUM:.0f}, 球内 {BALL_INNER:.0%}):")
        for i, e in enumerate(eyes):
            print(f"    眼{i+1} bbox x[{e[0]},{e[2]}] y[{e[1]},{e[3]}]"
                  f"  {e[2]-e[0]+1}×{e[3]-e[1]+1}")
        if mouth is not None:
            y0, x0 = mouth.min(axis=0)
            y1, x1 = mouth.max(axis=0)
            print(f"    嘴 {len(mouth)}px bbox x[{x0},{x1}] y[{y0},{y1}]  {x1-x0+1}×{y1-y0+1}")
        else:
            print("    嘴 没找到")
        v = lum[inside]
        print(f"  阈值余量 眼({EYE_LUM:.0f}) 与下一峰之间 {int(((v>EYE_LUM)&(v<70)).sum())}px")

    return dict(cx=cx, cy=cy, r=r, bbox=bbox, dy=dy, opt=opt, base=base,
                eyes=eyes, eye_masks=eye_masks, mouth_mask=mouth_mask,
                H=H, W=W, alpha=alpha)


def dump_masks(arr, info, path):
    """把人眼核对用的遮罩图写出来。

    这一步不是装饰:阈值落在空隙里是算出来的,但"算出来的洞是不是真的盖住了
    眼睛"只有看图才知道。耳朵那一带尤其要看 —— 它的亮度和嘴只差 12。
    """
    H, W = info["H"], info["W"]
    base = Image.fromarray(np.clip(arr[:, :, :3], 0, 255).astype(np.uint8)).convert("RGB")
    ov = np.array(base).copy()
    for m in info["eye_masks"]:
        ov[m] = (ov[m] * 0.25 + np.array([255, 40, 40]) * 0.75).astype(np.uint8)
    if info["mouth_mask"] is not None:
        m = info["mouth_mask"]
        ov[m] = (ov[m] * 0.25 + np.array([40, 255, 90]) * 0.75).astype(np.uint8)
    # 球心十字 + 球轮廓
    im = Image.fromarray(ov)
    d = ImageDraw.Draw(im)
    cx, cy, r = info["cx"], info["cy"], info["r"]
    d.line([(cx - 40, cy), (cx + 40, cy)], fill=(255, 255, 0), width=4)
    d.line([(cx, cy - 40), (cx, cy + 40)], fill=(255, 255, 0), width=4)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=(0, 255, 255), width=3)
    d.ellipse([cx - r * BALL_INNER, cy - r * BALL_INNER,
               cx + r * BALL_INNER, cy + r * BALL_INNER], outline=(255, 0, 255), width=3)
    im.resize((760, 760), Image.LANCZOS).save(path)
    print(f"  遮罩核对图 -> {path}")


# ---------------------------------------------------------------- 主流程
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True, help="透明底源图(ball_shot.mjs 的 .alpha.png)")
    ap.add_argument("--res", default="app/src/main/res")
    ap.add_argument("--check-only", action="store_true")
    ap.add_argument("--dump-masks", default=None)
    ap.add_argument("--no-sharpen", action="store_true")
    ap.add_argument("--decor", default="none", choices=("none", "cosmic"),
                    help="背景装饰样式。none=原来的纯色底(默认,便于复现旧样式)")
    ap.add_argument("--dump-backdrop", default=None,
                    help="把纯底片另存到该目录 —— 校验器要靠它把角色从渐变底上量出来")
    ap.add_argument("--content", type=float, default=1.0,
                    help="角色大小因子(乘在 CONTENT_OF_VISIBLE / LEGACY_CONTENT 上)。"
                         "1.0 = 沿用旧图视觉大小;调小给配景留空间。自适应层与传统层"
                         "同比例缩,保证两种图标视觉一致")
    args = ap.parse_args()

    src = Image.open(args.src).convert("RGBA")
    arr = np.asarray(src).astype(np.float64)
    print(f"源图 {src.size}")
    info = analyse(arr)
    if args.dump_masks:
        dump_masks(arr, info, args.dump_masks)
    if args.check_only:
        return 0

    H, W, r, dy = info["H"], info["W"], info["r"], info["dy"]
    cx, cy = info["cx"], info["cy"]
    ccx, ccy = (W - 1) / 2.0, (H - 1) / 2.0
    bbox = info["bbox"]

    # ---- 在源图分辨率上把要合成的东西都做好,之后每层只做同一次变换 ----
    # 单色层的 alpha = **源图的 alpha 原样**,只把五官挖掉。
    # 不二值化,理由见文件头"剪影绝不二值化"那一段 —— 描边的 alpha 是一条
    # 从 202 衰减到 7 的斜坡,阈值会把它切成断开的两段。
    holes = np.zeros((H, W), bool)
    for m in info["eye_masks"]:
        holes |= m
    if info["mouth_mask"] is not None:
        holes |= info["mouth_mask"]
    mono = info["alpha"].copy()
    mono[holes] = 0.0
    mono_img = Image.fromarray(np.clip(mono, 0, 255).astype(np.uint8), "L")

    res = Path(args.res)
    outdirs = {n: res / f"mipmap-{n}" for n, _ in DENSITIES}
    for d in outdirs.values():
        d.mkdir(parents=True, exist_ok=True)
    bdir = Path(args.dump_backdrop) if args.dump_backdrop else None
    if bdir:
        bdir.mkdir(parents=True, exist_ok=True)

    written = []
    content = CONTENT_OF_VISIBLE * args.content
    legacy_content = LEGACY_CONTENT * args.content
    if args.content != 1.0:
        print(f"角色缩放因子 {args.content:.3g}"
              f"  (自适应 {CONTENT_OF_VISIBLE*100:.1f}% → {content*100:.1f}% 可见圆,"
              f"传统 {LEGACY_CONTENT*100:.0f}% → {legacy_content*100:.1f}% 方形)")
    for name, dpi in DENSITIES:
        cvs = int(round(ADAPTIVE_DP * dpi))
        lgs = int(round(LEGACY_DP * dpi))

        # ============ 自适应层 ============
        # 变换的全部内容:把源图上那个**外接圆的圆心**(也就是源图中心
        # (ccx,ccy))对齐到画布中心,再整体下移 dy —— 那个 dy 就是上面为了
        # 让外接圆最小而算出来的量,不在这里用上它就等于白算。
        #
        # ⚠️ 这里踩过一次:写成 `cvs/2 + dy*scale - (cy+dy)*scale`,数学上
        # 化简成 `cvs/2 - cy*scale`,**dy 整个消失了** —— 432 画布上等于把
        # 角色上移了 17px,耳朵被顶出可见圆。所以下面把两项分开写。
        vis_r = cvs * VISIBLE_FRACTION / 2.0
        scale = (vis_r * content) / info["opt"]
        n = max(8, int(round(W * scale)))
        off_x = int(round(cvs / 2.0 - ccx * scale))
        off_y = int(round(cvs / 2.0 - ccy * scale + dy * scale))

        fg = Image.new("RGBA", (cvs, cvs), (0, 0, 0, 0))
        small = resize_rgba(src, n)
        blit(fg, small, off_x, off_y)
        bcx, bcy, br = off_x + cx * scale, off_y + cy * scale, r * scale
        if args.decor != "none":
            fg = rim_tint(fg, RIM_COLOR, br, RIM_OF_R)
        fg = fg if args.no_sharpen else sharpen_rgb(fg)
        bg_im = backdrop(cvs, bcx, bcy, br, args.decor)
        bg_im.save(outdirs[name] / "ic_launcher_background.webp", "WEBP", lossless=True, method=6)
        if bdir:
            bg_im.save(bdir / f"adaptive-{name}.png")
        fg.save(outdirs[name] / "ic_launcher_foreground.webp", "WEBP", lossless=True, method=6)

        mono_c = Image.new("L", (cvs, cvs), 0)
        blit(mono_c, resize_mask(mono_img, n), off_x, off_y)
        mc = np.asarray(mono_c)
        Image.fromarray(np.dstack([np.zeros_like(mc)] * 3 + [mc]), "RGBA").save(
            outdirs[name] / "ic_launcher_monochrome.webp", "WEBP", lossless=True, method=6)
        written += [outdirs[name] / f for f in
                    ("ic_launcher_background.webp", "ic_launcher_foreground.webp",
                     "ic_launcher_monochrome.webp")]

        # ============ 传统光栅(单独构建,不从自适应画布缩) ============
        char_h = bbox[1] - bbox[0] + 1
        char_w = bbox[3] - bbox[2] + 1
        lscale = lgs * legacy_content / max(char_h, char_w)
        ln = max(8, int(round(W * lscale)))
        lx = int(round(lgs / 2.0 - (bbox[2] + bbox[3] + 1) / 2.0 * lscale))
        ly = int(round(lgs / 2.0 - (bbox[0] + bbox[1] + 1) / 2.0 * lscale))
        lsmall = resize_rgba(src, ln)
        lc = Image.new("RGBA", (lgs, lgs), (0, 0, 0, 0))
        blit(lc, lsmall, lx, ly)
        if args.decor != "none":
            lc = rim_tint(lc, RIM_COLOR, r * lscale, RIM_OF_R)
        lc = lc if args.no_sharpen else sharpen_rgb(lc)
        flat = backdrop(lgs, lx + cx * lscale, ly + cy * lscale, r * lscale, args.decor)
        if bdir:
            flat.save(bdir / f"legacy-{name}.png")
        flat.paste(lc, (0, 0), lc)
        flat.save(outdirs[name] / "ic_launcher.webp", "WEBP", lossless=True, method=6)

        # 圆形版:同一个内容,按圆形给 alpha(旧图实测就是满幅圆)
        yy, xx = np.mgrid[0:lgs, 0:lgs]
        cc = (lgs - 1) / 2.0
        circ = Image.fromarray(
            ((np.hypot(xx - cc, yy - cc) <= cc + 0.5) * 255).astype(np.uint8), "L")
        rnd = Image.new("RGBA", (lgs, lgs), (0, 0, 0, 0))
        rnd.paste(flat, (0, 0), circ)
        rnd.save(outdirs[name] / "ic_launcher_round.webp", "WEBP", lossless=True, method=6)
        written += [outdirs[name] / "ic_launcher.webp", outdirs[name] / "ic_launcher_round.webp"]

        print(f"  {name:8s} 画布 {cvs:3d} 传统 {lgs:3d}  球直径 {2*r*scale:5.1f}"
              f" ({2*r*scale/cvs*100:4.1f}% 画布)  角色最大半径 "
              f"{vis_r*content:5.1f} / 可见 {vis_r:.0f}")

    # 中间产物不留,免得残留的 .png 在某些路径上盖住 .webp
    for p in res.glob("mipmap-*/*.png"):
        p.unlink()
    total = sum(p.stat().st_size for p in written)
    print(f"\n共 {len(written)} 个文件,{total/1024:.1f} KiB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
