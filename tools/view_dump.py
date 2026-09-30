#!/usr/bin/env python3
"""Dump another app's View Hierarchy over adb — for apps accessibility can't see.

Some app screens can return an empty accessibility root while their visible
content is rendered by a different window or by a custom surface. The first
case should be handled by enumerating accessibility windows; the second has no
semantic tree and needs pixels. This host-side script is a diagnostic fallback,
not proof that an app blocks accessibility.

So reach for this script when the tree is genuinely absent — for example a
mini-program or a WebView article rendering into its own surface — after the
service has already inspected all accessible windows.

The *view* hierarchy is still there, because that is the data Layout Inspector
uses. `dumpsys activity top` prints it — class, resource id, bounds and
visibility, ~700 lines for WeChat's main screen. It carries **no text**, so pair
the two channels:

    this script    → where things are, and which stable id they have
    screenshot     → what they actually say (feed it to a vision model)

  ./tools/view_dump.py                          # the focused app
  ./tools/view_dump.py --pkg com.tencent.mm     # a specific one
  ./tools/view_dump.py --grep j8g               # only nodes whose id matches
  ./tools/view_dump.py --json                   # machine-readable
  ./tools/view_dump.py --all                    # keep invisible/zero-size nodes

Requires a running `adb` and a device that already has the app on screen.
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys

# `android.widget.FrameLayout{c1f673c V.E...... ........ 0,0-1600,2524 #7f0a1c9d app:id/v6n}`
VIEW_RE = re.compile(r"^(?P<indent> *)(?P<cls>[A-Za-z_][\w.$]*)\{(?P<hash>[0-9a-fA-F]+)\s+(?P<flags>\S+)(?P<rest>.*)\}$")
BOUNDS_RE = re.compile(r"(-?\d+),(-?\d+)-(-?\d+),(-?\d+)")
ID_RE = re.compile(r"#([0-9a-fA-F]+)\s+(\S+)?")
FOCUS_RE = re.compile(r"mCurrentFocus=Window\{[^}]*\s+([\w.]+)/")


def adb_path(explicit):
    if explicit:
        return explicit
    if os.environ.get("ADB"):
        return os.environ["ADB"]
    found = shutil.which("adb")
    if found:
        return found
    # Android Studio / SDK default on macOS
    guess = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
    return guess if os.path.exists(guess) else "adb"


def run(adb, *args):
    proc = subprocess.run([adb, *args], capture_output=True, text=True)
    if proc.returncode != 0:
        sys.exit(f"adb {' '.join(args)} failed: {proc.stderr.strip()}")
    return proc.stdout


def focused_package(adb):
    out = run(adb, "shell", "dumpsys", "window")
    m = FOCUS_RE.search(out)
    return m.group(1) if m else None


def hierarchy_block(dump, pkg):
    """The `View Hierarchy:` block of `pkg`'s top activity, as raw lines."""
    lines = dump.splitlines()
    start = None
    for i, line in enumerate(lines):
        if line.startswith("  ACTIVITY ") and f" {pkg}/" in line:
            start = i
            break
    if start is None:
        return []
    vh = None
    for i in range(start, len(lines)):
        # A new task/section ends the search.
        if i > start and lines[i].startswith("TASK "):
            break
        if lines[i].strip() == "View Hierarchy:":
            vh = i + 1
            break
    if vh is None:
        return []
    block = []
    for line in lines[vh:]:
        if line.strip() == "":
            continue
        # The block opens with a `DecorView@hash[ActivityName]` line at 6 spaces,
        # then the tree proper at 8+. Anything shallower than 6 is the next
        # section of the activity dump.
        if len(line) - len(line.lstrip(" ")) < 6:
            break
        block.append(line)
    return block


def parse(lines):
    """Raw dump lines -> flat list of nodes with depth/class/id/bounds."""
    nodes = []
    for line in lines:
        m = VIEW_RE.match(line)
        if not m:
            continue  # "(nothing)", "(missing)", malformed title rows
        rest = m.group("rest")
        b = BOUNDS_RE.search(rest)
        i = ID_RE.search(rest)
        x1 = y1 = x2 = y2 = 0
        if b:
            x1, y1, x2, y2 = (int(v) for v in b.groups())
        nodes.append(
            {
                "depth": len(m.group("indent")) // 2,
                "class": m.group("cls"),
                "flags": m.group("flags"),
                "id": i.group(2) if i and i.group(2) else "",
                "bounds": [x1, y1, x2, y2],
                "w": x2 - x1,
                "h": y2 - y1,
            }
        )
    return nodes


def short_id(raw):
    if not raw:
        return ""
    return raw.rsplit("/", 1)[-1].replace(":", "_")


def keep(node, show_all, min_size, grep):
    if not show_all:
        # Layout Inspector's flags: index 0 is 'V' when the view is visible.
        if not node["flags"].startswith("V"):
            return False
        if node["w"] < min_size or node["h"] < min_size:
            return False
    if grep and grep.lower() not in f"{node['class']} {node['id']}".lower():
        return False
    return True


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--pkg", help="package to dump (default: whatever is focused)")
    ap.add_argument("--adb", help="path to adb")
    ap.add_argument("--grep", help="only nodes whose class or id contains this")
    ap.add_argument("--min-size", type=int, default=2, help="drop nodes thinner than this many px (default 2)")
    ap.add_argument("--all", action="store_true", help="keep invisible and zero-size nodes")
    ap.add_argument("--json", action="store_true", help="emit JSON instead of a tree")
    opts = ap.parse_args()

    adb = adb_path(opts.adb)
    pkg = opts.pkg or focused_package(adb)
    if not pkg:
        sys.exit("could not determine the focused package; pass --pkg")

    dump = run(adb, "shell", "dumpsys", "activity", "top")
    block = hierarchy_block(dump, pkg)
    if not block:
        sys.exit(f"no view hierarchy for {pkg} in `dumpsys activity top` (is it on screen and resumed?)")

    nodes = [n for n in parse(block) if keep(n, opts.all, opts.min_size, opts.grep)]

    if opts.json:
        print(json.dumps({"pkg": pkg, "nodes": nodes}, ensure_ascii=False, indent=2))
        return

    print(f"# {pkg} — view hierarchy ({len(nodes)} nodes, no text by design)")
    for n in nodes:
        cls = n["class"].rsplit(".", 1)[-1]
        ident = short_id(n["id"])
        box = ",".join(str(v) for v in n["bounds"])
        print(f"{'  ' * n['depth']}{cls}{(' #' + ident) if ident else ''} [{box}]")


if __name__ == "__main__":
    main()
