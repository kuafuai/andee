#!/usr/bin/env python3
"""Drive the tablet body over its own WS server (port 9008, internal method names).

  adb forward tcp:9008 tcp:9008
  ./tools/body.py ui_tree                     # element outline (indented eid|class|label|[x,y])
  ./tools/body.py tap_eid e7                  # tap by element number — preferred
  ./tools/body.py tap 500 470                 # 0-1000 RELATIVE coords (last resort)
  ./tools/body.py global home
  ./tools/body.py type '{"text":"hello"}'
  ./tools/body.py screenshot -o shot.png

Methods are the CommandDispatcher names; the `screen.` prefix is optional.
Positional args are sugar for the common tools — anything else, pass raw JSON.
"""
import argparse
import base64
import json
import sys

from websocket import create_connection

POSITIONAL = {
    "screen.tap": ["x:int", "y:int"],
    "screen.tap_id": ["eid"],
    "screen.long_press": ["x:int", "y:int", "duration_ms:int"],
    "screen.long_press_id": ["eid"],
    "screen.swipe": ["x1:int", "y1:int", "x2:int", "y2:int", "duration_ms:int"],
    "screen.type": ["text", "id"],
    "screen.submit_input": ["id"],
    "screen.global": ["action"],
    "screen.ui_tree": ["verbose:bool"],
}


def build_params(method, args):
    if len(args) == 1 and args[0].lstrip().startswith("{"):
        return json.loads(args[0])
    spec = POSITIONAL.get(method)
    if spec is None:
        if args:
            sys.exit(f"{method} takes no positional args — pass a JSON object instead")
        return {}
    if len(args) > len(spec):
        sys.exit(f"{method} takes at most {len(spec)} positional args: {' '.join(spec)}")
    params = {}
    for slot, raw in zip(spec, args):
        name, _, kind = slot.partition(":")
        if kind == "int":
            params[name] = int(raw)
        elif kind == "bool":
            params[name] = raw.lower() in ("1", "true", "yes")
        else:
            params[name] = raw
    return params


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("method")
    ap.add_argument("args", nargs="*")
    ap.add_argument("--url", default="ws://127.0.0.1:9008")
    ap.add_argument("-o", "--out", help="write base64 image result to this file")
    ap.add_argument("-t", "--timeout", type=float, default=30.0)
    opts = ap.parse_args()

    method = opts.method if "." in opts.method else f"screen.{opts.method}"
    envelope = {
        "type": "request",
        "id": "cli-1",
        "method": method,
        "params": build_params(method, opts.args),
    }

    ws = create_connection(opts.url, timeout=opts.timeout)
    try:
        ws.send(json.dumps(envelope))
        reply = json.loads(ws.recv())
    finally:
        ws.close()

    if "error" in reply:
        print(json.dumps(reply, ensure_ascii=False, indent=2))
        sys.exit(1)

    result = reply.get("result")
    if opts.out and isinstance(result, dict):
        # The body returns its PNG as `png_base64`; the other keys are here so
        # this stays usable against older builds.
        b64 = next(
            (result[k] for k in ("png_base64", "image", "data", "png", "base64") if k in result),
            None,
        )
        if b64:
            with open(opts.out, "wb") as f:
                f.write(base64.b64decode(b64))
            result = {k: v for k, v in result.items() if v is not b64}
            print(f"wrote {opts.out}")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
