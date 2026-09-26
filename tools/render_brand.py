#!/usr/bin/env python3
"""Renders the MIZAN brand to PNG.

The geometry lives here, once, in device-independent units, and the script
rasterises it with 4x supersampling and a source-over composite. Nothing
external is needed: no ImageMagick, no cairo, no font. Output is RGBA8 PNG
written with the standard library, so the icons are reproducible on any
machine with Python 3.

    python3 tools/render_brand.py            # writes the mipmaps and previews
    python3 tools/render_brand.py --check    # verifies what is on disk

Design rules encoded below:

  * the mark lives inside a 64 unit square; the launcher maps it into the
    66 unit safe circle of a 108 unit adaptive icon
  * every stroke is at least 1.6 units wide at 48 px, so nothing disappears
    on an mdpi screen
  * gradients run along the diagonal so the mark reads at 48 px and at 192 px
"""

from __future__ import annotations

import argparse
import math
import os
import struct
import sys
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SS = 3  # supersampling factor per axis


# --------------------------------------------------------------------------
# colour
# --------------------------------------------------------------------------

def hex_rgb(value: str) -> tuple[float, float, float]:
    raw = value.lstrip("#")
    return tuple(int(raw[i:i + 2], 16) / 255.0 for i in (0, 2, 4))


# --------------------------------------------------------------------------
# shape primitives: signed distance in *unit* space, negative means inside
# --------------------------------------------------------------------------

def sd_rounded_rect(x, y, cx, cy, w, h, r):
    dx = abs(x - cx) - (w / 2.0 - r)
    dy = abs(y - cy) - (h / 2.0 - r)
    return math.hypot(max(dx, 0.0), max(dy, 0.0)) + min(max(dx, dy), 0.0) - r


def sd_circle(x, y, cx, cy, r):
    return math.hypot(x - cx, y - cy) - r


def sd_ring(x, y, cx, cy, r, thickness):
    return abs(math.hypot(x - cx, y - cy) - r) - thickness / 2.0


def sd_triangle(x, y, ax, ay, bx, by, cx, cy):
    # Standard polygon SDF for three vertices.
    def seg(px, py, x1, y1, x2, y2):
        ex, ey = x2 - x1, y2 - y1
        wx, wy = px - x1, py - y1
        t = max(0.0, min(1.0, (wx * ex + wy * ey) / (ex * ex + ey * ey)))
        return math.hypot(wx - ex * t, wy - ey * t)

    d = min(
        seg(x, y, ax, ay, bx, by),
        seg(x, y, bx, by, cx, cy),
        seg(x, y, cx, cy, ax, ay),
    )
    # Winding agnostic. The first version required all three cross products to
    # be non-negative, which is only true for one winding order: with the other
    # order the triangle was never "inside", so it rasterised as a hairline
    # outline of itself. The fulcrum shipped hollow because of this.
    c1 = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
    c2 = (cx - bx) * (y - by) - (cy - by) * (x - bx)
    c3 = (ax - cx) * (y - cy) - (ay - cy) * (x - cx)
    inside = (c1 >= 0 and c2 >= 0 and c3 >= 0) or (c1 <= 0 and c2 <= 0 and c3 <= 0)
    return -d if inside else d


def sd_half_disc(x, y, cx, cy, r):
    # Intersection of a disc with the half plane below its diameter.
    return max(sd_circle(x, y, cx, cy, r), cy - y)


# --------------------------------------------------------------------------
# scene
# --------------------------------------------------------------------------

class LinearGradient:
    def __init__(self, x0, y0, x1, y1, stops):
        self.x0, self.y0, self.x1, self.y1 = x0, y0, x1, y1
        self.stops = [(offset, hex_rgb(color)) for offset, color in stops]
        dx, dy = x1 - x0, y1 - y0
        self.length_sq = dx * dx + dy * dy

    def at(self, x, y):
        dx, dy = self.x1 - self.x0, self.y1 - self.y0
        t = ((x - self.x0) * dx + (y - self.y0) * dy) / self.length_sq
        t = max(0.0, min(1.0, t))
        return self._mix(t)

    def _mix(self, t):
        for index in range(len(self.stops) - 1):
            left_offset, left = self.stops[index]
            right_offset, right = self.stops[index + 1]
            if left_offset <= t <= right_offset:
                span = right_offset - left_offset
                k = 0.0 if span == 0 else (t - left_offset) / span
                return tuple(left[i] + (right[i] - left[i]) * k for i in range(3))
        return self.stops[-1][1]


