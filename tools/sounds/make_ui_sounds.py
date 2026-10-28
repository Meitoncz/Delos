"""Generates the Delos UI sounds (see docs/GUI.md) into app/src/main/res/raw/.

Each sound is the prototype's WebAudio blip (docs/prototype/delos-gui.html, blip()): a sine wave
gliding exponentially from f0 to f1 over 70 ms, with a 10 ms exponential attack and a decay to
silence at 140 ms (160 ms in total). Standard library only:

    python tools/sounds/make_ui_sounds.py
"""

import math
import struct
import wave
from pathlib import Path

RAW = Path(__file__).resolve().parents[2] / "app/src/main/res/raw"
RATE = 44100

# name: (f0, f1, peak gain)
SOUNDS = {
    "ui_move": (660, 720, 0.025),
    "ui_select": (740, 990, 0.05),
    "ui_back": (520, 380, 0.05),
    "ui_open": (440, 660, 0.05),
    "ui_error": (220, 180, 0.05),
}

FLOOR = 0.0001  # exponential ramps cannot start or end at zero


def gain(t: float, peak: float) -> float:
    if t < 0.01:
        return FLOOR * (peak / FLOOR) ** (t / 0.01)
    if t < 0.14:
        return peak * (FLOOR / peak) ** ((t - 0.01) / 0.13)
    return FLOOR


def render(f0: float, f1: float, peak: float) -> bytes:
    frames = bytearray()
    phase = 0.0
    for i in range(int(RATE * 0.16)):
        t = i / RATE
        freq = f0 * (f1 / f0) ** (min(t, 0.07) / 0.07)
        phase += 2 * math.pi * freq / RATE
        frames += struct.pack("<h", round(32767 * gain(t, peak) * math.sin(phase)))
    return bytes(frames)


def main() -> None:
    RAW.mkdir(parents=True, exist_ok=True)
    for name, (f0, f1, peak) in SOUNDS.items():
        path = RAW / f"{name}.wav"
        with wave.open(str(path), "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(RATE)
            w.writeframes(render(f0, f1, peak))
        print(f"{path.name}: {path.stat().st_size} B")


if __name__ == "__main__":
    main()
