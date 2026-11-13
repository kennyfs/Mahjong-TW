#!/usr/bin/env python3
"""The faces of the tiles, drawn once here and written for the web page and the app.

Every face is a list of filled paths on a 60 x 80 face (x right, y down), painted in order. The characters (the
numerals and 萬, the winds and dragons, the flowers and their numbers) are glyph outlines of Noto Serif CJK TC Black
(SIL Open Font License 1.1: outlines drawn from a font are artwork made with it, which the license does not restrict);
the dots, the bamboo and the bird are drawn here. The body of a tile (its face color, edge and back) is drawn by the
renderers, so a theme can change it.

Outputs (committed, so building needs neither the font nor this script):
  web/src/lib/tile-art.ts                                        TILE_ART for the web page
  android/app/src/main/java/io/github/kennyfs/mahjong/ui/TileArt.kt  the same for the app
  art/sheet.svg                                                  every face on one sheet, to look at
  web/public/favicon.svg                                         the icon: 發 on a tile on the felt
  web/public/icon-192.png, icon-512.png                          the same as images (with rsvg-convert, when there)
  android/app/src/main/res/drawable/ic_launcher_foreground.xml   the same tile, the app's adaptive icon's foreground

    python3 art/make_tiles.py [path/to/NotoSerifCJK-Black.ttc]
"""
import math
import os
import shutil
import subprocess
import sys

from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTCollection

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT = sys.argv[1] if len(sys.argv) > 1 else '/usr/share/fonts/noto-cjk/NotoSerifCJK-Black.ttc'
TC = 3  # Noto Serif CJK TC in the collection

W, H = 60, 80

RED = '#C62828'
GREEN = '#1B7A3A'
BLUE = '#1D4E9E'
NAVY = '#14213D'
WHITE = '#FFFFFF'
SEASON = '#00695C'
PLANT = '#AD1457'

font = TTCollection(FONT).fonts[TC]
glyphs = font.getGlyphSet()
cmap = font.getBestCmap()


def num(x):
    s = f'{x:.1f}'
    if s.endswith('.0'):
        s = s[:-2]
    if s == '-0':
        s = '0'
    return s


def glyph(ch, box, mode='ink', valign='center'):
    """The outline of `ch` fitted in box (x0, y0, x1, y1): by its ink (`ink`) or by the em square (`em`)."""
    name = cmap[ord(ch)]
    x0, y0, x1, y1 = box
    if mode == 'em':
        s = min((x1 - x0) / 1000, (y1 - y0) / 1000)
        bx0, by0, bx1, by1 = 0, -120, 1000, 880
    else:
        bp = BoundsPen(glyphs)
        glyphs[name].draw(bp)
        bx0, by0, bx1, by1 = bp.bounds
        s = min((x1 - x0) / (bx1 - bx0), (y1 - y0) / (by1 - by0))
    # Centered in the box; font y up, ours down.
    dx = (x0 + x1) / 2 - s * (bx0 + bx1) / 2
    if valign == 'center':
        dy = (y0 + y1) / 2 + s * (by0 + by1) / 2
    else:
        dy = y0 + s * by1
    pen = SVGPathPen(glyphs, ntos=num)
    glyphs[name].draw(TransformPen(pen, (s, 0, 0, -s, dx, dy)))
    return pen.getCommands()


def circle(cx, cy, r):
    return f'M{num(cx - r)} {num(cy)}a{num(r)} {num(r)} 0 1 0 {num(2 * r)} 0a{num(r)} {num(r)} 0 1 0 {num(-2 * r)} 0Z'


def rect(x, y, w, h, r=0):
    if r <= 0:
        return f'M{num(x)} {num(y)}h{num(w)}v{num(h)}h{num(-w)}Z'
    return (f'M{num(x + r)} {num(y)}h{num(w - 2 * r)}a{num(r)} {num(r)} 0 0 1 {num(r)} {num(r)}v{num(h - 2 * r)}'
            f'a{num(r)} {num(r)} 0 0 1 {num(-r)} {num(r)}h{num(-(w - 2 * r))}a{num(r)} {num(r)} 0 0 1 {num(-r)} {num(-r)}'
            f'v{num(-(h - 2 * r))}a{num(r)} {num(r)} 0 0 1 {num(r)} {num(-r)}Z')