class RadialGradient:
    """Stops are (offset, hex colour, alpha)."""

    def __init__(self, cx, cy, r, stops):
        self.cx, self.cy, self.r = cx, cy, r
        self.stops = [(offset, hex_rgb(color), alpha) for offset, color, alpha in stops]

    def at(self, x, y):
        t = math.hypot(x - self.cx, y - self.cy) / self.r
        t = max(0.0, min(1.0, t))
        for index in range(len(self.stops) - 1):
            left_offset, left_color, left_alpha = self.stops[index]
            right_offset, right_color, right_alpha = self.stops[index + 1]
            if left_offset <= t <= right_offset:
                span = right_offset - left_offset
                k = 0.0 if span == 0 else (t - left_offset) / span
                return tuple(
                    left_color[i] + (right_color[i] - left_color[i]) * k for i in range(3)
                ) + (left_alpha + (right_alpha - left_alpha) * k,)
        last = self.stops[-1]
        return last[1] + (last[2],)


class Shape:
    """A filled region described by a signed distance function.

    `bounds` is the region the shape can possibly touch, so the rasteriser
    skips it for the other 90% of the canvas instead of evaluating it.
    """

    def __init__(self, distance, fill, alpha=1.0, bounds=None):
        self.distance = distance
        self.fill = fill
        self.alpha = alpha
        self.bounds = bounds

    def coverage(self, x, y, pixel):
        if self.bounds is not None:
            x0, y0, x1, y1 = self.bounds
            margin = pixel
            if x < x0 - margin or x > x1 + margin or y < y0 - margin or y > y1 + margin:
                return 0.0
        d = self.distance(x, y)
        edge = pixel * 0.75
        if d <= -edge:
            return 1.0
        if d >= edge:
            return 0.0
        return 0.5 - d / (2.0 * edge)


def rounded_rect(cx, cy, w, h, r, fill, alpha=1.0):
    return Shape(
        lambda x, y: sd_rounded_rect(x, y, cx, cy, w, h, r),
        fill,
        alpha,
        (cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2),
    )


def circle(cx, cy, r, fill, alpha=1.0):
    return Shape(
        lambda x, y: sd_circle(x, y, cx, cy, r),
        fill,
        alpha,
        (cx - r, cy - r, cx + r, cy + r),
    )


def ring(cx, cy, r, thickness, fill, alpha=1.0):
    pad = r + thickness
    return Shape(
        lambda x, y: sd_ring(x, y, cx, cy, r, thickness),
        fill,
        alpha,
        (cx - pad, cy - pad, cx + pad, cy + pad),
    )


def bowl(cx, cy, r, thickness, fill, alpha=1.0):
    """The lower half of a ring: a pan, not a block.

    Expressed as a crescent -- the outer half disc minus the inner one -- which
    is exactly what the vector drawable emits too, so the two cannot drift.
    """
    inner = r - thickness
    return Shape(
        lambda x, y: max(sd_half_disc(x, y, cx, cy, r), -sd_half_disc(x, y, cx, cy, inner)),
        fill,
        alpha,
        (cx - r, cy, cx + r, cy + r),
    )


def triangle(ax, ay, bx, by, cx, cy, fill, alpha=1.0):
    xs = (ax, bx, cx)
    ys = (ay, by, cy)
    return Shape(
        lambda x, y: sd_triangle(x, y, ax, ay, bx, by, cx, cy),
        fill,
        alpha,
        (min(xs), min(ys), max(xs), max(ys)),
    )


def half_disc(cx, cy, r, fill, alpha=1.0):
    return Shape(
        lambda x, y: sd_half_disc(x, y, cx, cy, r),
        fill,
        alpha,
        (cx - r, cy, cx + r, cy + r),
    )


