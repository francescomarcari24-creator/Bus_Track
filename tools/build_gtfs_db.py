"""Converte il GTFS statico di Roma in un database SQLite compatto da includere nell'app.

Uso:
    python tools/build_gtfs_db.py <rome_static_gtfs.zip> app/src/main/assets/gtfs.db

Il database contiene solo cio' che serve all'app per dare un nome alle entita'
dei feed GTFS-Realtime: linee, fermate, corse e l'elenco delle linee che
servono ogni fermata, il percorso tipico di ogni linea per direzione, piu' gli orari programmati in forma compressa.

stop_times.txt (~240 MB, 5 milioni di righe) viene ridotto a "profili": molte corse
fanno la stessa sequenza di fermate con gli stessi tempi relativi, quindi ogni corsa
salva solo profilo + orario di partenza e il profilo salva le fermate con l'offset
in secondi dalla partenza. i tracciati (shapes.txt) vengono ridotti a un solo percorso per linea e direzione,
semplificato (Douglas-Peucker, ~3 m) e salvato come polilinea codificata (algoritmo Google).
"""

import csv
import hashlib
import io
import os
import sqlite3
import sys
import zipfile
from datetime import datetime, timezone


def rows(zf: zipfile.ZipFile, name: str):
    with zf.open(name) as raw:
        yield from csv.DictReader(io.TextIOWrapper(raw, encoding="utf-8-sig", newline=""))


def none_if_blank(value):
    value = (value or "").strip()
    return value or None


def parse_time(hhmmss: str) -> int:
    """GTFS ammette orari oltre le 24:00 (es. 25:10:00 = 01:10 del giorno dopo)."""
    h, m, sec = hhmmss.strip().split(":")
    return int(h) * 3600 + int(m) * 60 + int(sec)


SHAPE_TOLERANCE_M = 3.0
# Varianti del percorso mostrate sulla mappa oltre al tracciato principale: quelle usate da almeno
# il 2% delle corse della direzione (e da almeno 2 corse), al massimo 5 per direzione.
VARIANT_MIN_SHARE = 0.02
VARIANT_MIN_TRIPS = 2
VARIANT_MAX = 5


def file_md5(path: str) -> str:
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def simplify(points, tolerance_m):
    """Douglas-Peucker (iterativo) su coordinate (lat, lon), con la tolleranza in metri."""
    if len(points) < 3:
        return list(points)
    import math

    lat0 = math.radians(sum(p[0] for p in points) / len(points))
    kx, ky = 111320.0 * math.cos(lat0), 110574.0
    xy = [(p[1] * kx, p[0] * ky) for p in points]
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        a, b = stack.pop()
        ax, ay = xy[a]
        bx, by = xy[b]
        dx, dy = bx - ax, by - ay
        norm = math.hypot(dx, dy)
        far, far_d = -1, 0.0
        for i in range(a + 1, b):
            px, py = xy[i]
            d = math.hypot(px - ax, py - ay) if norm == 0 else abs(dx * (ay - py) - (ax - px) * dy) / norm
            if d > far_d:
                far, far_d = i, d
        if far_d > tolerance_m:
            keep[far] = True
            stack.append((a, far))
            stack.append((far, b))
    return [p for p, k in zip(points, keep) if k]


def encode_polyline(points):
    """Polilinea codificata di Google (precisione 5 decimali, ~1 m)."""
    out = []
    prev_lat = prev_lon = 0
    for lat, lon in points:
        ilat, ilon = round(lat * 1e5), round(lon * 1e5)
        for delta in (ilat - prev_lat, ilon - prev_lon):
            v = ~(delta << 1) if delta < 0 else delta << 1
            while v >= 0x20:
                out.append(chr((0x20 | (v & 0x1F)) + 63))
                v >>= 5
            out.append(chr(v + 63))
        prev_lat, prev_lon = ilat, ilon
    return "".join(out)


