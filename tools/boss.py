#!/usr/bin/env python3
"""Command the ball's brain through the body hub, as if you were the user.

**This needs a hub you run yourself, and the hub is not in this repository.**
It is the `hub_url` side of `brain = hub` — the WebSocket server the device dials
out to (see `BodyWsClient`). With `brain = local` there is no hub and nothing for
this script to talk to. Set the address with `--hub` or `$BODY_HUB`.

The hub turns every device "event" into an inbound message for the brain agent,
so a *virtual* device identity can send instructions exactly like the real ball's
`asr.final` — the brain cannot tell the difference between this and a user
speaking. That is the whole mechanism, and it is worth saying out loud: this
impersonates a device to your own hub. It is a development tool for the person
who owns both ends.

  # One-shot: send an instruction, print the brain's live progress/replies
  BODY_HUB=ws://10.0.0.5:9100 ./tools/boss.py "帮我看下今天天气"

  # No wait: fire and forget (brain still works, output goes nowhere)
  ./tools/boss.py --hub ws://10.0.0.5:9100 --no-wait "去 Product Hunt 看看今天第一是什么"

  # Ask about progress on the running task
  ./tools/boss.py "进展如何？简单说一下"

  # Longer patience for long tasks (default 20 min)
  ./tools/boss.py --timeout 3600 "深度调研..."

Architecture notes, because they are load-bearing:
  * Replies are pushed ONLY to the connection that sent the message. The
    listener must stay open for the whole task, which is why send+listen
    share one socket here.
  * The virtual device registers with tools=[] — tool registration only
    feeds the hub's per-device tool pool, and the brain's screen tools come
    from the real device. Empty keeps us a pure mouthpiece.
  * Sessions are keyed by chat_id=device_id, so this identity has its own
    conversation history, separate from the ball's voice session.
"""
import argparse
import json
import os
import subprocess
import sys
import threading
import time

from websocket import create_connection

DEVICE_ID = "body-ops-7f2a"
DEVICE_NAME = "ops-inject"

# A "message" frame longer than this reads as a final deliverable, not
# narration. Narration ("progress") is never this long.
FINAL_HINT_CHARS = 300


