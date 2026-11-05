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
    TEAMO_KEY=sk-… python3 tools/pet_sprites.py girl listen speak   # 只给女孩补两个姿势
    (TEAMO_BASE_URL / TEAMO_IMAGE_MODEL 可换别的兼容端点和模型。)
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
    # ---- The ball's states (2026-11-05): the pet replaces the ball on the Mac, so every face the ball could
    # wear needs a drawing. One per `Mood` that the nine above cannot stand in for.
    "listen": (   # LISTENING — the mic is open; the one face that must never be mistaken for idle
        "Change only the pose and expression: listening closely — leaning slightly forward, one hand cupped behind "
        "an ear, eyes wide and attentive looking at the viewer, mouth closed in a small soft 'o', ahoge perked up, "
        "tail raised alert. "
    ),
    "speak": (    # SPEAKING — alternates with idle while the answer is read out
        "Change only the pose and expression: talking cheerfully to the viewer — mouth open mid-word, eyes bright, "
        "one hand open and raised a little as if explaining something, head tilted slightly, tail swaying. "
    ),
    "search": (   # SEARCHING — a task is running; the face worn for most of a task
        "Change only the pose and expression: busy working — looking down with focused eyes at a small glowing "
        "smartphone held in both hands, tapping it with one thumb, lips pressed in concentration, tail curled. "
    ),
    "holding": (  # HOLDING — waiting for the user's answer to a question
        "Change only the pose and expression: waiting patiently for an answer — head tilted to one side, hands "
        "clasped behind the back, gentle expectant smile, eyes looking straight at the viewer, tail still. "
    ),
    "curious": (  # CURIOUS
        "Change only the pose and expression: curious — leaning forward, eyes wide and sparkling, one finger touching "
        "the lower lip, ahoge bent into a little curl, tail tip twitching. "
    ),
    "anxious": (  # ANXIOUS / TENSE about something going wrong
        "Change only the pose and expression: worried and flustered — eyebrows raised in the middle, a small sweat "
        "drop at the temple, both hands held up near the chest, mouth in a wobbly line, tail tucked low. "
    ),
    "wink": (     # WINK
        "Change only the pose and expression: playful — winking with one eye closed, tongue just peeking out, one "
        "hand making a small heart shape near the cheek, tail flicked up. "
    ),
    "celebrate": (  # CELEBRATE / SUCCESS — something worked
        "Change only the pose and expression: celebrating a success — jumping with both arms raised high, eyes "
        "closed in happy arcs, mouth wide open in a cheer, a few small yellow sparkles around her, tail raised high. "
    ),
    "protect": (  # PROTECT — being careful with money, an account, a message about to be sent
        "Change only the pose and expression: careful and protective — holding a small round cobalt-blue shield "
        "with a white star in front of the chest with both hands, serious determined little frown, tail curled close. "
    ),
    # The phone docks the pet into the screen's right edge (2026-11-05): the app draws this with its right part
    # cut off by the edge, so it has to read as holding on round a corner, not as a body sliced in half.
    "peek": (
        "Change only the pose and expression: peeking round a corner — the body pressed against an invisible "
        "vertical wall on the RIGHT side of the picture, leaning out to the LEFT round its edge, both hands gripping "
        "the wall's edge at the right, head tilted out to the left looking at the viewer with a curious playful "
        "smile. "
    ),
    "sleep": (    # SLEEPING — fast asleep, not just sleepy
        "Change only the pose and expression: fast asleep while standing, eyes closed, head drooped to one side, "
        "a small bubble from the nose, hands loosely together, tail curled around the feet. "
    ),
}

