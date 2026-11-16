#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Original 'red play button' app icon — YouTube-style look, no trademark artwork.

Draws a classic red rounded-square with a white play triangle. This is a generic
video-play symbol drawn from scratch (no official YouTube assets, no wordmark).

Outputs (512x512 RGBA PNG unless noted):
  drawable/app_icon.png
  mipmap-xxxhdpi/ic_launcher_foreground.png   white triangle only, safe zone
  mipmap-xxxhdpi/ic_launcher.png               full red rounded square
  mipmap-xxxhdpi/ic_launcher_round.png         full red circle
"""
import math
import os
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
SUPER = 1400

# Muted, low-key brick red — far less saturated than pure #FF0000.
RED_TOP = (176, 66, 58)
RED_BOT = (122, 28, 24)
RED_SOLID = (150, 45, 40)


def play_triangle(cx, cy, w, h):
    """Right-pointing isosceles triangle with its CENTROID at (cx, cy).

    A triangle's centroid is the mean of its 3 vertices. With the vertical base
    at cx-a (two vertices) and the tip at cx+b, the centroid x-offset is
    (-2a + b)/3. Setting b = 2a makes it exactly 0 (optically centered),
    instead of centering the bounding box and leaving the mass shifted left.
    Total width a+b is kept at 1.02w to preserve the original size.
    """
    a = w * 0.34
    b = w * 0.68
    base = cx - a
    return [
        (base, cy - h * 0.5),
        (base, cy + h * 0.5),
        (cx + b, cy),
    ]


def red_field(shape="square"):
    yy, xx = np.mgrid[0:SUPER, 0:SUPER]
    t = yy / SUPER
    arr = np.empty((SUPER, SUPER, 4), dtype=np.float32)
    for i in range(3):
        arr[..., i] = RED_TOP[i] + (RED_BOT[i] - RED_TOP[i]) * t
    alpha = np.zeros((SUPER, SUPER), dtype=np.float32)
    c = SUPER / 2
    if shape == "circle":
        dist = np.sqrt((xx - c) ** 2 + (yy - c) ** 2)
        alpha = np.clip(SUPER / 2 - dist + 1, 0, 1) * 255
    else:
        r = SUPER * 0.22
        dx = np.maximum(np.abs(xx - c) - (c - r), 0)
        dy = np.maximum(np.abs(yy - c) - (c - r), 0)
        dist = np.sqrt(dx * dx + dy * dy)
        alpha = np.clip(r - dist + 1, 0, 1) * 255
    arr[..., 3] = alpha
    return Image.fromarray(arr.astype(np.uint8), "RGBA")


def white_triangle_layer(w, h, ring_r, ring_w, with_shadow=True):
    """White play triangle enclosed by a concentric white ring (hollow center)."""
    cx = cy = SUPER / 2
    layer = Image.new("RGBA", (SUPER, SUPER), (0, 0, 0, 0))
    if with_shadow:
        sh = Image.new("RGBA", (SUPER, SUPER), (0, 0, 0, 0))
        sd = ImageDraw.Draw(sh)
        ox, oy = cx + SUPER * 0.006, cy + SUPER * 0.010
        sd.ellipse([ox - ring_r, oy - ring_r, ox + ring_r, oy + ring_r],
                   outline=(90, 15, 12, 120), width=int(ring_w))
        sd.polygon(play_triangle(ox, oy, w, h), fill=(90, 15, 12, 120))
        sh = sh.filter(ImageFilter.GaussianBlur(SUPER * 0.012))
        layer.alpha_composite(sh)
    d = ImageDraw.Draw(layer)
    d.ellipse([cx - ring_r, cy - ring_r, cx + ring_r, cy + ring_r],
              outline=(255, 255, 255, 255), width=int(ring_w))
    d.polygon(play_triangle(cx, cy, w, h), fill=(255, 255, 255, 255))
    return layer


def save(img, rel, size=512):
    path = os.path.join(RES, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.resize((size, size), Image.LANCZOS).save(path)
    print("saved", path)


def main():
    # Foreground: white triangle only, sized inside the 66dp adaptive safe zone.
    fg_tri = white_triangle_layer(SUPER * 0.40, SUPER * 0.38,
                                  SUPER * 0.38, SUPER * 0.022, with_shadow=False)
    save(fg_tri, "mipmap-xxxhdpi/ic_launcher_foreground.png")

    # Full legacy icons: circle, white triangle inside a concentric white ring.
    tri = white_triangle_layer(SUPER * 0.44, SUPER * 0.42,
                               SUPER * 0.40, SUPER * 0.024, with_shadow=True)
    for shape, name in (("circle", "ic_launcher.png"), ("circle", "ic_launcher_round.png")):
        bg = red_field(shape)
        bg.alpha_composite(tri)
        save(bg, "mipmap-xxxhdpi/" + name)

    # Legacy drawable (full icon; white layer-list no longer used by manifest but keep consistent).
    legacy = red_field("circle")
    legacy.alpha_composite(tri)
    save(legacy, "drawable/app_icon.png")

    # Preview sheet
    prev = Image.new("RGB", (1180, 620), (244, 246, 248))
    p = fg_tri.resize((580, 580), Image.LANCZOS)
    prev.paste(p, (10, 20), p)
    full = red_field("square")
    full.alpha_composite(tri)
    full = full.resize((560, 560), Image.LANCZOS)
    prev.paste(full, (610, 30), full)
    d = ImageDraw.Draw(prev)
    d.text((20, 596), "foreground (white triangle)", fill=(80, 80, 80))
    d.text((620, 596), "full icon (red + play)", fill=(80, 80, 80))
    prev.save(os.path.join(ROOT, "play_icon_preview.png"))
    print("saved play_icon_preview.png")

    # Masked launcher previews
    def mask(kind):
        m = Image.new("L", (SUPER, SUPER), 0)
        d = ImageDraw.Draw(m)
        if kind == "circle":
            d.ellipse([0, 0, SUPER, SUPER], fill=255)
        elif kind == "squircle":
            pts, a, e = [], SUPER / 2, 0.45
            for i in range(400):
                t = 2 * math.pi * i / 400
                ct, st = math.cos(t), math.sin(t)
                pts.append((a * math.copysign(abs(ct) ** e, ct) + a,
                            a * math.copysign(abs(st) ** e, st) + a))
            d.polygon(pts, fill=255)
        else:
            d.rounded_rectangle([0, 0, SUPER - 1, SUPER - 1], radius=int(SUPER * 0.22), fill=255)
        return m

    cell = red_field("square")
    cell.alpha_composite(tri)
    out = Image.new("RGB", (1180, 440), (238, 240, 244))
    labels = ["circle", "squircle", "rounded square"]
    for i, kind in enumerate(["circle", "squircle", "square"]):
        transparent = Image.new("RGBA", (SUPER, SUPER), (0, 0, 0, 0))
        transparent.paste(cell, (0, 0), mask(kind))
        base = Image.new("RGBA", (SUPER, SUPER), (238, 240, 244, 255))
        base.alpha_composite(transparent)
        out.paste(base.convert("RGB").resize((360, 360), Image.LANCZOS), (30 + i * 385, 30))
    d = ImageDraw.Draw(out)
    for i, lab in enumerate(labels):
        d.text((30 + i * 385, 400), lab, fill=(70, 70, 70))
    out.save(os.path.join(ROOT, "play_launcher_preview.png"))
    print("saved play_launcher_preview.png")


if __name__ == "__main__":
    main()
