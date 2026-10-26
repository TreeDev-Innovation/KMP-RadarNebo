#!/usr/bin/env python3
"""
Downloads the Carto basemap tiles the map can show and stores them as app resources, so the street
backgrounds work offline: shared/src/commonMain/composeResources/files/map/<style>/<z>/<x>/<y>.png, plus
coverage.txt listing, per zoom, the tile range stored (MapTiles reads it to know what is bundled).

The area comes from the map camera's limits (MapProjection.bounds, MapCamera.MIN_ZOOM/MAX_ZOOM, and how
drawMapTiles picks a tile zoom): for each tile zoom, the union of every view that picks it, on typical
desktop and phone screens, with the camera centre anywhere it may go. Keep the constants below in step.

The key is read from CARTO_API_KEY or `carto.apiKey` in local.properties (never commit it).
Run: python3 -I tools/download-map-tiles.py [--dry-run]
"""
import math
import os
import shutil
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor

# MapProjection / MapCamera / drawMapTiles (shared/.../ui/map).
K = 0.6626
BOUNDS_LON = (22.0, 40.3)
BOUNDS_LAT = (44.2, 52.5)
MIN_ZOOM, MAX_ZOOM = 0.8, 16.0
FIT = 0.94
TILE_PX = 256
MIN_TILE_ZOOM = 3
# The deepest zoom stored; closer views upscale it. Each level about quadruples the download.
MAX_TILE_ZOOM = 9

# Screens in pixels: desktop windows, phones and tablets either way round.
SCREENS = [(1280, 800), (1920, 1080), (2560, 1440), (3456, 2160), (1080, 2400), (2400, 1080), (1640, 2360), (2360, 1640)]

STYLES = {"carto-dark": "dark_nolabels", "carto-light": "light_nolabels"}
URL = "https://basemaps.cartocdn.com/rastertiles/{style}/{z}/{x}/{y}.png?key={key}"

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
OUT = os.path.join(ROOT, "shared/src/commonMain/composeResources/files/map")


def lon_to_x(lon, n):
    return (lon + 180.0) / 360.0 * n


def lat_to_y(lat, n):
    r = math.radians(max(-85.0, min(85.0, lat)))
    return (1.0 - math.log(math.tan(r) + 1.0 / math.cos(r)) / math.pi) / 2.0 * n


def visible_areas():
    """Tile zoom -> (west, south, east, north): everything a view drawing that zoom can show."""
    bw = (BOUNDS_LON[1] - BOUNDS_LON[0]) * K
    bh = BOUNDS_LAT[1] - BOUNDS_LAT[0]
    areas = {}
    steps = 400
    for w, h in SCREENS:
        for i in range(steps + 1):
            zoom = MIN_ZOOM * (MAX_ZOOM / MIN_ZOOM) ** (i / steps)
            scale = min(w / bw, h / bh) * FIT * zoom  # px per world unit
            px_per_lon = scale * K
            z = math.ceil(math.log2(px_per_lon * 360.0 / TILE_PX))
            z = max(MIN_TILE_ZOOM, min(MAX_TILE_ZOOM, z))
            half_lon = w / px_per_lon / 2
            half_lat = h / scale / 2
            area = (BOUNDS_LON[0] - half_lon, BOUNDS_LAT[0] - half_lat, BOUNDS_LON[1] + half_lon, BOUNDS_LAT[1] + half_lat)
            old = areas.get(z, area)
            areas[z] = (min(old[0], area[0]), min(old[1], area[1]), max(old[2], area[2]), max(old[3], area[3]))
    # A coarser level stands in while a finer one loads, so it covers at least the finer one's area.
    for z in range(max(areas), MIN_TILE_ZOOM, -1):
        if z - 1 not in areas:
            areas[z - 1] = areas[z]
        else:
            a, b = areas[z - 1], areas[z]
            areas[z - 1] = (min(a[0], b[0]), min(a[1], b[1]), max(a[2], b[2]), max(a[3], b[3]))
    return areas


def tile_ranges(areas):
    ranges = {}
    for z, (west, south, east, north) in sorted(areas.items()):
        n = 1 << z
        x0 = max(0, math.floor(lon_to_x(west, n)))
        x1 = min(n - 1, math.floor(lon_to_x(east, n)))
        y0 = max(0, math.floor(lat_to_y(north, n)))
        y1 = min(n - 1, math.floor(lat_to_y(south, n)))
        ranges[z] = (x0, x1, y0, y1)
    return ranges


def api_key():
    key = os.environ.get("CARTO_API_KEY")
    if key:
        return key
    try:
        with open(os.path.join(ROOT, "local.properties")) as f:
            for line in f:
                name, _, value = line.partition("=")
                if name.strip() == "carto.apiKey":
                    return value.strip()
    except FileNotFoundError:
        pass
    sys.exit("No Carto key: set CARTO_API_KEY or carto.apiKey in local.properties")


def fetch(url, path):
    for attempt in range(4):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "RadarNebo tile bundler"})
            with urllib.request.urlopen(request, timeout=30) as response:
                data = response.read()
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "wb") as f:
                f.write(data)
            return len(data)
        except Exception as e:
            if attempt == 3:
                raise RuntimeError(f"{url.split('?')[0]}: {e}")
            time.sleep(1 + attempt * 2)


def main():
    areas = visible_areas()
    ranges = tile_ranges(areas)
    total = 0
    for z, (x0, x1, y0, y1) in ranges.items():
        w, s, e, n = areas[z]
        count = (x1 - x0 + 1) * (y1 - y0 + 1)
        total += count
        print(f"z{z}: lon {w:7.2f}..{e:6.2f}  lat {s:6.2f}..{n:6.2f}  tiles x {x0}..{x1} y {y0}..{y1}  ({count})")
    print(f"{total} tiles per style, {len(STYLES)} styles")
    if "--dry-run" in sys.argv:
        return

    key = api_key()
    for directory in STYLES:
        shutil.rmtree(os.path.join(OUT, directory), ignore_errors=True)
    jobs = [
        (URL.format(style=style, z=z, x=x, y=y, key=key), os.path.join(OUT, directory, str(z), str(x), f"{y}.png"))
        for directory, style in STYLES.items()
        for z, (x0, x1, y0, y1) in ranges.items()
        for x in range(x0, x1 + 1)
        for y in range(y0, y1 + 1)
    ]
    # Carto asks for modest parallelism.
    with ThreadPoolExecutor(max_workers=6) as pool:
        size = sum(pool.map(lambda job: fetch(*job), jobs))
    with open(os.path.join(OUT, "coverage.txt"), "w") as f:
        f.write("# zoom x0 x1 y0 y1: the tile ranges bundled for each style (tools/download-map-tiles.py)\n")
        for z, (x0, x1, y0, y1) in ranges.items():
            f.write(f"{z} {x0} {x1} {y0} {y1}\n")
    print(f"Stored {len(jobs)} tiles, {size / 1e6:.1f} MB, in {os.path.relpath(OUT, ROOT)}")


if __name__ == "__main__":
    main()
