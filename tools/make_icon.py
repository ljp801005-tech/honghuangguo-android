#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Generate the 丑橘 (ugly tangerine) mascot app icon.

Outputs (all 512x512 RGBA PNG unless noted):
  drawable/app_icon.png                 transparent mascot (legacy drawable)
  mipmap-xxxhdpi/ic_launcher_foreground.png  transparent mascot, inside adaptive safe zone
  mipmap-xxxhdpi/ic_launcher.png        rounded-square warm background + mascot (legacy)
  mipmap-xxxhdpi/ic_launcher_round.png  circular warm background + mascot (legacy)
"""
import math
import os
import random

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")

SUPER = 1400            # supersampled canvas
random.seed(20261116)


def adiff(a, b):
    return (a - b + math.pi) % (2 * math.pi) - math.pi


def radius_factor(theta):
    """Ugly-tangerine silhouette: lumpy body + signature top bump."""
    r = 1.0
    r += 0.018 * math.sin(5 * theta + 0.6)
    r += 0.012 * math.sin(9 * theta + 1.7)
    r += 0.014 * math.cos(3 * theta - 0.4)
    # top neck / bump — the signature "ugly" knob of a shiranui tangerine
    d = adiff(theta, -math.pi / 2)
    r += 0.175 * math.exp(-(d / 0.20) ** 2)
    r += 0.045 * math.exp(-(d / 0.42) ** 2)
    # slightly flattened bottom
    d2 = adiff(theta, math.pi / 2)
    r -= 0.035 * math.exp(-(d2 / 0.75) ** 2)
    return r


def body_polygon(cx, cy, rx, ry, n=900, scale=1.0):
    pts = []
    for i in range(n):
        th = 2 * math.pi * i / n
        f = radius_factor(th) * scale
        pts.append((cx + rx * f * math.cos(th), cy + ry * f * math.sin(th)))
    return pts


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def draw_leaf(base, cx, cy, angle_deg, length, width, c_dark, c_light):
    """A pointed leaf with a soft two-tone gradient and a central vein."""
    a = math.radians(angle_deg)
    dx, dy = math.cos(a), math.sin(a)
    px, py = -dy, dx
    layer = Image.new("RGBA", (SUPER, SUPER), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    left, right = [], []
    steps = 40
    for i in range(steps + 1):
        t = i / steps
        bx = cx + dx * length * t
        by = cy + dy * length * t
        w = width * (math.sin(math.pi * t) ** 0.75)
        left.append((bx + px * w, by + py * w))
        right.append((bx - px * w, by - py * w))
    poly = left + right[::-1]
    d.polygon(poly, fill=c_dark + (255,))
    # light upper-half sheen
    for i in range(steps + 1):
        t = i / steps
        bx = cx + dx * length * t
        by = cy + dy * length * t
        w = width * (math.sin(math.pi * t) ** 0.75)
        d.line([(bx + px * w * 0.15, by + py * w * 0.15),
                (bx + px * w * 0.9, by + py * w * 0.9)],
               fill=c_light + (165,), width=max(1, int(width * 0.18)))
    # vein
    d.line([(cx, cy), (cx + dx * length * 0.92, cy + dy * length * 0.92)],
           fill=(220, 245, 200, 150), width=max(1, int(width * 0.12)))
    layer = layer.filter(ImageFilter.GaussianBlur(SUPER * 0.0009))
    base.alpha_composite(layer)


def render_mascot():
    """Return the transparent 丑橘 mascot at SUPER resolution."""
    cx = cy = SUPER / 2
    rx = SUPER * 0.335
    ry = SUPER * 0.30

    img = Image.new("RGBA", (SUPER, SUPER), (0, 0, 0, 0))

    # ---- mask from silhouette ----
    mask = Image.new("L", (SUPER, SUPER), 0)
    ImageDraw.Draw(mask).polygon(body_polygon(cx, cy, rx, ry), fill=255)

    # ---- sphere lighting / gradient ----
    yy, xx = np.mgrid[0:SUPER, 0:SUPER]
    nx = (xx - cx) / rx
    ny = (yy - cy) / ry
    d2 = np.clip(nx * nx + ny * ny, 0, 1)
    nz = np.sqrt(np.clip(1.0 - d2, 0, 1))
    lx, ly, lz = -0.48, -0.55, 0.68
    ln = math.sqrt(lx * lx + ly * ly + lz * lz)
    lx, ly, lz = lx / ln, ly / ln, lz / ln
    diff = np.clip(nx * lx + ny * ly + nz * lz, 0, 1)
    shade = 0.60 + 0.55 * diff
    # warm top -> deep orange bottom
    t = np.clip((ny + 1) / 2, 0, 1)
    r = 255 - 14 * t
    g = 200 - 84 * t
    b = 82 - 66 * t
    # subtle edge rim (keep it clean, not muddy)
    rim = np.clip(nz / 0.20, 0.82, 1.0)
    arr = np.empty((SUPER, SUPER, 4), dtype=np.float32)
    arr[..., 0] = np.clip(r * shade * rim, 0, 255)
    arr[..., 1] = np.clip(g * shade * rim, 0, 255)
    arr[..., 2] = np.clip(b * shade * rim, 0, 255)
    m = np.asarray(mask, dtype=np.float32) / 255.0
    arr[..., 3] = m * 255
    body = Image.fromarray(arr.astype(np.uint8), "RGBA")
    img.alpha_composite(body)

    # ---- uniform fine pores (the "ugly" pebbled skin, subtle not dirty) ----
    tex = Image.new("RGBA", (SUPER, SUPER), (0, 0, 0, 0))
    td = ImageDraw.Draw(tex)
    for gx in range(-14, 15):
        for gy in range(-13, 14):
            jx = random.uniform(-0.35, 0.35)
            jy = random.uniform(-0.35, 0.35)
            px = cx + (gx + jx) * rx * 0.072
            py = cy + (gy + jy) * ry * 0.075
            ix, iy = int(px), int(py)
            if 0 <= ix < SUPER and 0 <= iy < SUPER and mask.getpixel((ix, iy)) == 0:
                continue
            # keep the face area cleaner
            if abs(px - cx) < rx * 0.5 and -ry * 0.3 < py - cy < ry * 0.62:
                continue
            rad = random.uniform(0.0035, 0.0062) * SUPER
            a = random.randint(12, 26)
            dark = random.random() < 0.5
            col = (150, 74, 20, a) if dark else (255, 222, 150, a)
            td.ellipse([px - rad, py - rad, px + rad, py + rad], fill=col)
    tex = tex.filter(ImageFilter.GaussianBlur(SUPER * 0.0012))
    img.alpha_composite(tex)

    # ---- specular highlight (soft, warm — never milky) ----
    hl = Image.new("RGBA", (SUPER, SUPER), (0, 0, 0, 0))
    hd = ImageDraw.Draw(hl)
    hd.ellipse([cx - rx * 0.72, cy - ry * 0.80, cx - rx * 0.16, cy - ry * 0.30],
               fill=(255, 246, 214, 66))
    hl = hl.filter(ImageFilter.GaussianBlur(SUPER * 0.028))
    img.alpha_composite(hl)
    # tight catchlight
    hl2 = Image.new("RGBA", (SUPER, SUPER), (0, 0, 0, 0))
    ImageDraw.Draw(hl2).ellipse(
        [cx - rx * 0.56, cy - ry * 0.66, cx - rx * 0.36, cy - ry * 0.48],
        fill=(255, 250, 226, 120))
    hl2 = hl2.filter(ImageFilter.GaussianBlur(SUPER * 0.014))
    img.alpha_composite(hl2)

    # ---- bottom bounce light ----
    bl = Image.new("RGBA", (SUPER, SUPER), (0, 0, 0, 0))
    ImageDraw.Draw(bl).ellipse(
        [cx - rx * 0.5, cy + ry * 0.58, cx + rx * 0.5, cy + ry * 1.0],
        fill=(255, 196, 96, 52))
    bl = bl.filter(ImageFilter.GaussianBlur(SUPER * 0.03))
    img.alpha_composite(bl)

    # ---- stem + leaves ----
    top_y = cy - ry * radius_factor(-math.pi / 2)
    sd = ImageDraw.Draw(img)
    stem_w = max(2, int(SUPER * 0.012))
    sd.line([(cx, top_y + SUPER * 0.01), (cx + SUPER * 0.004, top_y - SUPER * 0.075)],
            fill=(96, 74, 34, 255), width=stem_w)
    sd.ellipse([cx - SUPER * 0.02, top_y - SUPER * 0.01,
                cx + SUPER * 0.02, top_y + SUPER * 0.03],
               fill=(120, 92, 42, 255))
    draw_leaf(img, cx, top_y - SUPER * 0.055, -158, SUPER * 0.20, SUPER * 0.055,
              (34, 122, 56), (104, 196, 96))
    draw_leaf(img, cx, top_y - SUPER * 0.06, -30, SUPER * 0.17, SUPER * 0.048,
              (40, 138, 62), (122, 206, 104))

    # ---- cute face: blush, eyes, smile ----
    blush = Image.new("RGBA", (SUPER, SUPER), (0, 0, 0, 0))
    bd = ImageDraw.Draw(blush)
    for sign in (-1, 1):
        bx = cx + sign * rx * 0.40
        by = cy + ry * 0.20
        bd.ellipse([bx - rx * 0.15, by - ry * 0.085, bx + rx * 0.15, by + ry * 0.085],
                   fill=(255, 92, 92, 90))
    blush = blush.filter(ImageFilter.GaussianBlur(SUPER * 0.014))
    img.alpha_composite(blush)

    fd = ImageDraw.Draw(img)
    eye_y = cy - ry * 0.02
    eye_dx = rx * 0.32
    ew, eh = rx * 0.115, ry * 0.155
    for sign in (-1, 1):
        ex = cx + sign * eye_dx
        fd.ellipse([ex - ew, eye_y - eh, ex + ew, eye_y + eh], fill=(74, 44, 20, 255))
        # two catchlights
        fd.ellipse([ex - ew * 0.42, eye_y - eh * 0.62,
                    ex + ew * 0.10, eye_y - eh * 0.10], fill=(255, 255, 255, 245))
        fd.ellipse([ex + ew * 0.28, eye_y + eh * 0.18,
                    ex + ew * 0.62, eye_y + eh * 0.50], fill=(255, 255, 255, 210))

    # happy open smile
    mb = [cx - rx * 0.24, cy + ry * 0.24, cx + rx * 0.24, cy + ry * 0.58]
    fd.pieslice(mb, 0, 180, fill=(112, 46, 30, 255))
    fd.ellipse([cx - rx * 0.13, cy + ry * 0.40, cx + rx * 0.13, cy + ry * 0.60],
               fill=(238, 116, 120, 255))
    fd.arc(mb, 0, 180, fill=(74, 34, 22, 255), width=max(2, int(SUPER * 0.004)))

    return img


def warm_background(shape="square"):
    """Warm cream -> peach background canvas."""
    yy, xx = np.mgrid[0:SUPER, 0:SUPER]
    t = (xx / SUPER * 0.35 + yy / SUPER * 0.65)
    top = (255, 247, 230)
    bot = (255, 214, 170)
    arr = np.empty((SUPER, SUPER, 4), dtype=np.float32)
    for i in range(3):
        arr[..., i] = top[i] + (bot[i] - top[i]) * t
    alpha = np.zeros((SUPER, SUPER), dtype=np.float32)
    if shape == "square":
        r = SUPER * 0.22
        # rounded rect alpha via distance
        dx = np.maximum(np.abs(xx - SUPER / 2) - (SUPER / 2 - r), 0)
        dy = np.maximum(np.abs(yy - SUPER / 2) - (SUPER / 2 - r), 0)
        dist = np.sqrt(dx * dx + dy * dy)
        alpha = np.clip(r - dist + 1, 0, 1) * 255
    else:
        dist = np.sqrt((xx - SUPER / 2) ** 2 + (yy - SUPER / 2) ** 2)
        alpha = np.clip(SUPER / 2 - dist + 1, 0, 1) * 255
    arr[..., 3] = alpha
    return Image.fromarray(arr.astype(np.uint8), "RGBA")


def save(img, rel, size=512):
    path = os.path.join(RES, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.resize((size, size), Image.LANCZOS).save(path)
    print("saved", path)


def main():
    mascot = render_mascot()

    # legacy drawable (transparent)
    save(mascot, "drawable/app_icon.png")

    # adaptive foreground: smaller, centered in the 66dp safe zone
    fg = Image.new("RGBA", (SUPER, SUPER), (0, 0, 0, 0))
    small = mascot.resize((int(SUPER * 0.66), int(SUPER * 0.66)), Image.LANCZOS)
    fg.alpha_composite(small, (int(SUPER * 0.17), int(SUPER * 0.17)))
    save(fg, "mipmap-xxxhdpi/ic_launcher_foreground.png")

    # legacy launcher icons with background
    for shape, name in (("square", "ic_launcher.png"), ("circle", "ic_launcher_round.png")):
        bg = warm_background(shape)
        bg.alpha_composite(mascot)
        save(bg, "mipmap-xxxhdpi/" + name)

    # preview sheet on white + warm background, for quick visual review
    prev = Image.new("RGB", (1180, 620), (244, 246, 248))
    p = mascot.resize((580, 580), Image.LANCZOS)
    prev.paste(p, (10, 20), p)
    bgp = warm_background("square")
    bgp.alpha_composite(mascot)
    bgp = bgp.resize((560, 560), Image.LANCZOS)
    prev.paste(bgp, (610, 30), bgp)
    d = ImageDraw.Draw(prev)
    d.text((20, 596), "transparent", fill=(80, 80, 80))
    d.text((620, 596), "on warm background", fill=(80, 80, 80))
    preview_path = os.path.join(ROOT, "icon_preview.png")
    prev.save(preview_path)
    print("saved", preview_path)


if __name__ == "__main__":
    main()
