#!/usr/bin/env python3
"""把 cutie_lab_probe.js 注入奶油球实验室，用 headless Chrome 跑一遍，打印验收表。

⚠️ **这份工具还没在本机跑通过一次。** 写它的时候 headless Chrome 在这台机器上
连一个空白页都 dump 不出来（`--dump-dom` 90 秒超时，加不加沙箱都一样），所以
下面的判据阈值是**照着参数推的，不是量出来的**。第一次跑通之前，不要把它的
输出当作验收结论，也不要在提交信息里引用它。参数本身另有 `check_look_sync.py`
逐位核对，那一份是可靠的。

和 verify_faces_lab.py 同一套路（同一个 Chrome 定位逻辑、同一套
`--enable-unsafe-swiftshader`，**不要**加 `--disable-gpu`，那会让
getContext('webgl') 返回 null）。差别只在判据：这里量的是耳朵（和眉，如果这个
形象还有眉的话 —— 奶油球 2026-09-30 删掉了，见 `_brow_checks`）。

判据写在**这一侧**，不在探针里 —— 阈值是要反复调的，改 python 比改 JS 省事，
而且探针只负责"把数拿出来"，职责单一。

用法：
    python3 tools/verify_cutie_lab.py [页面路径]
"""
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PAGE = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "reports" / "ball-cutie-lab.html"
PROBE = ROOT / "tools" / "cutie_lab_probe.js"

# 验收线。改了造型就得回来看这三个数，别让它们默默漂走。
MIN_EAR_OUT_FRAC = 0.08     # 耳朵出轮廓的宽度 ÷ 球直径
MIN_BROW_PX = 300           # 眉毛造成差异的像素数（68 列字符画下约 5 笔）
MAX_UNEXPECTED_PX = 40      # 轮廓内、眉毛带以下的差异 —— 球体本身不许被动
MIN_BROW_ABOVE_EYE_PX = 2.0  # 眉差异的质心必须比眼睛中心高多少像素


