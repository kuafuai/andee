#!/usr/bin/env python3
"""Turn the device's grounding log into labelled mis-tap / hit pairs.

  adb pull /sdcard/Android/data/net.kuafuai.andee/files/grounding ./grounding
  ./tools/grounding_pair.py ./grounding                  # summary
  ./tools/grounding_pair.py ./grounding -o pairs.jsonl   # write the samples
  ./tools/grounding_pair.py ./grounding --pkg com.tencent.mm

## What it is looking for

The body's creed is that the model never does coordinate arithmetic: given an
element list it taps by e-number and the device resolves the pixels. Raw
0-1000 coordinates come out only where there is no list — mini-programs,
WebViews, game canvases — and that is exactly where a general-purpose model is
weakest. What it does there is miss, retry a little to the side, and hit. Each
of those sequences is a free training example: the screenshot it was looking
at, the point that was wrong, the point that was right.

## Why the labelling lives here and not on the device

Deciding *which* tap was a miss is a heuristic, and the first version of a
heuristic is wrong. On the device a wrong rule can only be fixed by shipping an
APK and re-collecting; here it can be fixed and re-run over logs already on
disk. So the device writes down only what it observed, and every judgement
below is this script's.

## The three labels, strongest first

`guard`   The device's own stuck-loop guard refused the tap (`refused:true`).
          Its scope is exactly this data set — it counts coordinate taps in
          120px buckets and exempts e-taps — so a bucket it tripped contains
          nothing but misses. Nothing here is inferred; the device had already
          worked it out and simply never wrote it down before.

`retry`   Two coordinate taps at different points on the same screen with no
          navigation between them, and then the episode moves on. The first
          missed, the last hit. Weaker than `guard` because "moved on" is read
          from the event stream, not from the screen.

`diff`    NOT IMPLEMENTED — there is no `--diff` flag, and no sample is ever
          emitted with this label. It is described here because the reason it
          was dropped is the reason not to add it back naively. The obvious
          test ("screen unchanged ⇒ the tap did nothing") does not work on this
          device: TapMarkerUi's cyan ring stays up for six seconds and is
          deliberately NOT blanked during a capture, so consecutive screenshots
          always differ by at least a ring. Worse, the ring sits on the tap
          point, which is where a successful tap's change would first appear.
          Anyone adding it has to mask that box first — which is what
          `marker_px` in the log and the `mask` field below are for. Even then
          it is corroboration, never a label on its own.

## One image for both points

Every sample carries a single screenshot — the last one before the *first*
tap of the sequence — and both the miss and the hit are expressed against it.
That is sound precisely because the misses missed: a tap that hit nothing left
the screen as it was, so the picture the brain was looking at when it chose the
wrong point is still the picture that was true when it chose the right one.
Where that assumption fails the sample is already excluded — a tap that *did*
change something is either followed by navigation (which cuts the run) or is
the last tap in it (the hit).

Stdlib only.
"""
import argparse
import json
import os
import sys
from collections import defaultdict

# A new episode when the screen has been idle this long. Matched to the
# device's own LOOP_WINDOW_MS: the stuck-loop guard remembers a bucket for five
# minutes, and cutting an episode sooner would strand a refusal in a different
# episode from the taps it is evidence about.
EPISODE_GAP_MS = 300_000

# The guard's bucket size, in device pixels — must stay equal to
# ScreenController.LOOP_RADIUS_PX. Bucketing is done on the logged `px`/`py`
# rather than on the 0-1000 values so this script reproduces the device's
# grouping exactly; 120px is a different number of relative units on every
# screen width, and guessing it wrong silently splits one hammered bucket into
# two and mislabels half of it.
LOOP_RADIUS_PX = 120

# Two taps closer than this are the same aim, not a correction — used only for
# the weaker `retry` label, which has no device-side bucket to match.
SAME_SPOT_NORM = 45

# Side of the square masked out around the last tap mark, in 0-1000 units.
# Sized against what TapMarkerUi actually DRAWS — a 30dp glow around the centre
# and a caption chip hanging ~55dp below it — not against its 144dp window,
# most of which is empty. There is no honest constant here: dp maps to a
# different number of relative units on every screen, so this covers the mark
# comfortably on the tablet this was written for and is the first thing to
# widen if a `diff` implementation starts seeing the ring as a change.
MARKER_MASK_NORM = 120


