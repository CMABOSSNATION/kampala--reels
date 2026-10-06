#!/usr/bin/env python3
"""Generates the bundled low-poly Kampala sets (set.glb + env.json) with no dependencies.
Usage: python3 tools/gen_set.py [assets/environments dir]"""
import json, os, random, struct, sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/environments"
FACES = [((0,0,1),[(-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)]), ((0,0,-1),[(1,-1,-1),(-1,-1,-1),(-1,1,-1),(1,1,-1)]),
         ((1,0,0),[(1,-1,1),(1,-1,-1),(1,1,-1),(1,1,1)]), ((-1,0,0),[(-1,-1,-1),(-1,-1,1),(-1,1,1),(-1,1,-1)]),
         ((0,1,0),[(-1,1,1),(1,1,1),(1,1,-1),(-1,1,-1)]), ((0,-1,0),[(-1,-1,-1),(1,-1,-1),(1,-1,1),(-1,-1,1)])]

def lin(h):
    h = h.lstrip("#"); return [round((int(h[i:i+2], 16) / 255) ** 2.2, 4) for i in (0, 2, 4)] + [1.0]

class S:
    def __init__(s): s.mats, s.geo = {}, {}
    def box(s, m, cx, y, cz, sx, sy, sz):          # y = bottom of box
        pos, nor, idx = s.geo.setdefault(m, ([], [], []))
        for n, cs in FACES:
            b = len(pos)
            for c in cs:
                pos.append((cx + c[0]*sx/2, y + (c[1]+1)*sy/2, cz + c[2]*sz/2)); nor.append(n)
            idx.extend([b, b+1, b+2, b, b+2, b+3])
    def save(s, path):
        names = list(s.geo); binb = bytearray(); views, accs, prims = [], [], []
        def add(data, target):
            off = len(binb); binb.extend(data)
            while len(binb) % 4: binb.append(0)
            views.append({"buffer": 0, "byteOffset": off, "byteLength": len(data), "target": target}); return len(views)-1
        def acc(view, ctype, count, typ, **kw):
            accs.append(dict(bufferView=view, componentType=ctype, count=count, type=typ, **kw)); return len(accs)-1
        for mi, name in enumerate(names):
            pos, nor, idx = s.geo[name]
            flat = [c for p in pos for c in p]
            a = acc(add(struct.pack(f"<{len(flat)}f", *flat), 34962), 5126, len(pos), "VEC3",
                    min=[min(p[i] for p in pos) for i in range(3)], max=[max(p[i] for p in pos) for i in range(3)])
            nf = [c for p in nor for c in p]
            n = acc(add(struct.pack(f"<{len(nf)}f", *nf), 34962), 5126, len(nor), "VEC3")
            i = acc(add(struct.pack(f"<{len(idx)}H", *idx), 34963), 5123, len(idx), "SCALAR")
            prims.append({"attributes": {"POSITION": a, "NORMAL": n}, "indices": i, "material": mi})
        mats = [{"name": n, "pbrMetallicRoughness": {"baseColorFactor": s.mats[n], "metallicFactor": 0.0, "roughnessFactor": 0.9}} for n in names]
        j = {"asset": {"version": "2.0", "generator": "kampala-reels gen_set.py"}, "scene": 0, "scenes": [{"nodes": [0]}],
             "nodes": [{"mesh": 0}], "meshes": [{"primitives": prims}], "materials": mats,
             "buffers": [{"byteLength": len(binb)}], "bufferViews": views, "accessors": accs}
        js = json.dumps(j, separators=(",", ":")).encode()
        while len(js) % 4: js += b" "
        total = 12 + 8 + len(js) + 8 + len(binb)
        with open(path, "wb") as f:
            f.write(struct.pack("<III", 0x46546C67, 2, total))
            f.write(struct.pack("<II", len(js), 0x4E4F534A) + js)
            f.write(struct.pack("<II", len(binb), 0x004E4942) + bytes(binb))

def base(s, ground, road_w=8.0, roads=True):
    s.mats.update(ground=lin(ground), asphalt=lin("#2b2d31"), sidewalk=lin("#9a9a94"), line=lin("#e9e5d2"), pole=lin("#4a4d52"))
    s.box("ground", 0, -0.11, -60, 240, 0.1, 240)
    if roads:
        s.box("asphalt", 0, -0.06, -60, road_w, 0.06, 240)
        for z in range(10, -150, -4): s.box("line", 0, 0, z, 0.15, 0.012, 1.8)

def matatu(s, x, z, r):
    s.mats.update(mwhite=lin("#f2f2f0"), mblue=lin("#1b4fb0"), glass=lin("#1c2530"), tyre=lin("#111111"))
    s.box("mwhite", x, 0.35, z, 1.8, 1.5, 4.3); s.box("mblue", x, 0.85, z, 1.84, 0.3, 4.34)
    s.box("glass", x, 1.3, z, 1.82, 0.5, 3.6)
    for dx in (-0.85, 0.85):
        for dz in (-1.4, 1.4): s.box("tyre", x+dx, 0.0, z+dz, 0.2, 0.6, 0.6)

def boda(s, x, z):
    s.mats.update(bred=lin("#b3261e"), rider=lin("#e0a030"), tyre=lin("#111111"))
    s.box("bred", x, 0.3, z, 0.35, 0.5, 1.7); s.box("rider", x, 0.8, z, 0.4, 0.8, 0.4)
    s.box("tyre", x, 0.0, z-0.7, 0.12, 0.5, 0.5); s.box("tyre", x, 0.0, z+0.7, 0.12, 0.5, 0.5)

