#!/usr/bin/env python3
"""实验室与真机是否还对得上。

`reports/ball-cutie-lab.html` 是这套球球的规格说明书：用户在那个页面上看效果、
拖参数、做决定，然后把结论落到 Kotlin。所以两边一旦漂移，用户就是拿一个假的样机
在做决策 —— 而这是发生过的：

  * 实验室的 RIM_FS 少写了一条扫描光带，真机上多出一条从下往上滚的色带。
    参数核对脚本查不出这个，因为参数全对，错的是着色器。
  * 我手改 RIM_FS 时把 `abs(d)` 写成了 `d`，band 从 [0,1] 变成最大 140，
    被 clamp 到满强度 —— 整颗球从下往上染色。实验室里依然看不见。

这个脚本查两件事：**参数逐位**、**着色器语义**（忽略空白与注释）。
只做检查，不改任何文件；不一致就退出码 1。

    python3 tools/check_look_sync.py
"""
import re
import sys
from pathlib import Path

# 仓库根目录按脚本自身位置推断。别写死绝对路径 —— 写死过一次，别人 clone 下来
# 脚本第一行就指向一个不存在的目录，且报错是"文件读不到"，看不出是路径问题。
ROOT = Path(__file__).resolve().parent.parent
KT_LOOK = ROOT / "app/src/main/java/net/kuafuai/andee/ui/ball/BallLook.kt"
KT_RENDERER = ROOT / "app/src/main/java/net/kuafuai/andee/ui/ball/EmotionBallRenderer.kt"
KT_MOOD = ROOT / "app/src/main/java/net/kuafuai/andee/ui/ball/Mood.kt"
LAB = ROOT / "reports/ball-cutie-lab.html"

# 参数名对应：实验室里叫什么 → Kotlin 里叫什么
PARAMS = [
    ("bodyBase", "bodyBase"), ("tint", "bodyTint"), ("albedoMul", "bodyAlbedo"),
    ("grad", "bodyGrad"), ("amb", "bodyAmb"), ("diff", "bodyDiff"),
    ("gloss", "bodyGloss"), ("bodyScale", "bodyScale"),
    ("faceZ", "faceZ"), ("eyeX", "eyeX"), ("eyeY", "eyeY"),
    ("eyeR", "eyeRadius"), ("eyeH", "eyeCyl"),
    ("mouthR", "mouthR"), ("mouthTube", "mouthTube"),
    ("mouthY", "mouthY"), ("mouthThick", "mouthThick"),
    ("eyeColor", "eyeColor"), ("pupilR", "pupilR"), ("specR", "glintR"),
    ("blushColor", "blushColor"), ("blushAlpha", "blushAlpha"),
    ("blushSize", "blushSize"), ("blushX", "blushX"), ("blushY", "blushY"),
    ("rimGain", "rimGain"), ("rimPow", "rimPow"),
    ("bubbleX", "bubbleX"), ("bubbleY", "bubbleY"), ("bubbleR", "bubbleR"),
    ("bubbleColor", "bubbleColor"), ("bubblePow", "bubblePow"),
    ("bubbleShine", "bubbleShine"), ("bubbleInner", "bubbleInner"),
    # 耳朵与眉毛。奶油球 2026-09-30 长出来的那一对耳朵，加在这里是因为它们**进
    # 了屏幕上的轮廓**：earH/earX/earY/earTilt 一起决定耳朵出不出球的边。
    # （眉毛曾经的理由也是"压不压到眼睛上" —— 但奶油球那对同一天删掉了，
    # 现在只有 BAJIE 还在用，而它在实验室里根本没有预设。字段仍然留着核对，
    # 因为它给"以后某个形象想要一根真的眉"留了路。）
    #
    # ⚠️ 想验"眉压没压到眼睛"不能只看 browY：眉盘是 dotMesh 缩放的**椭圆**再旋转，
    # 最低点是 `cy − √((w·sinθ)² + (h·cosθ)²)`，不是 `cy − browH`；眼睛的顶也不是
    # `eyeY + eyeRadius`（那只是球，漏了圆柱段 eyeCyl/2）—— 奶油球当初就是在这两个
    # 地方都算错，才把一根眉放到了几乎贴着眼球的位置（见 BallLook.CREAM 的注释）。
    # browShade 故意**不在**这个表里：它是"零即关"，Kotlin 侧没有任何形象写这一行，
    # 而实验室的 preset 表为了格式整齐每个形象都把它列出来。核对一个恒为默认值的
    # 字段没有意义。
    ("earH", "earH"), ("earW", "earW"), ("earThick", "earThick"),
    ("earX", "earX"), ("earY", "earY"), ("earZ", "earZ"),
    ("earTilt", "earTilt"), ("earBend", "earBend"), ("earShade", "earShade"),
    ("browW", "browW"), ("browH", "browH"),
    ("browY", "browY"), ("browTilt", "browTilt"),
    # 眼神。eyeCut 是**进屏幕轮廓的那一类**：0.48 把眼睛上沿切掉 23% 的高度，
    # 眼睛从竖椭圆变成横的；eyeTilt 把整颗眼睛连瞳孔一起转。这两条在实验室里
    # 调完不落回 Kotlin，用户看到的就是另一双眼睛。
    ("eyeCut", "eyeCut"), ("eyeTilt", "eyeTilt"),
    ("pupilAspect", "pupilAspect"),
]

