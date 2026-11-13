"""The table's sounds (public/sounds/simple/, public/sounds/real/), two sets the settings choose between:

- simple: made from scratch here, no recordings; each says what happened (a soft knock for a discard, a few for a call,
  a little tune for a win) and no more;
- real: tiles as they sound, made from recordings of dice and a chip on felt (scripts/sounds-src/, Kenney's Casino
  Audio, CC0: LICENSE-Kenney.txt), lowered (tiles are larger than dice) and cut into single knocks, laid out again:
  the shuffle in waves (hands pushing the tiles, dense; turning or lifted, sparse), a discard on the felt with the
  weight of a tile, a call's tiles laid out, a win's hand pushed down.

Each sound: 44.1 kHz mono WAV into OUT/simple and OUT/real; then each to public/sounds as mp3:

    uv run --no-project --with numpy,scipy python scripts/make_sounds.py OUT
    for s in simple real; do mkdir -p public/sounds/$s; for f in OUT/$s/*.wav; do
      ffmpeg -i $f -ac 1 -b:a 64k public/sounds/$s/$(basename $f .wav).mp3; done; done
"""
import os
import subprocess
import sys
import tempfile
import wave

import numpy as np
from scipy.signal import butter, find_peaks, resample_poly, sosfilt

SR = 44100
OUT = sys.argv[1]
SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'sounds-src')
rng = np.random.default_rng(7)


def t_of(sec):
    return np.arange(int(SR * sec)) / SR


def lowpass(x, fc, order=2):
    return sosfilt(butter(order, fc, 'lp', fs=SR, output='sos'), x)


def highpass(x, fc, order=2):
    return sosfilt(butter(order, fc, 'hp', fs=SR, output='sos'), x)


def place(buf, x, at, gain):
    i = int(at * SR)
    if i >= len(buf):
        return
    j = min(len(buf), i + len(x))
    buf[i:j] += gain * x[: j - i]


def room(x, wet=0.15):
    """A little room around it: a few early echoes, softened."""
    y = x.copy()
    for d, g in [(0.019, 0.5), (0.031, 0.4), (0.047, 0.3), (0.071, 0.2), (0.097, 0.12)]:
        k = int(d * SR)
        y[k:] += wet * g * lowpass(x[:-k], 3500) * 2
    return y


def save(kind, name, x, peak=0.9):
    os.makedirs(os.path.join(OUT, kind), exist_ok=True)
    x = x / (np.max(np.abs(x)) + 1e-9) * peak
    pcm = (np.clip(x, -1, 1) * 32767).astype(np.int16)
    with wave.open(os.path.join(OUT, kind, name + '.wav'), 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())
    print(kind, name, f'{len(x) / SR:.2f}s')


def burst_env(sec, waves, sparse=0.12):
    """A shuffle's density over `sec`: `waves` pushes (each a swell, dense), the hands turning between them (sparse)."""
    t = t_of(sec)
    d = np.full_like(t, sparse)
    # The pushes one after another, a little irregular (four players' hands: some overlap).
    starts = (np.arange(waves) + rng.uniform(-0.25, 0.25, waves)) * (sec - 0.3) / waves
    for s in starts:
        L = rng.uniform(0.22, 0.42)
        u = (t - s) / L
        d += np.where((u >= 0) & (u <= 1), np.sin(np.pi * np.clip(u, 0, 1)) ** 1.5, 0) * rng.uniform(0.7, 1.0)
    fade = np.minimum(1, np.minimum(t / 0.08, (sec - t) / 0.15))
    return np.clip(d, 0, 1.4) * np.clip(fade, 0, 1)


# ---------------------------------------------------------------- simple: made from scratch

