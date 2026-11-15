"""The table's sounds (public/sounds/), tiles as they sound, made from recordings of dice and a chip on felt
(scripts/sounds-src/, Kenney's Casino Audio, CC0: LICENSE-Kenney.txt), lowered (tiles are larger than dice) and cut
into single knocks, laid out again: the shuffle in waves (hands pushing the tiles, dense; turning or lifted, sparse), a
discard on the felt a little lower than the chip (a heavier thing), a call's tile knocking into the others, a win's
hand laid down at once (on felt: shorter and duller than on a hard table), one sound for the hands shown at a hand's
end. A hand's result over the whole screen, the player's success, failure, or neither: a steel drum's jingles from
Kenney's Short Jingles (CC0: LICENSE-Kenney-jingles.txt), as they are. A declared ready hand: two mallet notes. A
call's cue, as its moment starts: steel pan notes, made here.

(A second set, made from scratch, was dropped on 2026-11-13 at the user's word. real() starts from the random state
it had after that set's sounds, so its sounds stay as they were.)

Each sound: 44.1 kHz mono WAV into OUT; then each to public/sounds as mp3:

    uv run --no-project --with numpy,scipy python scripts/make_sounds.py OUT
    for f in OUT/*.wav; do ffmpeg -i $f -ac 1 -b:a 64k public/sounds/$(basename $f .wav).mp3; done
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


# Every file as loud as it can be without clipping (its mp3 too, whose peaks run a little over the wav's): how loud
# each is played is the game's (each sound's gain in lib/sound.ts, Sounds.kt), always at most 1, so that nothing is
# made louder after it is decoded (the app's SoundPool cannot).
PEAK = 0.9


def save(name, x):
    os.makedirs(OUT, exist_ok=True)
    x = x / (np.max(np.abs(x)) + 1e-9) * PEAK
    pcm = (np.clip(x, -1, 1) * 32767).astype(np.int16)
    with wave.open(os.path.join(OUT, name + '.wav'), 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())
    print(name, f'{len(x) / SR:.2f}s')


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


def note(f, dur, decay, bright=1.0):
    """A soft mallet note (marimba-like)."""
    t = t_of(dur)
    x = np.sin(2 * np.pi * f * t) * np.exp(-t / decay)
    x += 0.35 * bright * np.sin(2 * np.pi * f * 3.93 * t) * np.exp(-t / (decay * 0.25))
    x += 0.12 * np.sin(2 * np.pi * f * 2 * t) * np.exp(-t / (decay * 0.5))
    x *= np.minimum(1, t / 0.002)
    return x


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
    # (Where the dropped set left it: the sounds as they were.)
    rng.bit_generator.state = {'bit_generator': 'PCG64', 'state': {'state': 253202470338661063978099255859279573086,
                               'inc': 261136684632268670825940853076396136793}, 'has_uint32': 0, 'uinteger': 0}
    lay = read('chip-lay-3.ogg')
    a = np.argmax(np.abs(lay) > 0.05)
    lay = lay[max(0, a - int(0.003 * SR)):a + int(0.25 * SR)]
    lay /= np.max(np.abs(lay))
    shakes = [read(n) for n in ('dice-shake-1.ogg', 'dice-shake-2.ogg', 'dice-shake-3.ogg', 'dice-grab-1.ogg', 'dice-grab-2.ogg')]
    knocks = [g for s in shakes for g in grains(lower(s, 0.74))]
    print('knocks', len(knocks))

    def tile_down(f=1.0, weight=0.12):
        """A tile set down on the felt: the chip's lay (a little lower: a heavier thing), a faint thud under it."""
        x = lower(lay, f)
        b = np.zeros(len(x) + int(0.05 * SR))
        place(b, x, 0, 1.0)
        place(b, thump(200, 0.02), 0.0, weight)
        return lowpass(b, 14000)

    def knock():
        return knocks[rng.integers(len(knocks))]

    def clatter(n, spread, gain=1.0, at=0.0, into=None):
        """Tiles knocking into each other all at once (a meld pushed together, a hand laid down): `n` knocks within
        `spread` s, most of them at the start, softer as they go."""
        b = into if into is not None else np.zeros(int(SR * (at + spread + 0.15)))
        ts = np.sort(spread * rng.random(n) ** 1.6)
        ts[0] = 0
        for k, t in enumerate(ts):
            place(b, knock(), at + t, gain * rng.uniform(0.6, 1.0) * (1 - 0.5 * k / n))
        return b

    # A discard: the chip's lay as it is (the user's ear: any lower is too low), takes a shade apart.
    # (2026-11-13 again: the chip's pitch still too low and loud: higher, no thud.)
    for k, f in enumerate([1.15, 1.12, 1.18]):
        save(f'discard-{k + 1}', room(tile_down(f, 0.0), 0.08))
    # The deal: the hands' tiles come in all at once, slid on the table (no tile set down), knocking into each other:
    # one clatter, softer than a hand laid down.
    for k in range(2):
        b = lowpass(clatter(12, 0.1, 0.8), 9000)
        save(f'deal-{k + 1}', room(b, 0.06))
    # The shuffle: the knocks in waves, a soft slide of tiles on the felt under the dense parts. The random draws start
    # where they did for the take the user picked (2026-11-13), so that the other sounds can change without it. Its
    # length: deal_shuffle (2.5 s since 2026-11-13, a wave of pushes for each of the tiles' spots, DealFx) and a little
    # more, its tail heard in deal_pause.
    rng.bit_generator.state = {'bit_generator': 'PCG64', 'has_uint32': 0, 'uinteger': 328313141, 'state': {
        'state': 171114139180760816764104165298077797073, 'inc': 261136684632268670825940853076396136793}}
    sec = 2.7
    env = burst_env(sec, 5, 0.1)
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
    save('shuffle', room(b, 0.1))
    # The sounds after it from where they were before it changed (its 1.6 s take): the same as the user heard them.
    rng.bit_generator.state = {'bit_generator': 'PCG64', 'has_uint32': 1, 'uinteger': 4198255125, 'state': {
        'state': 47841476824944301602558690402175359218, 'inc': 261136684632268670825940853076396136793}}
    # A chow or a pung: the tile taken knocking into the two (the moment's tile landing in its gap).
    save('call', room(clatter(3, 0.012), 0.06))
    # A kong: into three.
    save('kong', room(clatter(5, 0.02), 0.06))

    def laid(n, spread, gain, dull):
        """A hand laid down at once: its weight on the felt, the tiles knocking into each other, quickly still."""
        b = np.zeros(int(SR * (spread + 0.3)))
        place(b, tile_down(0.9, 0.25), 0.0, 0.9 * gain)
        clatter(n, spread, 0.8 * gain, 0.002, b)
        return lowpass(b, dull)
    # The hands shown at a hand's end (as they open: one sound, one hand laid down), a shade apart by what it means
    # to the player: their own win; another's they neither gain nor lose by; another's they pay for; a draw.
    hand = laid(14, 0.09, 1.0, 12000)
    save('win', room(hand, 0.08))
    save('other', room(lower(hand, 0.95), 0.08))
    save('drawn', room(lower(hand, 0.92), 0.08))
    save('lose', room(lower(hand, 0.88), 0.08))
    # A flower: put out on the table; a moment later, its replacement drawn.
    b = np.zeros(int(SR * 0.5))
    place(b, tile_down(0.93), 0.0, 0.7)
    place(b, knock(), 0.3, 0.25)
    save('flower', room(b, 0.08))

