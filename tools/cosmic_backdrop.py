#!/usr/bin/env python3
"""星尘宇宙底片 —— 启动图标的自适应背景层。

## 为什么单独一个模块

底片不只生成器要用,**校验器也要用**:校验"角色在传统光栅图里占 88%"时,
原来靠 `a[1,1]` 取一个背景纯色再找和它不同的像素 —— 那招在渐变底上直接失效
(整个背景都和角点不一样,角色 bbox 会涨到整张图)。所以校验器改成"和纯底片
相减",于是底片必须是两边都能调到的同一份实现。

## 两条硬约束

1. **确定性。** 同一 (size, seed) 必须逐像素一样。图标是提交进仓库的产物,
   每次重跑都在 diff 里炸出一堆无意义的字节变更,是很实在的噪音。
2. **亮度压在球体之下。** 球体主体亮度实测 ≈130。底片再好看也不能跟球抢 ——
   目标是把辉光峰值压在 100 以下,给球留出对比度。这不是审美,是"小尺寸下
   球还认不认得出"的问题,所以校验器里有一条专门量它(见 check 8)。
"""
import numpy as np
from PIL import Image

SEED = 20260930

# 深空紫:中心偏紫,边缘近黑
CORE = (46, 26, 92)
EDGE = (9, 6, 19)
# 品红辉光,垫在球后面 —— 球挡住中心,露出来的正好是一圈光晕
GLOW = (214, 74, 172)
GLOW_STRENGTH = 0.42
# 低频紫云,给渐变加一点层次,免得看上去像"圆的线性渐变"
#
# ⚠️ 第一版是 n=24 / 强度 0.16,结果**看出「拼布」**了:每 18px 一个格子、格边可见,
# 中间再被品红辉光一放大,读起来像图片压缩块,不像星云。教训是这类"随机场放大"
# 的**格子尺寸**才是观感的主导 —— 幅度只有 ±10 几个色阶,眼睛照样抓得住连贯结构。
# 所以 n 从 24 降到 7(格子 ~62px,比球还大),强度同步减半:要的是**光的不均匀**,
# 不是**纹理**。
NEBULA = (120, 70, 200)
NEBULA_STRENGTH = 0.085
VIGNETTE = 0.30
STAR_N = 110


def _stars(size: int, seed: int) -> np.ndarray:
    """星点。核心 + 一圈淡光晕,大小/亮度/色偏都用同一个 rng 定,所以可重现。"""
    rng = np.random.default_rng(seed + 1)
    acc = np.zeros((size, size, 3), np.float64)
    scale = size / 432.0
    for _ in range(STAR_N):
        x = float(rng.random()) * size
        y = float(rng.random()) * size
        # **2.2 让大部分星很小、少数明显偏大 —— 均匀分布会得到"一片均匀的麻点"
        rr = (0.45 + float(rng.random()) ** 2.2 * 2.1) * scale
        b = 0.22 + 0.78 * float(rng.random()) ** 2.5
        t = float(rng.random())
        if t < 0.62:
            col = np.array([1.00, 1.00, 1.00])
        elif t < 0.84:
            col = np.array([0.78, 0.72, 1.00])   # 淡紫
        else:
            col = np.array([1.00, 0.74, 0.93])   # 淡粉
        col = col * b * 255.0
        for rad, k in ((rr, 1.0), (rr * 2.7, 0.20)):
            x0, x1 = max(0, int(x - rad - 1)), min(size, int(x + rad + 2))
            y0, y1 = max(0, int(y - rad - 1)), min(size, int(y + rad + 2))
            if x1 <= x0 or y1 <= y0:
                continue
            yy, xx = np.mgrid[y0:y1, x0:x1]
            d = np.hypot(xx + 0.5 - x, yy + 0.5 - y)
            w = np.clip(1.0 - d / max(rad, 1e-6), 0.0, 1.0)
            acc[y0:y1, x0:x1] += w[:, :, None] * col[None, None, :] * k
    return acc


def render(size: int, ball_cx: float = None, ball_cy: float = None,
           ball_r: float = None, seed: int = SEED) -> Image.Image:
    """渲一张 size×size 的星尘底片。

    bit 中心 / 半径用来把辉光垫在球的正后方 —— 传 None 就退回画布中心。
    传进来而不是"默认居中"是有理由的:自适应层里球被下移过 dy,辉光跟着
    画布中心走就会和球错开,看上去像贴歪了的反光。
    """
    yy, xx = np.mgrid[0:size, 0:size]
    cx = float(ball_cx) if ball_cx is not None else (size - 1) / 2.0
    cy = float(ball_cy) if ball_cy is not None else (size - 1) / 2.0
    R = float(ball_r) if ball_r else size * 0.30

    # 1) 径向渐变
    d = np.hypot(xx - cx, yy - cy)
    t = np.clip(d / (R * 2.6), 0.0, 1.0) ** 0.85
    img = np.zeros((size, size, 3), np.float64)
    for i in range(3):
        img[:, :, i] = CORE[i] * (1.0 - t) + EDGE[i] * t

    # 2) 品红辉光
    g = np.exp(-0.5 * (d / max(R * 0.92, 1e-6)) ** 2) * GLOW_STRENGTH
    img += g[:, :, None] * np.array(GLOW, np.float64)

    # 3) 低频紫云:小尺寸随机场放大 —— 用 BICUBIC 插值,确定性
    rng = np.random.default_rng(seed)
    n = 7
    low = rng.random((n, n))
    cloud = np.asarray(
        Image.fromarray((low * 255).astype(np.uint8)).resize((size, size), Image.BICUBIC)
    ).astype(np.float64) / 255.0
    cloud = cloud - cloud.mean()
    img += cloud[:, :, None] * NEBULA_STRENGTH * np.array(NEBULA, np.float64)

    # 4) 星点
    img += _stars(size, seed)

    # 5) 暗角(放在星点之后,角落的星也跟着压暗,像真的景深)
    v = np.clip(d / (size * 0.72), 0.0, 1.0) ** 2 * VIGNETTE
    img *= (1.0 - v)[:, :, None]

    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGB")


if __name__ == "__main__":
    import sys
    from pathlib import Path
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/shot/cosmic.png")
    out.parent.mkdir(parents=True, exist_ok=True)
    render(432).save(out)
    print(f"底片 -> {out}")
