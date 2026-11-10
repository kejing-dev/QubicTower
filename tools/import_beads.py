"""Qubic bead landing sounds v0.1.1 (profile D51「录音要干净」, board-game §15.1): a glazed bead sliding down its peg and
clicking onto the bead below / the turntable = clean single "click" hits from the freesound CC0 marble recordings in
~/Downloads/cc0-sounds/freesound-marbles (glass on glass, glass on wood), compared with v0.1.0's Kenney chip-lay.

  uv run --system-certs --with numpy --with scipy python3 tools/import_beads.py --scan    # rank every hit
  uv run --system-certs --with numpy --with scipy python3 tools/import_beads.py           # write land / land_2 / land_3

Chain = five-stones v0.1.5 import_stones.py (no thickening, no reverb, no compression):
  afconvert mp3 -> 44.1 kHz mono  ->  spectral subtraction (noise profile = the quietest 300 ms of the same recording,
  over-subtraction 2.0, spectral floor 3 %)  ->  4th-order Butterworth high-pass 120 Hz + low-pass 6 kHz (zero phase)
  ->  cut 3 ms before the onset until the envelope falls 48 dB below the peak (max 180 ms), 12 ms cosine fade-out
  ->  linear gain to a -1 dBFS peak.
"""
import os, subprocess, sys, tempfile, wave
import numpy as np
from scipy.signal import butter, sosfiltfilt, stft, istft

SR = 44100
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "sounds")
LIB = os.path.expanduser("~/Downloads/cc0-sounds")
SOURCES = {
    "H": ("freesound-marbles/marbles_628997.mp3", "https://freesound.org/people/KaleidacousticsAudio/sounds/628997/ (KaleidacousticsAudio, Marbles_Hitting each other)"),
    "W1": ("freesound-marbles/marbles_628994.mp3", "https://freesound.org/people/KaleidacousticsAudio/sounds/628994/ (KaleidacousticsAudio, Marbles_Bouncing on wooden floor 1)"),
    "W2": ("freesound-marbles/marbles_628995.mp3", "https://freesound.org/people/KaleidacousticsAudio/sounds/628995/ (KaleidacousticsAudio, Marbles_Bouncing on wooden floor 2)"),
}
for i in range(1, 4):
    SOURCES[f"K{i}"] = (f"kenney_casino-audio/Audio/chip-lay-{i}.ogg", "https://kenney.nl/assets/casino-audio (Kenney, chip-lay-%d)" % i)


def decode(rel):
    tmp = tempfile.mktemp(suffix=".wav")
    subprocess.run(["afconvert", "-f", "WAVE", "-d", "LEI16@44100", "-c", "1", os.path.join(LIB, rel), tmp], check=True)
    with wave.open(tmp) as w:
        x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float64) / 32768
    os.remove(tmp)
    return x


