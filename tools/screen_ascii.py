#!/usr/bin/env python3
"""把真机屏幕解码成字符画 —— "看不见图"时的眼睛。

和 dump_faces_ascii.py 同一个理由：这套球球是画出来的几何体,改参数不看效果
等于盲调。那个工具看的是实验室 HTML;这个看的是**真机** —— adb 截屏里就是
设备上实际合成出来的那一帧,零漂移。

    python3 tools/screen_ascii.py                    # 全屏,100 列
    python3 tools/screen_ascii.py 200                # 全屏,200 列
    python3 tools/screen_ascii.py 400 100 1400 950   # 裁剪 (左,上,右,下),120 列

只读不改。裁剪坐标是原始像素。
"""
import subprocess
import sys
from pathlib import Path

ADB = Path.home() / "Library/Android/sdk/platform-tools/adb"
RAMP = " .:-=+*#%@"
CHUNK = None  # 每个字符覆盖的像素,按宽高比 1:2 采样


def shot() -> bytes:
    p = subprocess.run([str(ADB), "exec-out", "screencap", "-p"],
                       capture_output=True, timeout=30)
    if p.returncode != 0:
        sys.exit(f"adb screencap 失败: {p.stderr.decode(errors='replace')[:500]}")
    return p.stdout


def main() -> int:
    args = [int(a) for a in sys.argv[1:]]
    crop = None
    if len(args) == 4:
        crop, args = args, []
    elif len(args) == 5:
        crop, args = args[1:], args[:1]
    width = args[0] if args else 100

    from PIL import Image
    import io
    img = Image.open(io.BytesIO(shot())).convert("L")
    if crop:
        img = img.crop(crop)
    w, h = img.size
    cols = min(width, w)
    # 终端字符约 1:2 高宽比,行数减半才是"不变形"
    rows = max(1, int(cols * h / w / 2))
    img = img.resize((cols, rows))
    # 自动拉伸对比:球上的五官(眼/鼻孔/嘴)只占身体亮度的一小段,不拉伸就
    # 全被冲进一两个灰阶里 —— 这个工具读的是形状,不是绝对亮度。
    px = img.load()
    lo, hi = img.getextrema()
    span = max(1, hi - lo)
    for y in range(rows):
        print("|" + "".join(
            RAMP[min(len(RAMP) - 1, (px[x, y] - lo) * (len(RAMP) - 1) // span)]
            for x in range(cols)) + "|")
    print(f"({w}x{h}px → {cols}x{rows} 字符)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