def clack(hard=1.0, pitch=1.0, dur=0.12):
    """A tile set down: a hard click (bone/plastic modes) and a soft thump of the felt under it."""
    t = t_of(dur)
    x = np.zeros_like(t)
    for f, d in [(2300, 0.022), (3600, 0.016), (5200, 0.010), (7400, 0.006), (1250, 0.030)]:
        f = f * pitch * rng.uniform(0.97, 1.03)
        x += rng.uniform(0.5, 1.0) * np.sin(2 * np.pi * f * t + rng.uniform(0, 6.28)) * np.exp(-t / (d * hard))
    x += 1.6 * highpass(rng.normal(0, 1, len(t)) * np.exp(-t / 0.0025), 1500)
    x += 0.8 * np.sin(2 * np.pi * 170 * pitch * t) * np.exp(-t / 0.018)
    x *= np.minimum(1, t / 0.0006)
    return x / np.max(np.abs(x))


def tick(pitch=1.0, dur=0.06):
    """Tile against tile, lighter and higher."""
    t = t_of(dur)
    x = np.zeros_like(t)
    for f, d in [(3100, 0.008), (4700, 0.006), (6600, 0.004), (2000, 0.010)]:
        f = f * pitch * rng.uniform(0.93, 1.07)
        x += rng.uniform(0.4, 1.0) * np.sin(2 * np.pi * f * t + rng.uniform(0, 6.28)) * np.exp(-t / d)
    x += 1.2 * highpass(rng.normal(0, 1, len(t)) * np.exp(-t / 0.0015), 2000)
    x *= np.minimum(1, t / 0.0004)
    return x / np.max(np.abs(x))


def note(f, dur, decay, bright=1.0):
    """A soft mallet note (marimba-like)."""
    t = t_of(dur)
    x = np.sin(2 * np.pi * f * t) * np.exp(-t / decay)
    x += 0.35 * bright * np.sin(2 * np.pi * f * 3.93 * t) * np.exp(-t / (decay * 0.25))
    x += 0.12 * np.sin(2 * np.pi * f * 2 * t) * np.exp(-t / (decay * 0.5))
    x *= np.minimum(1, t / 0.002)
    return x


def simple():
    for k in range(3):
        save('simple', f'discard-{k + 1}', room(np.concatenate([clack(pitch=rng.uniform(0.92, 1.08)), np.zeros(int(SR * 0.08))]), 0.12))
    for k in range(2):
        b = np.zeros(int(SR * 0.25))
        for i in range(4):
            place(b, tick(pitch=rng.uniform(0.85, 1.1)), i * 0.022 + rng.uniform(0, 0.006), rng.uniform(0.5, 1.0))
        place(b, clack(pitch=0.9, dur=0.1), 0.07, 0.5)
        save('simple', f'deal-{k + 1}', room(b, 0.1), 0.6)
    # The shuffle: soft ticks in waves (no rumble under them: not a machine turning).
    sec = 1.6
    env = burst_env(sec, 3, 0.1)
    b = np.zeros(int(SR * (sec + 0.15)))
    t = 0.0
    while t < sec:
        t += rng.exponential(1 / 90)
        d = env[min(len(env) - 1, int(t * SR))]
        if rng.random() < d:
            place(b, tick(pitch=rng.uniform(0.7, 1.0), dur=0.05), t, rng.uniform(0.25, 0.7) * (0.4 + 0.6 * d))
    save('simple', 'shuffle', room(lowpass(b, 7000), 0.12), 0.6)
    b = np.zeros(int(SR * 0.6))
    place(b, clack(), 0.0, 0.9)
    place(b, clack(pitch=1.05), 0.075, 0.8)
    place(b, note(784, 0.5, 0.18), 0.02, 0.55)
    save('simple', 'call', room(b, 0.2), 0.8)
    b = np.zeros(int(SR * 0.7))
    for i in range(3):
        place(b, clack(pitch=0.97 + 0.03 * i), i * 0.07, 0.85)
    place(b, note(587, 0.6, 0.22), 0.02, 0.6)
    save('simple', 'kong', room(b, 0.2), 0.8)
    b = np.zeros(int(SR * 1.3))
    for i, f in enumerate([523.25, 659.25, 783.99, 1046.5]):
        place(b, note(f, 1.0, 0.25 if i < 3 else 0.5), i * 0.085, 0.7 if i < 3 else 0.9)
    place(b, clack(pitch=0.95), 0.0, 0.5)
    save('simple', 'win', room(b, 0.3), 0.85)
    b = np.zeros(int(SR * 1.0))
    place(b, note(392.0, 0.8, 0.3, 0.5), 0.0, 0.8)
    place(b, note(311.1, 0.9, 0.4, 0.5), 0.18, 0.8)
    save('simple', 'lose', room(b, 0.3), 0.6)
    b = np.zeros(int(SR * 0.9))
    place(b, note(523.25, 0.7, 0.25, 0.6), 0.0, 0.7)
    place(b, note(523.25, 0.7, 0.3, 0.6), 0.16, 0.6)
    save('simple', 'drawn', room(b, 0.25), 0.6)
    save('simple', 'flower', room(note(1174.7, 0.4, 0.12), 0.2), 0.45)