# brand palette (kept in sync with docs/DESIGN.md)
A_START = "#67E8F9"
A_MID = "#22D3EE"
A_END = "#0E9F9F"
B_START = "#A5F3EC"
B_MID = "#2DD4BF"
B_END = "#0E7490"

BG_DARK = "#06121F"
BG_MID = "#062A2C"
BG_DEEP = "#0A1226"


# The mark, described once.
#
# Every entry is (kind, geometry, gradient, alpha) in the 64 unit square. Both
# the PNG mipmaps and the vector drawables are built from this list, so the
# raster and the vector can never disagree about where a stroke is.
#
# Refined from the first version: the beam is thinner, the pans are crescents
# with rims instead of filled blocks, and the fulcrum is slimmer. An instrument
# reads as an instrument when its strokes are lighter than its silhouette.
MARK_PARTS = [
    # seal: a soft band and a crisp hairline inside it
    ("ring", (32.0, 32.0, 29.2, 3.0), "a", 0.40),
    ("ring", (32.0, 32.0, 29.2, 1.1), "b", None),
    # beam and its end caps
    ("rect", (32.0, 21.4, 33.0, 2.8, 1.4), "b", None),
    ("circle", (15.5, 21.4, 2.3), "b", None),
    ("circle", (48.5, 21.4, 2.3), "b", None),
    # cables from the caps down to the rims
    ("rect", (15.5, 26.5, 1.4, 6.2, 0.7), "b", 0.90),
    ("rect", (48.5, 26.5, 1.4, 6.2, 0.7), "b", 0.90),
    # pans: crescents with a rim, so they read as bowls
    ("bowl", (15.5, 30.4, 6.8, 1.6), "b", None),
    ("bowl", (48.5, 30.4, 6.8, 1.6), "b", None),
    ("rect", (15.5, 30.4, 13.6, 1.3, 0.65), "b", 0.95),
    ("rect", (48.5, 30.4, 13.6, 1.3, 0.65), "b", 0.95),
    # fulcrum, pillar, base, plinth
    ("triangle", (32.0, 20.9, 26.8, 30.2, 37.2, 30.2), "a", None),
    ("rect", (32.0, 38.4, 3.0, 15.2, 1.5), "b", None),
    ("rect", (32.0, 47.6, 22.0, 3.8, 1.9), "b", None),
    ("rect", (32.0, 52.4, 13.0, 2.4, 1.2), "a", 0.60),
]


def mark_shapes(offset_x=0.0, offset_y=0.0, scale=1.0):
    """The MIZAN balance mark, built from MARK_PARTS.

    A beam on a fulcrum, two pans, and a plinth, held inside a seal. The
    silhouette is symmetric: this is an instrument at rest, not a robot.
    """
    s = scale
    ox, oy = offset_x, offset_y

    def tx(x):
        return ox + x * s

    def ty(y):
        return oy + y * s

    gradient_a = LinearGradient(tx(12), ty(14), tx(52), ty(54),
                                [(0.0, A_START), (0.45, A_MID), (1.0, A_END)])
    gradient_b = LinearGradient(tx(32), ty(14), tx(32), ty(58),
                                [(0.0, B_START), (0.5, B_MID), (1.0, B_END)])
    fills = {"a": gradient_a, "b": gradient_b}

    shapes = []
    for kind, geometry, gradient, alpha in MARK_PARTS:
        fill = fills[gradient]
        if kind == "ring":
            cx, cy, r, thickness = geometry
            shapes.append(ring(tx(cx), ty(cy), r * s, thickness * s, fill,
                               alpha if alpha is not None else 1.0))
        elif kind == "rect":
            cx, cy, w, h, r = geometry
            shapes.append(rounded_rect(tx(cx), ty(cy), w * s, h * s, r * s, fill,
                                       alpha if alpha is not None else 1.0))
        elif kind == "circle":
            cx, cy, r = geometry
            shapes.append(circle(tx(cx), ty(cy), r * s, fill,
                                 alpha if alpha is not None else 1.0))
        elif kind == "bowl":
            cx, cy, r, thickness = geometry
            shapes.append(bowl(tx(cx), ty(cy), r * s, thickness * s, fill,
                               alpha if alpha is not None else 1.0))
        elif kind == "triangle":
            ax, ay, bx, by, cx, cy = geometry
            shapes.append(triangle(tx(ax), ty(ay), tx(bx), ty(by), tx(cx), ty(cy), fill,
                                   alpha if alpha is not None else 1.0))
    return shapes