def adb_logcat_tail():
    """Device-side tool-call trail, as a daemon thread.

    The hub only pushes the brain's narration (progress) and replies
    (message) — its internal loop (LLM calls, tool params) lives in the
    cloud. But every tool call lands on the real device, and each one
    leaves a line in logcat (BodyWs "request ...", BodyWsClient "tool ...
    failed"). Tailing that turns "the brain has been silent for 3 minutes"
    into "it just took a screenshot and is reading the tree".

    adb is optional by design: no device attached (or adb missing) simply
    means no trail, not an error.
    """
    try:
        adb = subprocess.run(
            ["ls", f"{__import__('os').path.expanduser('~/Library/Android/sdk/platform-tools/adb')}"],
            capture_output=True).returncode == 0
        if not adb:
            return
        import os
        adb_path = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
        p = subprocess.Popen(
            [adb_path, "logcat", "-s", "-T", "1",
             "BodyWs:D", "BodyWsClient:I", "Body:I"],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
        for line in p.stdout:
            line = line.strip()
            if not line:
                continue
            # Keep it one line and compact: time + message body.
            try:
                body = line.split("): ", 1)[1] if "): " in line else line
                stamp = time.strftime("%H:%M:%S")
                print(f"[{stamp}] ⚙ {body[:150]}", flush=True)
            except Exception:
                pass
    except Exception:
        pass


def main():
    ap = argparse.ArgumentParser(description="Command the ball's brain via the hub")
    ap.add_argument("instruction", help="The instruction, in the user's voice")
    ap.add_argument("--hub", default=os.environ.get("BODY_HUB"),
                    help="ws:// address of your own body hub (or set $BODY_HUB)")
    ap.add_argument("--no-wait", action="store_true",
                    help="send and exit without listening for replies")
    ap.add_argument("--timeout", type=int, default=1200,
                    help="max seconds to listen for the final reply (default 1200)")
    ap.add_argument("--quiet", action="store_true", help="progress lines, final only")
    ap.add_argument("--verbose", action="store_true",
                    help="also tail the device logcat (every tool call the brain makes)")
    args = ap.parse_args()

    if not args.hub:
        sys.exit("no hub address: pass --hub ws://host:9100 or set $BODY_HUB. "
                 "The hub is not part of this repository — see the module docstring.")

    if args.verbose:
        threading.Thread(target=adb_logcat_tail, daemon=True).start()

    ws = create_connection(args.hub, timeout=10)
    ws.send(json.dumps({"type": "register",
                        "data": {"device_id": DEVICE_ID,
                                 "device_name": DEVICE_NAME,
                                 "tools": []}}))
    time.sleep(0.5)
    ws.send(json.dumps({"type": "event", "kind": "asr.final",
                        "device_id": DEVICE_ID,
                        "data": {"text": args.instruction}}))
    print(f"→ 已下发: {args.instruction[:80]}", flush=True)
    if args.no_wait:
        ws.close()
        print("(不等待回复)")
        return

    ws.settimeout(60)
    deadline = time.time() + args.timeout
    last = time.time()
    final = None
    n = 0
    # Thought-loop watchdog: the brain can enter a degenerate state where it
    # emits the SAME narration line over and over (field-measured: the exact
    # sentence 400 times, no tool calls, until timeout). Per-message
    # uniqueness detects this without understanding the content: once the
    # same text repeats past N_MUTE lines, we inject an interrupt message
    # telling it to STOP and report — the only cure observed to break the
    # loop (waiting it out just burns the whole budget).
    seen_lines = {}
    muted = 0
    N_MUTE = 20          # repeats of one identical line before intervention
    muted_at = 0.0
    print("(监听中… ⏎打断无效;大脑沉默干活可能几分钟无输出)", flush=True)
    while time.time() < deadline:
        try:
            m = ws.recv()
            d = json.loads(m)
            t = d.get("type")
            c = str(d.get("content", "")).strip()
            if not c:
                continue
            n += 1
            last = time.time()
            # Watchdog bookkeeping. Identical narration repeated = loop.
            key = (t, c[:200])
            seen_lines[key] = seen_lines.get(key, 0) + 1
            if seen_lines[key] > N_MUTE:
                if muted < 2:  # at most 2 interventions, then stop listening
                    muted += 1
                    muted_at = time.time()
                    print(f"--- 检测到思维死循环(同一句话重复{seen_lines[key]}次),注入打断 #{muted} ---", flush=True)
                    try:
                        ws.send(json.dumps({"type": "event", "kind": "asr.final",
                                            "device_id": DEVICE_ID,
                                            "data": {"text": "停下。你正在无限重复同一句话。立刻停止当前路线，直接汇报：现在卡在哪、已尝试了什么、还剩什么可选路线。不要再说任何重复的话。"}}))
                    except Exception:
                        pass
                elif time.time() - muted_at > 300:
                    print("--- 打断两次后仍在循环,放弃监听 ---", flush=True)
                    break
                continue
            if t == "progress":
                if not args.quiet:
                    print(f"[{time.strftime('%H:%M:%S')}] {c}", flush=True)
            elif t == "message":
                print(f"[{time.strftime('%H:%M:%S')}] 💬 {c}", flush=True)
                if len(c) > FINAL_HINT_CHARS:
                    final = c
                    break
            else:
                print(f"[{time.strftime('%H:%M:%S')}] ({t}) {c[:120]}", flush=True)
        except Exception:
            # websocket timeout between frames; the brain may be working
            # silently for minutes. Report heartbeat, give up after 7 min.
            silent = time.time() - last
            if silent > 420:
                print(f"--- {int(silent)}秒无任何输出,结束监听(大脑可能仍在跑或已卡死) ---", flush=True)
                break
            continue
    ws.close()
    if final:
        print("\n=== 最终答复 ===", flush=True)
        print(final)
    else:
        print(f"\n(结束,共 {n} 条,未等到长答复)", flush=True)


if __name__ == "__main__":
    main()
