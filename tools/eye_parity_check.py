"""Eye 网格的两个副本是不是同一份 —— gl/Eye.kt 对 实验室的 eyeMesh。

为什么需要这个：`check_look_sync.py` 管的是**参数表、shader 语义、配色归属**，
它看不见网格生成器。于是"Kotlin 里改成 0.48、实验室忘了改"这类漂移没有任何
东西会拦。

⚠️ 这里原来写的是"这台机器上 headless Chrome 用不了，所以'两幅画看起来一样'
这条退路也是断的，只剩结构比对"。**前半句 2026-09-30 被证伪了**：headless WebGL
在这台机器上是能跑的（见 `tools/ball_shot.mjs` 的文件头，关键是**不要加
`--disable-gpu`**）。所以现在其实有两条路：这一份结构比对（仍然是最便宜、
最不依赖环境的一条），以及真去渲一张图。**但别把这条更正当成"这个脚本可以退休了"**
—— 它验证的是"两串源文本在剥掉注释后一致"，那和"渲染结果一致"是两个不同的
命题，前者更弱但更早失败。

比对的是两串东西，都在剥掉注释之后取：

  A. **数字字面量序列**，按出现顺序。转写时最常错的是常数（0.85 / 2 / 1 / 3），
     而常数错不会报错，只会让两边画出不同的形状。
  B. **算术记号序列**（cos / sin / acos / PI / 以及 clamp 的方向）。它管的是
     公式的形状，比如"切的是 yCut 还是 half"。

两种语言的写法不同（`PI.toFloat()` vs `Math.PI`、`coerceIn(a,b)` vs
`Math.min(b,Math.max(a,·))`），所以有一个**显式的等价替换表**，见 SUBS。表短
且是穷尽的，替换表本身也会被打印出来 —— 免得"归一化到能对上"这件事被藏起来。

## 抓不到什么（别把它当全量证明）

1. **裸的符号翻转**。`1 - i/(ringCount-1)` 改成 `1 + i/...` 三个序列全绿 ——
   数字没变、函数没变、标识符没变，变的只是那个 `+`。试过加一条"运算符序列"，
   不行：Kotlin 的 `if (y <= -half) -half else …` 与 JS 的三元 `y<=-half?-half:…`
   是**同义改写**，运算符出现次数天然不同，实测 12 处假差异。收益盖不住成本，
   所以这一项留白，靠人眼。（真要在意，`tools/eye_mesh_check.py` 里加一条
   数值断言比在这里抠符号便宜。）
2. **语句顺序**。把"先算 uv 再算法线"调成反序，序列照样对得上。这个不影响
   顶点数据，只影响可读性。
3. 这只比 `Eye.build` 与 `eyeMesh`。**调用方**（`EmotionBallRenderer` 选哪个
   生成器、`BallLook.eyeCut` 的值）归 `tools/check_look_sync.py` 管。

## 它会被自己骗吗 —— 负向测试

写完先拿 5 处故意的改动试了一遍，4 处抓到（换常数 / 切面取反 / min-max 互换 /
轴向互换 `r*nx`↔`r*nz`），第 5 处就是上面第 1 条那个符号翻转。改这个脚本的
匹配规则之后**要重新做一遍这个负向测试** —— 一个永远返回 0 的检查比没有检查
更坏，因为它会让人放心。

退出码：0 = 两边同一份；1 = 有差异（差异逐条打印）。
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
KT = ROOT / "app/src/main/java/net/kuafuai/andee/ui/ball/gl/Eye.kt"
LAB = ROOT / "reports/ball-cutie-lab.html"


def slice_block(src: str, start: str) -> str:
    """从 `start` 起，按花括号配对切出整段（含首尾花括号）。"""
    i = src.index(start)
    j = src.index("{", i)
    depth, k = 0, j
    while True:
        if src[k] == "{":
            depth += 1
        elif src[k] == "}":
            depth -= 1
            if depth == 0:
                break
        k += 1
    return src[i : k + 1]


def strip_comments(text: str) -> str:
    # 块注释、行注释。两边语法一样。
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    text = re.sub(r"//[^\n]*", " ", text)
    return text


# 显式等价替换表：把两种语言里同一件事的不同写法归一。
# 每条都要能一眼看懂它在替什么，否则这个检查就变成"归一化到能对上"。
SUBS = [
    # 常量与类型
    (r"\bMath\s*\.\s*PI\b", "PI"),
    (r"\bPI\s*\.\s*toFloat\s*\(\s*\)", "PI"),
    # 数学函数
    (r"\bMath\s*\.\s*(cos|sin|acos|abs|min|max|hypot|floor)\b", r"\1"),
    # min/max 统一成大写，好和 coerceIn 归一后的 MIN/MAX 同框 —— 否则"夹取方向"
    # 这一项根本没被比到（Kotlin 一侧是大写、实验室一侧是小写，两组永远不相交）。
    (r"\bmin\s*\(", "MIN("),
    (r"\bmax\s*\(", "MAX("),
    # Kotlin 的夹取 / JS 的手写夹取，成对写法不同但语义相同
    (r"topCut\s*\.\s*coerceIn\s*\(\s*0f?\s*,\s*MAX_TOP_CUT\s*\)",
     "MIN(MAX_CUT,MAX(0,topCut))"),
    (r"\(\s*\(\s*yCut\s*-\s*half\s*\)\s*/\s*radius\s*\)\s*\.\s*coerceIn\s*\(\s*-1f?\s*,\s*1f?\s*\)",
     "MIN(1,MAX(-1,(yCut-half)/radius))"),
    (r"\(\s*ringCount\s*-\s*1\s*\)\s*\.\s*coerceAtLeast\s*\(\s*1\s*\)",
     "MAX(1,ringCount-1)"),
    # `MAX_TOP_CUT` / `MAX_CUT` 是同一个常数的两个名字
    (r"\bMAX_TOP_CUT\b", "MAX_CUT"),
    # `ArrayList<Float>(2 * capSegs + 3)` 的容量提示是 Kotlin 特有的、纯性能的
    # 参数，实验室的 `[]` 没有对应物 —— 它不影响一个顶点。整段吃掉，否则那两个
    # 数字（2、3）会把后面所有项错开。
    (r"ArrayList\s*<\s*Float\s*>\s*\(\s*[^()]*\)", "[]"),
    # Kotlin 的区间算符 `0..capSegs`：`..` 紧跟在数字后面，数字正则的边界断言会
    # 认为那是小数点在失败、于是这个 0 凭空消失（实验室写的是 `i=0;i<=capSegs`，
    # 它的 0 是独立记号）。四条区间正好差四项，长度对不上就是这么来的。
    (r"(?<=[\w.)])\s*\.\.\s*", " "),
    # 同一个变量的两种拼法
    (r"\bcylH\b", "cylinderHeight"),
    (r"\bonCyl\b", "onCylinder"),
    (r"\bth\b", "theta"),
    # 数字后缀
    (r"\b(\d+(?:\.\d+)?)[fF]\b", r"\1"),
]

NUM = re.compile(r"(?<![\w.])\d+(?:\.\d+)?(?![\w.])")
# 只认会改变几何形状的记号：函数与夹取方向。
MARK = re.compile(r"\b(cos|sin|acos|PI|MIN|MAX)\b")
# 第三个序列：**决定方向的那些标识符**，按出现顺序。
#
# 为什么数字序列不够：把 `r * nx` 写成 `r * nz` 是这颗网格最容易犯、也最难看出来
# 的错 —— 数字一项没变、函数一项没变，形状却整个转了个向（左右的法线/位置互换）。
# 只挑"轴与尺寸"这一小撮名字比，不把循环计数器 `i`/`j` 拉进来：Kotlin 的
# `for (i in 0 until n)` 与 JS 的 `for(i=0;i<n;i++)` 的出现次数本来就不一样，
# 混进来只会淹掉真正的差异。
AXIS = re.compile(
    r"\b(nx|nz|cy|radius|cylinderHeight|halfHeight|half|yCut|rCut|phiCut|"
    r"ringCount|stride|vertCount|capRing|capCenter)\b"
)


def normalize(text: str) -> str:
    text = strip_comments(text)
    for pat, rep in SUBS:
        text = re.sub(pat, rep, text)
    # 把空白折成一个空格，让"换行/缩进不同"不算差异。
    #
    # 但**不能删光**：删光会把 `0.85` 和后面的 `fun` 粘成 `0.85fun`，数字正则的
    # 边界断言随之失效、那个常数就凭空消失了（这个检查自己踩过一次 —— 于是
    # "两边一致"和"这一项没被比"看起来一模一样）。提取是按记号走的，空格本来
    # 就不参与比对，留着它只赚不错。
    return re.sub(r"\s+", " ", text)


def nums(text: str) -> list:
    return NUM.findall(text)


def marks(text: str) -> list:
    return MARK.findall(text)


def axis(text: str) -> list:
    return AXIS.findall(text)


def diff_seq(label: str, a: list, b: list) -> int:
    """逐位比两条序列；返回差异条数。"""
    if a == b:
        print(f"  {label}: 一致（{len(a)} 项）")
        return 0
    print(f"  {label}: ✗ 不一致  kotlin {len(a)} 项 / 实验室 {len(b)} 项")
    shown = 0
    for i in range(max(len(a), len(b))):
        x = a[i] if i < len(a) else "<无>"
        y = b[i] if i < len(b) else "<无>"
        if x != y:
            print(f"      第 {i} 项  kotlin={x}  实验室={y}")
            shown += 1
            if shown >= 12:
                print("      …（只列前 12 处）")
                break
    return 1


def kotlin_span() -> str:
    """Kotlin 那边要拼两段才等于实验室的一个函数。

    `MAX_TOP_CUT` 在 Kotlin 里是 object 级的 `const val`（因为 [BallLook.eyeCut]
    的 KDoc 要引它、而它又是 [Eye] 自己的上限），在实验室里是 `eyeMesh` 函数体里
    的第一条 `const MAX_CUT`。位置不同、值必须同源，所以把那段声明插进函数体的
    开头 —— 而且必须**插在同一个位置**：拼在最前面的话，两条数字序列会整体错开
    一位，后面看到的全是假差异。
    """
    src = KT.read_text()
    m = re.search(r"const val MAX_TOP_CUT\s*=\s*[\d.]+f", src)
    if not m:
        raise SystemExit("Eye.kt 里找不到 MAX_TOP_CUT 的声明 —— 检查本身失效了")
    block = slice_block(src, "fun build(")
    brace = block.index("{")  # 函数体的开括号（形参里没有花括号）
    return block[: brace + 1] + "\n" + m.group(0) + "\n" + block[brace + 1 :]


def main() -> int:
    kt = normalize(kotlin_span())
    lab = normalize(slice_block(LAB.read_text(), "function eyeMesh("))

    print("归一化替换表（两种语言同一件事的不同写法）：")
    for pat, rep in SUBS:
        print(f"  {pat}  →  {rep}")
    print()

    bad = 0
    bad += diff_seq("数字字面量序列", nums(kt), nums(lab))
    bad += diff_seq("算术记号序列", marks(kt), marks(lab))
    bad += diff_seq("轴与尺寸标识符序列", axis(kt), axis(lab))

    if not bad:
        print("\n✅ gl/Eye.kt 与实验室 eyeMesh 是同一份（数字、算术、轴向逐项相同）")
    else:
        print("\n❌ 两边已经漂了 —— 改一处必须改两处")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
