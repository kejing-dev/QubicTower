"""Builds every model of the Qubic Tower volume as GLB, in Python + Pillow (no Blender on this machine).

  python3 tools/build_models.py [--test-f]      # --test-f: "F↑" on every turntable label spot (D10 orientation check)

Writes app/src/main/assets/models/. Numbers match domain/Layout.kt (design-package spatialLayout); scene = design
coordinates (origin at the volume centre, +X right, +Y up, +Z toward the user); tower-local = the tilted frame
(base bottom y −0.14, turntable top −0.10).
  plinth.glb     plinth 0.54 x 0.04 x 0.30 (top −0.17) + the wedge under the tilted base (scene coords)
  trays.glb      two shallow trays 0.11 x 0.02 x 0.22 at x ±0.205, z −0.03 (scene coords)
  traypegs.glb   16 short pegs Ø1 cm, y −0.15…−0.02 (scene coords; the app gives them the frosted material)
  base.glb       base box 0.26 x 0.03 x 0.26 (tower-local), engraved 4 x 4 grid, corner marks, a front triangle
  turntable.glb  disc Ø0.27 x 1 cm (tower-local, turned by the app), rim labels A–D / 1–4 on all four sides
  peg.glb        one peg Ø1 cm x 15 cm, origin at its axis (tower-local y −0.10…+0.05)
  bead.glb       flattened bead Ø5 x 3.2 cm with a Ø1.2 cm hole, origin at its centre (≈1 k triangles)
Materials: graphite PBR with a fine stone / brushed grain texture, OPAQUE (profile D24 / B12); beads and pegs get
their materials in the app (amber lacquer / celadon glaze / frosted glass).
"""
import io
import json
import math
import os
import random
import struct
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "models")
FONT = os.path.join(ROOT, "app", "src", "main", "res", "font", "noto_sans_bold.ttf")
TEST_F = "--test-f" in sys.argv

GRAPHITE = (0x2B, 0x2F, 0x36)
GRAPHITE_LIGHT = (0x3A, 0x3F, 0x48)
INK_LABEL = (0xE9, 0xE4, 0xD8)
AMBER = (0xE8, 0x87, 0x3A)
CELADON = (0x2F, 0xA3, 0x9A)

PITCH = 0.06
TABLE_R = 0.135


def srgb_lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


class Glb:
    def __init__(self):
        self.bin = bytearray()
        self.views, self.accessors = [], []

    def _view(self, data, target=None):
        while len(self.bin) % 4:
            self.bin.append(0)
        off = len(self.bin)
        self.bin += data
        v = {"buffer": 0, "byteOffset": off, "byteLength": len(data)}
        if target:
            v["target"] = target
        self.views.append(v)
        return len(self.views) - 1

    def floats(self, items, n, minmax=False):
        flat = [c for it in items for c in it]
        view = self._view(struct.pack("<%df" % len(flat), *flat), 34962)
        acc = {"bufferView": view, "componentType": 5126, "count": len(items), "type": {2: "VEC2", 3: "VEC3"}[n]}
        if minmax:
            acc["min"] = [min(it[k] for it in items) for k in range(n)]
            acc["max"] = [max(it[k] for it in items) for k in range(n)]
        self.accessors.append(acc)
        return len(self.accessors) - 1

    def indices(self, idx):
        view = self._view(struct.pack("<%dI" % len(idx), *idx), 34963)
        self.accessors.append({"bufferView": view, "componentType": 5125, "count": len(idx), "type": "SCALAR"})
        return len(self.accessors) - 1

    def image(self, im):
        b = io.BytesIO()
        im.convert("RGB").save(b, "PNG", optimize=True)
        return self._view(b.getvalue())

    def write(self, path, gltf):
        gltf["asset"] = {"version": "2.0", "generator": "qubic-tower tools/build_models.py"}
        gltf["buffers"] = [{"byteLength": len(self.bin)}]
        gltf["bufferViews"] = self.views
        gltf["accessors"] = self.accessors
        js = json.dumps(gltf, separators=(",", ":")).encode()
        while len(js) % 4:
            js += b" "
        while len(self.bin) % 4:
            self.bin.append(0)
        total = 12 + 8 + len(js) + 8 + len(self.bin)
        with open(path, "wb") as f:
            f.write(struct.pack("<III", 0x46546C67, 2, total))
            f.write(struct.pack("<II", len(js), 0x4E4F534A) + js)
            f.write(struct.pack("<II", len(self.bin), 0x004E4942) + bytes(self.bin))