# 真正核对的形象。**这个元组是唯一的定义处** —— 下面的循环、末尾的报告都读它，
# 免得"核对了哪些"和"报告说核对了哪些"各写一遍然后对不上。
#
# imp 加进来的理由很具体：它是唯一一个曾经完全没覆盖的落地形象，而"眉毛和脸
# 同色看不见"那个 bug 正因为它不在这里，只能在真机上被发现。它现在在，但只是
# **部分**在 —— 角/獠牙/尾巴这一页画不出来，见 LAB_BLIND_SPOTS。
CHECKS = ("cream", "imp")

# 实验室**画不出来**的几何，按形象列。这和"参数没核对"是两件事，报告里必须分开：
# 前者是实验室会画出一颗形状不对的球（可见的谎），后者只是漏检（没覆盖）。
# 混在一起说，用户就会以为预设里的球等于真机上的球。
LAB_BLIND_SPOTS = {
    "imp": "角、獠牙、尾巴",
    "bajie": "猪鼻、鼻孔",
}
# 枚举型：实验室用的是字符串
ENUMS = [
    ("mouthDeep", lambda L: L.get("mouthMode") == "deep"),
    ("blushBlendNormal", lambda L: L.get("blushBlend") == "alpha"),
    ("rimDark", lambda L: L.get("rimMode") == "dark"),
]
# 着色器别名：实验室里叫 BODY/PTS，Kotlin 里叫 SHELL/POINTS
SHADER_ALIAS = {
    "SHELL_VS": "BODY_VS", "SHELL_FS": "BODY_FS",
    "POINTS_VS": "PTS_VS", "POINTS_FS": "PTS_FS",
}
SHADERS_IN_USE = ["SOLID_VS", "SOLID_FS", "SHELL_VS", "SHELL_FS",
                  "RIM_VS", "RIM_FS", "POINTS_VS", "POINTS_FS",
                  "BUBBLE_VS", "BUBBLE_FS"]

# 表情表的字段与默认值。两边的默认值必须一致 —— Face 的具名参数和实验室
# F() 的 Object.assign 都从这里取，改一边不改另一边就是一次静默漂移。
FACE_DEFAULTS = {
    "eyeArc": 0.0, "blush": 0.0, "spread": 1.0,
    "breath": 0.022, "gape": 0.0, "bubble": 0.0,
}
FACE_FIELDS = ["c", "eye", "brow", "smile", "pulse", "tilt", "skew", "jitter",
               "eyeArc", "blush", "spread", "breath", "gape", "bubble"]