# The two grown-up looks (2026-11-05), each with a voice and a manner of its own (`PetLook.voice` / `persona`).
# Not cartoon: a realistic person (the user's ask), but proportioned for a small window — the first, true-to-life
# cut had a head a pet's size makes unreadable and legs that took half the height, so these ask for a fuller head,
# a shorter leg line, and a face drawn to be looked at. Still Andee's small horns, tail and core. Adults, fully
# dressed; the allure is in the attitude, never in the clothes.
LADY = (
    "A beautiful East Asian woman in her early twenties, mascot for an AI companion named Andee, full body, standing "
    "quietly, facing the viewer. A reserved, cool, bookish elegance, like a quiet literature-club senior: a calm "
    "composed face with a faint gentle smile, soft violet eyes, natural light makeup. Very long straight dark-purple "
    "hair with a neat fringe, two small hairclips. Two small dark horns at the hairline, a slim dark tail behind her. "
    "Modest everyday clothes: a loose soft grey knit cardigan over a white high-collar blouse with a small ribbon, a "
    "long pleated dark-navy skirt below the knee, black tights and plain loafers, holding a small closed book. A small "
    "glowing cobalt-blue pin on the cardigan. Proportions slightly stylized for a small desktop figure: about 5.5 "
    "heads tall, the head a little larger than life so the face reads at small size. Fully clothed, modest, not sexy, "
    "not revealing, no tight clothes. "
)
BEAU = (
    "A handsome East Asian man about twenty-five, adult male features, a defined jaw, mascot for an AI companion named Andee, full body, "
    "standing in a relaxed easy pose, facing the viewer. Proportions slightly stylized for a small desktop figure: about 5.5 heads tall, the head a little larger than life so the face reads at small size, legs a little shorter than life (well under half the height). A clearly ADULT face and body, not a child or a teenager. NOT a long-legged fashion model. A soft, warm, good-looking face like a gentle idol: kind dark-brown eyes, a warm "
    "smile, clear skin. Soft fluffy dark-brown hair with a light fringe over the brows. Two small cream horns with "
    "coral tips at the hairline, a slim cream tail with a coral tip behind him. An oversized cream knit sweater over "
    "a white collared shirt, a small glowing cobalt-blue pin on the chest, light-blue straight jeans, white sneakers. "
)
# Not the cartoon STYLE the others use.
REAL_STYLE = (
    "Semi-realistic portrait rendering, like a high-end realistic digital painting or a 3D character render: "
    "realistic skin with soft subsurface light, realistic hair strands and fabric texture, soft studio lighting, "
    "beautiful and attractive, NOT cartoon, NOT chibi, NOT anime, NOT flat vector, no thick outlines. "
)
# The alien is a soft toy, not a sticker: no dark outlines (they are half of what made the first cut look eerie).
SOFT_STYLE = (
    "Soft 3D plush toy render, fuzzy felt texture, rounded shapes, gentle warm studio lighting, pastel colours, "
    "kawaii, highly readable at small sizes, no outlines. "
)
STYLE_OF = {"lady": REAL_STYLE, "beau": REAL_STYLE, "alien": SOFT_STYLE}

# Andee itself (2026-11-05): the plush blue ball of the brand illustrations (`images/brand/illustrations/`),
# horns and little paws, no legs. Its idle drawing is an edit of those illustrations, not a text prompt — a text
# prompt drew a stranger every time; the illustrations are what the ball already is.
ANDEE = (
    "Andee, the round plush mascot from the reference pictures: a soft fuzzy cobalt-blue ball body with two small "
    "rounded horns on top, big glossy dark-blue eyes with white glints, a small smile, two tiny round paws, a soft warm "
    "peach rim light around the edge. No legs, no tail, no clothes. "
)
ANDEE_STYLE = (
    "Exactly the reference pictures' style: soft 3D plush render, fuzzy velvety felt texture, gentle studio light, "
    "warm peach rim glow, kawaii, readable at small sizes, no outlines. "
)
# The pose texts were written for a figure with legs and a tail; the ball has neither.
NO_LEGS = {"cat": "It is a small kitten on four paws: wherever the text below mentions hands, arms, a phone, a cup, chopsticks or a shield, show it with the paws, the tail, the ears and the face, holding a prop in its front paws when there is one. ", "andee": "It is a round ball with two little paws and no legs, feet, knees or tail: wherever the text below "
           "mentions those, show the feeling with the body's tilt, the paws, the eyes and the horns instead. "}
BRAND = os.path.join(ROOT, "images", "brand", "illustrations")

# 大肥鱼 (2026-11-05): a chubby little whale-girl maid, after the meme the user pointed at — drawn as our own
# character from a description, not from those pictures, so it is not a copy of somebody else's art.
FISH = (
    "Chibi anime girl mascot for an AI companion named Andee, about 2.5 heads tall, full body, standing, facing the "
    "viewer, a little chubby and round. Long wavy deep navy-blue hair fading to light sky-blue at the ends, a big "
    "curled ahoge, soft round cheeks with pink blush, huge round sky-blue eyes with white glints, a small shy pout. "
    "Two small dark-blue whale-fin ears with pale undersides sticking out sideways from the hair, a small light-blue "
    "ribbon on one side, and a chubby dark-blue whale tail with a pale belly stripe curling out behind. A cute "
    "navy-and-white maid outfit: a frilly white maid headband, a navy long-sleeved dress with white frilly cuffs and "
    "collar, a navy bow, a white apron with a tiny whale embroidered on it, and a small round glowing cobalt-blue "
    "core brooch. "
)
# 小猫 (2026-11-05, replacing the gentle boyfriend): a round little kitten, with Andee's horns and core.
CAT = (
    "An adorable chubby little kitten mascot for an AI companion named Andee, full body, sitting upright, facing the "
    "viewer. Fluffy cream-white fur with soft orange-ginger patches on the ears, back and tail, a big round head, huge "
    "round amber eyes with big white glints, a tiny pink nose, a small happy 'w' mouth, soft pink blush, little pink "
    "paw pads, a long fluffy tail curling round. Two tiny rounded horns with coral tips between the ears, a small "
    "coral collar with a little round glowing cobalt-blue core charm. "
)
LOOKS = {"cat": CAT, "fish": FISH, "andee": ANDEE, "girl": GIRL, "boy": BOY, "blue-girl": BLUE_GIRL, "blue-boy": BLUE_BOY, "lady": LADY, "beau": BEAU}