class Mesh:
    """Positions / normals / uvs / indices, grouped in primitives by material index."""

    def __init__(self):
        self.pos, self.nor, self.uv = [], [], []
        self.prims = {}

    def tri(self, mat, a, b, c, n=None, uvs=((0, 0), (0, 0), (0, 0))):
        if n is None:
            u = [b[k] - a[k] for k in range(3)]; v = [c[k] - a[k] for k in range(3)]
            n = (u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0])
            l = math.sqrt(sum(x * x for x in n)) or 1.0
            n = tuple(x / l for x in n)
        base = len(self.pos)
        for p, t in zip((a, b, c), uvs):
            self.pos.append(tuple(p)); self.nor.append(tuple(n)); self.uv.append(tuple(t))
        self.prims.setdefault(mat, []).extend([base, base + 1, base + 2])

    def quad(self, mat, a, b, c, d, n=None, uvs=((0, 0), (1, 0), (1, 1), (0, 1))):
        """Counter-clockwise a-b-c-d seen from the front (normal side)."""
        self.tri(mat, a, b, c, n, (uvs[0], uvs[1], uvs[2]))
        self.tri(mat, a, c, d, n, (uvs[0], uvs[2], uvs[3]))

    def smooth(self, mat, verts, norms, uvs, idx):
        base = len(self.pos)
        self.pos += [tuple(v) for v in verts]; self.nor += [tuple(n) for n in norms]; self.uv += [tuple(t) for t in uvs]
        self.prims.setdefault(mat, []).extend(base + i for i in idx)

    def write(self, name, materials, images=()):
        g = Glb()
        pa = g.floats(self.pos, 3, minmax=True); na = g.floats(self.nor, 3); ta = g.floats(self.uv, 2)
        prims = [{"attributes": {"POSITION": pa, "NORMAL": na, "TEXCOORD_0": ta}, "indices": g.indices(idx), "material": m} for m, idx in sorted(self.prims.items())]
        gltf = {"scene": 0, "scenes": [{"nodes": [0]}], "nodes": [{"name": name, "mesh": 0}], "meshes": [{"name": name, "primitives": prims}], "materials": materials}
        if images:
            gltf["images"] = [{"bufferView": g.image(im), "mimeType": "image/png"} for im in images]
            gltf["textures"] = [{"source": i, "sampler": 0} for i in range(len(images))]
            gltf["samplers"] = [{"magFilter": 9729, "minFilter": 9987, "wrapS": 33071, "wrapT": 33071}]
        path = os.path.join(OUT, name + ".glb")
        g.write(path, gltf)
        tris = sum(len(i) for i in self.prims.values()) // 3
        print("%-16s %6d triangles %8d bytes" % (name + ".glb", tris, os.path.getsize(path)))


def mat(name, color=None, rough=0.6, tex=None, metal=0.0):
    pbr = {"metallicFactor": metal, "roughnessFactor": rough}
    if tex is not None:
        pbr["baseColorTexture"] = {"index": tex}
    if color is not None:
        pbr["baseColorFactor"] = [srgb_lin(c) for c in color] + [1.0]
    return {"name": name, "pbrMetallicRoughness": pbr, "alphaMode": "OPAQUE", "doubleSided": False}


def box(m, mi, x0, x1, y0, y1, z0, z1, uvf=None, sides=True, bottom=True):
    """Axis-aligned box faces; uvf(x, z) gives top uvs; sides take the uv of their top edge (streaks of the edge colour)."""
    uv = uvf or (lambda x, z: (0.5, 0.5))
    m.quad(mi, (x0, y1, z1), (x1, y1, z1), (x1, y1, z0), (x0, y1, z0), (0, 1, 0), (uv(x0, z1), uv(x1, z1), uv(x1, z0), uv(x0, z0)))
    if sides:
        m.quad(mi, (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1), (0, 0, 1), (uv(x0, z1), uv(x1, z1), uv(x1, z1), uv(x0, z1)))
        m.quad(mi, (x1, y0, z0), (x0, y0, z0), (x0, y1, z0), (x1, y1, z0), (0, 0, -1), (uv(x1, z0), uv(x0, z0), uv(x0, z0), uv(x1, z0)))
        m.quad(mi, (x1, y0, z1), (x1, y0, z0), (x1, y1, z0), (x1, y1, z1), (1, 0, 0), (uv(x1, z1), uv(x1, z0), uv(x1, z0), uv(x1, z1)))
        m.quad(mi, (x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0), (-1, 0, 0), (uv(x0, z0), uv(x0, z1), uv(x0, z1), uv(x0, z0)))
    if bottom:
        m.quad(mi, (x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1), (0, -1, 0), (uv(x0, z0), uv(x1, z0), uv(x1, z1), uv(x0, z1)))


