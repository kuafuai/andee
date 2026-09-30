#!/usr/bin/env python3
"""校验生成好的一整套 Android 启动图标。

这些判据是**踩过之后才定下来的**,每一条都对应一个曾经出过错的地方。
关键的一条是"剪影一致":第一版用 IoU 直接比前景层和单色层的剪影,得到
0.8611 —— 看起来像几何错了,其实是**挖空造成的**(前景不透明而单色为 0 的
像素正好等于眼睛+嘴的面积 5565px)。判据不修正的话,一个正确的结果会被
报成错误,而错误的判据比没有判据更危险:它教人忽略警告。

所以这里比的是"把挖空补回来之后的剪影"。

用法:check_launcher_icon.py --res app/src/main/res [--src 源图.alpha.png]
"""
import argparse
import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
import cosmic_backdrop

DENSITIES = [("mdpi", 1.0), ("hdpi", 1.5), ("xhdpi", 2.0), ("xxhdpi", 3.0), ("xxxhdpi", 4.0)]
ADAPTIVE_CANVAS = 108.0
LEGACY_CANVAS = 48.0
VISIBLE_FRACTION = 72.0 / 108.0
LEGACY_EXPECT = 0.88

BG = (14, 23, 43)
LUM_EYE = 60.0        # 前景层里眼睛的亮度上限(实测眼睛 ≈34,球体 ≈131)
LUM_MOUTH = 100.0     # ⚠️ 别写 120 —— 球体自身暗部底就是 120.6,会吞进来当嘴(见 check 6)
BALL_INNER = 0.90

# 底片装饰的判据(只在 --backdrop 给了以后生效)
GLOW_CEIL = 118.0      # 底片 P99 亮度上限。球体主体实测 ≈131,留 ~13 的余量
STAR_MIN_DELTA = 26.0  # 比底片中位数亮这么多,才算"一颗星"

failures: list[str] = []
notes: list[str] = []


def fail(msg):
    failures.append(msg)
    print(f"  ✗ {msg}")


def ok(msg):
    print(f"  ✓ {msg}")


def load_rgba(p):
    return np.asarray(Image.open(p).convert("RGBA")).astype(np.float64)


def check_alpha_semantics(res: Path):
    print("\n── 1. 文件完整性 / alpha 语义 ──")
    need_opaque = ("ic_launcher_background", "ic_launcher")
    need_alpha = ("ic_launcher_foreground", "ic_launcher_monochrome", "ic_launcher_round")
    n = 0
    for name, dpi in DENSITIES:
        for base in need_opaque + need_alpha:
            p = res / f"mipmap-{name}/{base}.webp"
            if not p.exists():
                fail(f"缺文件 {p}")
                continue
            n += 1
            a = load_rgba(p)
            al = a[:, :, 3]
            if base in need_opaque:
                if al.min() < 255:
                    fail(f"{p.name} ({name}) 应当全不透明,实测 alpha 最小 {al.min():.0f}")
            else:
                if al.max() == 0:
                    fail(f"{p.name} ({name}) 全透明,没有内容")
                elif al.min() == al.max():
                    fail(f"{p.name} ({name}) alpha 没有变化,不像是带剪影的层")
    if n == 25:
        ok(f"25 个文件齐全,background/ic_launcher 全不透明,其余带 alpha")


def check_canvas_sizes(res: Path):
    print("\n── 2. 画布尺寸 ──")
    bad = 0
    for name, dpi in DENSITIES:
        want_a = int(round(ADAPTIVE_CANVAS * dpi))
        want_l = int(round(LEGACY_CANVAS * dpi))
        for base, want in (("ic_launcher_background", want_a), ("ic_launcher_foreground", want_a),
                           ("ic_launcher_monochrome", want_a),
                           ("ic_launcher", want_l), ("ic_launcher_round", want_l)):
            w, h = Image.open(res / f"mipmap-{name}/{base}.webp").size
            if (w, h) != (want, want):
                fail(f"{name}/{base} 尺寸 {w}×{h},应为 {want}×{want}")
                bad += 1
    if not bad:
        ok("自适应层 108dp、传统层 48dp,五个密度都对")


