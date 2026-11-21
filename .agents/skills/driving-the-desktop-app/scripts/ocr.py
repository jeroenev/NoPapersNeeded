"""Find text in a screenshot, so an agent that cannot see images can locate UI elements.

Usage:
  uv run --quiet --with rapidocr-onnxruntime --with pillow python ocr.py <image> [phrase]
  uv run ... ocr.py <image> --crop x0,y0,x1,y1 [phrase]

Without a phrase: prints every text box as  'text' @ (cx,cy) box=(x0,y0)-(x1,y1) conf=0.97
With a phrase:    prints the best fuzzy match's centre, or exits 1 with the closest candidates.
Coordinates are always full-image pixels (a crop is offset back), so they can go straight
to `xdotool mousemove X Y click 1` or `adb shell input tap X Y`.
Low-confidence hits (conf < 0.6) are mostly icons misread as CJK glyphs; filter them out.
"""

import difflib
import sys

from PIL import Image
from rapidocr_onnxruntime import RapidOCR

args = sys.argv[1:]
crop = None
if "--crop" in args:
    i = args.index("--crop")
    crop = tuple(int(v) for v in args[i + 1].split(","))
    del args[i : i + 2]
image_path = args[0]
phrase = args[1] if len(args) > 1 else None

img = Image.open(image_path).convert("RGB")
ox, oy = 0, 0
if crop:
    img = img.crop(crop)
    ox, oy = crop[0], crop[1]

result, _ = RapidOCR()(img)
hits = []
for box, text, conf in result or []:
    xs = [p[0] + ox for p in box]
    ys = [p[1] + oy for p in box]
    hits.append((text, sum(xs) / 4, sum(ys) / 4, min(xs), min(ys), max(xs), max(ys), float(conf)))


def fmt(e):
    return (
        f"{e[0]!r} @ ({e[1]:.0f},{e[2]:.0f}) "
        f"box=({e[3]:.0f},{e[4]:.0f})-({e[5]:.0f},{e[6]:.0f}) conf={e[7]:.2f}"
    )


if not phrase:
    for e in hits:
        print(fmt(e))
    sys.exit(0)


def score(e):
    return difflib.SequenceMatcher(None, e[0].lower(), phrase.lower()).ratio()


ranked = sorted(hits, key=score, reverse=True)
if not ranked or score(ranked[0]) < 0.5:
    print(f"NOT FOUND: {phrase!r}; closest:")
    for e in ranked[:5]:
        print("  " + fmt(e))
    sys.exit(1)
print(fmt(ranked[0]))
