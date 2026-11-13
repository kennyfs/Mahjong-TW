"""The table's sounds (public/sounds/), made from scratch here, no recordings: a tile on the table, tiles on tiles,
the shuffle, a call's, a kong's, a win's, the player's deal-in, a draw, a flower. 44.1 kHz mono WAV into OUT; then
each to public/sounds as mp3:

    uv run --no-project --with numpy python scripts/make_sounds.py OUT
    for f in OUT/*.wav; do ffmpeg -i $f -ac 1 -b:a 64k public/sounds/$(basename $f .wav).mp3; done
"""
import os
import sys
import wave

import numpy as np

SR = 44100
OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
rng = np.random.default_rng(7)


def t_of(sec):
    return np.arange(int(SR * sec)) / SR


def lowpass(x, fc):
    # one-pole, twice
    a = np.exp(-2 * np.pi * fc / SR)
    for _ in range(2):
        y = np.empty_like(x)
        s = 0.0
        for i, v in enumerate(x):
            s = (1 - a) * v + a * s
            y[i] = s
        x = y
    return x


def highpass(x, fc):
    return x - lowpass(x, fc)


def clack(hard=1.0, pitch=1.0, dur=0.12):
    """A tile set down on the table: a hard click (bone/plastic modes) and a soft thump of the felt under it."""
    t = t_of(dur)
    modes = [(2300, 0.022), (3600, 0.016), (5200, 0.010), (7400, 0.006), (1250, 0.030)]
    x = np.zeros_like(t)
    for f, d in modes:
        f = f * pitch * rng.uniform(0.97, 1.03)
        x += rng.uniform(0.5, 1.0) * np.sin(2 * np.pi * f * t + rng.uniform(0, 6.28)) * np.exp(-t / (d * hard))
    n = rng.normal(0, 1, len(t)) * np.exp(-t / 0.0025)
    x += 1.6 * highpass(n, 1500)
    thump = np.sin(2 * np.pi * 170 * pitch * t) * np.exp(-t / 0.018)
    x += 0.8 * thump
    x *= np.minimum(1, t / 0.0006)
    return x / np.max(np.abs(x))


def tick(pitch=1.0, dur=0.06):
    """Tile against tile, lighter and higher (dealing, shuffling)."""
    t = t_of(dur)
    x = np.zeros_like(t)
    for f, d in [(3100, 0.008), (4700, 0.006), (6600, 0.004), (2000, 0.010)]:
        f = f * pitch * rng.uniform(0.93, 1.07)
        x += rng.uniform(0.4, 1.0) * np.sin(2 * np.pi * f * t + rng.uniform(0, 6.28)) * np.exp(-t / d)
    x += 1.2 * highpass(rng.normal(0, 1, len(t)) * np.exp(-t / 0.0015), 2000)
    x *= np.minimum(1, t / 0.0004)
    return x / np.max(np.abs(x))


def place(buf, x, at, gain):
    i = int(at * SR)
    j = min(len(buf), i + len(x))
    buf[i:j] += gain * x[: j - i]


def shuffle(sec=1.6):
    """Hands swirling the tiles on the felt: a rumble of sliding and many small knocks, swelling and dying away."""
    n = int(SR * (sec + 0.2))
    buf = np.zeros(n)
    env = lambda u: np.sin(np.pi * np.clip(u / sec, 0, 1)) ** 0.6
    slide = lowpass(rng.normal(0, 1, n), 900) * 6
    slide *= np.array([env(i / SR) for i in range(n)])
    buf += 0.35 * slide
    t = 0.0
    while t < sec:
        t += rng.exponential(1 / 70)
        g = env(t) * rng.uniform(0.15, 0.6)
        place(buf, tick(pitch=rng.uniform(0.75, 1.15), dur=0.05), t, g)
    return buf