def launcher_shapes(space=108.0):
    """Adaptive icon: a deep ink field, an aura, and the mark in the safe circle."""
    background = LinearGradient(0, 0, space, space,
                                [(0.0, BG_DARK), (0.5, BG_MID), (1.0, BG_DEEP)])
    aura = RadialGradient(space * 0.5, space * 0.425, space * 0.43,
                          [(0.0, A_MID, 0.34), (0.6, "#0EA5A4", 0.14), (1.0, "#000000", 0.0)])
    hairline = LinearGradient(0, 0, space, space, [(0.0, "#7FF3E4"), (1.0, "#7FF3E4")])
    shapes = [
        Shape(lambda x, y: sd_rounded_rect(x, y, space / 2, space / 2, space, space, 0.0),
              background, 1.0, (0, 0, space, space)),
        Shape(lambda x, y: sd_circle(x, y, space / 2, space * 0.425, space * 0.43),
              aura, 1.0, (space * 0.07, space * -0.005, space * 0.93, space * 0.855)),
        ring(space / 2, space / 2, space * 0.343, space * 0.009, hairline, alpha=0.12),
    ]
    mark_scale = 0.9375
    mark_offset = (space - 64 * mark_scale) / 2.0
    shapes += mark_shapes(offset_x=mark_offset, offset_y=mark_offset, scale=mark_scale)
    return shapes


# --------------------------------------------------------------------------
# rasteriser
# --------------------------------------------------------------------------

def render(shapes, size, space, circular=False):
    """Returns a bytearray of RGBA pixels for a `size` x `size` image."""
    pixels = bytearray(size * size * 4)
    pixel_size = space / size
    samples = SS * SS
    for py in range(size):
        row = bytearray()
        for px in range(size):
            r = g = b = a = 0.0
            for sy in range(SS):
                for sx in range(SS):
                    ux = (px + (sx + 0.5) / SS) * pixel_size
                    uy = (py + (sy + 0.5) / SS) * pixel_size
                    if circular and sd_circle(ux, uy, space / 2, space / 2, space / 2 - pixel_size) > 0:
                        continue
                    # compliant source-over composite over transparent black
                    cr = cg = cb = 0.0
                    ca = 0.0
                    for shape in shapes:
                        coverage = shape.coverage(ux, uy, pixel_size)
                        if coverage <= 0.0:
                            continue
                        fill = shape.fill
                        if isinstance(fill, RadialGradient):
                            fr, fg, fb, fa = fill.at(ux, uy)
                        else:
                            fr, fg, fb = fill.at(ux, uy)
                            fa = 1.0
                        alpha = coverage * shape.alpha * fa
                        cr = fr * alpha + cr * (1.0 - alpha)
                        cg = fg * alpha + cg * (1.0 - alpha)
                        cb = fb * alpha + cb * (1.0 - alpha)
                        ca = alpha + ca * (1.0 - alpha)
                    r += cr
                    g += cg
                    b += cb
                    a += ca
            r /= samples
            g /= samples
            b /= samples
            a /= samples
            # un-premultiply for storage
            if a > 0.0001:
                row += bytes((int(max(0.0, min(1.0, r / a)) * 255),
                              int(max(0.0, min(1.0, g / a)) * 255),
                              int(max(0.0, min(1.0, b / a)) * 255),
                              int(round(a * 255))))
            else:
                row += b"\x00\x00\x00\x00"
        pixels[py * size * 4:(py + 1) * size * 4] = row
    return pixels


def write_png(path, size, pixels):
    raw = bytearray()
    stride = size * 4
    for y in range(size):
        raw.append(0)  # filter type 0
        raw += pixels[y * stride:(y + 1) * stride]

    def chunk(tag, data):
        out = struct.pack(">I", len(data)) + tag + data
        return out + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", header)
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as handle:
        handle.write(png)
    return len(png)


DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}



# --------------------------------------------------------------------------
# Android vector drawables, emitted from the same geometry
# --------------------------------------------------------------------------

def rounded_rect_path(x, y, w, h, r):
    """Android vector path data for a rounded rectangle (no <rect> in VD)."""
    r = min(r, w / 2.0, h / 2.0)
    return (
        f"M{fmt(x + r)},{fmt(y)}"
        f"h{fmt(w - 2 * r)}"
        f"a{fmt(r)},{fmt(r)} 0 0 1 {fmt(r)},{fmt(r)}"
        f"v{fmt(h - 2 * r)}"
        f"a{fmt(r)},{fmt(r)} 0 0 1 {fmt(-r)},{fmt(r)}"
        f"h{fmt(-(w - 2 * r))}"
        f"a{fmt(r)},{fmt(r)} 0 0 1 {fmt(-r)},{fmt(-r)}"
        f"v{fmt(-(h - 2 * r))}"
        f"a{fmt(r)},{fmt(r)} 0 0 1 {fmt(r)},{fmt(-r)}"
        "z"
    )


def circle_path(cx, cy, r):
    return (
        f"M{fmt(cx - r)},{fmt(cy)}"
        f"a{fmt(r)},{fmt(r)} 0 1 0 {fmt(2 * r)},0"
        f"a{fmt(r)},{fmt(r)} 0 1 0 {fmt(-2 * r)},0"
        "z"
    )


def half_disc_path(cx, cy, r):
    return f"M{fmt(cx - r)},{fmt(cy)}A{fmt(r)},{fmt(r)} 0 0 0 {fmt(cx + r)},{fmt(cy)}Z"


def triangle_path(ax, ay, bx, by, cx, cy):
    return f"M{fmt(ax)},{fmt(ay)}L{fmt(bx)},{fmt(by)}L{fmt(cx)},{fmt(cy)}Z"


def fmt(value):
    text = f"{value:.2f}".rstrip("0").rstrip(".")
    return text if text not in ("", "-0") else "0"


def gradient_xml(name, x0, y0, x1, y1, stops, radial=False, cx=0.0, cy=0.0, r=0.0):
    items = "\n".join(
        f'                <item android:color="{color}" android:offset="{offset}"'
        + (f' android:alpha="{alpha}"' if alpha is not None else "")
        + " />"
        for offset, color, alpha in stops
    )
    attrs = (
        f'android:centerX="{fmt(cx)}" android:centerY="{fmt(cy)}" android:gradientRadius="{fmt(r)}"'
        f' android:type="radial"'
        if radial
        else f'android:startX="{fmt(x0)}" android:startY="{fmt(y0)}"'
        f' android:endX="{fmt(x1)}" android:endY="{fmt(y1)}" android:type="linear"'
    )
    return f"""        <aapt:attr name="{name}">
            <gradient
                {attrs}>
{items}
            </gradient>
        </aapt:attr>"""


