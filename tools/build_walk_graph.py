"""Costruisce il grafo pedonale di Roma (OpenStreetMap) per la navigazione a piedi dell'app.

Uso (serve la libreria osmium: pip install osmium):
    python tools/build_walk_graph.py <estratto.osm.pbf> app/src/main/assets/gtfs.db app/src/main/assets/walk.db

- Strade: quelle percorribili a piedi nell'area delle fermate (piu' un margine), escluse
  autostrade/superstrade e i tratti vietati ai pedoni.
- Grafo compatto: i nodi sono solo gli incroci (e i capi delle strade); ogni arco tra due incroci
  conserva nome, tipo, lunghezza e forma (polilinea codificata), per le indicazioni strada per
  strada e per disegnarlo sulla mappa.
- Si tiene solo la parte principale della rete (i pezzi isolati catturerebbero le fermate).
- footpaths: distanza a piedi lungo le strade tra fermate entro FOOTPATH_MAX_M (cambi di RAPTOR).
"""

import heapq
import math
import os
import sqlite3
import sys
import time
from collections import defaultdict

import osmium

FOOTPATH_MAX_M = 500.0
MARGIN_DEG = 0.012  # ~1 km oltre la fermata piu' esterna

WALKABLE = {
    "footway", "pedestrian", "path", "steps", "living_street", "residential", "unclassified",
    "service", "tertiary", "tertiary_link", "secondary", "secondary_link", "primary", "primary_link",
    "track", "cycleway", "corridor", "bridleway", "road",
}
# Tipi per le indicazioni (0 = strada normale).
KIND = {"steps": 1, "footway": 2, "pedestrian": 3, "path": 4, "track": 4, "cycleway": 5, "corridor": 6}


def haversine(lat1, lon1, lat2, lon2):
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def encode_polyline(points):
    out = []
    plat = plon = 0
    for lat, lon in points:
        ilat, ilon = round(lat * 1e5), round(lon * 1e5)
        for d in (ilat - plat, ilon - plon):
            v = ~(d << 1) if d < 0 else d << 1
            while v >= 0x20:
                out.append(chr((0x20 | (v & 0x1F)) + 63))
                v >>= 5
            out.append(chr(v + 63))
        plat, plon = ilat, ilon
    return "".join(out)


def walkable(tags):
    hw = tags.get("highway")
    if hw is None:
        return False
    foot = tags.get("foot")
    if foot in ("no", "private"):
        return False
    if hw in ("motorway", "motorway_link", "trunk", "trunk_link"):
        return foot in ("yes", "designated")
    if hw not in WALKABLE:
        return False
    if tags.get("access") in ("no", "private") and foot not in ("yes", "designated", "permissive"):
        return False
    if tags.get("area") == "yes" and hw != "pedestrian":
        return False
    return True


class WayCollector(osmium.SimpleHandler):
    def __init__(self, bbox):
        super().__init__()
        self.min_lat, self.max_lat, self.min_lon, self.max_lon = bbox
        self.ways = []  # (node_ids, coords, name, kind)

    def way(self, w):
        if not walkable(w.tags):
            return
        try:
            nodes = [(n.ref, n.location.lat, n.location.lon) for n in w.nodes]
        except osmium.InvalidLocationError:
            return
        if len(nodes) < 2:
            return
        if not any(self.min_lat <= la <= self.max_lat and self.min_lon <= lo <= self.max_lon for _, la, lo in nodes):
            return
        tags = w.tags
        name = tags.get("name") or tags.get("ref") or ""
        self.ways.append(([n[0] for n in nodes], [(n[1], n[2]) for n in nodes], name, KIND.get(tags.get("highway"), 0)))


