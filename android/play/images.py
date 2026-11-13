"""Google Play feature graphic (1024 x 500) and 512 x 512 icon, drawn with the app's own tiles (web/src/lib/tile-art.ts,
made by art/make_tiles.py) on its felt. Run from anywhere:
    uv run --no-project --with cairosvg --with pillow python android/play/images.py [OUT]
OUT defaults to this folder. Needs the Noto CJK fonts (fonts-noto-cjk) and cairo."""
import io
import math
import os
import re
import sys

import cairosvg
from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
OUT = sys.argv[1] if len(sys.argv) > 1 else HERE
NOTO = "/usr/share/fonts/opentype/noto"
TC = 3  # Noto Serif/Sans CJK TC in the collections
FELT, FELT_LIGHT, FELT_DARK = "#1F6F54", (38, 132, 99), (14, 66, 49)
FACE, EDGE, BACK = "#FDFBF3", "#D6D0C0", "#28559B"
S = 2  # drawn at twice the size, then scaled down for smooth edges


def tile_art():
    text = open(os.path.join(ROOT, "web", "src", "lib", "tile-art.ts"), encoding="utf-8").read()
    art = {}
    for key, body in re.findall(r'^  "([0-9a-z]+)": \[\n(.*?)^  \],', text, re.M | re.S):
        art[key] = [(d, c, even == "true") for d, c, even in re.findall(r'\["([^"]*)", "([^"]*)", (true|false)\]', body)]
    return art


ART = tile_art()


def tile_svg(key):
    """A standing tile as the app draws it: the face (60 x 80) on its blue back (60 x 88, the tile's thickness)."""
    parts = [f'<rect x="0" y="0" width="60" height="88" rx="7" fill="{BACK}"/>',
             f'<rect x="0" y="0" width="60" height="80" rx="7" fill="{FACE}" stroke="{EDGE}" stroke-width="1.2"/>']
    for d, c, even in ART[key]:
        parts.append(f'<path d="{d}" fill="{c}"{" fill-rule=\"evenodd\"" if even else ""}/>')
    return "\n".join(parts)


def render(svg, w, h):
    return Image.open(io.BytesIO(cairosvg.svg2png(bytestring=svg.encode(), output_width=w, output_height=h))).convert("RGBA")


def tile_image(key, width):
    height = round(width * 88 / 60)
    svg = f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="-1 -1 62 90">{tile_svg(key)}</svg>'
    return render(svg, width + round(width / 30), height + round(width / 30))


def paste_shadowed(base, im, cx, cy, angle=0.0):
    """Pastes im centred at (cx, cy), rotated, over a soft shadow."""
    if angle:
        im = im.rotate(angle, resample=Image.BICUBIC, expand=True)
    shadow = Image.new("RGBA", im.size, (0, 0, 0, 0))
    shadow.putalpha(im.getchannel("A").point(lambda a: a * 0.45))
    pad = 12 * S
    sh = Image.new("RGBA", (im.width + 2 * pad, im.height + 2 * pad), (0, 0, 0, 0))
    sh.paste(shadow, (pad, pad))
    sh = sh.filter(ImageFilter.GaussianBlur(6 * S))
    x, y = round(cx - im.width / 2), round(cy - im.height / 2)
    base.alpha_composite(sh, (x - pad + 3 * S, y - pad + 6 * S))
    base.alpha_composite(im, (x, y))


def felt(w, h):
    """The table's felt, lighter in the middle (as under a lamp)."""
    im = Image.new("RGB", (w, h), FELT_DARK)
    glow = Image.new("L", (w, h), 0)
    ImageDraw.Draw(glow).ellipse([-w * 0.1, -h * 0.6, w * 1.1, h * 1.5], fill=255)
    glow = glow.filter(ImageFilter.GaussianBlur(min(w, h) * 0.25))
    im.paste(Image.new("RGB", (w, h), FELT_LIGHT), (0, 0), glow)
    return im.convert("RGBA")


def font(name, size):
    return ImageFont.truetype(os.path.join(NOTO, name), size * S, index=TC)


def feature():
    w, h = 1024, 500
    im = felt(w * S, h * S)
    # Three sets fanned out on the right, each tile clear of the next.
    keys = ["1m", "2m", "3m", "1z", "1z", "1z", "6z", "6z", "6z"]
    tw, gap = 58 * S, 63 * S
    n = len(keys)
    cx, radius = 712 * S, 900 * S
    cy = 280 * S + radius
    step = math.degrees(gap / radius)
    for i, key in enumerate(keys):
        a = (i - (n - 1) / 2) * step  # degrees from the vertical
        r = math.radians(a)
        x, y = cx + radius * math.sin(r), cy - radius * math.cos(r)
        paste_shadowed(im, tile_image(key, tw), x, y, -a)
    d = ImageDraw.Draw(im)
    d.text((70 * S, 150 * S), "台灣麻將", font=font("NotoSerifCJK-Bold.ttc", 92), fill=FACE, anchor="ls")
    d.text((74 * S, 222 * S), "AI 分析與連線對戰", font=font("NotoSansCJK-Bold.ttc", 40), fill=(214, 238, 226), anchor="ls")
    small = font("NotoSansCJK-Medium.ttc", 24)
    for i, line in enumerate(["和神經網路電腦打十六張", "每一局都能回顧與分析", "網頁版：mahjong.1li.tw"]):
        d.text((76 * S, (300 + 40 * i) * S), line, font=small, fill=(176, 216, 196), anchor="ls")
    return im.resize((w, h), Image.LANCZOS).convert("RGB")


def icon():
    """The launcher icon (web/public/favicon.svg) with the felt to the edges: Play rounds the corners itself."""
    svg = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 96 96">'
           f'<rect width="96" height="96" fill="{FELT}"/>'
           f'<g transform="translate(23 14.67) scale(0.8333)">{tile_svg("6z")}</g></svg>')
    return render(svg, 512, 512).convert("RGB")


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for name, im in (("feature-zh.png", feature()), ("icon-512.png", icon())):
        path = os.path.join(OUT, name)
        im.save(path, optimize=True)
        print(path, im.size, os.path.getsize(path))
