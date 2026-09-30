#!/usr/bin/env python3
"""把 faces_lab_probe.js 注入设计稿，用 headless Chrome 跑一遍，打印验收表。

为什么不用 agent-browser：它在本机偶尔会因为 daemon 卡住而整条命令超时，
而这里要的只是"跑一段 JS，把结果读回来"。headless Chrome 加
`--enable-unsafe-swiftshader` 就能在无 GPU 的情况下给出 WebGL 上下文
（`--disable-gpu` 会让 getContext('webgl') 直接返回 null，别加），
`--dump-dom` 再把探针写进 DOM 的 JSON 取出来 —— 两步都是确定性的。

用法：
    python3 tools/verify_faces_lab.py [页面路径]

退出码：0 = 全部通过；1 = 有断言没过；2 = 页面本身就挂了。
"""
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PAGE = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "reports" / "ball-faces-lab.html"
PROBE = ROOT / "tools" / "faces_lab_probe.js"


def _find_chrome() -> str:
    """定位一个可用的 Chrome/Chromium。

    不写死 macOS 路径：可用 CHROME_PATH 覆盖，否则按"macOS 常见位置 → PATH"依次找，
    都找不到就退回 macOS 默认位置，好让"找不到 Chrome"的报错指向一个具体路径。
    """
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


def main() -> int:
    if not PAGE.exists():
        print(f"找不到页面：{PAGE}", file=sys.stderr)
        return 2
    if not Path(CHROME).exists():
        print(f"找不到 Chrome：{CHROME}", file=sys.stderr)
        return 2

    html = PAGE.read_text(encoding="utf-8")
    probe = PROBE.read_text(encoding="utf-8")
    if "</body>" not in html:
        print("页面里没有 </body>，不知道该把探针插在哪。", file=sys.stderr)
        return 2
    html = html.replace("</body>", f"<script>\n{probe}\n</script>\n</body>", 1)

    tmp = Path(tempfile.mkdtemp(prefix="faces-lab-"))
    harness = tmp / "lab-verify.html"
    harness.write_text(html, encoding="utf-8")

    proc = subprocess.run(
        [CHROME, *FLAGS, "--virtual-time-budget=15000", "--dump-dom", harness.as_uri()],
        capture_output=True, text=True, timeout=180,
    )
    dom = proc.stdout

    # 注意：探针自己的源码就在 DOM 里，而那行注释里有一模一样的 `<pre id="zh-probe">`
    # 字符串。所以不能取第一个匹配 —— 要取**能解析成 JSON** 的那一个。
    R = None
    for raw in re.findall(r'<pre id="zh-probe"[^>]*>(.*?)</pre>', dom, re.S):
        raw = raw.strip()
        if not raw.startswith("{"):
            continue
        try:
            R = json.loads(raw)
        except json.JSONDecodeError:
            continue
    if R is None:
        print("探针没有留下结果 —— 页面大概在 build() 阶段就挂了。", file=sys.stderr)
        err = re.search(r'<div id="err"[^>]*>(.*?)</div>', dom, re.S)
        if err and err.group(1).strip():
            print("页面自己的错误框：", file=sys.stderr)
            print(err.group(1)[:4000], file=sys.stderr)
        for raw in re.findall(r'<pre id="zh-probe"[^>]*>(.*?)</pre>', dom, re.S):
            if raw.strip():
                print("探针留下的原文：", raw.strip()[:2000], file=sys.stderr)
                break
        return 2
    print(f"球球性格实验室 · 验收  （探针 {R.get('version')}）")
    print("=" * 72)

    if R.get("fatal"):
        print("页面/探针抛异常：")
        print(R["fatal"])
        return 2

    failed = 0
    for c in R["checks"]:
        mark = "PASS" if c["ok"] else "FAIL"
        if not c["ok"]:
            failed += 1
        print(f"[{mark}] {c['name']}")
        print(f"       {c['detail']}")

    print("=" * 72)
    print(f"{len(R['checks']) - failed}/{len(R['checks'])} 通过")
    if R.get("facts"):
        print("facts:", json.dumps(R["facts"], ensure_ascii=False))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