def parse_kotlin_defaults(text):
    """`BallLook` 类声明里的默认值 —— 某个 look 没写某一行时，用的就是它们。

    为什么非要知道默认值：三个形象里只有 cream 把参数写齐了，另外两个大量省略。
    如果只比对"写出来的行"，省略的那些就既没核对、又和实验室的预设对不上 ——
    而 earH 恰恰是**零即关**那一类：Kotlin 省略 = 默认 0f，实验室预设写了 0.155，
    两边画的就不是同一颗球。**省略不等于"随便"**，它是一项被继承的值。

    只取**声明区**（`class BallLook(` 到 `) {` 之前）并丢掉注释行，所以 KDoc 里
    的示例文字不会被当成声明；`companion object` 里的 `private val EMPTY` 也不在
    这个区间内。
    """
    head = text[text.index("class BallLook("):]
    head = head[:head.index("\n) {")]
    lines = [ln for ln in head.split("\n")
             if not re.match(r"\s*(?:\*|/\*|//)", ln)]
    out = {}
    for m in re.finditer(
        r"\bval (\w+):\s*(\w+)\s*=\s*(floatArrayOf\([^)]*\)|[^,\n]+),",
        "\n".join(lines),
    ):
        name, kind, raw = m.group(1), m.group(2), m.group(3).strip()
        try:
            if kind == "Float":
                out[name] = float(raw.rstrip("f"))
            elif kind == "Boolean":
                out[name] = raw == "true"
            elif kind == "Int":
                out[name] = int(raw)
            elif kind == "FloatArray" and raw.startswith("floatArrayOf"):
                out[name] = [float(x.strip().rstrip("f"))
                             for x in raw[len("floatArrayOf("):-1].split(",")]
        except ValueError:
            # 值的形态不认识（比如 `EMPTY`）—— 跳过。跳过的后果是这一项被当成
            # "没有默认值"，会在下面报成 Kotlin=None，是**大声**的失败，不是静默。
            pass
    return out


def parse_kotlin_look(text, name):
    body = text[text.index(f"val {name} = BallLook("):]
    body = body[:body.index("\n)")]
    out = {}
    for m in re.finditer(
        r"(\w+)\s*=\s*(floatArrayOf\([^)]*\)|[-\d.]+f|true|false)", body
    ):
        k, v = m.group(1), m.group(2)
        if v.endswith("f"):
            out[k] = float(v[:-1])
        elif v.startswith("floatArrayOf"):
            out[k] = [float(x.strip().rstrip("f"))
                      for x in v[len("floatArrayOf("):-1].split(",")]
        else:
            out[k] = v == "true"
    return out


def parse_lab_preset(text, key):
    i = text.index(f"key:'{key}'")
    i = text.rindex("const P_", 0, i)
    j = text.index("\n};", i)
    out = {}
    for m in re.finditer(
        r"(\w+)\s*:\s*(\[[^\]]*\]|'[^']*'|[-\d.]+|true|false)", text[i:j]
    ):
        k, v = m.group(1), m.group(2)
        if v.startswith("["):
            out[k] = [float(x) for x in v[1:-1].split(",")]
        elif v.startswith("'"):
            out[k] = v[1:-1]
        elif v in ("true", "false"):
            out[k] = v == "true"
        else:
            out[k] = float(v)
    return out


def strip_glsl(src):
    """归一化成可比的语义文本：去注释，声明按集合比，其余按去空白文本比。

    三件事都归一化过，因为它们在 GLSL 里都没有意义、但会让天真的文本比对
    报出一堆假警报，检查很快就没人看了：

      * 空白与换行 —— 两边排版风格不同
      * 声明顺序 —— 各自按可读性排的
      * 一行几条声明 —— 一边一行一条，另一边图省事写成一行两条

    所以声明是**按声明粒度**提取（不是按行），再排序。
    """
    src = re.sub(r"//[^\n]*", "", src)
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)

    decl_pat = re.compile(r"\b(?:uniform|varying|attribute)\s+[^;]+;")
    decls = sorted(re.sub(r"\s+", "", d) for d in decl_pat.findall(src))
    body = decl_pat.sub("", src)
    body = re.sub(r"precision\s+\w+\s+\w+;", "", body)
    return ";".join(decls) + "||" + re.sub(r"\s+", "", body)


