"""Loudness evidence (B20 / ui_gates loudness, board-game §15.2) for an app with short feedback sounds only.

  python3 tools/loudness.py qa/loudness-v0.1.1.wav [qa/rapid5-v0.1.1.wav]

1. Voiced track: the sounds of a typical game at the default volume (100 %, SoundPlayer.GAIN per sound) in the app's
   v0.1.1 order (selection is silent: a turn = tick x 0-3 + land; AI telegraph lift; turn / snap; illegal; block; return;
   hint; note x 4; win; clear; draw), with the app's +-5 % rate variation, only the voiced parts (|x| above -40 dBFS, gaps
   trimmed to 40 ms). RMS / peak of that track.
2. Five quick drops (D51 「连点 5 次峰值 ≤ −1 dBFS」): the real timeline mixed sample by sample — five drops 0.45 s apart
   (the fastest the input lock allows), each with its layer's passing ticks (Layout.passTimes) and the land at
   Layout.fallMs, one stream per class (ticks and the land share the "drop" stream: a new one stops the previous, SoundPlayer.GROUP). Peak of the mix.
Rendered, not recorded: kj-mbp-m5 has no audio loopback from the emulator. Everything resampled to 44.1 kHz.
"""
import math, os, random, struct, sys, wave

SND = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app", "src", "main", "assets", "sounds")
SR = 44100
GAIN = {"tick": 0.6, "return": 0.7}
PHYSICAL = {"lift", "tick", "land", "return", "turn", "snap", "clear"}
HOVER_Y, G = 0.10, 2.5
LAYER_Y = [-0.084, -0.052, -0.020, 0.012]


def load(name):
    with wave.open(os.path.join(SND, name + ".wav")) as w:
        sr = w.getframerate()
        x = [v / 32768 for v in struct.unpack("<%dh" % w.getnframes(), w.readframes(w.getnframes()))]
    return resample(x, SR / sr)


def resample(x, k):
    if k == 1: return x
    n = int(len(x) * k)
    out = []
    for i in range(n):
        t = i / k; j = int(t); f = t - j
        a = x[min(j, len(x) - 1)]; b = x[min(j + 1, len(x) - 1)]
        out.append(a + (b - a) * f)
    return out


def variants(name):
    return [n for n in (name, name + "_2", name + "_3", name + "_4") if os.path.exists(os.path.join(SND, n + ".wav"))]


def voice(name, rnd, rate=1.0):
    x = load(rnd.choice(variants(name)))
    if name in PHYSICAL: rate *= 0.95 + rnd.random() * 0.10
    x = resample(x, 1 / rate)
    g = GAIN.get(name, 1.0)
    return [v * g for v in x]


def voiced(x, thr=10 ** (-40 / 20), gap=0.04):
    out, quiet = [], 0
    for v in x:
        if abs(v) < thr:
            quiet += 1
            if quiet > gap * SR: continue
        else:
            quiet = 0
        out.append(v)
    return out


def fall_ms(layer): return math.sqrt(2 * (HOVER_Y - LAYER_Y[layer]) / G) * 1000
def pass_ms(layer): return [math.sqrt(2 * (HOVER_Y - LAYER_Y[l]) / G) * 1000 for l in range(3, layer, -1)]


def db(v): return 20 * math.log10(max(v, 1e-9))


def write(path, x):
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, v)) * 32767)) for v in x))


def track(rnd):
    seq = []
    def drop(layer):
        for i, _ in enumerate(pass_ms(layer)): seq.extend(voiced(voice("tick", rnd, 1.12 - 0.08 * i)))
        seq.extend(voiced(voice("land", rnd, 1.05 - 0.04 * layer)))
    turn = lambda: (drop(rnd.randrange(4)), seq.extend(voiced(voice("lift", rnd))), drop(rnd.randrange(4)))
    for _ in range(4): turn()
    for n in ("turn", "turn", "turn", "snap"): seq.extend(voiced(voice(n, rnd)))
    turn()
    for n in ("illegal", "block"): seq.extend(voiced(voice(n, rnd)))
    for _ in range(2): turn()
    for n in ("return", "hint"): seq.extend(voiced(voice(n, rnd)))
    turn()
    for i in range(4): seq.extend(voiced(voice("note", rnd, 1 + 0.125 * i)))
    for n in ("win", "clear", "draw"): seq.extend(voiced(voice(n, rnd)))
    return seq


def rapid5(rnd, spacing_ms=450, layers=(0, 1, 2, 3, 0)):
    events = []  # (start ms, sound name, rate)
    for k, layer in enumerate(layers):
        t0 = k * spacing_ms
        for i, at in enumerate(pass_ms(layer)): events.append((t0 + at, "tick", 1.12 - 0.08 * i))
        events.append((t0 + fall_ms(layer), "land", 1.05 - 0.04 * layer))
    events.sort()
    total = int((max(e[0] for e in events) / 1000 + 0.4) * SR)
    mix = [0.0] * total
    playing = {}  # one stream per sound: (start sample, samples)
    clips = []
    for at, name, r in events:
        s = int(at / 1000 * SR)
        g = "drop" if name in ("tick", "land") else name   # SoundPlayer.GROUP: ticks and the land share one stream
        if g in playing:  # the new one stops the previous stream of the same class
            ps, px = playing[g]
            clips[px] = (clips[px][0], clips[px][1][: max(0, s - ps)])
        clips.append((s, voice(name, rnd, r)))
        playing[g] = (s, len(clips) - 1)
    for s, x in clips:
        for i, v in enumerate(x):
            if s + i < total: mix[s + i] += v
    return mix


def main():
    rnd = random.Random(5)
    seq = track(rnd)
    write(sys.argv[1], seq)
    rms = math.sqrt(sum(v * v for v in seq) / len(seq)); pk = max(abs(v) for v in seq)
    print("voiced %.1f s  RMS %.1f dBFS  peak %.1f dBFS  (rendered, default volume 100 %%)" % (len(seq) / SR, db(rms), db(pk)))
    worst = -99.0
    for seed in range(20):
        m = rapid5(random.Random(seed))
        worst = max(worst, db(max(abs(v) for v in m)))
        if seed == 0 and len(sys.argv) > 2: write(sys.argv[2], m)
    print("five quick drops (0.45 s apart, 20 random variant / pitch draws): worst mix peak %.2f dBFS" % worst)


main()