def check_visible_circle(res: Path):
    print("\n── 3. 自适应层:角色是否落在保证可见的圆里 ──")
    rows = []
    bad = 0
    for name, dpi in DENSITIES:
        cvs = int(round(ADAPTIVE_CANVAS * dpi))
        a = load_rgba(res / f"mipmap-{name}/ic_launcher_foreground.webp")
        al = a[:, :, 3]
        ys, xs = np.where(al > 8)
        cc = (cvs - 1) / 2.0
        r = float(np.hypot(xs - cc, ys - cc).max())
        vis = cvs * VISIBLE_FRACTION / 2.0
        rows.append((name, cvs, r, vis))
        if r >= vis:
            fail(f"{name}: 角色最大半径 {r:.2f} ≥ 可见圆 {vis:.1f},会被遮罩切到")
            bad += 1
    if not bad:
        slack = min(v - r for _, _, r, v in rows)
        ok(f"五个密度都放得下,最紧的一档余量 {slack:.2f}px(@其自身画布)")
    for name, cvs, r, vis in rows:
        print(f"      {name:8s} 画布{cvs:4d}  角色 {r:6.2f} / 可见 {vis:5.1f}  余量 {vis-r:5.2f}")


def check_silhouette_match(res: Path):
    """核心一项:单色层的剪影要和美术层一致 —— **把挖空补回来再比**。

    判据**不是**"固定 IoU 阈值"。第一版写了 0.98,结果 mdpi 0.9663 / hdpi
    0.9757 被判失败 —— 但量下来的事实是:五种密度的**最佳整数位移都是 (0,0)**
    (±1 扫一圈 IoU 一点都不提升),非挖空区 alpha 平均只差 0.53/255,而
    "对称差 ÷ 半个边界带"恒等于 **0.48px**(五档依次 0.48/0.49/0.48/0.48/0.48)。

    恒定的 0.48px 就是纯亚像素效应的签名:差异面积 ∝ 周长 × 常数,而 IoU 是
    面积比 ⇒ 小画布上必然更差。固定阈值等于**在惩罚分辨率,不在检查几何**。
    真正的几何错位会给出约 2.0px(差 1px 的轮廓)。

    所以判据两条:
      ① 位移扫描无收益 —— 两层注册正确;
      ② 对称差 ÷ 半个边界带 ≤ 1.0px —— 差异是亚像素级的。
    IoU 照打,但只作参考,不设阈值。
    """
    print("\n── 4. 单色层剪影 vs 美术层剪影(挖空已补回) ──")
    bad = 0
    for name, _ in DENSITIES:
        fg = load_rgba(res / f"mipmap-{name}/ic_launcher_foreground.webp")
        mo = load_rgba(res / f"mipmap-{name}/ic_launcher_monochrome.webp")
        fa, ma = fg[:, :, 3], mo[:, :, 3]
        # 挖空 = 实体上被单色层挖掉的洞(光看 ma==0 会把背景也算进去)
        holes = (ma == 0) & (fa > 200)
        sil_fg = fa > 128
        sil_mono = (ma > 128) | holes
        iou = (sil_fg & sil_mono).sum() / max(1, (sil_fg | sil_mono).sum())

        # ① 位移扫描:任何一个 ±1 整数位移能让 IoU 变好,就是真的错位了
        gain = 0.0
        for sy in (-1, 0, 1):
            for sx in (-1, 0, 1):
                s2 = np.roll(np.roll(sil_mono, sy, 0), sx, 1)
                gain = max(gain, (sil_fg & s2).sum() / max(1, (sil_fg | s2).sum()) - iou)

        # ② 边界带:1px 膨胀 ∩ 1px 腐蚀的环,面积 ≈ 周长 × 2
        d1 = np.roll(sil_fg, 1, 0) | np.roll(sil_fg, -1, 0) | np.roll(sil_fg, 1, 1) | np.roll(sil_fg, -1, 1)
        e1 = np.roll(sil_fg, 1, 0) & np.roll(sil_fg, -1, 0) & np.roll(sil_fg, 1, 1) & np.roll(sil_fg, -1, 1)
        perim = (d1 & ~e1).sum() / 2.0
        sym = int((sil_fg ^ sil_mono).sum())
        edge_px = sym / max(1.0, perim)

        mark = "✓" if (edge_px <= 1.0 and gain <= 1e-9) else "✗"
        print(f"      {name:8s} IoU {iou:.4f} (仅供参考)  对称差 {sym:4d}px /"
              f" 周长 {perim:6.1f} = {edge_px:.2f}px {mark}"
              f"   位移收益 {gain:+.4f}  挖空 {int(holes.sum())}px")
        if edge_px > 1.0:
            fail(f"{name}: 剪影差异达 {edge_px:.2f}px(周长归一),超出亚像素级")
            bad += 1
        if gain > 1e-9:
            fail(f"{name}: 单色层与美术层有整体错位(某个 ±1 位移能把 IoU 变好 {gain:+.4f})")
            bad += 1
    if not bad:
        ok("五档剪影差异均为亚像素级(周长归一 ≤1.0px),且无整体错位")