def dot(cx, cy, r, color):
    """A dot as the tiles have it: a ring, white inside, a center."""
    return [(circle(cx, cy, r), color), (circle(cx, cy, r * 0.66), WHITE), (circle(cx, cy, r * 0.46), color),
            (circle(cx, cy, r * 0.16), WHITE)]


def tint(color, amount):
    """`color` mixed with white."""
    r, g, b = (int(color[i:i + 2], 16) for i in (1, 3, 5))
    return '#%02X%02X%02X' % tuple(round(c + (255 - c) * amount) for c in (r, g, b))


def stick(cx, cy, w, h, color):
    """A bamboo stick: a stalk with three nodes and a light line down it."""
    x, y = cx - w / 2, cy - h / 2
    node = max(1.6, h * 0.07)
    out = [(rect(x, y, w, h, w * 0.45), color)]
    lw = max(1.2, w * 0.24)
    # The light line, broken at the middle node.
    out.append((rect(cx - lw / 2, y + node * 1.6, lw, h / 2 - node * 2.4, lw / 2), tint(color, 0.55)))
    out.append((rect(cx - lw / 2, cy + node * 0.8, lw, h / 2 - node * 2.4, lw / 2), tint(color, 0.55)))
    # Nodes: a slightly wider band at the ends and the middle.
    for ny in (y + node * 0.2, cy - node / 2, y + h - node * 1.2):
        out.append((rect(x - w * 0.08, ny, w * 1.16, node, node / 2), color))
    return out


def ellipse(cx, cy, rx, ry, angle=0.0):
    """An ellipse as four cubic arcs, turned by `angle` degrees."""
    k = 0.5523
    a = math.radians(angle)
    ca, sa = math.cos(a), math.sin(a)

    def p(x, y):
        return (cx + x * ca - y * sa, cy + x * sa + y * ca)

    pts = [p(rx, 0), p(rx, ry * k), p(rx * k, ry), p(0, ry), p(-rx * k, ry), p(-rx, ry * k), p(-rx, 0),
           p(-rx, -ry * k), p(-rx * k, -ry), p(0, -ry), p(rx * k, -ry), p(rx, -ry * k), p(rx, 0)]
    d = f'M{num(pts[0][0])} {num(pts[0][1])}'
    for i in range(1, 13, 3):
        d += 'C' + ' '.join(f'{num(px)} {num(py)}' for px, py in pts[i:i + 3])
    return d + 'Z'


def bird():
    """一條: a little bird, as the tile has it, facing left."""
    out = []
    # Tail feathers, behind the body.
    out.append(('M38 52C46 58 50 66 53 76C47 70 41 64 34 58Z', BLUE))
    out.append(('M35 54C40 62 42 69 42 77C37 71 34 64 31 57Z', RED))
    out.append(('M40 49C48 52 54 58 58 66C51 62 45 59 37 56Z', GREEN))
    # Body and wing.
    out.append((ellipse(31, 43, 13.5, 17, -28), GREEN))
    out.append((ellipse(35, 44, 7.5, 12.5, -18), '#0E5A27'))
    out.append(('M29 36C33 41 35 47 34 54', None))
    # Breast.
    out.append((ellipse(24, 47, 6, 9, 12), '#F2C14E'))
    # Head, crest, eye and beak.
    out.append(('M19 14L22 7L24 14L27 8L28 16Z', RED))
    out.append((circle(23, 21, 8), GREEN))
    out.append((circle(20.5, 19.5, 2.7), WHITE))
    out.append((circle(20, 19.5, 1.4), NAVY))
    out.append(('M15.6 20.5L8.5 24.5L16.5 25.5Z', RED))
    # Legs.
    out.append((rect(25, 58, 1.8, 10, 0.9), RED))
    out.append((rect(30, 58, 1.8, 10, 0.9), RED))
    out.append((rect(21.5, 66.5, 7, 1.8, 0.9), RED))
    out.append((rect(27, 66.5, 7, 1.8, 0.9), RED))
    return [(d, c) for d, c in out if c]


