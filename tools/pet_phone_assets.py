#!/usr/bin/env python3
"""The phone's copy of the pet pictures: the Mac's (desktop/src/main/resources/pet) scaled down for a folded
ball's box, into app/src/main/assets/pet. Generated, never hand-edited — rerun after pet_sprites.py / pet_anim.py.

The Mac's set is ~13 MB; a phone draws her at about 104 dp, so stills at 300 px tall and the idle loop at 220 px
were tried first (2026-11-05) and read as blurry: the unfolded card draws her ~260 dp tall, about 700 px on a
3x screen, so the idle loop at 220 px was upscaled threefold. Stills 560 px, idle frames 520 px: ~5 MB for five looks.
"""
import glob
import os
import shutil

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "desktop", "src", "main", "resources", "pet")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "pet")
LOOKS = ["andee", "girl", "boy", "andee-girl", "cat"]
STILL_H = 560
FRAME_H = 520
FRAMES = 12  # every other frame of the Mac's 20 is a smooth enough breath at the phone's size; see PhonePetView


def save(src, dst, h):
    im = Image.open(src).convert("RGBA")
    im = im.resize((max(1, round(im.width * h / im.height)), h), Image.LANCZOS)
    im.save(dst, "WEBP", quality=82, method=6)


def main():
    shutil.rmtree(OUT, ignore_errors=True)
    os.makedirs(os.path.join(OUT, "anim"))
    for look in LOOKS:
        for f in sorted(glob.glob(os.path.join(SRC, f"{look}-*.webp"))):
            save(f, os.path.join(OUT, os.path.basename(f)), STILL_H)
        frames = sorted(glob.glob(os.path.join(SRC, "anim", f"{look}-idle-*.webp")),
                        key=lambda p: int(p.rsplit("-", 1)[1].split(".")[0]))
        step = max(1, len(frames) / FRAMES)
        for i in range(FRAMES):
            if int(i * step) >= len(frames):
                break
            save(frames[int(i * step)], os.path.join(OUT, "anim", f"{look}-idle-{i}.webp"), FRAME_H)
    total = sum(os.path.getsize(p) for p in glob.glob(os.path.join(OUT, "**", "*.webp"), recursive=True))
    print(f"{OUT}: {total // 1024} KB")


if __name__ == "__main__":
    main()