def check_holes(res: Path):
    """挖空必须是"被实体包住的洞",不能碰到剪影外沿。"""
    print("\n── 5. 挖空是内洞,不是缺口 ──")
    bad = 0
    for name in ("xxxhdpi",):
        cvs = int(round(ADAPTIVE_CANVAS * {"xxxhdpi": 4.0}[name]))
        fg = load_rgba(res / f"mipmap-{name}/ic_launcher_foreground.webp")
        mo = load_rgba(res / f"mipmap-{name}/ic_launcher_monochrome.webp")
        fa, ma = fg[:, :, 3], mo[:, :, 3]
        holes = (ma == 0) & (fa > 200)
        sil = fa > 128
        ys, xs = np.where(sil)
        hy, hx = np.where(holes)
        for lbl, arr, lo, hi in (("x", hx, xs.min(), xs.max()), ("y", hy, ys.min(), ys.max())):
            if arr.min() <= lo or arr.max() >= hi:
                fail(f"挖空在 {lbl} 方向碰到剪影边界(洞 {arr.min()}..{arr.max()},"
                     f"剪影 {lo}..{hi}) —— 洞漏到外面了")
                bad += 1
        # 洞应当在球体内部:到球心的距离 < 剪影的包络
        cc = (cvs - 1) / 2.0
        rmax = float(np.hypot(xs - cc, ys - cc).max())
        rh = float(np.hypot(hx - cc, hy - cc).max())
        print(f"      挖空 {len(hx)} px,最远点到中心 {rh:.1f} / 剪影包络 {rmax:.1f}")
        if rh >= rmax * 0.98:
            fail(f"挖空的最远点已经贴到剪影边缘({rh:.1f} vs {rmax:.1f})")
            bad += 1
    if not bad:
        ok("挖空完全在剪影内部,没有漏到边界")


def check_holes_align_with_face(res: Path):
    """洞要盖在真的五官上 —— 用美术层自己的暗像素当对照。

    两个坑:

    ① **阈值必须落在实测的谷底里,不能"看着像"。** 原来嘴用 LUM_MOUTH=120,
       而球体自身的暗部底就是 120.6(下半部亮度分位 5%=78.9、10%=120.6、
       50%=124.1)。阈值贴上去的结果是把球体暗部一起吞进来当嘴:115→120 时
       "嘴"从 589px 涨到 725px、覆盖从 89.3% 掉到 72.6%;120→125 更是暴涨到
       6131px。改成 100(谷底正中)后是 551px / 95.3%。

    ② **洞的边缘是抗锯齿的,`ma == 0` 取不到那一圈。** 单色层的 alpha 在洞的
       边界上是一条从 0 斜坡到 255 的过渡,所以"严格相等 0"会漏掉最外一圈。
       实测:嘴覆盖不到的那 26px **100% 都落在洞的 1px 边缘带上**。
       所以判据给 1px 容差 —— 但**同时**还要严判,否则洞整体偏出去也能靠边缘
       带凑过去。两条都要过。
    """
    print("\n── 6. 挖空位置与美术层的五官对齐 ──")
    tol_edges = 0.98   # 洞 ∪ 洞外 1px,应当几乎全覆盖
    min_strict = 0.70  # 只用洞本身,不许偏太远
    bad = 0
    name, dpi = "xxxhdpi", 4.0
    cvs = int(round(ADAPTIVE_CANVAS * dpi))
    fg = load_rgba(res / f"mipmap-{name}/ic_launcher_foreground.webp")
    mo = load_rgba(res / f"mipmap-{name}/ic_launcher_monochrome.webp")
    fa, ma = fg[:, :, 3], mo[:, :, 3]
    lum = 0.2126 * fg[:, :, 0] + 0.7152 * fg[:, :, 1] + 0.0722 * fg[:, :, 2]

    ys, xs = np.where(fa > 128)
    # 球心 = 剪影里最宽那一行的中心;球半径 = 最宽行的半宽
    best = (0, 0)
    for y in range(ys.min(), ys.max() + 1):
        xr = np.where(fa[y] > 128)[0]
        if len(xr) and xr.max() - xr.min() + 1 > best[0]:
            best = (xr.max() - xr.min() + 1, (xr.min() + xr.max()) / 2.0)
    ball_cx = best[1]
    ball_cy = float(ys.max()) - best[0] / 2.0
    ball_r = best[0] / 2.0

    yy, xx = np.mgrid[0:cvs, 0:cvs]
    inside = np.hypot(xx - ball_cx, yy - ball_cy) < ball_r * BALL_INNER
    holes = (ma == 0) & (fa > 200)
    hd = (holes | np.roll(holes, 1, 0) | np.roll(holes, -1, 0)
          | np.roll(holes, 1, 1) | np.roll(holes, -1, 1))
    fringe = hd & ~holes

    for lbl, mask in (("眼睛", inside & (yy < ball_cy) & (lum < LUM_EYE)),
                      ("嘴", inside & (yy > ball_cy) & (lum < LUM_MOUTH))):
        if mask.sum() == 0:
            notes.append(f"{lbl}: 在美术层里没找到暗像素,跳过")
            print(f"      {lbl}: 没找到对照像素,跳过")
            continue
        n = mask.sum()
        s = (mask & holes).sum() / n
        t = (mask & hd).sum() / n
        inedge = (mask & fringe).sum()
        print(f"      {lbl}: 暗像素 {int(n):5d}px  严格覆盖 {s*100:5.1f}%"
              f"  (含洞外 1px 边缘带) {t*100:5.1f}%   边缘带 {int(inedge)}px")
        if t < tol_edges:
            fail(f"{lbl} 含边缘带也只有 {t*100:.1f}% 落在洞上 —— 洞和五官没对上")
            bad += 1
        elif s < min_strict:
            fail(f"{lbl} 严格覆盖只有 {s*100:.1f}%(<{min_strict*100:.0f}%) ——"
                 f"靠边缘带凑数,洞偏了")
            bad += 1
    if not bad:
        ok(f"眼睛和嘴都落在洞上(含洞外 1px 边缘带 ≥{tol_edges*100:.0f}%),"
           f"且严格覆盖 ≥{min_strict*100:.0f}%")