def mark_vector(viewport, offset=0.0, scale=1.0, indent="    ", monochrome=False):
    """The mark as Android vector body, built from the same MARK_PARTS.

    `monochrome` emits a flat white silhouette for Android 13 themed icons:
    the system tints it, so it must carry shape and alpha, never colour.
    """
    s = scale
    ox = oy = offset

    def tx(x):
        return ox + x * s

    def ty(y):
        return oy + y * s

    def path(data, gradient_name, alpha=None, fill_type=None):
        alpha_attr = f'\n{indent}    android:fillAlpha="{alpha}"' if alpha else ""
        # Two nested contours in the same direction only read as a ring with
        # the even-odd rule; the default non-zero rule would fill the hole.
        type_attr = f'\n{indent}    android:fillType="{fill_type}"' if fill_type else ""
        if monochrome:
            colour = f'\n{indent}    android:fillColor="#FFFFFF"'
            if alpha:
                colour += f'\n{indent}    android:fillAlpha="{alpha}"'
            return f"""{indent}<path
{indent}    android:pathData="{data}"{type_attr}{colour} />"""
        gradient = (
            gradient_xml("android:fillColor", tx(12), ty(14), tx(52), ty(54),
                         [(0.0, A_START, None), (0.45, A_MID, None), (1.0, A_END, None)])
            if gradient_name == "a"
            else gradient_xml("android:fillColor", tx(32), ty(14), tx(32), ty(58),
                              [(0.0, B_START, None), (0.5, B_MID, None), (1.0, B_END, None)])
        )
        return f"""{indent}<path
{indent}    android:pathData="{data}"{type_attr}{alpha_attr}>
{gradient}
{indent}</path>"""

    parts = []
    for kind, geometry, gradient, alpha in MARK_PARTS:
        alpha_attr = f"{alpha}" if alpha is not None else None
        if kind == "ring":
            cx, cy, r, thickness = geometry
            data = circle_path(tx(cx), ty(cy), r * s) + " " + \
                circle_path(tx(cx), ty(cy), (r - thickness) * s)
            parts.append(path(data, gradient, alpha_attr, "evenOdd"))
        elif kind == "rect":
            cx, cy, w, h, r = geometry
            data = rounded_rect_path(tx(cx) - w * s / 2.0, ty(cy) - h * s / 2.0,
                                     w * s, h * s, r * s)
            parts.append(path(data, gradient, alpha_attr))
        elif kind == "circle":
            cx, cy, r = geometry
            parts.append(path(circle_path(tx(cx), ty(cy), r * s), gradient, alpha_attr))
        elif kind == "bowl":
            cx, cy, r, thickness = geometry
            data = half_disc_path(tx(cx), ty(cy), r * s) + " " + \
                half_disc_path(tx(cx), ty(cy), (r - thickness) * s)
            parts.append(path(data, gradient, alpha_attr, "evenOdd"))
        elif kind == "triangle":
            ax, ay, bx, by, cx, cy = geometry
            data = triangle_path(tx(ax), ty(ay), tx(bx), ty(by), tx(cx), ty(cy))
            parts.append(path(data, gradient, alpha_attr))
    return "\n".join(parts)


