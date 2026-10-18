#!/usr/bin/env bash
# Prints the view tree of the screen on the connected device or emulator, one node per line
# with its bounds in dp, so a layout is measured from numbers instead of a screenshot.
#
#   tool/layout.sh [--px] [--text] [--depth <n>] [<pattern>]
#
# Each line is `[left,top][right,bottom] <class> #<resource id> '<text or description>'`,
# indented by depth. Wrappers that add nothing (one child with the same bounds, no text) are
# folded into their child. --px prints pixels instead of dp. --text keeps only the nodes with a
# text or description. --depth <n> stops n levels below the root. A pattern (a regular
# expression, case does not matter) keeps the nodes whose class, resource id, text or
# description match, with their ancestors. The dump waits for the screen to be idle.
# Needs adb on PATH or ANDROID_HOME, and python.
set -euo pipefail

unit=dp; text_only=0; depth=0; pattern=""
while [ $# -gt 0 ]; do
  case "$1" in
    --px) unit=px; shift ;;
    --text) text_only=1; shift ;;
    --depth) depth="$2"; shift 2 ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    -*) echo "unknown argument $1" >&2; exit 2 ;;
    *) pattern="$1"; shift ;;
  esac
done
if ! command -v adb > /dev/null; then
  adb() { "${ANDROID_HOME:?adb is not on PATH and ANDROID_HOME is not set}/platform-tools/adb" "$@"; }
fi
py=python; command -v python > /dev/null || py=python3

density=$(adb shell wm density | tr -d '\r' | awk '/density:/ {d=$NF} END {print d}')
dump=$(mktemp)
trap 'rm -f "$dump"' EXIT
MSYS_NO_PATHCONV=1 adb exec-out uiautomator dump /dev/tty > "$dump"   # only here: Git Bash would turn /dev/tty into a Windows path, and python needs the real temp path

"$py" -I - "$dump" "${density:-160}" "$unit" "$text_only" "$depth" "$pattern" <<'PY'
import re, sys, xml.etree.ElementTree as ET
for s in (sys.stdout, sys.stderr):
    s.reconfigure(encoding="utf-8", errors="replace")
path, density, unit, text_only, depth_max, pattern = sys.argv[1:7]
density, text_only, depth_max = float(density), text_only == '1', int(depth_max)
data = open(path, 'rb').read().decode('utf-8', 'replace')
start, end = data.find('<hierarchy'), data.rfind('</hierarchy>')
if start < 0 or end < 0:
    sys.exit('no view tree in the dump: ' + data.strip()[:300])
root = ET.fromstring(data[start:end + len('</hierarchy>')])
rx = re.compile(pattern, re.I) if pattern else None
scale = 1.0 if unit == 'px' else 160.0 / density

def fmt(v):
    s = f"{v * scale:.1f}"
    return s[:-2] if s.endswith('.0') else s

def bounds(n):
    return [int(x) for x in re.findall(r'-?\d+', n.get('bounds', ''))]

def label(n):
    cls = n.get('class', '').rsplit('.', 1)[-1]
    rid = n.get('resource-id', '').rsplit('/', 1)[-1]
    txt = n.get('text') or n.get('content-desc') or ''
    return cls, rid, txt

keep = None
if rx:
    keep = set()
    def mark(n, chain):
        chain = chain + [n]
        if rx.search(' '.join(label(n))):
            keep.update(id(x) for x in chain)
        for c in n:
            mark(c, chain)
    for c in root:
        mark(c, [])

lines = []
def walk(n, depth):
    if keep is not None and id(n) not in keep:
        return
    kids = [c for c in n if c.tag == 'node' and (keep is None or id(c) in keep)]
    cls, rid, txt = label(n)
    b = bounds(n)
    if len(kids) == 1 and not txt and not rid and bounds(kids[0]) == b:
        walk(kids[0], depth)
        return
    if depth_max and depth >= depth_max:
        return
    if len(b) == 4 and (txt or not text_only):
        pos = f"[{fmt(b[0])},{fmt(b[1])}][{fmt(b[2])},{fmt(b[3])}]"
        lines.append('  ' * depth + f"{pos} {cls}" + (f" #{rid}" if rid else '') + (f" {txt!r}" if txt else ''))
        depth += 1
    for c in kids:
        walk(c, depth)

for c in root:
    if c.tag == 'node':
        walk(c, 0)
print('\n'.join(lines))
print(f"{len(lines)} nodes, {density:g} dpi, {unit}", file=sys.stderr)
PY
