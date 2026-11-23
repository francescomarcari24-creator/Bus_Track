"""Converte il GTFS statico di Roma in un database SQLite compatto da includere nell'app.

Uso:
    python tools/build_gtfs_db.py <rome_static_gtfs.zip> app/src/main/assets/gtfs.db

Il database contiene solo cio' che serve all'app per dare un nome alle entita'
dei feed GTFS-Realtime: linee, fermate, corse e l'elenco delle linee che
servono ogni fermata, il percorso tipico di ogni linea per direzione, piu' gli orari programmati in forma compressa.

stop_times.txt (~240 MB, 5 milioni di righe) viene ridotto a "profili": molte corse
fanno la stessa sequenza di fermate con gli stessi tempi relativi, quindi ogni corsa
salva solo profilo + orario di partenza e il profilo salva le fermate con l'offset
in secondi dalla partenza. shapes.txt non viene copiato.
"""

import csv
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
        # (route, direzione) -> {sequenza fermate: [numero corse, {capolinea: conteggio}]}
        patterns = {}
        for trip_id, (route_id, headsign, direction, service_id) in trip_info.items():
            times = sorted(stop_times.pop(trip_id, []))
            profile_id = start = None
            if times:
                start = times[0][2]
                key = tuple((stop_id, sec - start) for _, stop_id, sec in times)
                profile_id = profiles.setdefault(key, len(profiles))
                pairs.update((stop_id, route_id) for _, stop_id, _ in times)
                stops_seq = tuple(stop_id for _, stop_id, _ in times)
                entry = patterns.setdefault((route_id, direction or 0), {}).setdefault(stops_seq, [0, {}])
                entry[0] += 1
                entry[1][headsign] = entry[1].get(headsign, 0) + 1
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
            seq, (count, headsigns) = max(seqs.items(), key=lambda kv: kv[1][0])
            headsign = max(headsigns.items(), key=lambda kv: kv[1])[0]
            total = sum(c for c, _ in seqs.values())
            pattern_rows.append((route_id, direction, headsign, total, ",".join(seq)))
        db.executemany("INSERT INTO route_patterns VALUES (?,?,?,?,?)", pattern_rows)

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
            ("schema_version", "3"),
            ("feed_start_date", min(dates) if dates else ""),
            ("feed_end_date", max(dates) if dates else ""),
            ("built_at", datetime.now(timezone.utc).isoformat(timespec="seconds")),
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
