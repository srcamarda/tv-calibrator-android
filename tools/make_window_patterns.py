"""Window test patterns for measuring a TV with TV Cal Probe, as PNGs to play from a USB stick.

Each image is a black 3840x2160 frame with a centred window (40% of the width and height, 16% of the
area) at one level. A small window keeps the TV's brightness limiter (ABL) out of the readings, as a
full white screen can dim itself. The name of the pattern sits in a corner in dark grey, away from
the window, so you can tell which one is on screen.

Levels are full-range 8-bit RGB (0-255), which TVs show correctly for photos.

    python tools/make_window_patterns.py [output folder]
"""

import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

W, H = 3840, 2160
WINDOW = 0.40

# (file name, label in the app, RGB)
PATTERNS = [
    ("01-W100", "W100", (255, 255, 255)),
    ("02-G75", "G75", (191, 191, 191)),
    ("03-G50", "G50", (128, 128, 128)),
    ("04-G25", "G25", (64, 64, 64)),
    ("05-G10", "G10", (26, 26, 26)),
    ("06-R", "R", (255, 0, 0)),
    ("07-G", "G", (0, 255, 0)),
    ("08-B", "B", (0, 0, 255)),
]


def font(size: int):
    for name in ("arial.ttf", "DejaVuSans.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            pass
    return ImageFont.load_default()


def main() -> None:
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parent.parent / "patterns"
    out.mkdir(parents=True, exist_ok=True)
    label_font = font(48)
    w, h = int(W * WINDOW), int(H * WINDOW)
    x0, y0 = (W - w) // 2, (H - h) // 2
    for name, label, rgb in PATTERNS:
        img = Image.new("RGB", (W, H), (0, 0, 0))
        d = ImageDraw.Draw(img)
        d.rectangle([x0, y0, x0 + w - 1, y0 + h - 1], fill=rgb)
        d.text((60, H - 110), f"{label}  RGB {rgb[0]},{rgb[1]},{rgb[2]}", fill=(48, 48, 48), font=label_font)
        img.save(out / f"{name}.png", optimize=True)
        print(out / f"{name}.png")


if __name__ == "__main__":
    main()
