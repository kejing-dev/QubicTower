"""Blank of the control window in a window crop (unified rule 2026-11-05): (content top - window top + window bottom -
content bottom) / window height, plus the largest blank band inside the content. A row is blank when no neighbouring pixels
across the inner 90 % of the window width differ by more than 28 (no text, no edge; the glass gradient is smooth) (no text, no control, no card edge).

  python3 tools/blank.py SHOT.png [...]      SHOT = a crop that holds the TabBar + the window (tools: rt.sh shot)
"""
import sys
import numpy as np
from PIL import Image


def analyse(path):
    im = np.asarray(Image.open(path).convert("RGB")).astype(float)
    lum = 0.2126 * im[..., 0] + 0.7152 * im[..., 1] + 0.0722 * im[..., 2]
    sat = im.max(axis=2) - im.min(axis=2)
    glass = (lum > 150) & (sat < 25)
    rows = glass.mean(axis=1)
    ys = np.where(rows > 0.45)[0]           # window rows: most of the row is glass (the TabBar row is narrower)
    runs, start, prev = [], ys[0], ys[0]
    for v in ys[1:]:
        if v != prev + 1:
            runs.append((start, prev)); start = v
        prev = v
    runs.append((start, prev))
    y0, y1 = max(runs, key=lambda r: r[1] - r[0])
    cols = glass[y0:y1].mean(axis=0)
    xs = np.where(cols > 0.5)[0]
    x0, x1 = xs[0], xs[-1]
    w = x1 - x0
    inner = lum[y0 + 6:y1 - 6, int(x0 + 0.05 * w):int(x1 - 0.05 * w)]
    sub = im[y0 + 6:y1 - 6, int(x0 + 0.05 * w):int(x1 - 0.05 * w)]
    plain = (inner > 150) & (inner < 236) & ((sub.max(axis=2) - sub.min(axis=2)) < 25)
    busy = (np.abs(np.diff(inner, axis=1)).max(axis=1) > 28) | (plain.mean(axis=1) < 0.97)
    idx = np.where(busy)[0]
    h = y1 - y0
    top = idx[0] + 6; bottom = h - (idx[-1] + 6)
    gaps = np.diff(idx)
    band = int(gaps.max()) if len(gaps) else 0
    return h, top, bottom, band


for p in sys.argv[1:]:
    h, t, b, band = analyse(p)
    print("%s  window %d px  blank top %d + bottom %d = %.1f %%  largest inner band %.1f %%" % (p.split("/")[-1], h, t, b, 100 * (t + b) / h, 100 * band / h))