def main(pbf, gtfs_db, out_path):
    t0 = time.time()
    g = sqlite3.connect(gtfs_db)
    stops = g.execute("SELECT stop_id, lat, lon FROM stops").fetchall()
    g.close()
    bbox = (min(s[1] for s in stops) - MARGIN_DEG, max(s[1] for s in stops) + MARGIN_DEG,
            min(s[2] for s in stops) - MARGIN_DEG, max(s[2] for s in stops) + MARGIN_DEG)

    h = WayCollector(bbox)
    h.apply_file(pbf, locations=True, idx="flex_mem")
    print(f"Strade percorribili: {len(h.ways)} ({time.time() - t0:.0f} s)")

    # Incroci: nodi usati da piu' strade o capi di strada.
    use = defaultdict(int)
    coord = {}
    for ids, cs, _, _ in h.ways:
        for i, (nid, c) in enumerate(zip(ids, cs)):
            use[nid] += 1 if 0 < i < len(ids) - 1 else 2
            coord[nid] = c
    junction = {nid for nid, u in use.items() if u >= 2}

    # Archi tra incroci consecutivi, con la forma.
    names = {}
    edges = []  # (a_osm, b_osm, length, name_id, kind, points)
    for ids, cs, name, kind in h.ways:
        nid = names.setdefault(name, len(names))
        start = 0
        for i in range(1, len(ids)):
            if ids[i] in junction or i == len(ids) - 1:
                pts = cs[start:i + 1]
                length = sum(haversine(*pts[k], *pts[k + 1]) for k in range(len(pts) - 1))
                if ids[start] != ids[i] and length > 0:
                    edges.append((ids[start], ids[i], length, nid, kind, pts))
                start = i
    print(f"Archi tra incroci: {len(edges)}")

    # Componente connessa principale.
    adj = defaultdict(list)
    for e in edges:
        adj[e[0]].append(e[1])
        adj[e[1]].append(e[0])
    seen, best = set(), set()
    for s in adj:
        if s in seen:
            continue
        comp, stack = {s}, [s]
        seen.add(s)
        while stack:
            x = stack.pop()
            for y in adj[x]:
                if y not in seen:
                    seen.add(y)
                    comp.add(y)
                    stack.append(y)
        if len(comp) > len(best):
            best = comp
    edges = [e for e in edges if e[0] in best]
    node_ids = sorted({e[0] for e in edges} | {e[1] for e in edges})
    index = {nid: i for i, nid in enumerate(node_ids)}
    print(f"Rete principale: {len(node_ids)} incroci, {len(edges)} archi")

    # Aggancio delle fermate al punto della rete piu' vicino (vertici della forma degli archi).
    cell = 0.002
    grid = defaultdict(list)
    for ei, e in enumerate(edges):
        for k, (la, lo) in enumerate(e[5]):
            grid[(int(la / cell), int(lo / cell))].append((ei, k))

    def snap(lat, lon):
        best_d, best_ref = 1e18, None
        gx, gy = int(lat / cell), int(lon / cell)
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for ei, k in grid.get((gx + dx, gy + dy), ()):
                    la, lo = edges[ei][5][k]
                    d = haversine(lat, lon, la, lo)
                    if d < best_d:
                        best_d, best_ref = d, (ei, k)
        return best_d, best_ref

    # Grafo per Dijkstra: incroci + distanze lungo l'arco fino al vertice di aggancio.
    nbr = defaultdict(list)
    for ei, e in enumerate(edges):
        a, b = index[e[0]], index[e[1]]
        nbr[a].append((b, e[2]))
        nbr[b].append((a, e[2]))

    def partial(e, k):
        pts = e[5]
        d_from_a = sum(haversine(*pts[j], *pts[j + 1]) for j in range(k))
        return d_from_a, e[2] - d_from_a

    stop_snap = {}
    for sid, la, lo in stops:
        d, ref = snap(la, lo)
        if ref is not None and d <= 300:
            stop_snap[sid] = (d, ref)
    print(f"Fermate agganciate alla rete: {len(stop_snap)} / {len(stops)}")

    # Fermate per arco, per riconoscerle durante la visita.
    stops_on_node = defaultdict(list)  # incrocio -> [(stop, distanza dall'incrocio + aggancio)]
    for sid, (d, (ei, k)) in stop_snap.items():
        da, db = partial(edges[ei], k)
        stops_on_node[index[edges[ei][0]]].append((sid, da + d))
        stops_on_node[index[edges[ei][1]]].append((sid, db + d))

    footpaths = []
    t1 = time.time()
    for n, (sid, (d0, (ei, k))) in enumerate(stop_snap.items()):
        da, db = partial(edges[ei], k)
        a, b = index[edges[ei][0]], index[edges[ei][1]]
        dist = {a: d0 + da, b: d0 + db}
        pq = [(d0 + da, a), (d0 + db, b)]
        heapq.heapify(pq)
        found = {}
        while pq:
            dcur, x = heapq.heappop(pq)
            if dcur > dist.get(x, 1e18) or dcur > FOOTPATH_MAX_M:
                continue
            for other, dd in stops_on_node.get(x, ()):
                if other != sid:
                    tot = dcur + dd
                    if tot <= FOOTPATH_MAX_M and tot < found.get(other, 1e18):
                        found[other] = tot
            for y, w in nbr[x]:
                nd = dcur + w
                if nd < dist.get(y, 1e18) and nd <= FOOTPATH_MAX_M:
                    dist[y] = nd
                    heapq.heappush(pq, (nd, y))
        for other, tot in found.items():
            footpaths.append((sid, other, round(tot)))
        if n % 2000 == 0:
            print(f"  cambi a piedi: {n}/{len(stop_snap)} fermate ({time.time() - t1:.0f} s)")
    print(f"Cambi a piedi tra fermate: {len(footpaths)}")

    if os.path.exists(out_path):
        os.remove(out_path)
    db = sqlite3.connect(out_path)
    db.executescript("""
        PRAGMA journal_mode = OFF;
        CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID;
        CREATE TABLE nodes (id INTEGER PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL);
        CREATE TABLE names (id INTEGER PRIMARY KEY, name TEXT NOT NULL);
        CREATE TABLE edges (id INTEGER PRIMARY KEY, a INTEGER NOT NULL, b INTEGER NOT NULL,
            length REAL NOT NULL, name_id INTEGER NOT NULL, kind INTEGER NOT NULL, geom TEXT NOT NULL);
        CREATE TABLE footpaths (from_stop TEXT NOT NULL, to_stop TEXT NOT NULL, meters INTEGER NOT NULL,
            PRIMARY KEY (from_stop, to_stop)) WITHOUT ROWID;
        CREATE TABLE stop_nodes (stop_id TEXT PRIMARY KEY, node INTEGER NOT NULL, gap_m REAL NOT NULL) WITHOUT ROWID;
    """)
    db.executemany("INSERT INTO nodes VALUES (?,?,?)", ((index[n], *coord[n]) for n in node_ids))
    db.executemany("INSERT INTO names VALUES (?,?)", ((i, n) for n, i in names.items()))
    db.executemany(
        "INSERT INTO edges VALUES (?,?,?,?,?,?,?)",
        ((i, index[e[0]], index[e[1]], round(e[2], 1), e[3], e[4], encode_polyline(e[5])) for i, e in enumerate(edges)),
    )
    db.executemany("INSERT INTO footpaths VALUES (?,?,?)", footpaths)
    # Incrocio della rete piu' vicino a ogni fermata (aggancio pronto per l'app, niente calcolo sul telefono).
    ngrid = defaultdict(list)
    for nid in node_ids:
        la, lo = coord[nid]
        ngrid[(int(la / cell), int(lo / cell))].append(nid)
    stop_nodes = []
    for sid, la, lo in stops:
        gx, gy = int(la / cell), int(lo / cell)
        best_d, best_n = 1e18, None
        for dx in (-2, -1, 0, 1, 2):
            for dy in (-2, -1, 0, 1, 2):
                for nid in ngrid.get((gx + dx, gy + dy), ()):
                    d = haversine(la, lo, *coord[nid])
                    if d < best_d:
                        best_d, best_n = d, nid
        if best_n is not None and best_d <= 400:
            stop_nodes.append((sid, index[best_n], round(best_d, 1)))
    db.executemany("INSERT INTO stop_nodes VALUES (?,?,?)", stop_nodes)
    print(f"Fermate con incrocio vicino: {len(stop_nodes)}")
    db.executemany("INSERT INTO meta VALUES (?,?)", [
        ("schema_version", "2"),
        ("source", os.path.basename(pbf)),
        ("footpath_max_m", str(int(FOOTPATH_MAX_M))),
    ])
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(f"Creato {out_path} ({os.path.getsize(out_path) / 1e6:.1f} MB) in {time.time() - t0:.0f} s")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2], sys.argv[3])