def read_events(root):
    """Every JSONL generation, oldest first, in sequence order."""
    names = []
    for i in range(9, 0, -1):
        p = os.path.join(root, f"events.jsonl.{i}")
        if os.path.exists(p):
            names.append(p)
    p = os.path.join(root, "events.jsonl")
    if os.path.exists(p):
        names.append(p)
    if not names:
        sys.exit(f"no events.jsonl under {root}")

    out = []
    for name in names:
        with open(name, encoding="utf-8") as fh:
            for lineno, line in enumerate(fh, 1):
                line = line.strip()
                if not line:
                    continue
                try:
                    out.append(json.loads(line))
                except json.JSONDecodeError:
                    # A power-off mid-append truncates the last line. One bad
                    # line is not a reason to discard the file.
                    print(f"skipping malformed {name}:{lineno}", file=sys.stderr)
    out.sort(key=lambda e: (e.get("ts", 0), e.get("seq", 0)))
    return out


def split_episodes(events):
    """Contiguous runs on one package, cut at long gaps and at package changes."""
    episodes = []
    cur = []
    last_ts = None
    last_pkg = None
    for e in events:
        pkg = e.get("pkg")
        ts = e.get("ts", 0)
        gap = last_ts is not None and ts - last_ts > EPISODE_GAP_MS
        moved = pkg and last_pkg and pkg != last_pkg
        if cur and (gap or moved):
            episodes.append(cur)
            cur = []
        cur.append(e)
        last_ts = ts
        if pkg:
            last_pkg = pkg
    if cur:
        episodes.append(cur)
    return episodes


def human_touched(episode):
    """Any user-driven navigation poisons the whole episode.

    Not squeamishness: the brain's next tap after a human pressed back is
    aimed at a screen the human chose, so reading it as a correction of the
    brain's previous tap would label a point that was never wrong.
    """
    return any(e.get("actor") == "user" for e in episode)


def near(a, b):
    return abs(a[0] - b[0]) <= SAME_SPOT_NORM and abs(a[1] - b[1]) <= SAME_SPOT_NORM


def last_shot_before(episode, idx):
    """The image the brain was looking at when it chose event `idx`."""
    for j in range(idx - 1, -1, -1):
        if episode[j].get("ev") == "shot":
            return episode[j]
    return None