def kotlin_shaders(text):
    return {
        m.group(1): strip_glsl(m.group(2))
        for m in re.finditer(
            r'private const val (\w+_(?:VS|FS)) = """\n(.*?)\n\s*"""', text, re.S
        )
    }


def lab_shaders(text):
    out = {}
    for m in re.finditer(r"const (VS|FS)_(\w+) = `\n(.*?)\n`;", text, re.S):
        # 实验室里变量名不一致（mv vs mvpos）不影响语义，统一一下再比
        src = m.group(3)
        out[f"{m.group(2)}_{m.group(1)}"] = strip_glsl(src)
    return out


def slice_region(text, start_pat, end_pat):
    """截取 [start_pat, 之后第一次出现的 end_pat) 之间的源码，带行号返回。"""
    i = text.index(start_pat)
    start_line = text.count("\n", 0, i) + 1
    j = i + len(start_pat) + text[i + len(start_pat):].index(end_pat)
    return [(start_line + n, ln)
            for n, ln in enumerate(text[i:j].split("\n"))]


def check_color_ownership(label, rows, set_pat, draw_pat):
    """每一笔都必须在自己所在的分支里设过颜色。

    这是踩过两次的坑：`uColor` 是没有 attribute 的 uniform，**它会跨 draw call
    保持上次的值**。所以少设一次不会报错，只会静默地继承上一笔的颜色。

    为什么不能靠"上一条 draw 之后有没有 set"来判断：圆眼那一路是**条件分支**，
    源码里写着 `eyeMesh.draw()`、运行时却可能因为尺寸阈值不执行，
    静态看行序永远是对齐的。所以要给颜色算**作用域**：

      * 一次 set 从它自己开始生效；
      * 出现缩进比它更小的行（也就是退出了它所处的块）就失效；
      * 没有生效颜色的 draw 就是漏洞。

    这样"同色画很多笔"不会误报，而"某个分支忘了设色"会被精确抓住。

    颜色的判据是**两个 program 都算**（五官段用 SOLID，鼻涕泡用自己的 BUBBLE）。
    更严的做法是给每一笔记住它属于哪个 program，但五官段里只有水滴这一笔换了
    program，为它建一套映射不划算；代价是"用了 A 却只设了 B 的颜色"这种错会漏掉，
    而那种错在屏幕上是一眼能看出来的。
    """
    problems = []
    active = None          # 当前生效颜色所在行的缩进
    for line_no, raw in rows:
        code = raw.split("//")[0].rstrip()
        if not code.strip():
            continue
        indent = len(code) - len(code.lstrip())
        if active is not None and indent < active:
            active = None
        if re.search(set_pat, code):
            active = indent
        if re.search(draw_pat, code) and active is None:
            problems.append(
                f"{label}:{line_no} 这一笔没有在自身分支内设颜色，"
                f"会继承上一笔的遗留色"
            )
            print(f"  ✗ 第 {line_no} 行：{code.strip()[:70]}")
    return problems


def parse_kotlin_face(text):
    """Mood.kt 的 FACE 表 → {mood: {字段: 值}}，默认值补齐。"""
    out = {}
    for m in re.finditer(r"Mood\.(\w+) to Face\(", text):
        i, depth, j = m.end(), 1, m.end()
        while depth:
            if text[j] == "(":
                depth += 1
            elif text[j] == ")":
                depth -= 1
            j += 1
        # 顶层逗号切分（rgb(...) 里的逗号不算）
        parts, buf, d = [], "", 0
        for ch in text[i:j - 1]:
            if ch == "(":
                d += 1
            elif ch == ")":
                d -= 1
            if ch == "," and d == 0:
                parts.append(buf)
                buf = ""
            else:
                buf += ch
        parts = [p.strip() for p in parts if p.strip()]

        # Face 的位置参数顺序：color, eye, brow, smile, pulse, tilt, skew, jitter
        ORDER = ["c", "eye", "brow", "smile", "pulse", "tilt", "skew", "jitter"]
        vals = dict(FACE_DEFAULTS)
        for k, raw in zip(ORDER, parts):
            hexm = re.search(r"0x([0-9a-fA-F]+)", raw)
            vals[k] = int(hexm.group(1), 16) if k == "c" else float(raw.rstrip("f"))
        for raw in parts[len(ORDER):]:
            name, _, val = raw.partition("=")
            vals[name.strip()] = float(val.strip().rstrip("f"))
        out[m.group(1)] = vals
    return out


