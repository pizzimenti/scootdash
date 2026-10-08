#!/usr/bin/env python3
"""Draws the launcher icon (adaptive foreground + legacy square) into res/mipmap-*."""
import math
import os
import sys
from PIL import Image, ImageDraw

DUSK = (0x12, 0x1C, 0x24, 255)
FACE = (0x16, 0x23, 0x2C, 255)
PUMICE = (0xE6, 0xE1, 0xD3, 255)
SAGE = (0x9D, 0xB5, 0x8A, 255)
SIGNAL = (0xE5, 0x53, 0x3D, 255)
PONDEROSA = (0xF0, 0x8A, 0x3C, 255)
SS = 4  # supersampling


def arc(d, cx, cy, r, a0, a1, width, color):
    d.arc([cx - r, cy - r, cx + r, cy + r], a0, a1, fill=color, width=width)


def dial(size, art_frac, background):
    S = size * SS
    img = Image.new('RGBA', (S, S), DUSK if background else (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    cx = cy = S / 2
    R = S * art_frac / 2
    # cruise ring
    arc(d, cx, cy, R, 0, 360, int(R * 0.08), PONDEROSA)
    # face
    fr = R * 0.86
    d.ellipse([cx - fr, cy - fr, cx + fr, cy + fr], fill=FACE)
    # scale: 150 deg -> 390 deg, sage to 70 %, red after
    rs = R * 0.70
    w = int(R * 0.13)
    split = 150 + 240 * 0.72
    arc(d, cx, cy, rs, 150, split, w, SAGE)
    arc(d, cx, cy, rs, split, 390, w, SIGNAL)
    # needle at 58 %
    a = math.radians(150 + 240 * 0.58)
    tip = R * 0.62
    d.line([cx, cy, cx + math.cos(a) * tip, cy + math.sin(a) * tip], fill=PUMICE, width=int(R * 0.09))
    hub = R * 0.12
    d.ellipse([cx - hub, cy - hub, cx + hub, cy + hub], fill=PUMICE)
    return img.resize((size, size), Image.LANCZOS)


def main(res):
    dens = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}
    for name, k in dens.items():
        out = os.path.join(res, 'mipmap-' + name)
        os.makedirs(out, exist_ok=True)
        dial(int(108 * k), 0.58, False).save(os.path.join(out, 'ic_launcher_fg.png'))
        legacy = dial(int(48 * k), 0.86, True)
        # round the legacy square's corners
        m = Image.new('L', legacy.size, 0)
        ImageDraw.Draw(m).rounded_rectangle([0, 0, legacy.size[0] - 1, legacy.size[1] - 1], radius=int(legacy.size[0] * 0.22), fill=255)
        legacy.putalpha(m)
        legacy.save(os.path.join(out, 'ic_launcher.png'))
    any26 = os.path.join(res, 'mipmap-anydpi-v26')
    os.makedirs(any26, exist_ok=True)
    with open(os.path.join(any26, 'ic_launcher.xml'), 'w') as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n'
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@color/icon_bg"/>\n'
                '    <foreground android:drawable="@mipmap/ic_launcher_fg"/>\n'
                '</adaptive-icon>\n')
    dial(512, 0.86, True).save(os.path.join(os.path.dirname(res.rstrip('/')), '..', '..', 'tools', 'icon-512.png'))


if __name__ == '__main__':
    main(sys.argv[1])
