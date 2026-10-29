#!/usr/bin/env python3
"""Compose the Andee DMG background from icon-inspired art and clear guidance.

Finder draws the actual app and Applications icons. This image only supplies
decoration and instructions; coordinates match dmgbuild-settings.py.
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).parent
WIDTH, HEIGHT = 820, 500
SCALE = 2


def xy(values):
    return tuple(round(value * SCALE) for value in values)


def font(size):
    for name in (
        "/System/Library/Fonts/STHeiti Medium.ttc",
        "/System/Library/Fonts/Hiragino Sans GB.ttc",
        "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
    ):
        try:
            return ImageFont.truetype(name, size * SCALE)
        except OSError:
            pass
    return ImageFont.load_default()


art = Image.open(HERE / "background-art.png").convert("RGB")
image = art.resize((WIDTH * SCALE, HEIGHT * SCALE), Image.Resampling.LANCZOS)
decor = Image.new("RGBA", image.size)
d = ImageDraw.Draw(decor)

# Translucent area behind the real Finder items, keeping the art visible.
d.rounded_rectangle(
    xy((122, 178, 698, 438)),
    radius=72 * SCALE,
    fill=(255, 255, 255, 42),
    outline=(255, 255, 255, 100),
    width=2 * SCALE,
)

# The arrow sits between icon centres (225, 246) and (595, 246).
d.rounded_rectangle(
    xy((331, 240, 487, 252)),
    radius=6 * SCALE,
    fill=(255, 255, 255, 225),
)
d.polygon(
    [xy((476, 219)), xy((514, 246)), xy((476, 273))],
    fill=(255, 255, 255, 225),
)
d.rounded_rectangle(
    xy((334, 243, 486, 249)),
    radius=3 * SCALE,
    fill=(92, 123, 213, 240),
)
d.polygon(
    [xy((478, 228)), xy((503, 246)), xy((478, 264))],
    fill=(92, 123, 213, 240),
)

d.text(
    xy((410, 370)), "拖拽完成安装",
    font=font(23), fill=(40, 58, 111, 245), anchor="mm",
)
d.text(
    xy((410, 402)), "Drag Andee to Applications",
    font=font(13), fill=(70, 85, 135, 230), anchor="mm",
)

image = Image.alpha_composite(image.convert("RGBA"), decor)
image = image.convert("RGB").resize((WIDTH, HEIGHT), Image.Resampling.LANCZOS)
output = HERE / "background.png"
image.save(output, optimize=True)
print(f"wrote {output} ({WIDTH}x{HEIGHT})")