def parse_lab_face(text):
    """实验室 FACE_NEW → {mood: {字段: 值}}，默认值补齐。"""
    i = text.index("const FACE_NEW")
    body = text[i:text.index("\nconst MOODS", i)]
    out = {}
    for m in re.finditer(r"^\s*(\w+):\s*F\(\{([^}]*)\}\)", body, re.M):
        vals = dict(FACE_DEFAULTS)
        for kv in m.group(2).split(","):
            name, _, val = kv.partition(":")
            name, val = name.strip(), val.strip()
            if not name:
                continue
            vals[name] = int(val, 16) if val.startswith("0x") else float(val)
        out[m.group(1)] = vals
    return out


def main():
    look_src = open(KT_LOOK).read()
    render_src = open(KT_RENDERER).read()
    mood_src = open(KT_MOOD).read()
    lab_src = open(LAB).read()

    problems = []

    print("── 参数 ──")
    # 默认值是**跨形象共用**的，解析一次就够。
    defaults = parse_kotlin_defaults(look_src)
    for look in CHECKS:
        k = dict(defaults)
        k.update(parse_kotlin_look(look_src, look.upper()))
        lab = parse_lab_preset(lab_src, look)
        n = 0
        for labk, ktk in PARAMS:
            a, b = k.get(ktk), lab.get(labk)
            ok = False
            if isinstance(a, list) and isinstance(b, list) and len(a) == len(b):
                ok = all(abs(x - y) < 1e-6 for x, y in zip(a, b))
            elif isinstance(a, float) and isinstance(b, float):
                ok = abs(a - b) < 1e-6
            if ok:
                n += 1
            else:
                problems.append(f"{look}.{ktk}: Kotlin={a} 实验室={b}")
        for ktk, fn in ENUMS:
            if k.get(ktk) == fn(lab):
                n += 1
            else:
                problems.append(f"{look}.{ktk}: Kotlin={k.get(ktk)} 期望={fn(lab)}")
        print(f"  {look:<6} {n} / {len(PARAMS) + len(ENUMS)} 项一致")

    print("── 着色器（忽略注释与空白）──")
    K = kotlin_shaders(render_src)
    L = lab_shaders(lab_src)
    for name in SHADERS_IN_USE:
        lab_name = SHADER_ALIAS.get(name, name)
        a, b = K.get(name), L.get(lab_name)
        if a is None or b is None:
            problems.append(f"{name}: 一边缺失（实验室里叫 {lab_name}）")
            print(f"  ✗ {name:<10} 一边缺失")
        elif a == b:
            tag = f"（实验室里叫 {lab_name}）" if lab_name != name else ""
            print(f"  ✓ {name:<10} 语义一致{tag}")
        else:
            i = next((j for j in range(min(len(a), len(b))) if a[j] != b[j]),
                     min(len(a), len(b)))
            problems.append(
                f"{name}: 语义不同，首个差异在第 {i} 字符\n"
                f"      Kotlin: ...{a[max(0, i - 40):i + 60]}\n"
                f"      实验室: ...{b[max(0, i - 40):i + 60]}"
            )
            print(f"  ✗ {name:<10} 语义不同")

    print("── 表情表 ──")
    KF, LF = parse_kotlin_face(mood_src), parse_lab_face(lab_src)
    if set(KF) != set(LF):
        problems.append(f"表情表的表情对不上：只在一侧有 "
                        f"{sorted(set(KF) ^ set(LF))}")
    bad_rows = 0
    for mood in sorted(set(KF) & set(LF)):
        diffs = []
        for f in FACE_FIELDS:
            a, b = KF[mood].get(f), LF[mood].get(f)
            if a is None or b is None:
                diffs.append(f"{f}: 一侧缺失 ({a} / {b})")
            elif a != b:
                diffs.append(f"{f}: Mood.kt={a} 实验室={b}")
        if diffs:
            bad_rows += 1
            problems.append(f"{mood}: " + "；".join(diffs))
            print(f"  ✗ {mood}")
    print(f"  {len(KF) - bad_rows} / {len(KF)} 个表情逐字段一致")

    print("── 五官段：每一笔是否自带颜色 ──")
    problems += check_color_ownership(
        "EmotionBallRenderer.drawFace",
        slice_region(render_src, "private fun drawFace()", "\n    private fun "),
        r'(?:solidShader|bubbleShader)\.uniform\("uColor"\)',
        r"\w+\.draw\(\)",
    )
    problems += check_color_ownership(
        "lab 五官段",
        slice_region(lab_src, "/* ---- 2. 五官 ---- */", "/* ---- 3. 腮红 ---- */"),
        r"(?:pSolid|pBubble)\.u\('uColor'\)",
        r"this\.m\w+\.draw\(\)",
    )
    if not any("自身分支内设颜色" in p for p in problems):
        print("  ✓ 两边每一笔都自带颜色")

    # 落地的不再是一个常量，而是一张可切换的表：用户在全屏卡片上左右滑动球球
    # 换形象，没存过就用第一个。这里要说清楚**哪些真的核对过**、哪些没有，以及
    # 有预设但画不全的那种 —— 三种状态混成一句"都查了"就是这篇脚本最该避免的事。
    m = re.search(r"val ALL = listOf\(([^)]*)\)", look_src)
    names = [n.strip().lower() for n in m.group(1).split(",")] if m else []
    lab_default = re.search(r"let curPreset=P_(\w+)", lab_src).group(1).lower()
    print("\n可切换的形象: " + "、".join(names) + "（默认第一个）")
    print("参数逐项核对过: " + ("、".join(CHECKS) if CHECKS else "（无）"))
    print("实验室默认选中: " + lab_default)
    for n in names:
        if n in CHECKS:
            continue
        if f"key:'{n}'" in lab_src:
            print(f"  ⓘ {n} 有预设但没核对（不在 CHECKS 里）")
        else:
            print(f"  ⓘ {n} 实验室里没有预设 —— 它的参数完全没核对")
    for n, blind in LAB_BLIND_SPOTS.items():
        if f"key:'{n}'" in lab_src:
            print(f"  ⚠️ {n} 的预设是**部分**的：实验室画不出 {blind}。"
                  f"脸可信，轮廓不可信")

    # 这份脚本**看不到网格生成器**（Capsule / Ear / Eye 这一族）：它比的是参数表、
    # shader 语义、表情表和配色归属。同一个生成器在 Kotlin 与实验室各存一份，漂了
    # 这里一声不响 —— `Eye` 就是新加的那一个。所以先把"它管不到的那部分由谁管"
    # 说出来：跑一条命令得出"全部一致"很容易被读成"什么都查过了"。
    print("\n── 这份脚本看不到的，由谁管 ──")
    for rel, what in (
        ("tools/eye_parity_check.py", "gl/Eye.kt ↔ 实验室 eyeMesh：数字 / 算术 / 轴向逐项"),
        ("tools/eye_mesh_check.py", "Eye 网格本身合法 + 注释里写下的数"),
    ):
        mark = "✓" if (ROOT / rel).exists() else "✗ 缺失"
        print(f"  {mark} {rel} —— {what}")

    if problems:
        print(f"\n❌ {len(problems)} 处不一致：")
        for p in problems:
            print("  ✗ " + p)
        return 1
    print("\n✅ 全部一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