# ---------------------------------------------------------------- textures

def grain(base, size=512, seed=1, streak=0.0, contrast=10):
    """Fine stone grain (+ optional brushed streaks along u) around a base colour."""
    rnd = random.Random(seed)
    im = Image.new("RGB", (size, size), base)
    px = im.load()
    for y in range(size):
        row = rnd.uniform(-1, 1) * streak
        for x in range(size):
            n = rnd.uniform(-1, 1) * contrast + row * contrast
            px[x, y] = tuple(max(0, min(255, int(c + n))) for c in base)
    return im.filter(ImageFilter.GaussianBlur(0.6))


def label_font(px):
    return ImageFont.truetype(FONT, px)


def base_texture(size=1024):
    """Base top (x, z in ±0.13 -> u, v): 4 x 4 engraved cells 0.5 mm deep look, corner L marks, a front triangle."""
    im = grain(GRAPHITE, size, seed=3, contrast=7)
    d = ImageDraw.Draw(im)
    s = size / 0.26
    def p(x, z):
        return ((x + 0.13) * s, (z + 0.13) * s)
    dark = (0x1F, 0x22, 0x28); hi = (0x4A, 0x50, 0x5A)
    for k in range(5):
        v = -0.12 + k * PITCH
        d.line([p(v, -0.12), p(v, 0.12)], fill=dark, width=5)
        d.line([p(v + 0.0012, -0.12), p(v + 0.0012, 0.12)], fill=hi, width=1)
        d.line([p(-0.12, v), p(0.12, v)], fill=dark, width=5)
        d.line([p(-0.12, v + 0.0012), p(0.12, v + 0.0012)], fill=hi, width=1)
    for sx in (-1, 1):
        for sz in (-1, 1):
            cx, cz = sx * 0.125, sz * 0.125
            d.line([p(cx, cz), p(cx - sx * 0.012, cz)], fill=hi, width=4)
            d.line([p(cx, cz), p(cx, cz - sz * 0.012)], fill=hi, width=4)
    d.polygon([p(-0.008, 0.128), p(0.008, 0.128), p(0.0, 0.117)], fill=INK_LABEL)
    return im


def turntable_texture(size=1024):
    """Turntable top (x, z in ±TABLE_R -> u, v; image down = +Z = toward the user). Labels stand on the rim, their
    bottoms toward the outside: front / back show the columns A–D, left / right the rows 1–4 (design: 四面各印一圈)."""
    im = grain((0x26, 0x29, 0x30), size, seed=5, contrast=6)
    d = ImageDraw.Draw(im)
    s = size / (2 * TABLE_R)
    def p(x, z):
        return ((x + TABLE_R) * s, (z + TABLE_R) * s)
    # rim band
    for r, col, w in ((TABLE_R - 0.002, (0x55, 0x5B, 0x66), 6), (0.113, (0x3B, 0x40, 0x49), 3)):
        d.ellipse([p(-r, -r), p(r, r)], outline=col, width=w)
    font = label_font(int(0.0105 * s))
    rl = 0.124
    def label(text, x, z, rot):
        tw = font.getbbox(text)
        g = Image.new("RGBA", (int(0.03 * s), int(0.03 * s)), (0, 0, 0, 0))
        gd = ImageDraw.Draw(g)
        gd.text((g.width / 2, g.height / 2), text, font=font, fill=INK_LABEL + (255,), anchor="mm")
        g = g.rotate(rot, resample=Image.BICUBIC)
        cx, cz = p(x, z)
        im.paste(g, (int(cx - g.width / 2), int(cz - g.height / 2)), g)
    for i in range(4):
        v = (i - 1.5) * PITCH
        w = math.sqrt(rl * rl - v * v)
        col = "F↑" if TEST_F else "ABCD"[i]
        row = "F↑" if TEST_F else "1234"[i]
        label(col, v, w, 0)        # front: upright (bottom toward +Z)
        label(col, v, -w, 180)     # back: bottom toward −Z
        label(row, -w, v, -90)     # left: bottom toward −X
        label(row, w, v, 90)       # right: bottom toward +X
    return im


