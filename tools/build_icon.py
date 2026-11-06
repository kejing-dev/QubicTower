"""Qubic Tower icon (design-package appIcon / mustHave.customIcon): an indigo #2A2D5C disc, four cream short pegs in
2 x 2 perspective with amber and celadon beads, one amber light line from lower left to upper right. No text.

  python3 tools/build_icon.py [--icon512 out.png] [--preview out.png]

Writes res/drawable/icon_3d_layer_{0,1,2}.png + icon_3d_sdf_{0,1,2}.png (PICO layered icon), mipmap-xxxhdpi/
ic_spatial_launcher.png and the adaptive foreground vector res/drawable/ic_launcher_fg.xml (same polygons).
"""
import math
import os
import sys
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S, SS = 1024, 4
INDIGO = (0x2A, 0x2D, 0x5C)
CREAM = (0xF3, 0xEC, 0xDC)
CREAM_DIM = (0xC9, 0xC3, 0xB6)
AMBER = (0xE8, 0x87, 0x3A)
CELADON = (0x2F, 0xA3, 0x9A)
LIGHT = (0xFF, 0xB8, 0x5C)


def circle(cx, cy, r, n=48):
    return [(cx + r * math.cos(2 * math.pi * i / n), cy + r * math.sin(2 * math.pi * i / n)) for i in range(n)]


def ellipse(cx, cy, rx, ry, n=36):
    return [(cx + rx * math.cos(2 * math.pi * i / n), cy + ry * math.sin(2 * math.pi * i / n)) for i in range(n)]


def rect(x0, y0, x1, y1):
    return [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]


# pegs: back pair (smaller, higher) and front pair
BACK = [(44.0, 26.0, 60.0, 4.0), (66.0, 26.0, 60.0, 4.0)]     # x, top, bottom, width
FRONT = [(34.0, 36.0, 80.0, 5.0), (74.0, 36.0, 80.0, 5.0)]
PEGS_BACK = [rect(x - w / 2, t, x + w / 2, b) for x, t, b, w in BACK]
PEGS_FRONT = [rect(x - w / 2, t, x + w / 2, b) for x, t, b, w in FRONT]
# beads (rx, ry): back ones smaller
BEADS_BACK = [(ellipse(44, 55, 8.5, 4.6), CELADON), (ellipse(66, 55, 8.5, 4.6), AMBER), (ellipse(66, 46.5, 8.5, 4.6), AMBER)]
BEADS_FRONT = [(ellipse(34, 74, 10.5, 5.6), AMBER), (ellipse(74, 74, 10.5, 5.6), CELADON), (ellipse(74, 63.5, 10.5, 5.6), AMBER)]


def beam():
    a, b, w = (20.0, 86.0), (88.0, 22.0), 1.8
    dx, dy = b[0] - a[0], b[1] - a[1]
    l = math.hypot(dx, dy); nx, ny = -dy / l * w, dx / l * w
    return [(a[0] + nx, a[1] + ny), (b[0] + nx, b[1] + ny), (b[0] - nx, b[1] - ny), (a[0] - nx, a[1] - ny)]


BEAM = beam()


def big(draw_fn):
    im = Image.new("RGBA", (S * SS, S * SS), (0, 0, 0, 0))
    draw_fn(ImageDraw.Draw(im), S * SS / 108.0)
    return im.resize((S, S), Image.LANCZOS)


def sc(pts, k):
    return [(x * k, y * k) for x, y in pts]


def layer_bg():
    return big(lambda dr, k: dr.ellipse((0, 0, 108 * k - 1, 108 * k - 1), fill=INDIGO + (255,)))


def layer_back():
    def d(dr, k):
        for p in PEGS_BACK:
            dr.polygon(sc(p, k), fill=CREAM_DIM + (255,))
        for p, c in BEADS_BACK:
            dr.polygon(sc(p, k), fill=c + (255,))
    return big(d)


def layer_front():
    def d(dr, k):
        dr.polygon(sc(BEAM, k), fill=LIGHT + (255,))
        for p in PEGS_FRONT:
            dr.polygon(sc(p, k), fill=CREAM + (255,))
        for p, c in BEADS_FRONT:
            dr.polygon(sc(p, k), fill=c + (255,))
    return big(d)


def sdf(layer):
    inside = layer.getchannel("A").point(lambda v: 255 if v >= 128 else 0)
    dist = Image.new("L", inside.size, 0)
    cur = inside
    for i in range(1, 33):
        dist.paste(min(255, i * 8), mask=cur)
        cur = cur.filter(ImageFilter.MinFilter(3) if i % 2 else ImageFilter.Kernel((3, 3), (0, 1, 0, 1, 1, 1, 0, 1, 0), 5))
        cur = cur.point(lambda v: 255 if v >= 255 else 0)
    dist = Image.composite(dist, Image.new("L", inside.size, 0), inside)
    return Image.merge("RGBA", (dist, dist, dist, dist))


def path(pts):
    return "M" + "L".join("%.2f,%.2f" % p for p in pts) + "Z"


def vector():
    hexc = lambda c: "#FF%02X%02X%02X" % c
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<!-- Qubic Tower launcher foreground (tools/build_icon.py draws the same artwork for the PICO layered icon). -->',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">',
    ]
    for p in PEGS_BACK:
        lines.append('    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(CREAM_DIM), path(p)))
    for p, c in BEADS_BACK:
        lines.append('    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(c), path(p)))
    lines.append('    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(LIGHT), path(BEAM)))
    for p in PEGS_FRONT:
        lines.append('    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(CREAM), path(p)))
    for p, c in BEADS_FRONT:
        lines.append('    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(c), path(p)))
    lines.append('</vector>')
    open(os.path.join(RES, "drawable", "ic_launcher_fg.xml"), "w").write("\n".join(lines) + "\n")


def main():
    layers = [layer_bg(), layer_back(), layer_front()]
    for i, layer in enumerate(layers):
        layer.save(os.path.join(RES, "drawable", f"icon_3d_layer_{i}.png"))
        sdf(layer).save(os.path.join(RES, "drawable", f"icon_3d_sdf_{i}.png"))
    flat = Image.new("RGBA", layers[0].size, (0, 0, 0, 0))
    for l in layers:
        flat.alpha_composite(l)
    flat.save(os.path.join(RES, "mipmap-xxxhdpi", "ic_spatial_launcher.png"))
    vector()
    if "--icon512" in sys.argv:
        flat.resize((512, 512), Image.LANCZOS).save(sys.argv[sys.argv.index("--icon512") + 1])
    if "--preview" in sys.argv:
        w = 256
        sheet = Image.new("RGB", (w * 4, w), (60, 64, 70))
        for i, t in enumerate(layers + [flat]):
            r = t.resize((w, w), Image.LANCZOS)
            sheet.paste(r, (i * w, 0), r)
        sheet.save(sys.argv[sys.argv.index("--preview") + 1])
    print("ICON ok")


main()