def jingles():
    """A hand's result to the player: their own win (rising), a hand they pay for (falling), neither."""
    for name, src in [('success', 'jingles_STEEL02.ogg'), ('failure', 'jingles_STEEL01.ogg'), ('neutral', 'jingles_STEEL13.ogg')]:
        x = read(src)
        on = np.where(np.abs(x) > 0.01 * np.max(np.abs(x)))[0]
        x = x[max(0, on[0] - int(0.005 * SR)):]
        save(name, x)


def declare():
    """天聽, 地聽, 報聽: surprise. Two mallet notes, the second a fourth higher and longer (the user's: two sounds, the
    second a little higher and longer)."""
    b = np.zeros(int(SR * 0.9))
    place(b, note(783.99, 0.25, 0.07), 0.0, 0.7)
    place(b, note(1046.5, 0.8, 0.22), 0.13, 1.0)
    save('declare', room(b, 0.2))


def pan(f, dur, decay, bright=1.0):
    """A steel pan's note (the results' jingles are a steel drum's): the note, its octave and twelfth (a pan's are
    tuned so), the octave a shade apart from the note's double (its slow shimmer), the upper ones dying first; a
    small metal tick at the strike."""
    t = t_of(dur)
    x = np.sin(2 * np.pi * f * t) * np.exp(-t / decay)
    x += 0.5 * bright * np.sin(2 * np.pi * (2 * f + 1.3) * t) * np.exp(-t / (decay * 0.6))
    x += 0.2 * bright * np.sin(2 * np.pi * 3 * f * t) * np.exp(-t / (decay * 0.3))
    x *= np.minimum(1, t / 0.0015)
    tick = highpass(rng.normal(0, 1, len(t)), 3000) * np.exp(-t / 0.004) * 0.12 * bright
    return x + tick


