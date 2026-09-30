#!/usr/bin/env python3
"""把 faces_lab_ascii.js 注入设计稿，跑一遍，印出字符画 + 绘制调用计数。

这是"看不见图"时的眼睛：把 WebGL 画布解码成字符，用形状判断设计对不对。

用法：
    python3 tools/dump_faces_ascii.py             # 全部
    python3 tools/dump_faces_ascii.py 暴躁         # 只印标签里含"暴躁"的
    python3 tools/dump_faces_ascii.py 小恶魔 alpha  # 只印 alpha 图
"""
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PAGE = ROOT / "reports" / "ball-faces-lab.html"
SCRIPT = ROOT / "tools" / "faces_lab_ascii.js"


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
    "--window-size=1500,3000",
    "--hide-scrollbars",
]


def main() -> int:
    filters = [a for a in sys.argv[1:] if a != "alpha"]
    want_alpha = "alpha" in sys.argv[1:]

    html = PAGE.read_text(encoding="utf-8")
    probe = SCRIPT.read_text(encoding="utf-8")
    html = html.replace("</body>", f"<script>\n{probe}\n</script>\n</body>", 1)

    tmp = Path(tempfile.mkdtemp(prefix="faces-ascii-"))
    h = tmp / "ascii.html"
    h.write_text(html, encoding="utf-8")

    proc = subprocess.run(
        [CHROME, *FLAGS, "--virtual-time-budget=20000", "--dump-dom", h.as_uri()],
        capture_output=True, text=True, timeout=240,
    )

    OUT = None
    for raw in re.findall(r'<pre id="zh-ascii"[^>]*>(.*?)</pre>', proc.stdout, re.S):
        raw = raw.strip()
        if raw.startswith("{"):
            try:
                OUT = json.loads(raw)
            except json.JSONDecodeError:
                continue
    if OUT is None:
        print("没有拿到字符画。", file=sys.stderr)
        err = re.search(r'<div id="err"[^>]*>(.*?)</div>', proc.stdout, re.S)
        if err and err.group(1).strip():
            print("页面错误框：", err.group(1)[:3000], file=sys.stderr)
        return 2
    if OUT.get("fatal"):
        print("探针抛异常：")
        print(OUT["fatal"])
        return 2

    print("绘制调用计数  （0 = 这个件根本没被调用；>0 但看不见 = 对比度/位置问题）")
    print("-" * 74)
    for n in OUT["notes"]:
        calls = "  ".join(f"{k}={v}" for k, v in n["calls"].items())
        print(f"  {n['label']:<28} {calls}")

    for row, n in zip(OUT["rows"], OUT["notes"]):
        if filters and not any(f in n["label"] for f in filters):
            continue
        print()
        print("=" * 74)
        print(f"{n['label']}   [{n['key']} · {n['mood']} · {n['size'][0]}x{n['size'][1]}]")
        print("=" * 74)
        art = row["alpha"] if want_alpha else row["ascii"]
        for ln in art:
            print("|" + ln + "|")

    return 0


if __name__ == "__main__":
    sys.exit(main())
