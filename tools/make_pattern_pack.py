"""The SDR pattern pack for the guided steps, as still pictures for a USB stick.

One picture per step pattern, named like upstream's pattern videos ("SDR 01 - White (bright end).png"),
so the app's "Show on the TV" line matches the file. Each is a black 3840x2160 frame with the measured
window exactly where upstream's videos put it: 1214x684 at (1312, 738), 10% of the screen. The pattern's
name sits in a corner in dark grey, away from the window.

Levels: greys are the video's signal level as full-range 8-bit (G30 = 30% = 77); colours are 75%
(191), as in the videos. TVs show photos full-range, so these match the video levels.

The Quick check (SDR 00) is a video with sync flashes: play it from YouTube (https://youtu.be/jGk6o8bytjY).

    python tools/make_pattern_pack.py [output folder]
"""

import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

W, H = 3840, 2160
WIN_W, WIN_H, WIN_X, WIN_Y = 1214, 684, 1312, 738

# (file name, RGB 0-1): CalibrationKit Patterns.packFiles(hdr = false), without the Quick check
PACK = [
    ("SDR 01 - White (bright end)", (1.0, 1.0, 1.0)),
    ("SDR 02 - Grey 30 (dark end)", (0.3, 0.3, 0.3)),
    ("SDR 03 - Grey 50 (middle)", (0.5, 0.5, 0.5)),
    ("SDR 04 - Grey 20", (0.2, 0.2, 0.2)),
    ("SDR 05 - Grey 40", (0.4, 0.4, 0.4)),
    ("SDR 06 - Grey 60", (0.6, 0.6, 0.6)),
    ("SDR 07 - Grey 80", (0.8, 0.8, 0.8)),
    ("SDR 08 - Red", (0.75, 0, 0)),
    ("SDR 09 - Green", (0, 0.75, 0)),
    ("SDR 10 - Blue", (0, 0, 0.75)),
    ("SDR 11 - Cyan", (0, 0.75, 0.75)),
    ("SDR 12 - Magenta", (0.75, 0, 0.75)),
    ("SDR 13 - Yellow", (0.75, 0.75, 0)),
]


def font(size: int):
    for name in ("arial.ttf", "DejaVuSans.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            pass
    return ImageFont.load_default()


def main() -> None:
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parent.parent / "patterns" / "pack"
    out.mkdir(parents=True, exist_ok=True)
    label_font = font(48)
    for name, level in PACK:
        rgb = tuple(round(v * 255) for v in level)
        img = Image.new("RGB", (W, H), (0, 0, 0))
        d = ImageDraw.Draw(img)
        d.rectangle([WIN_X, WIN_Y, WIN_X + WIN_W - 1, WIN_Y + WIN_H - 1], fill=rgb)
        d.text((60, H - 110), name, fill=(48, 48, 48), font=label_font)
        img.save(out / f"{name}.png", optimize=True)
        print(out / f"{name}.png")


if __name__ == "__main__":
    main()