def cues():
    """A call's cue, as its moment starts (the tiles' knock comes as the tile lands, a moment later): something
    other than a discard happens. Steel pan notes, short and light (calls come often), each call its own shape: a
    chow three notes stepping up (a run); a pung three strikes of one note, the last ringing on with its fifth (three
    alike); an open kong the pung's strikes and then the fourth, a chord a beat later; a concealed kong the same
    darker, lower and muted (turned face down); an added kong one note, then it and its octave (one tile set on the
    three). The random state of their own, so that the sounds above stay as they are."""
    rng.bit_generator.state = np.random.default_rng(20261116).bit_generator.state
    C5, G5, A5, C6, D6, E6 = 523.25, 783.99, 880.0, 1046.5, 1174.66, 1318.51

    def shape(sec, notes, dull=None):
        b = np.zeros(int(SR * sec))
        for at, fs, gain, decay, bright in notes:
            for f in fs:
                place(b, pan(f, sec - at, decay, bright), at, gain / len(fs) ** 0.5)
        if dull:
            b = lowpass(b, dull)
        return room(b, 0.18)
    save('cue-chi', shape(0.6, [(0.0, [C6], 0.55, 0.07, 1.0), (0.06, [D6], 0.65, 0.07, 1.0),
                                (0.12, [E6], 0.9, 0.22, 1.0)]))
    save('cue-pon', shape(0.65, [(0.0, [A5], 0.55, 0.06, 1.0), (0.06, [A5], 0.65, 0.06, 1.0),
                                 (0.12, [A5, E6], 1.0, 0.25, 1.0)]))
    save('cue-kan', shape(0.85, [(0.0, [G5], 0.5, 0.05, 1.0), (0.055, [G5], 0.55, 0.05, 1.0),
                                 (0.11, [G5], 0.6, 0.05, 1.0), (0.24, [G5, D6, 2 * G5], 1.1, 0.35, 1.0)]))
    save('cue-ankan', shape(0.85, [(0.0, [C5], 0.5, 0.05, 0.4), (0.055, [C5], 0.55, 0.05, 0.4),
                                   (0.11, [C5], 0.6, 0.05, 0.4), (0.24, [C5, G5], 1.0, 0.3, 0.4)],
                                 dull=3000))
    save('cue-kakan', shape(0.7, [(0.0, [G5], 0.55, 0.06, 0.6), (0.1, [G5, 2 * G5], 1.0, 0.3, 1.0)]))


real()
jingles()
declare()
cues()