# ---------------------------------------------------------------- real: from the CC0 recordings

def read(name):
    """A source recording (ogg, through ffmpeg), mono floats at SR."""
    with tempfile.TemporaryDirectory() as d:
        wav = os.path.join(d, 'x.wav')
        subprocess.run(['ffmpeg', '-loglevel', 'error', '-y', '-i', os.path.join(SRC, name), '-ac', '1', '-ar', str(SR), wav], check=True)
        with wave.open(wav) as w:
            x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(float) / 32768
    return x


def lower(x, factor):
    """Lower and slower by `factor` (< 1: a larger, heavier thing)."""
    up = 100
    return resample_poly(x, up, int(round(up * factor)))


def grains(x, length=0.06):
    """The single knocks of a recording: a short piece from just before each onset, faded."""
    hp = highpass(x, 1500)
    hop = int(SR * 0.004)
    e = np.array([np.sum(hp[i:i + hop] ** 2) for i in range(0, len(hp) - hop, hop)])
    flux = np.maximum(0, np.diff(np.log(e + 1e-9)))
    pk, _ = find_peaks(flux, height=1.5, distance=4)
    out = []
    n = int(length * SR)
    fade = np.minimum(1, np.minimum(np.arange(n) / (0.002 * SR), (n - np.arange(n)) / (0.02 * SR)))
    for p in pk:
        i = max(0, p * hop - int(0.002 * SR))
        g = x[i:i + n]
        if len(g) < n or np.max(np.abs(g)) < 0.02:
            continue
        out.append(g * fade / np.max(np.abs(g)))
    return out


def thump(f=150, decay=0.03, dur=0.12):
    """The felt and the table under a tile set down: a low, short thud."""
    t = t_of(dur)
    x = np.sin(2 * np.pi * f * t * (1 - 0.15 * t / dur)) * np.exp(-t / decay)
    x += 0.5 * lowpass(rng.normal(0, 1, len(t)), 400) * np.exp(-t / 0.01)
    return x * np.minimum(1, t / 0.001)