def row_buildings(s, r, x0, zmin, hmin, hmax, pal, sign=True):
    for i, c in enumerate(pal): s.mats[f"b{i}"] = lin(c)
    s.mats.update(sg0=lin("#e63946"), sg1=lin("#f4c430"), sg2=lin("#2a9d8f"), sg3=lin("#ff7f11"))
    for side in (-1, 1):
        z = 6.0
        while z > zmin:
            d, w, h = r.uniform(6, 11), r.uniform(7, 12), r.uniform(hmin, hmax)
            s.box(f"b{r.randrange(len(pal))}", side*(x0 + w/2), 0, z - d/2, w, h, d)
            if sign: s.box(f"sg{r.randrange(4)}", side*(x0 - 0.15), 3.0, z - d/2, 0.3, 0.9, d*0.7)
            z -= d + r.uniform(0, 1.5)

def street(r):
    s = S(); base(s, "#4c5a3c"); s.mats["sidewalk"] = lin("#9a9a94")
    for side in (-1, 1): s.box("sidewalk", side*5, -0.06, -60, 2.2, 0.16, 240)
    row_buildings(s, r, 6.2, -150, 5, 20, ["#c9b79c", "#d9cfc1", "#b86b4b", "#8fa3ad", "#e4d5a1"])
    for _ in range(7): matatu(s, r.choice((-2.2, 2.2)), -r.uniform(6, 80), r)
    for _ in range(9): boda(s, r.choice((-3.2, -1.1, 1.1, 3.2)), -r.uniform(4, 70))
    for z in range(0, -150, -15):
        for side in (-1, 1): s.box("pole", side*4.4, 0, z, 0.15, 6, 0.15)
    return s, {"sky": [0.55, 0.75, 0.95], "ambient": [0.85, 0.9, 1.0]}

def nakasero(r):
    s = S(); base(s, "#3f6b2f", road_w=6.0)
    s.mats.update(villa=lin("#efe4cf"), roof=lin("#8c2f23"), wall=lin("#d8cdb8"), trunk=lin("#5a3b22"), leaf=lin("#2f7a34"), leaf2=lin("#3f9142"))
    for side in (-1, 1):
        s.box("wall", side*5, 0, -60, 0.3, 1.8, 240)
        z = 2.0
        while z > -140:
            s.box("villa", side*(14 + r.uniform(0, 4)), 0, z, 10, 5, 9); s.box("roof", side*(14 + r.uniform(0, 0.1)), 5, z, 11, 1.2, 10)
            z -= r.uniform(18, 26)
    for _ in range(90):
        x, z = r.choice((-1, 1))*r.uniform(6.5, 40), -r.uniform(-5, 140)
        h = r.uniform(2, 3.5); s.box("trunk", x, 0, z, 0.4, h, 0.4)
        s.box("leaf", x, h, z, 3.2, 2.8, 3.2); s.box("leaf2", x, h+2.8, z, 2.0, 2.0, 2.0)
    for _ in range(3): matatu(s, r.choice((-1.4, 1.4)), -r.uniform(10, 60), r)
    return s, {"sky": [0.6, 0.8, 0.96], "ambient": [0.9, 0.95, 1.0]}

def busega(r):
    s = S(); base(s, "#6b5b43", road_w=9.0)
    s.box("asphalt", 0, -0.06, -14, 240, 0.06, 8.0)                       # cross road
    s.mats.update(st0=lin("#e63946"), st1=lin("#f4c430"), st2=lin("#2a9d8f"), st3=lin("#3a86ff"), st4=lin("#ff7f11"), spole=lin("#5a4632"))
    for side in (-1, 1):
        s.box("sidewalk", side*6.2, -0.06, -60, 2.4, 0.16, 240)
        for i in range(14):
            x, z = side*r.uniform(8.5, 10.5), -r.uniform(0, 100)
            if abs(z + 14) < 6: continue
            for dx in (-1.1, 1.1):
                for dz in (-1.1, 1.1): s.box("spole", x+dx, 0, z+dz, 0.1, 2.2, 0.1)
            s.box(f"st{r.randrange(5)}", x, 2.2, z, 2.8, 0.15, 2.8)
    row_buildings(s, r, 12.5, -140, 4, 12, ["#c9b79c", "#a5654a", "#d4c08a", "#7e8f99"], sign=False)
    for _ in range(10): matatu(s, r.choice((-2.6, 2.6)), -r.uniform(6, 90), r)
    for _ in range(14): boda(s, r.choice((-3.6, -1.2, 1.2, 3.6)), -r.uniform(4, 80))
    return s, {"sky": [0.96, 0.7, 0.45], "ambient": [1.0, 0.82, 0.65]}

for name, fn in (("kampala_street", street), ("nakasero", nakasero), ("busega_junction", busega)):
    d = os.path.join(ROOT, name); os.makedirs(d, exist_ok=True)
    sc, cfg = fn(random.Random(name)); sc.save(os.path.join(d, "set.glb"))
    json.dump(cfg, open(os.path.join(d, "env.json"), "w"))
    print(name, os.path.getsize(os.path.join(d, "set.glb")) // 1024, "KB")
