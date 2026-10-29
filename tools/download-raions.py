#!/usr/bin/env python3
"""
Downloads the centre and size of every raion (district) and hromada (community) of Ukraine from OpenStreetMap and
writes them to core/src/commonMain/kotlin/com/gusak/lab/radarnebo/geo/UkraineRaions.kt and UkraineHromadas.kt.

alerts.in.ua raises most alerts per raion and gives no coordinates, only the raion's name ("Баштанський район")
and its oblast. Without this table a raion alert can only be placed somewhere in its oblast, and drone tracks
built from such positions zig-zag. Each raion is listed with its current, former and alternative Ukrainian
names (several were renamed in 2023-2024), its oblast, the centre of its bounding box and a radius (half the mean
of the box's width and height), which tells the tracker how far from the centre the target may be. Hromadas are
listed the same way, by the stem of their name ("Червоногригорівська"): OSM calls them "сільська/селищна/міська
громада", alerts.in.ua "територіальна громада".

Data © OpenStreetMap contributors, ODbL. Needs no key.
Run: python3 -I tools/download-raions.py
"""
import json
import math
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
OUT = os.path.join(ROOT, "core/src/commonMain/kotlin/com/gusak/lab/radarnebo/geo/UkraineRaions.kt")
OUT_HROMADAS = os.path.join(ROOT, "core/src/commonMain/kotlin/com/gusak/lab/radarnebo/geo/UkraineHromadas.kt")

QUERY = """
[out:json][timeout:250];
area["ISO3166-1"="UA"][admin_level=2]->.ua;
rel(area.ua)[boundary=administrative][admin_level=4];
map_to_area->.oblasts;
foreach.oblasts->.oblast(
  .oblast out tags;
  rel(area.oblast)[boundary=administrative][admin_level~"^[67]$"];
  out tags bb;
);
"""

# Oblast names as OSM writes them -> alerts.in.ua oblast UIDs (Regions.oblasts). Kyiv and Sevastopol are
# alerted as whole cities, so their own districts are left out.
OBLASTS = {
    "Хмельницька область": 3, "Вінницька область": 4, "Рівненська область": 5, "Волинська область": 8,
    "Дніпропетровська область": 9, "Житомирська область": 10, "Закарпатська область": 11,
    "Запорізька область": 12, "Івано-Франківська область": 13, "Київська область": 14,
    "Кіровоградська область": 15, "Луганська область": 16, "Миколаївська область": 17, "Одеська область": 18,
    "Полтавська область": 19, "Сумська область": 20, "Тернопільська область": 21, "Харківська область": 22,
    "Херсонська область": 23, "Черкаська область": 24, "Чернігівська область": 25,
    "Чернівецька область": 26, "Львівська область": 27, "Донецька область": 28,
    "Автономна Республіка Крим": 29,
}

# Ukrainian raion names are adjectives in -ський/-цький/-зький; the Russian-named Crimean overlay is skipped.
UKRAINIAN_RAION = re.compile(r"(ськ|цьк|зьк)ий район$")
NAME_TAGS = ("name", "name:uk", "official_name", "old_name", "old_name:uk", "alt_name:uk", "alt_name")
HROMADA = re.compile(r"^(.+?)\s+(міська|селищна|сільська|територіальна)\s+(територіальна\s+)?громада$")


def fetch():
    request = urllib.request.Request(
        "https://overpass-api.de/api/interpreter",
        data=urllib.parse.urlencode({"data": QUERY}).encode(),
        headers={"User-Agent": "RadarNebo raion bundler"},
    )
    # The public Overpass server answers 429/502/504 when busy; it asks clients to back off and retry.
    for attempt in range(5):
        try:
            with urllib.request.urlopen(request, timeout=300) as response:
                return json.load(response)["elements"]
        except urllib.error.HTTPError as e:
            if attempt == 4 or e.code not in (429, 502, 503, 504):
                raise
            time.sleep(15 * (attempt + 1))