def real():
    lay = read('chip-lay-3.ogg')
    a = np.argmax(np.abs(lay) > 0.05)
    lay = lay[max(0, a - int(0.003 * SR)):a + int(0.25 * SR)]
    lay /= np.max(np.abs(lay))
    shakes = [read(n) for n in ('dice-shake-1.ogg', 'dice-shake-2.ogg', 'dice-shake-3.ogg', 'dice-grab-1.ogg', 'dice-grab-2.ogg')]
    knocks = [g for s in shakes for g in grains(lower(s, 0.74))]
    print('knocks', len(knocks))

    def tile_down(f, weight=0.4, pitch=1.0):
        """A tile set down on the felt: the chip's lay, lowered, and the thud of a heavier thing."""
        x = lower(lay, f * pitch)
        b = np.zeros(len(x) + int(0.05 * SR))
        place(b, x, 0, 1.0)
        place(b, thump(140 / f * 0.8), 0.0, weight)
        return lowpass(b, 9000)

    for k, f in enumerate([0.8, 0.76, 0.84]):
        save('real', f'discard-{k + 1}', room(tile_down(f), 0.1), 0.9)
    # A packet of four dealt: picked off the wall, set down together (a few knocks and one lay).
    for k in range(2):
        b = np.zeros(int(SR * 0.3))
        for i in range(4):
            place(b, knocks[rng.integers(len(knocks))], i * 0.018 + rng.uniform(0, 0.008), rng.uniform(0.25, 0.5))
        place(b, tile_down(0.82, 0.4), 0.06, 0.7)
        save('real', f'deal-{k + 1}', room(b, 0.08), 0.65)
    # The shuffle: the knocks in waves, a soft slide of tiles on the felt under the dense parts.
    sec = 1.6
    env = burst_env(sec, 3, 0.1)
    b = np.zeros(int(SR * (sec + 0.2)))
    t = 0.0
    while t < sec:
        t += rng.exponential(1 / 160)
        d = env[min(len(env) - 1, int(t * SR))]
        if rng.random() < d:
            place(b, knocks[rng.integers(len(knocks))], t, rng.uniform(0.15, 0.6) * (0.35 + 0.65 * d))
    slide = lowpass(highpass(rng.normal(0, 1, len(b)), 300), 2500) * 0.05
    slide[:len(env)] *= env
    slide[len(env):] = 0
    b += slide
    save('real', 'shuffle', room(b, 0.1), 0.75)
    # A chow or a pung: two tiles of the hand laid out face up, beside the one taken.
    b = np.zeros(int(SR * 0.45))
    place(b, tile_down(0.8, 0.5), 0.0, 0.9)
    place(b, tile_down(0.78, 0.5), 0.09, 0.85)
    place(b, tile_down(0.82, 0.4), 0.17, 0.6)
    save('real', 'call', room(b, 0.1), 0.85)
    # A kong: four laid out.
    b = np.zeros(int(SR * 0.55))
    for i in range(4):
        place(b, tile_down(0.78 + 0.02 * (i % 2), 0.5), i * 0.085, 0.9 - 0.08 * i)
    save('real', 'kong', room(b, 0.1), 0.85)
    # A win: the hand pushed down, tile after tile falling on its back along the row, ending on a thud.
    def push(n, gap, gain):
        b = np.zeros(int(SR * (n * gap + 0.4)))
        for i in range(n):
            g = gain * (0.55 + 0.45 * rng.random()) * (0.7 + 0.3 * i / n)
            place(b, knocks[rng.integers(len(knocks))], i * gap + rng.uniform(0, gap * 0.4), g * 0.6)
            if i % 3 == 0:
                place(b, tile_down(0.8 + rng.uniform(-0.03, 0.03), 0.35), i * gap, g * 0.5)
        place(b, thump(110, 0.05, 0.2), n * gap, 0.6 * gain)
        return b
    save('real', 'win', room(push(16, 0.018, 1.0), 0.12), 0.9)
    # The player's own deal-in: the winner's hand pushed down all the same, a little slower.
    save('real', 'lose', room(push(16, 0.024, 0.9), 0.12), 0.8)
    # A draw: the hands shown, slower and softer.
    save('real', 'drawn', room(push(12, 0.035, 0.7), 0.12), 0.7)
    # A flower: put out on the table, its replacement drawn.
    b = np.zeros(int(SR * 0.35))
    place(b, tile_down(0.86, 0.35), 0.0, 0.7)
    place(b, knocks[rng.integers(len(knocks))], 0.16, 0.25)
    save('real', 'flower', room(b, 0.1), 0.55)


simple()
real()