# ---------------------------------------------------------------- shapes

def cylinder(m, mi, cx, cz, r, y0, y1, seg=16, top=True, bottom=False, uv_top=None):
    for k in range(seg):
        a0 = 2 * math.pi * k / seg; a1 = 2 * math.pi * (k + 1) / seg
        x0, z0 = cx + r * math.cos(a0), cz + r * math.sin(a0)
        x1, z1 = cx + r * math.cos(a1), cz + r * math.sin(a1)
        n0 = (math.cos(a0), 0, math.sin(a0)); n1 = (math.cos(a1), 0, math.sin(a1))
        m.smooth(mi, [(x0, y0, z0), (x1, y0, z1), (x1, y1, z1), (x0, y1, z0)], [n0, n1, n1, n0],
                 [(k / seg, 1), ((k + 1) / seg, 1), ((k + 1) / seg, 0), (k / seg, 0)], [0, 2, 1, 0, 3, 2])
        uvf = uv_top or (lambda x, z: (0.5, 0.5))
        if top:
            m.tri(mi, (cx, y1, cz), (x1, y1, z1), (x0, y1, z0), (0, 1, 0), (uvf(cx, cz), uvf(x1, z1), uvf(x0, z0)))
        if bottom:
            m.tri(mi, (cx, y0, cz), (x0, y0, z0), (x1, y0, z1), (0, -1, 0))


def build_plinth():
    m = Mesh()
    uv = lambda x, z: ((x + 0.27) / 0.54 * 3, (z + 0.15) / 0.30 * 1.7)
    box(m, 0, -0.27, 0.27, -0.21, -0.17, -0.15, 0.15, uvf=uv)
    # wedge: front-bottom edge (y −0.17, z +0.13), back-bottom (z −0.114), back-top (y −0.081)
    fb, bb, bt = (-0.17, 0.13), (-0.17, -0.114), (-0.081, -0.114)
    for sx in (-0.13, 0.13):
        a, b_, c = (sx, fb[0], fb[1]), (sx, bb[0], bb[1]), (sx, bt[0], bt[1])
        if sx > 0:
            m.tri(0, a, b_, c, (1, 0, 0), (uv(0, 0.13), uv(0, -0.114), uv(0.03, -0.114)))
        else:
            m.tri(0, a, c, b_, (-1, 0, 0), (uv(0, 0.13), uv(0.03, -0.114), uv(0, -0.114)))
    m.quad(0, (0.13, bb[0], bb[1]), (-0.13, bb[0], bb[1]), (-0.13, bt[0], bt[1]), (0.13, bt[0], bt[1]), (0, 0, -1))
    ln = math.hypot(bt[0] - fb[0], bt[1] - fb[1])
    n = (0, (fb[1] - bt[1]) / ln, (bt[0] - fb[0]) / ln)
    m.quad(0, (-0.13, fb[0], fb[1]), (0.13, fb[0], fb[1]), (0.13, bt[0], bt[1]), (-0.13, bt[0], bt[1]), n)
    m.write("plinth", [mat("graphite", tex=0, rough=0.7, metal=0.1)], [grain(GRAPHITE, 512, seed=1, streak=0.25, contrast=9)])


def build_trays():
    m = Mesh()
    for sx in (-1, 1):
        x0, x1 = sx * 0.205 - 0.055, sx * 0.205 + 0.055
        z0, z1 = -0.14, 0.08
        y0, y1, rim = -0.17, -0.15, 0.005
        # floor slightly below the rim (inset 1.5 mm), rim 5 mm wide
        box(m, 0, x0, x1, y0, y1 - 0.0015, z0, z1, bottom=False)
        for (a, b_, c, d_) in ((x0, x1, z0, z0 + rim), (x0, x1, z1 - rim, z1), (x0, x0 + rim, z0, z1), (x1 - rim, x1, z0, z1)):
            box(m, 0, a, b_, y1 - 0.0015, y1 + 0.0015, c, d_, bottom=False)
        for (px, pz) in [(sx * 0.205 + dx, -0.03 + dz) for dx in (-0.0275, 0.0275) for dz in (-0.0825, -0.0275, 0.0275, 0.0825)]:
            cylinder(m, 0, px, pz, 0.0085, y1 - 0.0015, y1 + 0.0008, seg=20)   # socket collar
    m.write("trays", [mat("graphite_light", tex=0, rough=0.65, metal=0.1)], [grain(GRAPHITE_LIGHT, 256, seed=2, contrast=8)])


