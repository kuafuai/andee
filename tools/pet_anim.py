#!/usr/bin/env python3
"""One still → a real frame loop, numbered like a master and encoded twice.

Why this exists: the spline-free version of "the pet breathes" is a scale/rotate on a bitmap, which
reads as a sticker. This takes the RGBA master and warps it with a mesh whose amplitude grows toward
the top of the body — feet stay planted, hair and shoulders sway a beat behind — then writes the
frames out. Frames computed from one master are **exactly aligned**, which a generated 3x3 sheet never
was; that was the failure that made the generated frames look worse than the still.

Two encodes, on purpose:
  * `assets/anim/<look>-<kind>-N.webp` — what the Mac app plays (Skia draws bitmaps; no video).
  * `<look>-<kind>.webm` — 640-wide VP9 with alpha, the same container dsh-pet ships, for a browser
    or an Electron host that has a real <video>. Kept as the archive master, not loaded by the app.

Usage: python3 tools/pet_anim.py blue-girl [idle] [frames]
"""
import os
import subprocess
import sys

import numpy as np
from PIL import Image
from scipy.ndimage import map_coordinates

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "desktop", "src", "main", "resources", "pet")
OUT = os.path.join(ROOT, "desktop", "src", "main", "resources", "pet", "anim")
MASTER = os.path.join(ROOT, "build", "anim-master")

FRAMES = 20
FPS = 20
# 三个幅度都压在 1~2%：位移再大就变成"橡皮人"，这是看出来的临界值，不是算出来的。
SWAY_X = 0.012      # 横向摆动，头顶为最大
SWAY_LAG = 0.45     # 相位的纵向滞后：头发比身体慢半拍
BREATH_Y = 0.008    # 纵向呼吸，脚底为锚点
BOB = 0.004         # 整体轻微上下


def load(path):
    im = Image.open(path).convert("RGBA")
    a = np.asarray(im).astype(np.float32) / 255.0
    return a  # (h, w, 4) premultiply later


def warp_frame(rgba, phase):
    """Return one warped frame at [phase] (0..1 of the loop)."""
    h, w = rgba.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    yn = yy / (h - 1)          # 0 at the top, 1 at the feet
    lift = (1.0 - yn) ** 2     # no motion at the feet, most at the crown

    ang = 2.0 * np.pi * phase
    dx = SWAY_X * w * lift * np.sin(ang - SWAY_LAG * (1.0 - yn) * 2.0 * np.pi)
    dy = -(BREATH_Y * h * (1.0 - yn) + BOB * h) * np.sin(ang)

    src_x = xx - dx
    src_y = yy - dy

    a = rgba[:, :, 3]
    # Sample premultiplied colour so the transparent margin cannot bleed dark fringes into the body.
    pm = rgba[:, :, :3] * a[:, :, None]
    out = np.empty_like(rgba)
    for c in range(3):
        out[:, :, c] = map_coordinates(pm[:, :, c], [src_y, src_x], order=1, mode="nearest")
    alpha = map_coordinates(a, [src_y, src_x], order=1, mode="nearest")
    out[:, :, 3] = alpha
    nz = alpha > 1e-4
    for c in range(3):
        out[:, :, c][nz] /= alpha[nz]
        out[:, :, c][~nz] = 0.0
    return Image.fromarray((np.clip(out, 0, 1) * 255).astype(np.uint8), "RGBA")


def build(look, kind="idle", frames=FRAMES):
    master_path = os.path.join(SRC, f"{look}-{kind}.webp")
    if not os.path.exists(master_path):
        raise SystemExit(f"no master: {master_path}")
    rgba = load(master_path)
    os.makedirs(OUT, exist_ok=True)
    # Stale frames from an earlier run with a different frame count would be played as if they
    # belonged (the loader stops at the first missing index, not at the new count).
    for old in os.listdir(OUT):
        if old.startswith(f"{look}-{kind}-") and old.endswith(".webp"):
            os.remove(os.path.join(OUT, old))
    os.makedirs(MASTER, exist_ok=True)
    pngs = []
    for i in range(frames):
        frame = warp_frame(rgba, i / frames)
        png = os.path.join(MASTER, f"{look}-{kind}-{i:03d}.png")
        frame.save(png)
        if frame.height > 600:
            scale = 600 / frame.height
            frame = frame.resize((max(1, round(frame.width * scale)), 600), Image.LANCZOS)
        pngs.append(png)
        frame.save(os.path.join(OUT, f"{look}-{kind}-{i}.webp"), "WEBP", quality=92, method=4)
    webm = os.path.join(MASTER, f"{look}-{kind}.webm")
    subprocess.run([
        "ffmpeg", "-y", "-loglevel", "error", "-framerate", str(FPS),
        "-i", os.path.join(MASTER, f"{look}-{kind}-%03d.png"),
        "-c:v", "libvpx-vp9", "-pix_fmt", "yuva420p", "-b:v", "0", "-crf", "28",
        "-an", webm,
    ], check=True)
    size = sum(os.path.getsize(os.path.join(OUT, f"{look}-{kind}-{i}.webp")) for i in range(frames))
    print(f"{look}/{kind}: {frames} frames, webp {size // 1024} KB, webm {os.path.getsize(webm) // 1024} KB")


if __name__ == "__main__":
    build(sys.argv[1] if len(sys.argv) > 1 else "blue-girl",
          sys.argv[2] if len(sys.argv) > 2 else "idle",
          int(sys.argv[3]) if len(sys.argv) > 3 else FRAMES)
