#!/usr/bin/env python3
"""生成 THIRD_PARTY_NOTICES.md —— 依赖许可证清单。

为什么要脚本而不是手抄：ML Kit 会拖进 60 多个传递依赖，手工列必然漏，漏一个就是
一份不诚实的合规文件。

许可证来源优先级（都不猜，读不到就报出来）：
  1. 本地 Gradle 缓存里各 artifact 的 POM `<licenses>`
  2. MANUAL 表 —— POM 里没写、已经到上游仓库 LICENSE 原文逐个核实过的
  3. 都没有 → 列进 "Unresolved"，不写进正文

用法：
    ./gradlew :app:dependencies --configuration debugRuntimeClasspath > /tmp/deps-runtime.txt
    ./gradlew :app:dependencies --configuration debugCompileClasspath  > /tmp/deps-compile.txt
    python3 tools/license_audit.py /tmp/deps-runtime.txt /tmp/deps-compile.txt

输出写到一个路径由 -o 指定的文件，默认是仓库根目录的 THIRD_PARTY_NOTICES.md。

注意：依赖树里的版本要取 `->` **右边**那个（Gradle 会把冲突解析后的版本写在箭头后），
取错会把一堆旧版本写进清单。
"""
import argparse
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
DEFAULT_OUT = HERE.parent / "THIRD_PARTY_NOTICES.md"
CACHE = Path.home() / ".gradle" / "caches" / "modules-2" / "files-2.1"

COORD = re.compile(r"(?:[|+\\\- ]{4,})?([A-Za-z0-9_.\-]+):([A-Za-z0-9_.\-]+):([0-9][A-Za-z0-9_.\-]*)")

# POM 里没有 <licenses> 的，逐个到上游 LICENSE 原文核实过（核实日期 2026-09-29）
MANUAL = {
    ("com.github.mik3y", "usb-serial-for-android"): (
        "MIT", "https://github.com/mik3y/usb-serial-for-android/blob/master/LICENSE.txt"),
    ("com.google.auto.value", "auto-value-annotations"): (
        "Apache-2.0", "https://github.com/google/auto/blob/main/LICENSE"),
    ("com.google.guava", "listenablefuture"): (
        "Apache-2.0", "https://repo1.maven.org/maven2/com/google/guava/guava-parent/26.0-android/guava-parent-26.0-android.pom"),
    ("org.slf4j", "slf4j-api"): (
        "MIT", "https://github.com/qos-ch/slf4j/blob/master/LICENSE.txt"),
}

# 把 POM 里五花八门的写法归一到规范名
CANON = {
    "the apache software license, version 2.0": "Apache-2.0",
    "the apache license, version 2.0": "Apache-2.0",
    "apache 2.0": "Apache-2.0",
    "apache-2.0": "Apache-2.0",
    "mit license": "MIT",
    "mit": "MIT",
    "bsd license": "BSD-3-Clause",
    "ml kit terms of service": "ML Kit Terms of Service",
    "android software development kit license": "Android SDK License",
}

NOTES = {
    "Apache-2.0": "Permissive. Requires keeping the license text and any `NOTICE` file, and stating what you changed.",
    "MIT": "Permissive. Requires keeping the copyright notice and license text.",
    "BSD-3-Clause": "Permissive. Requires keeping the copyright notice; do not use the project's name to endorse your fork.",
    "Android SDK License": "**Not an open-source license.** Google's SDK terms govern redistribution of these `play-services-*` stubs.",
    "ML Kit Terms of Service": "**Not an open-source license.** The barcode-scanning SDK and its model are governed by Google's ML Kit Terms of Service, and the model is fetched at runtime from Google. Confirm this is acceptable for your redistribution.",
}

# 不随包发布、因此不进清单的（测试用）
TEST_ONLY = ("`junit:junit:4.13.2` (EPL-1.0)", "`androidx.test.espresso:espresso-core:3.5.1` (Apache-2.0)",
             "`androidx.test.ext:junit:1.1.5` (Apache-2.0)")


def parse_tree(path: Path) -> dict:
    found = {}
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if "(c)" in line:            # 依赖约束，不是实际依赖
            continue
        m = COORD.search(line)
        if not m:
            continue
        group, artifact, version = m.groups()
        arrow = re.search(r"->\s*([0-9][A-Za-z0-9_.\-]*)", line)
        if arrow:
            version = arrow.group(1)
        found[(group, artifact)] = version
    return found


