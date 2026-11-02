#!/usr/bin/env python3
"""Pre-record the first-run onboarding's spoken lines.

The onboarding runs before the user has entered any key and maybe before the
device is online, so its voice cannot be synthesised live: it is recorded here,
once, and shipped in the APK under app/src/main/assets/onboarding/<lang>/<id>.mp3.
OnboardingUi plays the clip for the page it is on (when sound is on) and knows
the ids below, never the words — so this table is the only copy of the script.

Andee's own voice: the Andee look names no speaker, so it speaks with
VoiceConfig.TTS_SPEAKER, and that is what is used here. Change one, change both.

    VOLC_TTS_KEY=<火山 API key> python3 tools/onboarding_voice.py [id ...]

The key is read from the environment only and is never written anywhere.
Re-run after editing a line; pass ids to redo only those.
"""
import base64
import json
import os
import sys
import urllib.request
import uuid

ENDPOINT = "https://openspeech.bytedance.com/api/v3/tts/unidirectional"
RESOURCE = "seed-tts-2.0"          # VoiceConfig.DEFAULT_TTS_RESOURCE
SPEAKER = "zh_female_vv_uranus_bigtts"  # VoiceConfig.TTS_SPEAKER
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "onboarding")

# id -> (zh, en). Ids are OnboardingUi's: a page name, or perm_<SelfCheck finding id>.
# The prelude (language and sound) has no line: it is where the user decides whether Andee speaks.
LINES = {
    "intro": (
        "我会住进你的设备，成为它的灵魂。别的助手都在等你开口，我不用等，我一直都在。",
        "I'll move into your device and become its soul. Other assistants wait for you to ask. I don't wait. I'm always here.",
    ),
    "shopping": (
        "比如你想买牛奶，我记得你喝哪种，自己去找券、比价。付款前，一定回来问你。",
        "Say you want milk. I remember the kind you like, find coupons and compare prices. Before paying, I always come back to you.",
    ),
    "customer": (
        "客户来了，我先替你接住，问清楚。只把要你拍板的事交给你。",
        "When a customer comes in, I catch them first and ask the right questions. Only the decisions that need you reach you.",
    ),
    "subscriptions": (
        "每个月悄悄扣的钱，我帮你盯着。不用的，我提醒你退掉。这种麻烦事，交给我。",
        "The money that quietly leaves every month, I keep an eye on it. What you don't use, I'll suggest cancelling. Leave chores like this to me.",
    ),
    "perm_overlay": (
        "先让我待在你的屏幕上，这样你随时都能找到我。",
        "First, let me stay on your screen, so I'm always within reach.",
    ),
    "perm_accessibility": (
        "开了无障碍，我才能真正替你动手。放心，关键的事我都会先问你。",
        "With accessibility on, I can really do things for you. Don't worry, I'll ask before anything important.",
    ),
    "perm_microphone": (
        "让我听见你吧。你不跟我说话的时候，声音不会发给模型。",
        "Let me hear you. When you're not talking to me, your voice isn't sent to the model.",
    ),
    "model": (
        "最后给我选个大脑。不想折腾的话，选 CodeFlying 就好。",
        "Last, choose a brain for me. If you'd rather not fuss, CodeFlying is the easy way.",
    ),
    "meet": (
        "以后，我们怎么称呼彼此呢？",
        "So, what shall we call each other?",
    ),
    "ready": (
        "好啦，我们认识了。以后有事，直接跟我说。",
        "There, now we know each other. Whenever you need something, just tell me.",
    ),
}


def synth(key: str, text: str) -> bytes:
    body = {
        "user": {"uid": "andee-onboarding"},
        "req_params": {
            "text": text,
            "speaker": SPEAKER,
            "audio_params": {"format": "mp3", "sample_rate": 24000},
        },
    }
    req = urllib.request.Request(
        ENDPOINT,
        data=json.dumps(body).encode(),
        headers={
            "X-Api-Key": key,
            "X-Api-Resource-Id": RESOURCE,
            "X-Api-Request-Id": str(uuid.uuid4()),
            "Content-Type": "application/json",
        },
    )
    audio = b""
    with urllib.request.urlopen(req, timeout=60) as r:
        for raw in r:
            raw = raw.strip()
            if not raw:
                continue
            o = json.loads(raw)
            if o.get("data"):
                audio += base64.b64decode(o["data"])
            elif o.get("code") not in (0, 20000000):
                raise RuntimeError(f"{o.get('code')}: {o.get('message')}")
    if not audio:
        raise RuntimeError("no audio returned")
    return audio


def main() -> None:
    key = os.environ.get("VOLC_TTS_KEY", "").strip()
    if not key:
        sys.exit("set VOLC_TTS_KEY")
    only = set(sys.argv[1:])
    for clip, (zh, en) in LINES.items():
        if only and clip not in only:
            continue
        for lang, text in (("zh", zh), ("en", en)):
            d = os.path.join(OUT, lang)
            os.makedirs(d, exist_ok=True)
            path = os.path.join(d, f"{clip}.mp3")
            data = synth(key, text)
            with open(path, "wb") as f:
                f.write(data)
            print(f"{lang}/{clip}.mp3  {len(data) // 1024} KB")


if __name__ == "__main__":
    main()