NUMERALS = '一二三四五六七八九'
WINDS = '東南西北'
FLOWERS = '春夏秋冬梅蘭菊竹'


def faces():
    f = {}
    for n in range(1, 10):
        f[f'{n}m'] = [(glyph(NUMERALS[n - 1], (7, 1, 53, 41), mode='em'), NAVY),
                      (glyph('萬', (8, 40, 52, 77)), RED)]
    # Dots.
    f['1p'] = [(circle(30, 40, 25), BLUE), (circle(30, 40, 22.5), WHITE), (circle(30, 40, 20.5), GREEN),
               (circle(30, 40, 15.5), WHITE), (circle(30, 40, 13.5), RED), (circle(30, 40, 8), WHITE),
               (circle(30, 40, 5.5), BLUE)]
    lay = {
        2: [(30, 21, GREEN), (30, 59, BLUE)],
        3: [(15, 16, BLUE), (30, 40, RED), (45, 64, GREEN)],
        4: [(17, 23, BLUE), (43, 23, GREEN), (17, 57, GREEN), (43, 57, BLUE)],
        5: [(16, 18, BLUE), (44, 18, GREEN), (30, 40, RED), (16, 62, GREEN), (44, 62, BLUE)],
        6: [(18, 15, GREEN), (42, 15, GREEN), (18, 44, RED), (42, 44, RED), (18, 66, RED), (42, 66, RED)],
        7: [(13, 12, GREEN), (30, 19, GREEN), (47, 26, GREEN), (18, 47, RED), (42, 47, RED), (18, 67, RED),
            (42, 67, RED)],
        8: [(18, y, BLUE) for y in (12, 31, 49, 68)] + [(42, y, BLUE) for y in (12, 31, 49, 68)],
        9: [(x, y, c) for y, c in ((15, BLUE), (40, RED), (65, GREEN)) for x in (13, 30, 47)],
    }
    radius = {2: 15, 3: 11.5, 4: 12, 5: 10.5, 6: 9.5, 7: 8.6, 8: 8.6, 9: 8.8}
    for n, dots in lay.items():
        f[f'{n}p'] = [layer for x, y, c in dots for layer in dot(x, y, radius[n], c)]
    # Bamboo.
    f['1s'] = bird()
    sticks = {
        2: [(30, 21, 11, 34, BLUE), (30, 59, 11, 34, GREEN)],
        3: [(30, 21, 11, 34, BLUE), (17, 59, 11, 34, GREEN), (43, 59, 11, 34, GREEN)],
        4: [(17, 21, 11, 34, GREEN), (43, 21, 11, 34, BLUE), (17, 59, 11, 34, BLUE), (43, 59, 11, 34, GREEN)],
        5: [(13, 21, 10, 34, GREEN), (47, 21, 10, 34, BLUE), (30, 40, 10, 34, RED), (13, 59, 10, 34, BLUE),
            (47, 59, 10, 34, GREEN)],
        6: [(x, y, 10, 34, c) for y, c in ((21, GREEN), (59, BLUE)) for x in (13, 30, 47)],
        7: [(30, 13, 10, 22, RED)] + [(x, y, 10, 24, GREEN) for y in (40, 66) for x in (13, 30, 47)],
        8: [(x, y, 8.5, 34, c) for y, c in ((21, GREEN), (59, BLUE)) for x in (10, 23.3, 36.6, 50)],
        9: [(x, y, 10, 23, c) for x, c in ((13, GREEN), (30, RED), (47, BLUE)) for y in (14, 40, 66)],
    }
    for n, ss in sticks.items():
        f[f'{n}s'] = [layer for x, y, w, h, c in ss for layer in stick(x, y, w, h, c)]
    # Winds and dragons.
    for i, ch in enumerate(WINDS):
        f[f'{i + 1}z'] = [(glyph(ch, (7, 10, 53, 70)), NAVY)]
    f['5z'] = [(rect(9, 8, 42, 64, 3) + rect(14, 13, 32, 54, 1), BLUE, True),
               (rect(17, 16, 26, 48, 0.5) + rect(19, 18, 22, 44, 0), BLUE, True)]
    f['6z'] = [(glyph('發', (5, 10, 55, 70)), GREEN)]
    f['7z'] = [(glyph('中', (7, 8, 53, 72)), RED)]
    # Flowers: the name, and the seat's number in the corner.
    for i, ch in enumerate(FLOWERS):
        color = SEASON if i < 4 else PLANT
        f[f'{i + 1}f'] = [(glyph(ch, (13, 24, 55, 74)), color),
                          (glyph(str(i % 4 + 1), (3, 3, 17, 19)), color)]
    return f