def build_traypegs():
    m = Mesh()
    for sx in (-1, 1):
        for dx in (-0.0275, 0.0275):
            for dz in (-0.0825, -0.0275, 0.0275, 0.0825):
                cylinder(m, 0, sx * 0.205 + dx, -0.03 + dz, 0.005, -0.15, -0.02, seg=12)
    m.write("traypegs", [mat("frost", color=(0xE6, 0xEA, 0xEE), rough=0.8)])


def build_base():
    m = Mesh()
    uv = lambda x, z: ((x + 0.13) / 0.26, (z + 0.13) / 0.26)
    box(m, 0, -0.13, 0.13, -0.14, -0.11, -0.13, 0.13, uvf=uv)
    m.write("base", [mat("graphite_top", tex=0, rough=0.7, metal=0.1)], [base_texture()])


def build_turntable():
    m = Mesh()
    uv = lambda x, z: ((x + TABLE_R) / (2 * TABLE_R), (z + TABLE_R) / (2 * TABLE_R))
    cylinder(m, 0, 0, 0, TABLE_R, -0.11, -0.10, seg=64, top=True, bottom=True, uv_top=uv)
    m.write("turntable", [mat("turntable", tex=0, rough=0.55, metal=0.15)], [turntable_texture()])


def build_peg():
    m = Mesh()
    cylinder(m, 0, 0, 0, 0.005, -0.10, 0.05, seg=12)
    m.write("peg", [mat("frost", color=(0xE6, 0xEA, 0xEE), rough=0.8)])


def build_bead():
    """A revolved profile: a vertical hole wall at r = 6 mm, then a half-ellipse out to r = 25 mm (semi-axes 19 x 16 mm)."""
    m = Mesh()
    seg, steps = 28, 16
    hole, a, b = 0.006, 0.019, 0.016
    prof = []   # (r, y, nr, ny, v)
    for k in range(steps + 1):
        phi = -math.pi / 2 + math.pi * k / steps
        r = hole + a * math.cos(phi); y = b * math.sin(phi)
        nr, ny = math.cos(phi) / a, math.sin(phi) / b
        l = math.hypot(nr, ny); prof.append((r, y, nr / l, ny / l, 0.1 + 0.8 * k / steps))
    rings = []
    for (r, y, nr, ny, v) in prof:
        rings.append([((r * math.cos(2 * math.pi * j / seg), y, r * math.sin(2 * math.pi * j / seg)),
                       (nr * math.cos(2 * math.pi * j / seg), ny, nr * math.sin(2 * math.pi * j / seg)), (j / seg * 4, v)) for j in range(seg + 1)])
    verts, norms, uvs, idx = [], [], [], []
    for ring in rings:
        for (p, n, t) in ring:
            verts.append(p); norms.append(n); uvs.append(t)
    w = seg + 1
    for k in range(steps):
        for j in range(seg):
            a0, a1, b0, b1 = k * w + j, k * w + j + 1, (k + 1) * w + j, (k + 1) * w + j + 1
            idx += [a0, b0, a1, a1, b0, b1]
    # hole wall (normals point to the axis)
    base = len(verts)
    for j in range(seg + 1):
        t = 2 * math.pi * j / seg
        for y in (-b, b):
            verts.append((hole * math.cos(t), y, hole * math.sin(t))); norms.append((-math.cos(t), 0, -math.sin(t))); uvs.append((j / seg * 4, 0.05 if y < 0 else 0.95))
    for j in range(seg):
        a0, a1, b0, b1 = base + 2 * j, base + 2 * j + 2, base + 2 * j + 1, base + 2 * j + 3
        idx += [a0, a1, b0, b0, a1, b1]
    m.smooth(0, verts, norms, uvs, idx)
    m.write("bead", [mat("bead", color=AMBER, rough=0.35)])


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    build_plinth(); build_trays(); build_traypegs(); build_base(); build_turntable(); build_peg(); build_bead()
    if TEST_F:
        print("TEST-F: turntable labels are F↑ (orientation check, D10); rebuild without --test-f before shipping")
