#!/usr/bin/env python3
"""桌宠素材的来源:生成 → 裁掉透明边 → 按角色缩放到资源目录。

## 它为什么存在

`desktop/src/main/resources/pet/*.png` 是**生成出来的**,不是手画的。图一旦进仓库,
下一个人要改形象时手里必须有生成它的那段文字 —— 否则"再画一个一样的"变成重新设计,
桌宠的连续性就断在这一步。所以角色设定、提示词、尺寸和裁剪规则都住在这里,
图可以由它一字不差地再做一遍。

## 两个预制形象,一套设定

`girl` 与 `boy` 是**同一个角色**的两种样子:同样的犄角、珊瑚色领巾、胸口的蓝色核心、
奶油色带珊瑚尖的尾巴 —— 桌宠是"桌面上的 Andee",不是一堆不相干的表情包。
**故意不是蓝色**:球已经是钴蓝,而蓝发白裙的女孩是别人的吉祥物。

## 三个姿态

`idle` 由文字生成;`happy`(被点一下)与 `air`(被甩出去)以 `idle` 为参考走
`/v1/images/edits` —— 两段独立文字各生成一次,发色和比例会漂,而桌宠一秒钟内会在两张图
之间切,漂就是抖。

## 边界

* API key 只从环境变量读(`TEAMO_KEY`),仓库里没有 key。
* 输出是**裁到身体**的透明 PNG,宽高比跟着角色走 —— 窗口大小直接由这个比例算出来
  (`PetSprites.aspectOf`),所以这里不能统一补成正方形。
* 用户自己加的图走 `PetLibrary.addCustom`(运行期),不经过这个脚本。

用法:
    TEAMO_KEY=sk-… python3 tools/pet_sprites.py            # 全部生成并安装
    TEAMO_KEY=sk-… python3 tools/pet_sprites.py girl       # 只做女孩
"""

import base64
import json
import os
import subprocess
import sys

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW = os.path.join(ROOT, "build", "pet-sprites")
OUT = os.path.join(ROOT, "desktop", "src", "main", "resources", "pet")

API = os.environ.get("TEAMO_BASE_URL", "https://api.teamorouter.ai/v1").rstrip("/")
MODEL = os.environ.get("TEAMO_IMAGE_MODEL", "gpt-image-2.5-sunburst")

SHARED = (
    "Chibi anime {who} mascot for an AI companion named Andee, 2.5 heads tall, full body, standing, facing the viewer. "
    "Fair warm human skin, soft peach blush, big teal-green eyes with bright glossy glints and thick lashes. "
    "Two small smooth rounded horns at the hairline, cream with warm coral tips; small pointed elf ears. "
    "Oversized white t-shirt with a coral neckerchief and a small round glowing cobalt-blue core on the chest, navy shorts, "
    "bare feet, a slim cream tail with a coral tip curling out behind. "
)
GIRL = SHARED.format(who="girl") + (
    "Short cream-blonde bob with one long side lock and a soft ahoge, calm gentle closed-mouth smile. "
)
BOY = SHARED.format(who="boy") + (
    "Short sand-blond messy hair with a small cowlick, calm friendly closed-mouth smile. "
)
# The cobalt pair is the logo's own blue: same character and outfit, hair and trim in cobalt.
BLUE_SHARED = SHARED.replace(
    "cream with warm coral tips", "cobalt blue with warm coral tips",
).replace("a slim cream tail", "a slim cobalt-blue tail")
BLUE_GIRL = BLUE_SHARED.format(who="girl") + (
    "Short cobalt-blue bob with one long side lock and a soft ahoge, calm gentle closed-mouth smile. "
)
BLUE_BOY = BLUE_SHARED.format(who="boy") + (
    "Short cobalt-blue messy hair with a small cowlick, calm friendly closed-mouth smile. "
)

STYLE = (
    "Soft flat vector illustration, thick clean dark-navy outlines, gentle cel shading, plush velvety texture, "
    "subtle warm coral rim light on one side, kawaii, highly readable at small sizes, simple silhouette. "
)
FRAME = (
    "Full body, whole character visible with generous empty margin on all four sides, centered, isolated on a fully "
    "transparent background, no text, no props, no ground, no shadow, no border, no watermark."
)
KEEP = "Same exact character, same art style, same colours, same proportions, same outfit. "