def kotlin_string(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def centre_and_radius(bounds):
    """Centre of the bounding box and half the mean of its width and height, in km."""
    lat = (bounds["minlat"] + bounds["maxlat"]) / 2
    lon = (bounds["minlon"] + bounds["maxlon"]) / 2
    height = (bounds["maxlat"] - bounds["minlat"]) * 111.2
    width = (bounds["maxlon"] - bounds["minlon"]) * 111.2 * math.cos(math.radians(lat))
    return lat, lon, (width + height) / 4


def main():
    raions = {}
    hromadas = {}
    oblast = None
    for element in fetch():
        tags = element.get("tags", {})
        if element["type"] == "area":
            oblast = OBLASTS.get(tags.get("name"))
            continue
        name = tags.get("name", "")
        if oblast is None or "bounds" not in element:
            continue
        lat, lon, radius = centre_and_radius(element["bounds"])
        if tags.get("admin_level") == "7":
            match = HROMADA.match(name)
            if match:
                hromadas[element["id"]] = (oblast, match.group(1), lat, lon, radius)
            continue
        if not UKRAINIAN_RAION.search(name):
            continue
        names = []
        for tag in NAME_TAGS:
            for value in tags.get(tag, "").split(";"):
                value = value.strip()
                if UKRAINIAN_RAION.search(value) and value not in names:
                    names.append(value)
        raions[element["id"]] = (oblast, names, tags.get("name:en", name), lat, lon, radius)

    lines = []
    for oblast, names, name_en, lat, lon, radius in sorted(raions.values(), key=lambda r: (r[0], r[1][0])):
        lines.append(
            f"        Raion({oblast}, listOf({', '.join(map(kotlin_string, names))}), {kotlin_string(name_en)}, "
            f"{lat:.4f}, {lon:.4f}, {radius:.1f}),"
        )
    with open(OUT, "w") as f:
        f.write(f"""package com.gusak.lab.radarnebo.geo

// Generated by tools/download-raions.py from OpenStreetMap raion boundaries (admin_level=6); do not edit.
// Data © OpenStreetMap contributors, ODbL. Centres are the middle of each raion's bounding box.

/**
 * A raion (district): its alerts.in.ua oblast UID, Ukrainian names (current first, then former ones), centre and
 * radius in km (half the mean of its bounding box's width and height).
 */
class Raion(val oblastUid: Int, val names: List<String>, val nameEn: String, val lat: Double, val lon: Double, val radiusKm: Double) {{
    val point get() = GeoPoint(lat, lon)
}}

object UkraineRaions {{
    val all: List<Raion> = listOf(
{chr(10).join(lines)}
    )
}}
""")
    hromada_lines = [
        f"        Hromada({oblast}, {kotlin_string(stem)}, {lat:.4f}, {lon:.4f}, {radius:.1f}),"
        for oblast, stem, lat, lon, radius in sorted(hromadas.values(), key=lambda h: (h[0], h[1]))
    ]
    with open(OUT_HROMADAS, "w") as f:
        f.write(f"""package com.gusak.lab.radarnebo.geo

// Generated by tools/download-raions.py from OpenStreetMap hromada boundaries (admin_level=7); do not edit.
// Data © OpenStreetMap contributors, ODbL. Centres are the middle of each hromada's bounding box.

/** A hromada (community): its alerts.in.ua oblast UID, the stem of its name ("Червоногригорівська"), centre and radius in km. */
class Hromada(val oblastUid: Int, val stem: String, val lat: Double, val lon: Double, val radiusKm: Double) {{
    val point get() = GeoPoint(lat, lon)
}}

object UkraineHromadas {{
    val all: List<Hromada> = listOf(
{chr(10).join(hromada_lines)}
    )
}}
""")
    print(f"Wrote {len(raions)} raions to {os.path.relpath(OUT, ROOT)} and {len(hromadas)} hromadas to {os.path.relpath(OUT_HROMADAS, ROOT)}")


if __name__ == "__main__":
    main()