def check_legacy(res: Path, bdir: Path | None, expect: float):
    """传统光栅图占比。

    ⚠️ **底片从纯色换成渐变之后,"拿角点当背景色"这招就废了。** 原来靠
    `a[1,1]` 取一个纯色再找和它不同的像素;换成星尘底片以后整个背景都和角点
    不同,于是角色 bbox 涨到整张图、占比量出 100.0%。这不是产物的问题,是判据
    的前提没了 —— 所以改成**和纯底片相减**:底片由 `cosmic_backdrop.render`
    出,生成器和校验器调的是同一份实现,不是各写一遍。

    ⚠️ `expect` 不能写死 0.88 —— 那 88% 是**量旧图量出来的**,不是一条独立于
    角色的约定。一旦下调用 `--content` 把角色缩小(为了给配景留空间),预期的
    占比必然跟着降,写死就会把正确的结果报成失败。这是这条判据**第二次**因为
    前提变化而失效。
    """
    print("\n── 7. 传统光栅图占比 / 圆形版 ──")
    bad = 0
    for name, dpi in DENSITIES:
        lgs = int(round(LEGACY_CANVAS * dpi))
        a = load_rgba(res / f"mipmap-{name}/ic_launcher.webp")
        if bdir is not None:
            bd = load_rgba(bdir / f"legacy-{name}.png")[:, :, :3]
            d = np.abs(a[:, :, :3] - bd).sum(axis=2)
        else:
            bg = a[1, 1, :3]
            d = np.abs(a[:, :, :3] - bg).sum(axis=2)
        ys, xs = np.where(d > 12)
        frac = max(xs.max() - xs.min() + 1, ys.max() - ys.min() + 1) / lgs
        mark = "✓" if abs(frac - expect) < 0.03 else "✗"
        print(f"      {name:8s} 传统图 {lgs:3d}px 角色占 {frac*100:5.1f}%"
              f" (目标 {expect*100:.1f}%) {mark}")
        if abs(frac - expect) >= 0.03:
            fail(f"{name}: 传统图角色占 {frac*100:.1f}%,偏离目标 {expect*100:.1f}%")
            bad += 1

        r = load_rgba(res / f"mipmap-{name}/ic_launcher_round.webp")
        ral = r[:, :, 3]
        rys, rxs = np.where(ral > 128)
        # 满幅圆:最宽行应当等于边长(±2),且四角透明
        widths = {}
        for y in range(rys.min(), rys.max() + 1, max(1, lgs // 24)):
            xr = np.where(ral[y] > 128)[0]
            if len(xr):
                widths[y] = xr.max() - xr.min() + 1
        wmax = max(widths.values()) if widths else 0
        corners = ral[0, 0] + ral[0, -1] + ral[-1, 0] + ral[-1, -1]
        if wmax < lgs - 2:
            fail(f"{name}: 圆形版最宽行 {wmax},应接近边长 {lgs}")
            bad += 1
        if corners > 8:
            fail(f"{name}: 圆形版四角不透明(合计 {corners}),不是圆")
            bad += 1
    if not bad:
        ok(f"传统图占 {expect*100:.1f}% 满幅(与生成时 --content 一致),"
           f"圆形版是满幅圆(四角透明)")


def check_decor(bdir: Path | None):
    """底片装饰本身的三条:确定性、亮度上限、星点存在。

    这三条都是"看着挺好、下一版就悄悄坏掉"的类型,所以写成判据:

    * **确定性** —— 图标是提交进仓库的产物。同一 (size, seed) 只要有一点随机漂移,
      每次重跑都在 diff 里炸出一堆无意义的字节变更,真改动会被淹掉。
      所以这里**跨进程**比:同一个尺寸渲两遍必须逐像素相同。
    * **亮度上限** —— 球体主体亮度实测 ≈130,底片再好看也不能跟球抢。把小尺寸下
      "球还认不认得出"这件事变成一条可量的线。
    * **星点存在** —— 装饰最容易出的事是"改了参数之后星点全被暗角/渐变吃掉",
      而肉眼在预览图那样的大图上根本发现不了。
    """
    print("\n── 8. 底片装饰(确定性 / 亮度上限 / 星点) ──")
    if bdir is None:
        print("      没给 --backdrop,跳过(底片是纯色时这三条不适用)")
        return
    bad = 0

    # ① 确定性:同一个尺寸渲两遍
    a1 = np.asarray(cosmic_backdrop.render(432))
    a2 = np.asarray(cosmic_backdrop.render(432))
    if not np.array_equal(a1, a2):
        fail("同一尺寸渲两遍不一致 —— 底片不是确定性的(图标会每次重建都变)")
        bad += 1
    else:
        print("      确定性:同尺寸两遍逐像素相同 ✓")

    # ② 亮度上限
    bg = load_rgba(bdir / "adaptive-xxxhdpi.png")[:, :, :3]
    lum = 0.2126 * bg[:, :, 0] + 0.7152 * bg[:, :, 1] + 0.0722 * bg[:, :, 2]
    print(f"      底片亮度 均值 {lum.mean():.1f}  中位 {np.median(lum):.1f}"
          f"  P99 {np.percentile(lum, 99):.1f}  最大 {lum.max():.1f}")
    if np.percentile(lum, 99) > GLOW_CEIL:
        fail(f"底片 P99 亮度 {np.percentile(lum, 99):.1f} 超过上限 {GLOW_CEIL}"
             f" —— 会和球体(≈131)抢对比度")
        bad += 1

    # ③ 星点:局部亮于周边 N 的孤立小亮点
    med = np.median(lum)
    lit = lum > med + STAR_MIN_DELTA
    print(f"      星点数(亮于底片中位数 +{STAR_MIN_DELTA}) {int(lit.sum())} px")
    if lit.sum() < 200:
        fail(f"亮星只有 {int(lit.sum())} px —— 星点被渐变或暗角吃掉了")
        bad += 1
    if not bad:
        ok("底片确定性、辉光不压过球、星点确实存在")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--res", default="app/src/main/res")
    ap.add_argument("--src", default=None, help="源图,给了就顺便核对几何比例")
    ap.add_argument("--backdrop", default=None,
                    help="纯底片目录(生成器 --dump-backdrop 的产物)。底片是渐变时"
                         "必给,否则第 7 项量不准")
    ap.add_argument("--content", type=float, default=1.0,
                    help="角色大小因子,必须和生成时用的相同 —— 第 7 项的目标占比"
                         "要跟着它走(88% 是量旧图来的,不是独立约定)")
    args = ap.parse_args()
    res = Path(args.res)
    bdir = Path(args.backdrop) if args.backdrop else None
    expect_legacy = LEGACY_EXPECT * args.content

    print(f"校验 {res}")
    check_alpha_semantics(res)
    check_canvas_sizes(res)
    check_visible_circle(res)
    check_silhouette_match(res)
    check_holes(res)
    check_holes_align_with_face(res)
    check_legacy(res, bdir, expect_legacy)
    check_decor(bdir)

    total = sum(p.stat().st_size for p in res.glob("mipmap-*/*.webp"))
    print(f"\n总体积 {total/1024:.1f} KiB")
    for n in notes:
        print(f"  注: {n}")
    if failures:
        print(f"\n✗ {len(failures)} 项没过")
        return 1
    print("\n✓ 全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