POSES = {
    "idle": None,   # generated from the description above
    "happy": (
        "Change only the pose and expression: delighted, eyes closed in happy arcs, mouth open in a cheerful smile, "
        "one arm raised in a friendly wave, one knee lifted as if bouncing, tail raised. "
    ),
    "air": (
        "Change only the pose and expression: tumbling in mid-air right after being thrown, both arms up, knees tucked, "
        "hair, neckerchief and tail flung upward, mouth open in a small surprised 'oh', eyes wide. "
    ),
    "angry": (
        "Change only the pose and expression: annoyed and pouting — brows pushed down, cheeks puffed out, mouth in a "
        "small frown, arms crossed over the chest, tail flicking up. Keep it cute, not scary. "
    ),
    "sad": (
        "Change only the pose and expression: sad and small — eyes cast down with a single small tear at the corner, "
        "mouth turned down, shoulders slumped, tail hanging low, hands held together in front. "
    ),
    "sleepy": (
        "Change only the pose and expression: sleepy — eyes closed, mouth open in a small yawn, head tilted, one hand "
        "rubbing an eye, tail curled around the feet. "
    ),
    "think": (
        "Change only the pose and expression: thinking — one hand on the chin, eyes looking up and to the side, small "
        "pursed smile, one eyebrow slightly raised, tail curled into a question-mark shape. "
    ),
    # The two props. They are what makes "she does something" readable at a glance; at 60 fps the
    # procedural breath does the rest.
    "tea": (
        "Change only the pose: she is holding a small white-and-blue takeaway bubble tea cup with a straw in both "
        "hands, lifting it to sip, eyes closed and happy, the cup stays in her hands. "
    ),
    "hotpot": (
        "Change only the pose: she is sitting and eating from a small bubbling hotpot — a little blue-and-cream pot of "
        "broth with a small orange flame under it, chopsticks in one hand holding a slice of meat, a happy closed-eye "
        "expression, a wisp of steam. "
    ),
}

LOOKS = {"girl": GIRL, "boy": BOY, "blue-girl": BLUE_GIRL, "blue-boy": BLUE_BOY}

# 缩到多高(px)。资源是 2x 的,运行时高度取 PetLook.baseHeight。
HEIGHT = {"girl": 820, "boy": 820, "blue-girl": 820, "blue-boy": 820}

# 裁边后保留的空隙:占长边的比例。窗口紧贴身体,空隙越大挡住的点击越多。
PAD = 0.008


def post(url, args):
    key = os.environ["TEAMO_KEY"]
    out = subprocess.run(["curl", "-sS", "-m", "300", url, "-H", "Authorization: Bearer " + key] + args,
                         capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(out.stderr[:400])
    return base64.b64decode(json.loads(out.stdout)["data"][0]["b64_json"])


def generate(look, pose):
    desc = LOOKS[look]
    if pose == "idle":
        body = {"model": MODEL, "prompt": desc + STYLE + FRAME, "size": "1024x1536", "background": "transparent", "n": 1}
        return post(API + "/images/generations",
                    ["-H", "Content-Type: application/json", "--data-binary", json.dumps(body)])
    ref = os.path.join(RAW, f"{look}-idle.png")
    args = ["-F", "model=" + MODEL, "-F", f"image=@{ref};type=image/png", "-F", "size=1024x1536",
            "-F", "background=transparent", "-F", "output_format=png",
            "-F", "prompt=" + KEEP + POSES[pose] + STYLE + FRAME]
    return post(API + "/images/edits", args)


def install(look, pose, png):
    os.makedirs(RAW, exist_ok=True)
    path = os.path.join(RAW, f"{look}-{pose}.png")
    with open(path, "wb") as f:
        f.write(png)
    im = Image.open(path).convert("RGBA")
    box = im.getchannel("A").getbbox()
    if box:
        pad = int(max(im.size) * PAD)
        box = (max(0, box[0] - pad), max(0, box[1] - pad), min(im.width, box[2] + pad), min(im.height, box[3] + pad))
        im = im.crop(box)
    h = HEIGHT[look]
    im = im.resize((max(1, round(im.width * h / im.height)), h), Image.LANCZOS)
    os.makedirs(OUT, exist_ok=True)
    # WebP: a ninth of the PNG for this flat art, decoded through Skia in the app.
    dst = os.path.join(OUT, f"{look}-{pose}.webp")
    im.save(dst, "WEBP", quality=93, method=6)
    print(f"  {look}/{pose}: {im.width}x{im.height} {os.path.getsize(dst) // 1024} KB")


def main(only):
    for look in ([only] if only else list(LOOKS)):
        print(look)
        for pose in POSES:
            install(look, pose, generate(look, pose))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else None)