def main(zip_path: str, out_path: str) -> None:
    if os.path.exists(out_path):
        os.remove(out_path)
    os.makedirs(os.path.dirname(out_path) or ".", exist_ok=True)

    db = sqlite3.connect(out_path)
    db.executescript(
        """
        PRAGMA journal_mode = OFF;
        PRAGMA synchronous = OFF;
        CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID;
        CREATE TABLE routes (
            route_id TEXT PRIMARY KEY,
            short_name TEXT,
            long_name TEXT,
            route_type INTEGER NOT NULL,
            color TEXT,
            text_color TEXT,
            agency_name TEXT
        ) WITHOUT ROWID;
        CREATE TABLE stops (
            stop_id TEXT PRIMARY KEY,
            code TEXT,
            name TEXT NOT NULL,
            lat REAL NOT NULL,
            lon REAL NOT NULL
        ) WITHOUT ROWID;
        CREATE TABLE trips (
            trip_id TEXT PRIMARY KEY,
            route_id TEXT NOT NULL,
            headsign TEXT,
            direction_id INTEGER,
            service_id TEXT NOT NULL,
            profile_id INTEGER,
            start_sec INTEGER
        ) WITHOUT ROWID;
        CREATE TABLE profile_stops (
            stop_id TEXT NOT NULL,
            profile_id INTEGER NOT NULL,
            idx INTEGER NOT NULL,
            offset_sec INTEGER NOT NULL,
            PRIMARY KEY (stop_id, profile_id, idx)
        ) WITHOUT ROWID;
        CREATE TABLE route_patterns (
            route_id TEXT NOT NULL,
            direction_id INTEGER NOT NULL,
            headsign TEXT,
            trip_count INTEGER NOT NULL,
            stop_ids TEXT NOT NULL,
            PRIMARY KEY (route_id, direction_id)
        ) WITHOUT ROWID;
        CREATE TABLE route_shapes (
            route_id TEXT NOT NULL,
            direction_id INTEGER NOT NULL,
            shape_id TEXT NOT NULL,
            trip_count INTEGER NOT NULL,
            is_main INTEGER NOT NULL,
            polyline TEXT NOT NULL,
            PRIMARY KEY (route_id, direction_id, shape_id)
        ) WITHOUT ROWID;
        CREATE TABLE calendar_dates (
            date INTEGER NOT NULL,
            service_id TEXT NOT NULL,
            PRIMARY KEY (date, service_id)
        ) WITHOUT ROWID;
        CREATE TABLE stop_routes (
            stop_id TEXT NOT NULL,
            route_id TEXT NOT NULL,
            PRIMARY KEY (stop_id, route_id)
        ) WITHOUT ROWID;
        """
    )

    with zipfile.ZipFile(zip_path) as zf:
        agencies = {a["agency_id"]: a["agency_name"] for a in rows(zf, "agency.txt")}

        db.executemany(
            "INSERT INTO routes VALUES (?,?,?,?,?,?,?)",
            (
                (
                    r["route_id"],
                    none_if_blank(r.get("route_short_name")),
                    none_if_blank(r.get("route_long_name")),
                    int(r["route_type"]),
                    none_if_blank(r.get("route_color")),
                    none_if_blank(r.get("route_text_color")),
                    agencies.get(r.get("agency_id", "")),
                )
                for r in rows(zf, "routes.txt")
            ),
        )

        db.executemany(
            "INSERT INTO stops VALUES (?,?,?,?,?)",
            (
                (
                    s["stop_id"],
                    none_if_blank(s.get("stop_code")),
                    s["stop_name"].strip(),
                    float(s["stop_lat"]),
                    float(s["stop_lon"]),
                )
                for s in rows(zf, "stops.txt")
            ),
        )

        trip_info = {}
        for t in rows(zf, "trips.txt"):
            direction = none_if_blank(t.get("direction_id"))
            trip_info[t["trip_id"]] = (
                t["route_id"],
                none_if_blank(t.get("trip_headsign")),
                int(direction) if direction is not None else None,
                t["service_id"],
                none_if_blank(t.get("shape_id")),
            )

        # trip_id -> [(stop_sequence, stop_id, secondi dalla mezzanotte del giorno di servizio)]
        stop_times = {}
        for st in rows(zf, "stop_times.txt"):
            hhmmss = st["arrival_time"] or st["departure_time"]
            if not hhmmss:
                continue
            stop_times.setdefault(st["trip_id"], []).append(
                (int(st["stop_sequence"]), st["stop_id"], parse_time(hhmmss))
            )

        profiles = {}
        trip_rows = []
        pairs = set()
        # (route, direzione) -> {sequenza fermate: [numero corse, {capolinea: conteggio}, {shape_id: conteggio}]}
        patterns = {}
        wanted_shapes = {}
        # (route, direzione) -> {shape_id: numero di corse}, su tutte le corse (per le varianti)
        shape_trips = {}
        for trip_id, (route_id, headsign, direction, service_id, shape_id) in trip_info.items():
            times = sorted(stop_times.pop(trip_id, []))
            profile_id = start = None
            if times:
                start = times[0][2]
                key = tuple((stop_id, sec - start) for _, stop_id, sec in times)
                profile_id = profiles.setdefault(key, len(profiles))
                pairs.update((stop_id, route_id) for _, stop_id, _ in times)
                stops_seq = tuple(stop_id for _, stop_id, _ in times)
                entry = patterns.setdefault((route_id, direction or 0), {}).setdefault(stops_seq, [0, {}, {}])
                entry[0] += 1
                entry[1][headsign] = entry[1].get(headsign, 0) + 1
                if shape_id:
                    entry[2][shape_id] = entry[2].get(shape_id, 0) + 1
                    counts = shape_trips.setdefault((route_id, direction or 0), {})
                    counts[shape_id] = counts.get(shape_id, 0) + 1
            trip_rows.append((trip_id, route_id, headsign, direction, service_id, profile_id, start))
        db.executemany("INSERT INTO trips VALUES (?,?,?,?,?,?,?)", trip_rows)
        db.executemany(
            "INSERT OR IGNORE INTO profile_stops VALUES (?,?,?,?)",
            (
                (stop_id, profile_id, idx, offset)
                for key, profile_id in profiles.items()
                for idx, (stop_id, offset) in enumerate(key)
            ),
        )
        db.executemany("INSERT INTO stop_routes VALUES (?,?)", sorted(pairs))

        # Per ogni linea e direzione si tiene il percorso piu' frequente (le varianti
        # brevi o deviate sono minoritarie) e il suo capolinea piu' comune.
        pattern_rows = []
        for (route_id, direction), seqs in patterns.items():
            seq, (count, headsigns, shape_counts) = max(seqs.items(), key=lambda kv: kv[1][0])
            headsign = max(headsigns.items(), key=lambda kv: kv[1])[0]
            total = sum(c[0] for c in seqs.values())
            pattern_rows.append((route_id, direction, headsign, total, ",".join(seq)))
            # Tracciato: quello usato piu' spesso dalle corse di questo percorso.
            if shape_counts:
                wanted_shapes[(route_id, direction)] = max(shape_counts.items(), key=lambda kv: kv[1])[0]
        db.executemany("INSERT INTO route_patterns VALUES (?,?,?,?,?)", pattern_rows)

        # Tracciati da salvare: il principale di ogni (linea, direzione) piu' le varianti frequenti.
        shapes_to_save = {}  # (route, direzione) -> [(shape_id, corse, principale)]
        for key, counts in shape_trips.items():
            main = wanted_shapes.get(key)
            total = sum(counts.values())
            variants = sorted(
                (sid for sid, n in counts.items()
                 if sid != main and n >= VARIANT_MIN_TRIPS and n >= VARIANT_MIN_SHARE * total),
                key=lambda sid: -counts[sid],
            )[:VARIANT_MAX]
            chosen = ([main] if main else []) + variants
            shapes_to_save[key] = [(sid, counts.get(sid, 0), sid == main) for sid in chosen]

        # Legge shapes.txt tenendo solo i tracciati scelti.
        needed = {sid for lst in shapes_to_save.values() for sid, _, _ in lst}
        raw_shapes = {}
        for sp in rows(zf, "shapes.txt"):
            if sp["shape_id"] in needed:
                raw_shapes.setdefault(sp["shape_id"], []).append(
                    (int(sp["shape_pt_sequence"]), float(sp["shape_pt_lat"]), float(sp["shape_pt_lon"]))
                )
        shape_rows = []
        points_before = points_after = 0
        for (route_id, direction), lst in shapes_to_save.items():
            for shape_id, trip_count, is_main in lst:
                pts = [(lat, lon) for _, lat, lon in sorted(raw_shapes.get(shape_id, []))]
                if len(pts) < 2:
                    continue
                simple = simplify(pts, SHAPE_TOLERANCE_M)
                points_before += len(pts)
                points_after += len(simple)
                shape_rows.append((route_id, direction, shape_id, trip_count, 1 if is_main else 0, encode_polyline(simple)))
        db.executemany("INSERT INTO route_shapes VALUES (?,?,?,?,?,?)", shape_rows)
        print(f"Tracciati: {len(shape_rows)} ({points_before} -> {points_after} punti)")

        db.executemany(
            "INSERT OR IGNORE INTO calendar_dates VALUES (?,?)",
            (
                (int(d["date"]), d["service_id"])
                for d in rows(zf, "calendar_dates.txt")
                if d["exception_type"] == "1"
            ),
        )
        dates = [d["date"] for d in rows(zf, "calendar_dates.txt")]

    db.executemany(
        "INSERT INTO meta VALUES (?,?)",
        [
            ("schema_version", "5"),
            ("feed_start_date", min(dates) if dates else ""),
            ("feed_end_date", max(dates) if dates else ""),
            ("built_at", datetime.now(timezone.utc).isoformat(timespec="seconds")),
            # MD5 dello zip di origine: l'app lo confronta con rome_static_gtfs.zip.md5 per
            # capire se Roma Mobilita' ha pubblicato un GTFS nuovo da scaricare.
            ("source_md5", file_md5(zip_path)),
        ],
    )
    db.execute("CREATE INDEX stops_name ON stops(name COLLATE NOCASE)")
    db.execute("CREATE INDEX trips_profile ON trips(profile_id)")
    db.commit()
    db.execute("VACUUM")
    db.close()

    print(f"Creato {out_path} ({os.path.getsize(out_path) / 1e6:.1f} MB)")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