def _find_chrome() -> str:
    import os
    import shutil

    fallback = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
    candidates = [
        os.environ.get("CHROME_PATH"),
        fallback,
        "/Applications/Chromium.app/Contents/MacOS/Chromium",
        "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
        "/usr/bin/google-chrome",
        "/usr/bin/chromium",
        "/usr/bin/chromium-browser",
        "/snap/bin/chromium",
    ]
    candidates += [
        shutil.which(name)
        for name in ("google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "chrome")
    ]
    for path in candidates:
        if path and Path(path).exists():
            return path
    return fallback


CHROME = _find_chrome()

FLAGS = [
    "--headless=new",
    "--no-sandbox",
    "--enable-unsafe-swiftshader",
    "--window-size=1500,3600",
    "--hide-scrollbars",
]


def run_probe() -> dict:
    html = PAGE.read_text(encoding="utf-8")
    probe = PROBE.read_text(encoding="utf-8")
    if "</body>" not in html:
        raise SystemExit("页面里没有 </body>，不知道该把探针插在哪。")
    html = html.replace("</body>", f"<script>\n{probe}\n</script>\n</body>", 1)

    tmp = Path(tempfile.mkdtemp(prefix="cutie-lab-"))
    harness = tmp / "lab-verify.html"
    harness.write_text(html, encoding="utf-8")

    # Chrome 不给 --user-data-dir 就会去 ~/Library/Application Support/Google/
    # Chrome-headless 建一个临时 profile，跑完还要删掉它 —— 在受限环境里那一
    # 步会被删除规则拦下，表现为"整条命令没有任何输出"。给它一个自己的目录，
    # 写和清都在 temp 里完成。
    profile = tmp / "chrome-profile"
    proc = subprocess.run(
        [CHROME, *FLAGS, f"--user-data-dir={profile}",
         "--virtual-time-budget=20000", "--dump-dom", harness.as_uri()],
        capture_output=True, text=True, timeout=240,
    )
    dom = proc.stdout

    # 探针源码自己就在 DOM 里，注释里也有 `<pre id="zh-probe"` 这个字符串，
    # 所以不能取第一个匹配 —— 要取**能解析成 JSON** 的那一个。
    for raw in re.findall(r'<pre id="zh-probe"[^>]*>(.*?)</pre>', dom, re.S):
        raw = raw.strip()
        if not raw.startswith("{"):
            continue
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            continue

    print("探针没有留下结果 —— 页面大概在 build() 阶段就挂了。", file=sys.stderr)
    err = re.search(r'<div id="err"[^>]*>(.*?)</div>', dom, re.S)
    if err and err.group(1).strip():
        print("页面自己的错误框：", file=sys.stderr)
        print(err.group(1)[:4000], file=sys.stderr)
    raise SystemExit(2)


def _brow_checks(m: dict, f: dict) -> list:
    """眉的那两条断言，按"这个形象到底有没有眉"来选。

    奶油球 2026-09-30 把眉删掉了（browW 0）。探针的两组 A/B 里，`browOff` 是
    `{browW:0}`，而预设本身现在也是 0 —— 两次拍的是**同一个配置**，browDiffPx
    恒为 0。原来那两条"看得见、且高于眼睛"的断言拿 0 去比 300px 的阈值，会稳定
    FAIL。阈值没写错，是世界变了，所以要改的是断言不是数。

    分成两支：有眉查"看得见且位置对"；没眉查反向的"关掉前后必须一模一样"。

    ⚠️ 说清第二支有多弱：它是**接近重言式**的 —— 两次拍同一配置，本来就该相等。
    它挡不住"某个分支在偷偷画一笔"，只挡得住"渲染本身不确定"（未初始化状态、
    随机抖动）。真正的"删干净了没有"要靠字符画那一栏人眼看。别把它当强验证。
    """
    if f["browW"] > 0:
        return [
            ("眉毛在眼睛上方留下可见的一笔",
             m["browDiffPx"] >= MIN_BROW_PX,
             f"{m['browDiffPx']}px ≥ {MIN_BROW_PX}px，最大通道差 {m['browDiffMaxDelta']}"),
            ("眉差异的质心高于眼睛中心",
             (m["browDiffCentroidFromTop"] or 0) <= f["eyeRowFromTop"] - MIN_BROW_ABOVE_EYE_PX,
             f"质心行 {m['browDiffCentroidFromTop']:.1f} ≤ 眼睛行 {f['eyeRowFromTop']:.1f} "
             f"− {MIN_BROW_ABOVE_EYE_PX:.0f}"),
        ]
    return [
        ("本形象没有眉：关眉前后应完全一致（弱检查，见 docstring）",
         m["browDiffPx"] == 0,
         f"关眉前后差异 {m['browDiffPx']}px，应为 0"),
    ]


def main() -> int:
    if not PAGE.exists():
        print(f"找不到页面：{PAGE}", file=sys.stderr)
        return 2
    if not Path(CHROME).exists():
        print(f"找不到 Chrome：{CHROME}", file=sys.stderr)
        return 2

    R = run_probe()
    if R.get("fatal"):
        print("页面/探针抛异常：", file=sys.stderr)
        print(R["fatal"], file=sys.stderr)
        return 2

    print(f"奶油球 · 耳朵验收  （探针 {R.get('version')}）")
    print("=" * 72)

    m, f = R["measure"], R["facts"]
    print("─ 参数 ─")
    print(f"  球半径(含描边) {f['bodyRadiusPx']:.1f}px  1 世界单位 = {f['pxPerUnit']:.1f}px")
    print(f"  眼睛中心在第 {f['eyeRowFromTop']:.1f} 行（自上而下）")
    print(f"  earH={f['earH']} earX={f['earX']} earY={f['earY']} earTilt={f['earTilt']}° "
          f"earBend={f['earBend']}° earShade={f['earShade']}")
    print(f"  browW={f['browW']} browH={f['browH']} browY={f['browY']} browTilt={f['browTilt']}°")
    print()
    print("─ 量 ─")
    rp = m["radiusPx"]
    print(f"  最大半径  全开 {rp['both']:.1f}  只关眉 {rp['browOff']:.1f}  "
          f"只关耳 {rp['earOff']:.1f}  基线 {rp['none']:.1f}")
    print(f"  耳朵伸出轮廓 {m['earOutPx']:.1f}px  = 球直径的 {m['earOutFracOfDiameter']*100:.1f}%"
          f"   （为它多出的像素 {m['earNewPx']}）")
    print(f"  眉毛差异 {m['browDiffPx']}px  最大通道差 {m['browDiffMaxDelta']}"
          f"  质心在第 {m['browDiffCentroidFromTop']:.1f} 行（眼睛在第 {f['eyeRowFromTop']:.1f} 行）")
    print(f"  轮廓内 / 眉毛带以下的意外差异 {m['unexpectedPx']}px   （总差异 {m['allDiffPx']}px）")
    print()
    print("─ 差异逐行分布（自上而下，28 行）─")
    mk = max(1, max(m["allDiffRows"]))
    for i, v in enumerate(m["allDiffRows"]):
        bar = "▉" * int(round(v / mk * 46))
        print(f"  {i:2d} {v:5d} {bar}")
    print()

    checks = [
        ("耳朵伸出轮廓",
         m["earOutFracOfDiameter"] >= MIN_EAR_OUT_FRAC,
         f"{m['earOutFracOfDiameter']*100:.1f}% ≥ {MIN_EAR_OUT_FRAC*100:.0f}%（球直径）"),
        ("耳朵确实落在轮廓外（不是只在里面换色）",
         m["earNewPx"] >= 100,
         f"新增像素 {m['earNewPx']} ≥ 100"),
    ] + _brow_checks(m, f) + [
        ("球体本身没有被意外改动",
         m["unexpectedPx"] <= MAX_UNEXPECTED_PX,
         f"意外差异 {m['unexpectedPx']}px ≤ {MAX_UNEXPECTED_PX}px"),
    ]

    failed = 0
    for name, ok, detail in checks:
        if not ok:
            failed += 1
        print(f"[{'PASS' if ok else 'FAIL'}] {name}")
        print(f"       {detail}")
    print("=" * 72)
    print(f"{len(checks) - failed}/{len(checks)} 通过")

    if R.get("rows"):
        print()
        for row in R["rows"]:
            print(f"── {row['label']} ──")
            for line in row["ascii"]:
                print("  " + line)
            print()
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