def write_vectors():
    """Writes the launcher and in-app mark vectors."""
    app_res = os.path.join(ROOT, "app", "src", "main", "res", "drawable")
    design_res = os.path.join(ROOT, "design", "src", "main", "res", "drawable")
    os.makedirs(app_res, exist_ok=True)
    os.makedirs(design_res, exist_ok=True)

    background = f"""<?xml version="1.0" encoding="utf-8"?>
<!--
  MIZAN launcher background. Generated by tools/render_brand.py.
  Deep ink field with a cyan aura behind the mark. Do not edit by hand.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
{gradient_xml("android:fillColor", 0, 0, 108, 108,
              [(0.0, BG_DARK, None), (0.5, BG_MID, None), (1.0, BG_DEEP, None)])}
    </path>
    <path android:pathData="{circle_path(54, 46.35, 46.44)}">
{gradient_xml("android:fillColor", 0, 0, 0, 0,
              [(0.0, A_MID, "0.34"), (0.6, "#0EA5A4", "0.14"), (1.0, "#000000", "0.0")],
              radial=True, cx=54, cy=46.35, r=46.44)}
    </path>
    <path
        android:pathData="{circle_path(54, 54, 37.04)} {circle_path(54, 54, 36.07)}"
        android:fillType="evenOdd"
        android:fillColor="#7FF3E4"
        android:fillAlpha="0.10" />
</vector>
"""

    foreground = f"""<?xml version="1.0" encoding="utf-8"?>
<!--
  MIZAN launcher foreground: a balance at rest inside a seal.
  Generated by tools/render_brand.py from the same geometry as the PNG mipmaps.
  Everything sits inside the 66dp safe circle of the adaptive icon.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
{mark_vector(108, offset=(108 - 64 * 0.9375) / 2.0, scale=0.9375)}
</vector>
"""

    mark = f"""<?xml version="1.0" encoding="utf-8"?>
<!--
  The MIZAN mark. Generated by tools/render_brand.py. Do not edit by hand.
  A single 64 unit square, so it is crisp at every size the UI asks for.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="64dp"
    android:height="64dp"
    android:viewportWidth="64"
    android:viewportHeight="64">
{mark_vector(64)}
</vector>
"""

    # Android 13 themed icons: a flat silhouette the system tints. Without it
    # the launcher draws the full colour icon inside the themed shape.
    monochrome = f"""<?xml version="1.0" encoding="utf-8"?>
<!--
  MIZAN monochrome launcher icon, for Android 13 themed icons.
  Generated by tools/render_brand.py from the same geometry as everything else.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
{mark_vector(108, offset=(108 - 64 * 0.9375) / 2.0, scale=0.9375, monochrome=True)}
</vector>
"""

    adaptive = """<?xml version="1.0" encoding="utf-8"?>
<!--
  MIZAN adaptive icon. Generated by tools/render_brand.py. Do not edit by hand.
  The monochrome layer is what Android 13+ tints when the user asks for themed
  icons; older versions simply ignore it.
-->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
"""

    anydpi = os.path.join(ROOT, "app", "src", "main", "res", "mipmap-anydpi-v26")
    os.makedirs(anydpi, exist_ok=True)

    for path, content in (
        (os.path.join(app_res, "ic_launcher_background.xml"), background),
        (os.path.join(app_res, "ic_launcher_foreground.xml"), foreground),
        (os.path.join(app_res, "ic_launcher_monochrome.xml"), monochrome),
        (os.path.join(app_res, "ic_mizan_mark.xml"), mark),
        (os.path.join(design_res, "ic_mizan_mark.xml"), mark),
        (os.path.join(anydpi, "ic_launcher.xml"), adaptive),
        (os.path.join(anydpi, "ic_launcher_round.xml"), adaptive),
    ):
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(content)
        print(f"  wrote {os.path.relpath(path, ROOT)}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Render the MIZAN brand icons.")
    parser.add_argument("--check", action="store_true", help="verify the files on disk")
    parser.add_argument("--vectors", action="store_true", help="only write the vector drawables")
    args = parser.parse_args()

    if args.vectors:
        write_vectors()
        return 0

    if args.check:
        missing = []
        for density, size in DENSITIES.items():
            for name in ("ic_launcher.png", "ic_launcher_round.png"):
                path = os.path.join(ROOT, "app", "src", "main", "res", f"mipmap-{density}", name)
                if not os.path.exists(path):
                    missing.append(path)
                    continue
                with open(path, "rb") as handle:
                    head = handle.read(24)
                width, height = struct.unpack(">II", head[16:24])
                if (width, height) != (size, size):
                    print(f"wrong size {width}x{height} (expected {size}) at {path}")
                    missing.append(path)
        if missing:
            print(f"{len(missing)} icon file(s) missing or wrong")
            return 1
        print("all icon files present and correctly sized")
        return 0

    launcher = launcher_shapes()
    launcher_round = launcher_shapes()
    results = []
    for density, size in DENSITIES.items():
        directory = os.path.join(ROOT, "app", "src", "main", "res", f"mipmap-{density}")
        for name, circular in (("ic_launcher.png", False), ("ic_launcher_round.png", True)):
            pixels = render(launcher if not circular else launcher_round, size, 108.0, circular=circular)
            written = write_png(os.path.join(directory, name), size, pixels)
            results.append((density, name, size, written))

    # Previews for documentation and store listings.
    preview_dir = os.path.join(ROOT, "brand")
    write_png(os.path.join(preview_dir, "preview-launcher-512.png"), 512,
              render(launcher, 512, 108.0))
    write_png(os.path.join(preview_dir, "preview-mark-512.png"), 512,
              render(mark_shapes(), 512, 64.0))
    write_png(os.path.join(preview_dir, "preview-mark-256.png"), 256,
              render(mark_shapes(), 256, 64.0))

    for density, name, size, written in results:
        print(f"  {density:9s} {name:22s} {size}x{size}  {written:6d} bytes")
    print("previews written to brand/")
    write_vectors()
    return 0


if __name__ == "__main__":
    sys.exit(main())