def pair_episode(episode):
    """Emit {image, miss[], hit, label_src} for each correction in one episode."""
    samples = []

    # --- label 1: the guard already knows -----------------------------------
    # Group coordinate taps into the same 120px-equivalent buckets the device
    # used. A bucket holding a refusal was hammered, so every coordinate tap in
    # it missed. The hit, if any, is whatever coordinate tap came next
    # elsewhere on the same screen.
    buckets = defaultdict(list)
    for i, e in enumerate(episode):
        if e.get("ev") == "tap" and e.get("mode") == "coord" and "px" in e:
            key = (e["px"] // LOOP_RADIUS_PX, e["py"] // LOOP_RADIUS_PX)
            buckets[key].append(i)
    refused_idx = set()
    for key, idxs in buckets.items():
        if not any(episode[i].get("refused") for i in idxs):
            continue
        refused_idx.update(idxs)
        shot = last_shot_before(episode, idxs[0])
        if not shot:
            continue
        miss = []
        for i in idxs:
            p = [episode[i]["nx"], episode[i]["ny"]]
            # The same point four times is one wrong answer repeated, not four
            # wrong answers — weight it once.
            if p not in miss:
                miss.append(p)
        hit = None
        for j in range(idxs[-1] + 1, len(episode)):
            ev = episode[j]
            if ev.get("ev") in ("swipe", "nav"):
                break
            if ev.get("ev") == "tap" and ev.get("mode") == "coord":
                if not near((ev["nx"], ev["ny"]), (miss[0][0], miss[0][1])):
                    hit = [ev["nx"], ev["ny"]]
                break
        samples.append(make_sample(shot, miss, hit, "guard", episode))

    # --- label 2: it moved, then it moved on --------------------------------
    # A run of coordinate taps at distinct points with no navigation between
    # them. The brain kept aiming at the same thing and only the last one can
    # have worked, so everything before it is a miss.
    run = []
    for i, e in enumerate(episode):
        ev = e.get("ev")
        if ev == "tap" and e.get("mode") == "coord":
            if i in refused_idx:
                # Already emitted above under the stronger `guard` label. Cut
                # the run here rather than reaching across it: joining the taps
                # either side would re-label those same points, and the tap
                # that follows a hammered bucket is very often another miss,
                # not the hit this label assumes.
                samples.extend(flush_run(episode, run))
                run = []
            else:
                run.append(i)
            continue
        # Anything that changes the screen ends the run: after a scroll or a
        # back the earlier point does not refer to the same pixels.
        if ev in ("swipe", "nav") or (ev == "tap" and e.get("mode") == "eid"):
            samples.extend(flush_run(episode, run))
            run = []
    samples.extend(flush_run(episode, run))

    return [s for s in samples if s]


def flush_run(episode, run):
    if len(run) < 2:
        return []
    pts = [(episode[i]["nx"], episode[i]["ny"]) for i in run]
    # All in one spot is a model re-pressing the same button, not correcting
    # its aim — no information about where the target actually was.
    if all(near(p, pts[0]) for p in pts[1:]):
        return []
    shot = last_shot_before(episode, run[0])
    if not shot:
        return []
    miss = [list(p) for p in pts[:-1]]
    hit = list(pts[-1])
    return [make_sample(shot, miss, hit, "retry", episode)]


def make_sample(shot, miss, hit, label_src, episode):
    return {
        "image": shot.get("file"),
        "shot_id": shot.get("shot_id"),
        "image_w": shot.get("w"),
        "image_h": shot.get("h"),
        "pkg": shot.get("pkg") or next((e.get("pkg") for e in episode if e.get("pkg")), None),
        "miss": miss,
        "hit": hit,
        "label_src": label_src,
        # Where our own tap ring sits in the image, in 0-1000 units. Anything
        # comparing two screenshots must blank this box first — see the module
        # docstring.
        "mask": marker_mask(shot),
    }


def marker_mask(shot):
    px = shot.get("marker_px")
    if not px or not shot.get("src_w") or not shot.get("src_h"):
        return None
    nx = px[0] * 1000 // shot["src_w"]
    ny = px[1] * 1000 // shot["src_h"]
    half = MARKER_MASK_NORM // 2
    return [max(0, nx - half), max(0, ny - half),
            min(1000, nx + half), min(1000, ny + half)]


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("root", help="the pulled grounding/ directory")
    ap.add_argument("-o", "--out", help="write samples as JSONL here")
    ap.add_argument("--pkg", help="only this package")
    ap.add_argument("--keep-user", action="store_true",
                    help="keep episodes a human steered (normally dropped)")
    args = ap.parse_args()

    events = read_events(args.root)
    episodes = split_episodes(events)

    kept, dropped_user, dropped_pkg = [], 0, 0
    for ep in episodes:
        pkg = next((e.get("pkg") for e in ep if e.get("pkg")), None)
        if args.pkg and pkg != args.pkg:
            dropped_pkg += 1
            continue
        if human_touched(ep) and not args.keep_user:
            dropped_user += 1
            continue
        kept.append(ep)

    samples = []
    for ep in kept:
        samples.extend(pair_episode(ep))

    by_src = defaultdict(int)
    for s in samples:
        by_src[s["label_src"]] += 1
    missing_image = sum(
        1 for s in samples
        if s["image"] and not os.path.exists(os.path.join(args.root, s["image"]))
    )

    print(f"events        {len(events)}")
    print(f"episodes      {len(episodes)} "
          f"(kept {len(kept)}, user-steered {dropped_user}, other pkg {dropped_pkg})")
    print(f"samples       {len(samples)}  " +
          "  ".join(f"{k}={v}" for k, v in sorted(by_src.items())))
    if missing_image:
        # Expected, not a bug: the PNG rotation is much tighter than the
        # JSONL's, so old events outlive their pictures.
        print(f"  ({missing_image} reference a PNG that has since rotated away)")

    if args.out:
        with open(args.out, "w", encoding="utf-8") as fh:
            for s in samples:
                fh.write(json.dumps(s, ensure_ascii=False) + "\n")
        print(f"wrote {args.out}")


if __name__ == "__main__":
    main()