# 缩到多高(px)。资源是 2x 的,运行时高度取 PetLook.baseHeight。
HEIGHT = {"cat": 760, "fish": 820, "andee": 700, "girl": 820, "boy": 820, "blue-girl": 820, "blue-boy": 820, "lady": 820, "beau": 820}

# 裁边后保留的空隙:占长边的比例。窗口紧贴身体,空隙越大挡住的点击越多。
PAD = 0.008


def post(url, args):
    key = os.environ["TEAMO_KEY"]
    out = subprocess.run(["curl", "-sS", "-m", "300", url, "-H", "Authorization: Bearer " + key] + args,
                         capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(out.stderr[:400])
    return base64.b64decode(json.loads(out.stdout)["data"][0]["b64_json"])


def reference(look):
    """The idle drawing every pose is edited from. The raw generation if it is still in build/, else the shipped
    WebP (same picture, trimmed) — so a pose can be added later without regenerating idle and changing the face."""
    raw = os.path.join(RAW, f"{look}-idle.png")
    if os.path.exists(raw):
        return raw
    os.makedirs(RAW, exist_ok=True)
    ref = os.path.join(RAW, f"{look}-idle-ref.png")
    im = Image.open(os.path.join(OUT, f"{look}-idle.webp")).convert("RGBA")
    # Back onto a 2:3 transparent canvas with a margin, the shape the edits endpoint is asked for.
    w, h = 1024, 1536
    s = min((w * 0.8) / im.width, (h * 0.86) / im.height)
    im = im.resize((round(im.width * s), round(im.height * s)), Image.LANCZOS)
    canvas = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    canvas.alpha_composite(im, ((w - im.width) // 2, (h - im.height) // 2))
    canvas.save(ref)
    return ref


def generate(look, pose):
    desc = LOOKS[look]
    if look == "andee" and pose == "idle":
        refs = []
        for name in ("wave", "think", "rest"):
            png = os.path.join(RAW, f"brand-{name}.png")
            os.makedirs(RAW, exist_ok=True)
            Image.open(os.path.join(BRAND, f"{name}.webp")).convert("RGBA").save(png)
            refs += ["-F", f"image[]=@{png};type=image/png"]
        args = ["-F", "model=" + MODEL] + refs + ["-F", "size=1024x1024", "-F", "background=transparent",
                "-F", "output_format=png", "-F", "prompt=" + desc + "Calm and content, a gentle closed-mouth smile, "
                "both paws resting low at the sides of the body, looking at the viewer. " + ANDEE_STYLE + FRAME]
        return post(API + "/images/edits", args)
    if pose == "idle":
        body = {"model": MODEL, "prompt": desc + STYLE_OF.get(look, STYLE) + FRAME, "size": "1024x1536", "background": "transparent", "n": 1}
        return post(API + "/images/generations",
                    ["-H", "Content-Type: application/json", "--data-binary", json.dumps(body)])
    ref = reference(look)
    args = ["-F", "model=" + MODEL, "-F", f"image=@{ref};type=image/png", "-F", "size=1024x1536",
            "-F", "background=transparent", "-F", "output_format=png",
            "-F", "prompt=" + KEEP + NO_LEGS.get(look, "") + POSES[pose] + STYLE_OF.get(look, STYLE) + FRAME]
    return post(API + "/images/edits", args)


def install(look, pose, png):
    os.makedirs(RAW, exist_ok=True)
    path = os.path.join(RAW, f"{look}-{pose}.png")
    with open(path, "wb") as f:
        f.write(png)
    im = Image.open(path).convert("RGBA")
    # The endpoint sometimes ignores background=transparent and returns an opaque picture (a black backdrop, or a
    # glow filling the frame). On a desktop that is a rectangle, not a character: refuse it, ask again.
    corners = [im.getpixel(c)[3] for c in ((0, 0), (im.width - 1, 0), (0, im.height - 1), (im.width - 1, im.height - 1))]
    if max(corners) > 10:
        raise RuntimeError(f"{look}/{pose}: came back without a transparent background; run it again")
    # A realistic render comes back wrapped in a near-invisible haze (alpha 1-10) that reaches the frame's edges;
    # trimmed with it, the box was twice as wide as the person and every transparent pixel of it eats clicks.
    im.putalpha(im.getchannel("A").point(lambda v: 0 if v < 12 else v))
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


def main(only, poses):
    for look in ([only] if only else list(LOOKS)):
        print(look)
        for pose in (poses or POSES):
            install(look, pose, generate(look, pose))


if __name__ == "__main__":
    # pet_sprites.py [look] [pose …] — e.g. `girl listen speak` adds two poses to one look and leaves the rest.
    main(sys.argv[1] if len(sys.argv) > 1 else None, sys.argv[2:])
