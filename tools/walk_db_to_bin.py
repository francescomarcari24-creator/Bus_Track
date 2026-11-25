"""Converte il grafo pedonale (walk.db, da build_walk_graph.py) nel file binario compatto che l'app
legge direttamente dall'APK, senza copiarlo (walk.bin, non compresso nell'APK e mappato in memoria).

Uso:
    python tools/build_walk_graph.py <estratto.osm.pbf> app/src/main/assets/gtfs.db <tmp>/walk.db
    python tools/walk_db_to_bin.py <tmp>/walk.db app/src/main/assets/walk.bin

Formato (little endian, letto da routing/WalkData.kt):
    "BTW1", versione int32
    nodeCount, edgeCount, nameCount, stopCount, footCount   (int32)
    lat int32[n], lon int32[n]                (gradi * 1e6)
    a int32[E], b int32[E], length float32[E], name uint16[E], kind uint8[E]
    geomStart int32[E+1]                      (offset nel blocco delle forme)
    nomi: nameCount x (uint16 lunghezza + UTF-8)
    fermate: stopCount x (uint16 lunghezza + UTF-8 dello stop_id)
    stopNode int32[S] (-1 = nessuno), stopGap float32[S]
    footFrom int32[F], footTo int32[F] (indici delle fermate), footMeters uint16[F]
    forme: polilinee codificate (ASCII) una dopo l'altra
"""
import sqlite3
import struct
import sys
from array import array


def main(db_path, out_path):
    db = sqlite3.connect(db_path)
    nodes = db.execute("SELECT id, lat, lon FROM nodes ORDER BY id").fetchall()
    assert all(r[0] == i for i, r in enumerate(nodes)), "id dei nodi non consecutivi"
    edges = db.execute("SELECT id, a, b, length, name_id, kind, geom FROM edges ORDER BY id").fetchall()
    assert all(r[0] == i for i, r in enumerate(edges)), "id dei tratti non consecutivi"
    name_count = db.execute("SELECT MAX(id) + 1 FROM names").fetchone()[0]
    names = [""] * name_count
    for i, n in db.execute("SELECT id, name FROM names"):
        names[i] = n or ""
    assert name_count < 65536
    stop_nodes = db.execute("SELECT stop_id, node, gap_m FROM stop_nodes").fetchall()
    foot = db.execute("SELECT from_stop, to_stop, meters FROM footpaths").fetchall()

    stops = sorted({s for s, _, _ in stop_nodes} | {f for f, _, _ in foot} | {t for _, t, _ in foot})
    stop_index = {s: i for i, s in enumerate(stops)}
    node_of = [-1] * len(stops)
    gap_of = [0.0] * len(stops)
    for s, n, g in stop_nodes:
        node_of[stop_index[s]] = n
        gap_of[stop_index[s]] = g

    geoms = [e[6].encode("ascii") for e in edges]
    geom_start = array("i", [0])
    for g in geoms:
        geom_start.append(geom_start[-1] + len(g))

    def ints(values, code="i"):
        a = array(code, values)
        if sys.byteorder != "little":
            a.byteswap()
        return a.tobytes()

    def strings(values):
        out = bytearray()
        for v in values:
            b = v.encode("utf-8")
            out += struct.pack("<H", len(b)) + b
        return bytes(out)

    with open(out_path, "wb") as f:
        f.write(b"BTW1")
        f.write(struct.pack("<6i", 1, len(nodes), len(edges), name_count, len(stops), len(foot)))
        f.write(ints([round(r[1] * 1e6) for r in nodes]))
        f.write(ints([round(r[2] * 1e6) for r in nodes]))
        f.write(ints([e[1] for e in edges]))
        f.write(ints([e[2] for e in edges]))
        f.write(ints([e[3] for e in edges], "f"))
        f.write(ints([e[4] for e in edges], "H"))
        f.write(ints([e[5] for e in edges], "B"))
        f.write(geom_start.tobytes() if sys.byteorder == "little" else ints(geom_start))
        f.write(strings(names))
        f.write(strings(stops))
        f.write(ints(node_of))
        f.write(ints(gap_of, "f"))
        f.write(ints([stop_index[x] for x, _, _ in foot]))
        f.write(ints([stop_index[y] for _, y, _ in foot]))
        f.write(ints([min(int(m), 65535) for _, _, m in foot], "H"))
        for g in geoms:
            f.write(g)
    print(f"{out_path}: {len(nodes)} incroci, {len(edges)} tratti, {len(stops)} fermate, {len(foot)} cambi a piedi")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
