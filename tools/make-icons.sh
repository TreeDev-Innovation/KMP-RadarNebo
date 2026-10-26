#!/bin/sh
# Regenerates the desktop icons from tools/make-icons.swift: the app's (app/desktop) and the server
# console's (server-console), as .icns (macOS), .ico (Windows) and .png (Linux, and the Dock at run time).
set -e
cd "$(dirname "$0")/.."
work=$(mktemp -d)
swiftc -O tools/make-icons.swift -o "$work/make-icons"
"$work/make-icons" "$work/png"

pack() { # pack <name> <module dir>
    name=$1; dir=$2
    mkdir -p "$dir/icons" "$dir/src/main/resources" "$work/$name.iconset"
    for s in 16 32 128 256 512; do
        cp "$work/png/$name-$s.png" "$work/$name.iconset/icon_${s}x${s}.png"
        cp "$work/png/$name-$((s * 2)).png" "$work/$name.iconset/icon_${s}x${s}@2x.png"
    done
    iconutil -c icns "$work/$name.iconset" -o "$dir/icons/$name.icns"
    python3 -I - "$dir/icons/$name.ico" "$work/png/$name" <<'PY'
import struct, sys
# An .ico holding PNG images, which Windows Vista and later read.
out, prefix = sys.argv[1], sys.argv[2]
images = [(s, open(f"{prefix}-{s}.png", "rb").read()) for s in (16, 32, 48, 64, 128, 256)]
offset = 6 + 16 * len(images)
header = struct.pack("<HHH", 0, 1, len(images))
entries = b""
for size, data in images:
    entries += struct.pack("<BBBBHHII", size % 256, size % 256, 0, 0, 1, 32, len(data), offset)
    offset += len(data)
open(out, "wb").write(header + entries + b"".join(d for _, d in images))
PY
    cp "$work/png/$name-512.png" "$dir/icons/$name.png"
    cp "$work/png/$name-512.png" "$dir/src/main/resources/$name.png"
}
pack radarnebo app/desktop
pack radarnebo-server server-console
rm -rf "$work"
echo "Icons written to app/desktop/icons and server-console/icons"
