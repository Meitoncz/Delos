"""Generates the Delos UI sounds (see docs/GUI.md) into app/src/main/res/raw/.

The "Soft" set, chosen from the prototype variants: each cue is one or two dry sine notes on a
pentatonic scale, an octave-ish low (all pitches x0.6 of C major pentatonic around C5), with a soft
12 ms exponential attack, a smooth exponential fade, a barely audible pitch glide, a trace of the
second harmonic and a low-pass filter. Standard library only:

    python tools/sounds/make_ui_sounds.py

The numbers mirror the WebAudio prototype (a note is `tone()` there): peak gain, attack and decay in
seconds, glide in seconds, partials as (ratio, gain, decay scale), low-pass cut-off in Hz.
"""

import math
import struct
import wave
from pathlib import Path

RAW = Path(__file__).resolve().parents[2] / "app/src/main/res/raw"
RATE = 44100
FLOOR = 0.0001  # exponential ramps cannot start or end at zero
# The prototype's gains are relative to a quiet master; on the media stream (which the volume keys control)
# the sounds need this boost to be heard at a normal volume.
LOUDNESS = 2.5

# pentatonic anchors (Hz) and the shared pitch scale
G5, E5, A5, C5, D5 = 783.99, 659.25, 880.0, 523.25, 587.33
K = 0.6
HARMONIC = [(1, 1.0, 1.0), (2, 0.12, 0.6)]


def note(t, f0, f1, glide, gain, dec, lp, partials=HARMONIC, attack=0.012):
    return dict(t=t, f0=f0, f1=f1, glide=glide, gain=gain, dec=dec, lp=lp, partials=partials, attack=attack)


# name: notes
SOUNDS = {
    "ui_move": [note(0, G5 * K * 0.985, G5 * K, 0.05, 0.04, 0.13, 1800)],
    "ui_select": [
        note(0, E5 * K * 0.97, E5 * K, 0.05, 0.06, 0.15, 1800),
        note(0.08, A5 * K, A5 * K, 0.05, 0.06, 0.20, 1800),
    ],
    "ui_back": [
        note(0, A5 * K, A5 * K, 0.05, 0.055, 0.13, 1800),
        note(0.08, E5 * K, E5 * K * 0.98, 0.10, 0.055, 0.20, 1800),
    ],
    "ui_open": [
        note(0, C5 * K, C5 * K, 0.05, 0.055, 0.12, 1800),
        note(0.08, G5 * K, G5 * K, 0.05, 0.055, 0.20, 1800),
    ],
    "ui_error": [note(0, D5 * K * 0.98, D5 * K * 0.9, 0.12, 0.07, 0.20, 1200, [(1, 1.0, 1.0), (2, 0.10, 0.6)])],
}


def partial(n: dict, ratio: float, peak: float, dec_scale: float, count: int) -> list:
    """One partial of a note, starting at its own time; zeros before the note."""
    out = [0.0] * count
    start = round(n["t"] * RATE)
    dec = n["dec"] * dec_scale
    attack = n["attack"]
    phase = 0.0
    total = attack + dec
    for i in range(start, min(count, start + int(RATE * (total + 0.01)))):
        t = (i - start) / RATE
        freq = n["f0"] * (n["f1"] / n["f0"]) ** (min(t, n["glide"]) / n["glide"]) * ratio
        phase += 2 * math.pi * freq / RATE
        if t < attack:
            g = FLOOR * (peak / FLOOR) ** (t / attack)
        elif t < total:
            g = peak * (FLOOR / peak) ** ((t - attack) / dec)
        else:
            g = FLOOR
        out[i] = g * math.sin(phase)
    return out


def lowpass(x: list, cutoff: float, q_db: float = 1.0) -> list:
    """RBJ biquad low-pass, like WebAudio's default (Q 1 dB)."""
    q = 10 ** (q_db / 20)
    w0 = 2 * math.pi * cutoff / RATE
    alpha = math.sin(w0) / (2 * q)
    cosw = math.cos(w0)
    b0 = (1 - cosw) / 2
    b1 = 1 - cosw
    b2 = b0
    a0 = 1 + alpha
    a1 = -2 * cosw
    a2 = 1 - alpha
    y = [0.0] * len(x)
    x1 = x2 = y1 = y2 = 0.0
    for i, v in enumerate(x):
        o = (b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2) / a0
        y[i] = o
        x2, x1, y2, y1 = x1, v, y1, o
    return y


def render(notes: list) -> bytes:
    length = max(n["t"] + n["attack"] + n["dec"] * max(p[2] for p in n["partials"]) for n in notes)
    count = int(RATE * (length + 0.05))
    mix = [0.0] * count
    for n in notes:
        voice = [0.0] * count
        for ratio, pg, ds in n["partials"]:
            for i, v in enumerate(partial(n, ratio, n["gain"] * pg, ds, count)):
                voice[i] += v
        for i, v in enumerate(lowpass(voice, n["lp"])):
            mix[i] += v
    peak = max(abs(v) for v in mix) * LOUDNESS
    assert peak < 0.9, f"clipping risk: peak {peak:.2f}"
    return b"".join(struct.pack("<h", round(32767 * LOUDNESS * v)) for v in mix)


def main() -> None:
    RAW.mkdir(parents=True, exist_ok=True)
    for name, notes in SOUNDS.items():
        path = RAW / f"{name}.wav"
        with wave.open(str(path), "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(RATE)
            w.writeframes(render(notes))
        print(f"{path.name}: {path.stat().st_size} B")


if __name__ == "__main__":
    main()