def denoise(x):
    win = int(0.3 * SR)
    if len(x) > 3 * win:
        e = [np.sqrt(np.mean(x[i:i + win] ** 2)) for i in range(0, len(x) - win, win // 4)]
        i0 = int(np.argmin(e)) * (win // 4)
        noise = x[i0:i0 + win]
    else:
        noise = np.tile(x[-int(0.02 * SR):], 3)  # short studio file: its own tail
    f, t, Z = stft(x, SR, nperseg=1024, noverlap=768)
    _, _, N = stft(noise, SR, nperseg=1024, noverlap=768)
    prof = np.mean(np.abs(N), axis=1, keepdims=True)
    mag = np.abs(Z)
    clean = np.maximum(mag - 2.0 * prof, 0.03 * mag)
    _, y = istft(clean * np.exp(1j * np.angle(Z)), SR, nperseg=1024, noverlap=768)
    return y[: len(x)], float(20 * np.log10(np.sqrt(np.mean(noise ** 2)) + 1e-9))


def band(x):
    sos = np.vstack([butter(4, 120, "highpass", fs=SR, output="sos"), butter(4, 6000, "lowpass", fs=SR, output="sos")])
    return sosfiltfilt(sos, x)


def onsets(x):
    hop = 220
    env = np.array([np.max(np.abs(x[i:i + hop])) for i in range(0, len(x) - hop, hop)])
    out, last = [], -10 ** 9
    for i in range(3, len(env)):
        if env[i] > 0.05 and env[i] > 4 * (env[i - 3:i].max() + 1e-4) and i * hop - last > int(0.25 * SR):
            out.append(i * hop); last = i * hop
    return out


def cut(y, at):
    s = max(0, at - int(0.003 * SR))
    seg = y[s:s + int(0.25 * SR)].copy()
    pk = np.max(np.abs(seg)) + 1e-12
    env = np.abs(seg)
    idx = np.argmax(env)
    thr = pk * 10 ** (-48 / 20)
    k = idx
    quiet = 0
    for j in range(idx, len(seg)):
        quiet = quiet + 1 if env[j] < thr else 0
        if quiet > int(0.004 * SR): k = j; break
        k = j
    k = min(max(k, idx + int(0.03 * SR)), int(0.18 * SR))
    seg = seg[:k]
    fade = min(int(0.012 * SR), len(seg) // 3)
    seg[-fade:] *= 0.5 * (1 + np.cos(np.linspace(0, np.pi, fade)))
    second = np.max(np.abs(seg[int(0.03 * SR):])) / pk if len(seg) > int(0.03 * SR) else 0.0  # later spike = double hit
    tail = float(20 * np.log10(np.sqrt(np.mean(seg[-fade:] ** 2)) / pk + 1e-9))
    spec = np.abs(np.fft.rfft(seg * np.hanning(len(seg))))
    fr = np.fft.rfftfreq(len(seg), 1 / SR)
    centroid = float(np.sum(fr * spec) / (np.sum(spec) + 1e-12))
    return seg / pk * 10 ** (-1 / 20), second, tail, centroid


def candidates():
    for key, (rel, url) in SOURCES.items():
        x = decode(rel)
        y, noise_db = denoise(x)
        y = band(y)
        ons = onsets(y) if not key.startswith("K") else [int(np.argmax(np.abs(y) > 0.05 * np.max(np.abs(y))))]
        for at in ons:
            seg, second, tail, cen = cut(y, at)
            rms = float(20 * np.log10(np.sqrt(np.mean(seg ** 2)) + 1e-9))
            yield dict(key=key, at=at / SR, seg=seg, second=second, tail=tail, centroid=cen, rms=rms, noise=noise_db, url=url, rel=rel)


def write(name, seg):
    os.makedirs(OUT, exist_ok=True)
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((np.clip(seg, -1, 1) * 32767).astype(np.int16).tobytes())


# chosen after --scan (key, onset seconds): single, clean, crisp hits
CHOSEN = {
    # --scan 2026-11-09: Kenney chip-lay 1 / 2 are the cleanest single clicks (2nd hit 0.05-0.06, tail -57..-58 dB,
    # centroid 4.6-5.0 kHz = a crisp "嗒"); chip-lay-3 is the third variant (2nd 0.11, 1.2 kHz, a rounder click).
    # The marble recordings ring (tail only -37..-44 dB at the 180 ms cap) and every marble hit has a 2nd hit >= 0.12:
    # not used.
    "land": ("K1", 0.036), "land_2": ("K2", 0.070), "land_3": ("K3", 0.063),
}

if __name__ == "__main__":
    cs = list(candidates())
    if "--scan" in sys.argv:
        for c in sorted(cs, key=lambda c: (c["second"], c["tail"])):
            print("%-3s %7.3fs  2nd-hit %.2f  tail %6.1f dB  centroid %5.0f Hz  rms %6.1f dBFS  dur %3.0f ms  (raw floor %5.1f dBFS)" % (
                c["key"], c["at"], c["second"], c["tail"], c["centroid"], c["rms"], 1000 * len(c["seg"]) / SR, c["noise"]))
    else:
        for name, (key, t) in CHOSEN.items():
            c = min((c for c in cs if c["key"] == key), key=lambda c: abs(c["at"] - t))
            write(name, c["seg"])
            print("%s <- %s @ %.3f s  tail %.1f dB  centroid %.0f Hz  rms %.1f dBFS  %s" % (name, c["key"], c["at"], c["tail"], c["centroid"], c["rms"], c["url"]))