def layers(face):
    out = []
    for layer in face:
        d, c = layer[0], layer[1]
        even = len(layer) > 2 and layer[2]
        out.append((d, c, even))
    return out


def write_ts(f):
    lines = [
        '// Generated by art/make_tiles.py: do not edit. The faces of the tiles on a 60 x 80 face, each a list of',
        '// [path, fill, even-odd] painted in order; the numerals, words and flowers are glyphs of Noto Serif CJK TC',
        '// Black (SIL Open Font License 1.1).',
        'export type Layer = [string, string, boolean];',
        'export const FACE_W = 60;',
        'export const FACE_H = 80;',
        'export const TILE_ART: Record<string, Layer[]> = {',
    ]
    for k, face in f.items():
        lines.append(f'  "{k}": [')
        for d, c, even in layers(face):
            lines.append(f'    ["{d}", "{c}", {"true" if even else "false"}],')
        lines.append('  ],')
    lines.append('};')
    path = os.path.join(ROOT, 'web', 'src', 'lib', 'tile-art.ts')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w') as out:
        out.write('\n'.join(lines) + '\n')
    return path


def write_kt(f):
    lines = [
        '// Generated by art/make_tiles.py: do not edit. The faces of the tiles on a 60 x 80 face, each a list of layers',
        '// (path, fill, even-odd) painted in order; the numerals, words and flowers are glyphs of Noto Serif CJK TC',
        '// Black (SIL Open Font License 1.1).',
        'package io.github.kennyfs.mahjong.ui',
        '',
        'class ArtLayer(val path: String, val color: Long, val evenOdd: Boolean)',
        '',
        'object TileArt {',
        '  const val FACE_W = 60f',
        '  const val FACE_H = 80f',
        '',
        '  private fun l(path: String, color: Long, evenOdd: Boolean = false) = ArtLayer(path, color, evenOdd)',
        '',
    ]
    names = []
    for k, face in f.items():
        fn = 'f' + k
        names.append((k, fn))
        lines.append(f'  private fun {fn}() = listOf(')
        for d, c, even in layers(face):
            argb = '0xFF' + c[1:].upper()
            lines.append(f'    l("{d}", {argb}{", true" if even else ""}),')
        lines.append('  )')
        lines.append('')
    lines.append('  /** Every face by its tile\'s name ("1m" ... "7z", "1f" ... "8f"). */')
    lines.append('  val faces: Map<String, List<ArtLayer>> by lazy {')
    lines.append('    mapOf(')
    for k, fn in names:
        lines.append(f'      "{k}" to {fn}(),')
    lines.append('    )')
    lines.append('  }')
    lines.append('}')
    path = os.path.join(ROOT, 'android', 'app', 'src', 'main', 'java', 'io', 'github', 'kennyfs', 'mahjong', 'ui',
                        'TileArt.kt')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w') as out:
        out.write('\n'.join(lines) + '\n')
    return path