def pom_path(group: str, artifact: str, version: str) -> Path | None:
    base = CACHE / group / artifact / version
    if not base.is_dir():
        return None
    poms = sorted(base.rglob(f"{artifact}-{version}.pom")) or sorted(base.rglob("*.pom"))
    return poms[0] if poms else None


def licenses_of(pom: Path) -> list[tuple[str, str]]:
    try:
        root = ET.parse(pom).getroot()
    except ET.ParseError:
        return []
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    out = []
    for lic in root.findall(".//m:licenses/m:license", ns) or root.findall(".//licenses/license"):
        name = (lic.findtext("m:name", default="", namespaces=ns) or lic.findtext("name", default="")).strip()
        url = (lic.findtext("m:url", default="", namespaces=ns) or lic.findtext("url", default="")).strip()
        if name:
            out.append((name, url))
    return out


def render(coords: dict, out: Path) -> tuple[int, int, list[str]]:
    by_license = defaultdict(list)
    unresolved = []
    for (group, artifact), version in sorted(coords.items()):
        coord = f"{group}:{artifact}:{version}"
        manual = MANUAL.get((group, artifact))
        if manual:
            by_license[manual[0]].append((coord, manual[1]))
            continue
        p = pom_path(group, artifact, version)
        lics = licenses_of(p) if p else []
        if not lics:
            unresolved.append(coord)
            continue
        for raw, url in lics:
            by_license[CANON.get(raw.strip().lower(), raw.strip())].append((coord, url))

    total = sum(len(v) for v in by_license.values())
    order = sorted(by_license.items(), key=lambda kv: (-len(kv[1]), kv[0]))

    L = []
    L.append("# Third-party notices\n")
    L.append("Andee bundles the following third-party components. The list is generated, not hand-written — "
             "it enumerates the **resolved** dependency graph, so it includes transitive dependencies.\n")
    L.append("Regenerate it with:\n")
    L.append("```bash")
    L.append("./gradlew :app:dependencies --configuration debugRuntimeClasspath > /tmp/deps-runtime.txt")
    L.append("./gradlew :app:dependencies --configuration debugCompileClasspath  > /tmp/deps-compile.txt")
    L.append("python3 tools/license_audit.py /tmp/deps-runtime.txt /tmp/deps-compile.txt")
    L.append("```\n")
    L.append("Licenses are read from each artifact's POM. Four artifacts declare nothing in their POM, and one "
             "inherits it from a parent; those were verified against the upstream repository instead and are "
             "included on that basis.\n")
    L.append("**Test-only dependencies are not listed here** because they are not shipped: "
             + ", ".join(TEST_ONLY) + ".\n")

    L.append("---\n")
    L.append(f"## Summary — {total} components\n")
    L.append("| License | Count | What it requires |")
    L.append("|---|---|---|")
    for lic, items in order:
        L.append(f"| {lic} | {len(items)} | {NOTES.get(lic, '')} |")
    L.append("")

    L.append("---\n")
    L.append("## Components by license\n")
    for lic, items in order:
        L.append(f"### {lic} ({len(items)})\n")
        if NOTES.get(lic):
            L.append(f"{NOTES[lic]}\n")
        L.append("| Component | License reference |")
        L.append("|---|---|")
        for coord, url in sorted(items):
            L.append(f"| `{coord}` | {url or '—'} |")
        L.append("")

    if unresolved:
        L.append("---\n")
        L.append("## Unresolved\n")
        L.append("Present in the dependency graph, but no license could be confirmed — so they are **not** "
                 "claimed above. Resolve these before relying on this document:\n")
        for c in unresolved:
            L.append(f"- `{c}`")
        L.append("")

    out.write_text("\n".join(L) + "\n", encoding="utf-8")
    return total, len(by_license), unresolved


def main() -> int:
    ap = argparse.ArgumentParser(description="生成依赖许可证清单")
    ap.add_argument("trees", nargs="+", help="`gradlew :app:dependencies` 的输出文件")
    ap.add_argument("-o", "--out", default=str(DEFAULT_OUT), help="输出路径")
    args = ap.parse_args()

    coords = {}
    for f in args.trees:
        coords.update(parse_tree(Path(f)))
    if not coords:
        print("没解析到任何坐标 —— 依赖树文件是不是空的？", file=sys.stderr)
        return 2

    out = Path(args.out)
    total, n_lic, unresolved = render(coords, out)
    print(f"{out}: {total} 个组件 / {n_lic} 种许可证", end="")
    print(f" / ⚠ {len(unresolved)} 个未决" if unresolved else " / 无未决")
    for c in unresolved:
        print(f"  ⚠ {c}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