def note(f, dur, decay, bright=1.0):
    """A soft mallet note (marimba-like: the fundamental, a 4x partial dying fast)."""
    t = t_of(dur)
    x = np.sin(2 * np.pi * f * t) * np.exp(-t / decay)
    x += 0.35 * bright * np.sin(2 * np.pi * f * 3.93 * t) * np.exp(-t / (decay * 0.25))
    x += 0.12 * np.sin(2 * np.pi * f * 2 * t) * np.exp(-t / (decay * 0.5))
    x *= np.minimum(1, t / 0.002)
    return x


def room(x, wet=0.18):
    """A little room around it: a few early echoes, softened."""
    y = x.copy()
    for d, g in [(0.019, 0.5), (0.031, 0.4), (0.047, 0.3), (0.071, 0.2), (0.097, 0.12)]:
        k = int(d * SR)
        y[k:] += wet * g * lowpass(x[:-k], 3500) * 2
    return y


def save(name, x, peak=0.9):
    x = x / np.max(np.abs(x)) * peak
    pcm = (np.clip(x, -1, 1) * 32767).astype(np.int16)
    with wave.open(os.path.join(OUT, name + '.wav'), 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())
    print(name, f'{len(x) / SR:.2f}s')


# A tile discarded: three takes, a little different (one is picked at random each time).
for k in range(3):
    save(f'discard-{k + 1}', room(np.concatenate([clack(pitch=rng.uniform(0.92, 1.08)), np.zeros(int(SR * 0.08))]), 0.12))

# A packet of four dealt: four quick ticks landing together.
for k in range(2):
    b = np.zeros(int(SR * 0.25))
    for i in range(4):
        place(b, tick(pitch=rng.uniform(0.85, 1.1)), i * 0.022 + rng.uniform(0, 0.006), rng.uniform(0.5, 1.0))
    place(b, clack(pitch=0.9, dur=0.1), 0.07, 0.5)
    save(f'deal-{k + 1}', room(b, 0.1), 0.6)

save('shuffle', room(shuffle(1.6), 0.15), 0.7)

# A chow or a pung: the tiles laid out (two knocks) and a light mallet note over them.
b = np.zeros(int(SR * 0.6))
place(b, clack(pitch=1.0), 0.0, 0.9)
place(b, clack(pitch=1.05), 0.075, 0.8)
place(b, note(784, 0.5, 0.18), 0.02, 0.55)
save('call', room(b, 0.2), 0.8)

# A kong: three knocks, a lower note.
b = np.zeros(int(SR * 0.7))
for i in range(3):
    place(b, clack(pitch=0.97 + 0.03 * i), i * 0.07, 0.85)
place(b, note(587, 0.6, 0.22), 0.02, 0.6)
save('kong', room(b, 0.2), 0.8)

# A win: a quick rising arpeggio, the last note held.
b = np.zeros(int(SR * 1.3))
for i, f in enumerate([523.25, 659.25, 783.99, 1046.5]):
    place(b, note(f, 1.0, 0.25 if i < 3 else 0.5), i * 0.085, 0.7 if i < 3 else 0.9)
place(b, clack(pitch=0.95), 0.0, 0.5)
save('win', room(b, 0.3), 0.85)

# The player's own deal-in: two low notes falling, soft.
b = np.zeros(int(SR * 1.0))
place(b, note(392.0, 0.8, 0.3, 0.5), 0.0, 0.8)
place(b, note(311.1, 0.9, 0.4, 0.5), 0.18, 0.8)
save('lose', room(b, 0.3), 0.6)

# A draw (流局): two even notes, neither up nor down.
b = np.zeros(int(SR * 0.9))
place(b, note(523.25, 0.7, 0.25, 0.6), 0.0, 0.7)
place(b, note(523.25, 0.7, 0.3, 0.6), 0.16, 0.6)
save('drawn', room(b, 0.25), 0.6)

# A flower (補花): one small bright note.
save('flower', room(note(1174.7, 0.4, 0.12), 0.2), 0.45)