def write_sheet(f):
    cols = 9
    tw, th = 66, 92
    rows = math.ceil(len(f) / cols)
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {cols * tw + 6} {rows * th + 6}" '
             f'width="{(cols * tw + 6) * 2}" height="{(rows * th + 6) * 2}">',
             '<rect width="100%" height="100%" fill="#1F6F54"/>']
    for i, (k, face) in enumerate(f.items()):
        x, y = 6 + (i % cols) * tw, 6 + (i // cols) * th
        parts.append(f'<g transform="translate({x} {y})">')
        parts.append(f'<rect x="0" y="0" width="60" height="86" rx="6" fill="#28559B"/>')
        parts.append(f'<rect x="0" y="0" width="60" height="80" rx="6" fill="#FDFBF3" stroke="#D6D0C0"/>')
        for d, c, even in layers(face):
            rule = ' fill-rule="evenodd"' if even else ''
            parts.append(f'<path d="{d}" fill="{c}"{rule}/>')
        parts.append('</g>')
    parts.append('</svg>')
    path = os.path.join(ROOT, 'art', 'sheet.svg')
    with open(path, 'w') as out:
        out.write('\n'.join(parts) + '\n')
    return path


FELT = '#1F6F54'
FACE = '#FDFBF3'
EDGE = '#D6D0C0'
BACK = '#28559B'


def write_icon(f):
    """The icon: the green dragon (發) on a tile standing on the felt, for the page (an SVG, and images of it for a
    home screen) and the app (a vector drawable: the adaptive icon's foreground, the tile within its 66 dp safe zone
    of 108)."""
    face = layers(f['6z'])
    svg = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 96 96">',
           f'<rect width="96" height="96" rx="20" fill="{FELT}"/>',
           # (Centred by the tile's face, 60 x 80: the blue edge under it, the tile's thickness, is not counted.)
           '<g transform="translate(23 14.67) scale(0.8333)">',
           f'<rect x="0" y="0" width="60" height="88" rx="7" fill="{BACK}"/>',
           f'<rect x="0" y="0" width="60" height="80" rx="7" fill="{FACE}" stroke="{EDGE}" stroke-width="1.2"/>']
    for d, c, even in face:
        rule = ' fill-rule="evenodd"' if even else ''
        svg.append(f'<path d="{d}" fill="{c}"{rule}/>')
    svg += ['</g>', '</svg>']
    web = os.path.join(ROOT, 'web', 'public', 'favicon.svg')
    os.makedirs(os.path.dirname(web), exist_ok=True)
    with open(web, 'w') as out:
        out.write('\n'.join(svg) + '\n')
    images = []
    if shutil.which('rsvg-convert'):
        for size in (192, 512):
            png = os.path.join(ROOT, 'web', 'public', f'icon-{size}.png')
            subprocess.run(['rsvg-convert', '-w', str(size), '-h', str(size), web, '-o', png], check=True)
            images.append(png)
    # The drawable: 108 x 108, the tile 60 x 88 scaled by 0.66 (40 x 58), inside the safe zone, its face (60 x 80) at
    # the middle: the blue edge under it (the tile's thickness) is not counted (the user's: the face looked high).
    sc = 0.66
    tx, ty = (108 - 60 * sc) / 2, (108 - 80 * sc) / 2
    xml = ['<?xml version="1.0" encoding="utf-8"?>',
           '<!-- Generated by art/make_tiles.py: do not edit. The launcher icon\'s foreground: 發 on a tile. -->',
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">',
           f'  <group android:translateX="{tx:.2f}" android:translateY="{ty:.2f}" android:scaleX="{sc}" android:scaleY="{sc}">',
           f'    <path android:fillColor="{BACK}" android:pathData="M7,0 H53 A7,7 0 0 1 60,7 V81 A7,7 0 0 1 53,88 H7 A7,7 0 0 1 0,81 V7 A7,7 0 0 1 7,0 Z"/>',
           f'    <path android:fillColor="{FACE}" android:strokeColor="{EDGE}" android:strokeWidth="1.2" android:pathData="M7,0 H53 A7,7 0 0 1 60,7 V73 A7,7 0 0 1 53,80 H7 A7,7 0 0 1 0,73 V7 A7,7 0 0 1 7,0 Z"/>']
    for d, c, even in face:
        rule = ' android:fillType="evenOdd"' if even else ''
        xml.append(f'    <path android:fillColor="{c}"{rule} android:pathData="{d}"/>')
    xml += ['  </group>', '</vector>']
    app = os.path.join(ROOT, 'android', 'app', 'src', 'main', 'res', 'drawable', 'ic_launcher_foreground.xml')
    os.makedirs(os.path.dirname(app), exist_ok=True)
    with open(app, 'w') as out:
        out.write('\n'.join(xml) + '\n')
    return (web, *images, app)


if __name__ == '__main__':
    f = faces()
    assert len(f) == 42, len(f)
    for p in (write_ts(f), write_kt(f), write_sheet(f), *write_icon(f)):
        print(p, os.path.getsize(p))
