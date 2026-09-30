#!/usr/bin/env python3
"""ball_shot.mjs 的自检:透明版叠底色,应当等于实底版。

## 它为什么存在

图标的两套图来源不同,但**都由浏览器输出、我都不自己合成**:
  * 前台层(自适应图标)要透明底的直通 alpha;
  * 传统光栅图要实底成品。
唯一需要我担保的事情是:那张透明 PNG 的 alpha 语义是**直通**的。
如果它是预乘的而我不知道,前台层叠到深色底上会整体偏暗 —— 小尺寸下很难
发现,正是最该机器判的一类错。

## 判据有对照,所以它有牙齿

只报一个"差异是多少"没用 —— 差异多小才算对?所以这里**同时算两种合成**:

  A. 把透明版当成直通叠上去;
  B. 把透明版当成预乘叠上去。

然后要求 **A 明显比 B 接近浏览器的实底版**。这不是阈值,是二选一。
如果哪天我把语义搞反了,A 和 B 会互换,脚本立刻翻脸 —— 第一版脚本
(只比一个数)就没有这个性质,它报的"最大差 9"读起来像是"我的代码有 bug",
实际只是预乘存 8 bit 的舍入。

## 容差是按物理原因分的

* **不透明像素(alpha ≥ 250)** 允许 ≤ 2:两边都是不透明,合成是恒等,只该有
  截屏 → sRGB 的舍入。真错的话这里是几十上百。
* **半透明边缘**允许 ≤ 24:浏览器把画布按预乘存成 8 bit,深色 + 低 alpha 的
  像素(比如直通色 20、alpha 40 → 存成 3,再乘回来 19)量化误差被 1/alpha
  放大。这是预乘存储的固有代价,不是我的 bug。

用法:ball_shot_verify.py <transparent.png> <over_bg.png> R G B
"""
import sys

from PIL import Image, ImageChops

OPAQUE_TOL = 2
EDGE_TOL = 24


def composite(straight_png: Image.Image, bg: tuple) -> Image.Image:
    """按 **直通 alpha** 合成:out = c*a + bg*(1-a)。"""
    a = straight_png.convert("RGBA")
    bgimg = Image.new("RGBA", a.size, bg + (255,))
    return Image.alpha_composite(bgimg, a).convert("RGB")


def composite_as_premultiplied(png: Image.Image, bg: tuple) -> Image.Image:
    """按 **预乘** 解释同一份像素:c 已经被乘过 a,所以不再乘第二遍。

    这就是"我理解错了"的那条假设。它在数学上是对的另一种约定,只是配上
    Android 的前台层会错 —— 这里把它算出来当对照组。
    """
    a = png.convert("RGBA")
    out = Image.new("RGB", a.size)
    sp, dp = a.load(), out.load()
    w, h = a.size
    for y in range(h):
        for x in range(w):
            r, g, b, al = sp[x, y]
            f = al / 255.0
            dp[x, y] = (
                min(255, int(round(r + bg[0] * (1 - f)))),
                min(255, int(round(g + bg[1] * (1 - f)))),
                min(255, int(round(b + bg[2] * (1 - f)))),
            )
    return out


def stats(diff: Image.Image):
    """(最大通道差, 按 alpha 分档的最大差)。"""
    return max(i for i, n in enumerate(diff.convert("L").histogram()) if n)


def main() -> int:
    if len(sys.argv) != 6:
        print(__doc__)
        return 2
    tp, bp = sys.argv[1], sys.argv[2]
    bg = tuple(int(v) for v in sys.argv[3:6])

    t = Image.open(tp).convert("RGBA")
    b = Image.open(bp).convert("RGB")
    if t.size != b.size:
        print(f"✗ 尺寸不一致: 透明版 {t.size} vs 实底版 {b.size}")
        return 1

    straight = composite(t, bg)
    premul = composite_as_premultiplied(t, bg)

    d_straight = ImageChops.difference(straight, b)
    d_premul = ImageChops.difference(premul, b)

    alpha = t.getchannel("A")
    apx = alpha.load()
    s_op = d_straight.load()
    p_op = d_premul.load()
    w, h = t.size

    opaque_n = edge_n = 0
    s_opaque_max = s_edge_max = 0
    s_opaque_bad = 0
    p_edge_max = 0
    for y in range(h):
        for x in range(w):
            av = apx[x, y]
            ds = max(s_op[x, y])
            dp = max(p_op[x, y])
            if av >= 250:
                opaque_n += 1
                s_opaque_max = max(s_opaque_max, ds)
                if ds > OPAQUE_TOL:
                    s_opaque_bad += 1
            elif av > 8:
                edge_n += 1
                s_edge_max = max(s_edge_max, ds)
                p_edge_max = max(p_edge_max, dp)

    print(f"  alpha: 边缘 {edge_n} 像素、不透明 {opaque_n} 像素")
    print(f"  不透明像素: 超差 {s_opaque_bad},最大差 {s_opaque_max} (容差 {OPAQUE_TOL})")
    print(f"  半透明边缘: 最大差 {s_edge_max} (容差 {EDGE_TOL})")
    print()
    print("  ── 对照 ──")
    print(f"  当成直通:   边缘最大差 {s_edge_max}")
    print(f"  当成预乘:   边缘最大差 {p_edge_max}   ← 另一种约定,应当明显更差")
    if p_edge_max <= s_edge_max * 2:
        print("  ✗ 两种合成差不多差 —— 这个检查分辨不出语义,不能算通过")
        return 1
    print(f"  预乘/直通 = {p_edge_max / max(1, s_edge_max):.1f}×  ⇒ 检查有分辨力")

    ok = s_opaque_bad == 0 and s_edge_max <= EDGE_TOL
    print()
    print("  ✓ 透明版与实底版自洽,且语义判定为**直通**" if ok
          else "  ✗ 超出容差,合成或 alpha 语义至少有一步是错的")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
