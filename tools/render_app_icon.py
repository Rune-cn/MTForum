#!/usr/bin/env python3
"""Render the launcher-icon vectors to PNG previews.

There is no design tool on the build machine, so the icon lives entirely in
`app/src/main/res/drawable/ic_launcher_*.xml`. This script rasterises exactly those
files (it parses `pathData` itself, so the preview can never drift from the
shipped art) and writes:

  docs/screenshots/app-icon.png            512x512 full-bleed 108dp canvas
  docs/screenshots/app-icon-round.png      circle-masked, on a launcher-like field
  docs/screenshots/app-icon-safe-zone.png  foreground + adaptive-icon mask overlays

It also prints the safe-zone audit: the largest distance (in dp) from the canvas
centre that any foreground pixel reaches. Adaptive icons are masked to a 66dp
circle, so that number must stay <= 33.

Usage:
    python tools/render_app_icon.py [--res-dir DIR] [--out-dir DIR] [--size N]
"""

from __future__ import annotations

import argparse
import math
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

from lxml import etree
from PIL import Image, ImageDraw, ImageFont

A = "{http://schemas.android.com/apk/res/android}"
ANDROID_NS = "http://schemas.android.com/apk/res/android"

SAFE_ZONE_RADIUS_DP = 33.0  # 66dp circle the launcher guarantees to show
CANVAS_DP = 108.0
SS = 4  # supersampling factor for anti-aliasing


# --------------------------------------------------------------------------- #
# Android vector XML
# --------------------------------------------------------------------------- #
@dataclass
class VectorPath:
    data: str
    fill: tuple[int, int, int, int] | None
    stroke: tuple[int, int, int, int] | None
    stroke_width: float
    even_odd: bool
    cap_round: bool
    translate: tuple[float, float] = (0.0, 0.0)


@dataclass
class Vector:
    width_dp: float
    height_dp: float
    viewport_w: float
    viewport_h: float
    paths: list[VectorPath] = field(default_factory=list)


def parse_color(text: str | None) -> tuple[int, int, int, int] | None:
    """Android colour -> RGBA. `#AARRGGBB` (or `#RGB`/`#ARGB` short forms)."""
    if not text or text == "@null":
        return None
    text = text.strip()
    if not text.startswith("#"):
        raise SystemExit(f"unsupported colour reference: {text}")
    raw = text[1:]
    if len(raw) == 3:
        raw = "FF" + "".join(c * 2 for c in raw)
    elif len(raw) == 4:
        raw = "".join(c * 2 for c in raw)
    elif len(raw) == 6:
        raw = "FF" + raw
    if len(raw) != 8:
        raise SystemExit(f"unsupported colour literal: {text}")
    # Android is #AARRGGBB, Pillow wants RGB(A)
    return (
        int(raw[2:4], 16),
        int(raw[4:6], 16),
        int(raw[6:8], 16),
        int(raw[0:2], 16),
    )


def load_vector(path: Path) -> Vector:
    root = etree.parse(str(path)).getroot()
    if root.tag != "vector":
        raise SystemExit(f"{path}: not a <vector>")
    vec = Vector(
        width_dp=float(root.get(f"{A}width", "108dp").replace("dp", "")),
        height_dp=float(root.get(f"{A}height", "108dp").replace("dp", "")),
        viewport_w=float(root.get(f"{A}viewportWidth", "108")),
        viewport_h=float(root.get(f"{A}viewportHeight", "108")),
    )
    walk_vector(root, vec, path)
    if not vec.paths:
        raise SystemExit(f"{path}: no <path> elements")
    return vec


def walk_vector(node, vec: Vector, source: Path, tx: float = 0.0, ty: float = 0.0) -> None:
    """Collect paths in document order, accumulating <group> translates.

    Only translate is supported: rotation/scale/pivot are rejected loudly rather than silently
    rendered wrong, because this renderer's output is published as the icon preview.
    """
    for child in node:
        if not isinstance(child.tag, str):  # comments / processing instructions
            continue
        tag = etree.QName(child).localname
        if tag == "group":
            for attr in ("rotation", "scaleX", "scaleY", "pivotX", "pivotY"):
                if child.get(f"{A}{attr}") is not None:
                    raise SystemExit(f"{source}: <group> {attr} is not supported by the preview renderer")
            walk_vector(
                child,
                vec,
                source,
                tx + float(child.get(f"{A}translateX", 0) or 0),
                ty + float(child.get(f"{A}translateY", 0) or 0),
            )
        elif tag == "path":
            vec.paths.append(
                VectorPath(
                    data=child.get(f"{A}pathData", ""),
                    fill=parse_color(child.get(f"{A}fillColor")),
                    stroke=parse_color(child.get(f"{A}strokeColor")),
                    stroke_width=float(child.get(f"{A}strokeWidth", "0") or 0),
                    even_odd=(child.get(f"{A}fillType", "nonZero") == "evenOdd"),
                    cap_round=(child.get(f"{A}strokeLineCap", "butt") == "round"),
                    translate=(tx, ty),
                )
            )


# --------------------------------------------------------------------------- #
# pathData -> polyline(s)
# --------------------------------------------------------------------------- #
_TOKEN = re.compile(r"[MmLlHhVvCcSsQqTtAaZz]|-?(?:\d+\.\d*|\.\d+|\d+)(?:[eE][-+]?\d+)?")


def _flatten_cubic(p0, p1, p2, p3, steps=22):
    out = []
    for i in range(1, steps + 1):
        t = i / steps
        mt = 1 - t
        x = mt**3 * p0[0] + 3 * mt**2 * t * p1[0] + 3 * mt * t**2 * p2[0] + t**3 * p3[0]
        y = mt**3 * p0[1] + 3 * mt**2 * t * p1[1] + 3 * mt * t**2 * p2[1] + t**3 * p3[1]
        out.append((x, y))
    return out


def _flatten_arc(p0, rx, ry, phi_deg, large_arc, sweep, p1, steps=24):
    """SVG endpoint arc -> sampled points (SVG 1.1 F.6 implementation notes)."""
    if rx == 0 or ry == 0 or (p0 == p1):
        return [p1]
    phi = math.radians(phi_deg)
    dx2, dy2 = (p0[0] - p1[0]) / 2, (p0[1] - p1[1]) / 2
    x1 = math.cos(phi) * dx2 + math.sin(phi) * dy2
    y1 = -math.sin(phi) * dx2 + math.cos(phi) * dy2
    rx, ry = abs(rx), abs(ry)
    lam = x1**2 / rx**2 + y1**2 / ry**2
    if lam > 1:
        s = math.sqrt(lam)
        rx, ry = rx * s, ry * s
    num = rx**2 * ry**2 - rx**2 * y1**2 - ry**2 * x1**2
    den = rx**2 * y1**2 + ry**2 * x1**2
    coef = math.sqrt(max(0.0, num / den)) if den else 0.0
    if large_arc == sweep:
        coef = -coef
    cx1 = coef * rx * y1 / ry
    cy1 = -coef * ry * x1 / rx
    cx = math.cos(phi) * cx1 - math.sin(phi) * cy1 + (p0[0] + p1[0]) / 2
    cy = math.sin(phi) * cx1 + math.cos(phi) * cy1 + (p0[1] + p1[1]) / 2
    theta1 = math.atan2((y1 - cy1) / ry, (x1 - cx1) / rx)
    theta2 = math.atan2((-y1 - cy1) / ry, (-x1 - cx1) / rx)
    dtheta = theta2 - theta1
    if sweep and dtheta < 0:
        dtheta += 2 * math.pi
    if not sweep and dtheta > 0:
        dtheta -= 2 * math.pi
    out = []
    for i in range(1, steps + 1):
        t = theta1 + dtheta * i / steps
        x = cx + rx * math.cos(t) * math.cos(phi) - ry * math.sin(t) * math.sin(phi)
        y = cy + rx * math.cos(t) * math.sin(phi) + ry * math.sin(t) * math.cos(phi)
        out.append((x, y))
    return out


def path_to_subpaths(data: str) -> list[list[tuple[float, float]]]:
    tokens = _TOKEN.findall(data)
    if not tokens:
        return []
    subpaths: list[list[tuple[float, float]]] = []
    cur: list[tuple[float, float]] = []
    pos = (0.0, 0.0)
    start = (0.0, 0.0)
    prev_ctrl: tuple[float, float] | None = None
    prev_cmd = ""
    i = 0

    def num() -> float:
        nonlocal i
        v = float(tokens[i])
        i += 1
        return v

    cmd = ""
    while i < len(tokens):
        if re.fullmatch(r"[A-Za-z]", tokens[i]):
            cmd = tokens[i]
            i += 1
            if cmd in "Zz":
                if cur:
                    cur.append(start)
                    subpaths.append(cur[:])
                cur = []
                pos = start
                prev_ctrl = None
                prev_cmd = "Z"
                continue
        elif cmd == "":
            raise SystemExit(f"pathData starts without a command: {data!r}")
        # implicit repetition of the previous command (M/m turns into L/l)
        rel = cmd.islower()
        c = cmd.upper()
        if c == "M":
            x, y = num(), num()
            if rel:
                x, y = pos[0] + x, pos[1] + y
            if cur:
                subpaths.append(cur[:])
            cur = [(x, y)]
            start = (x, y)
            pos = (x, y)
            prev_ctrl = None
            cmd = "l" if rel else "L"
            prev_cmd = "M"
        elif c == "L":
            x, y = num(), num()
            if rel:
                x, y = pos[0] + x, pos[1] + y
            if not cur:
                cur = [pos]
            cur.append((x, y))
            pos = (x, y)
            prev_ctrl = None
            prev_cmd = "L"
        elif c == "H":
            x = num()
            if rel:
                x += pos[0]
            if not cur:
                cur = [pos]
            cur.append((x, pos[1]))
            pos = (x, pos[1])
            prev_ctrl = None
            prev_cmd = "H"
        elif c == "V":
            y = num()
            if rel:
                y += pos[1]
            if not cur:
                cur = [pos]
            cur.append((pos[0], y))
            pos = (pos[0], y)
            prev_ctrl = None
            prev_cmd = "V"
        elif c == "C":
            x1, y1, x2, y2, x, y = (num() for _ in range(6))
            if rel:
                x1, y1 = pos[0] + x1, pos[1] + y1
                x2, y2 = pos[0] + x2, pos[1] + y2
                x, y = pos[0] + x, pos[1] + y
            if not cur:
                cur = [pos]
            cur.extend(_flatten_cubic(pos, (x1, y1), (x2, y2), (x, y)))
            prev_ctrl = (x2, y2)
            pos = (x, y)
            prev_cmd = "C"
        elif c == "S":
            x2, y2, x, y = (num() for _ in range(4))
            if rel:
                x2, y2 = pos[0] + x2, pos[1] + y2
                x, y = pos[0] + x, pos[1] + y
            if prev_cmd in ("C", "S") and prev_ctrl is not None:
                x1, y1 = 2 * pos[0] - prev_ctrl[0], 2 * pos[1] - prev_ctrl[1]
            else:
                x1, y1 = pos
            if not cur:
                cur = [pos]
            cur.extend(_flatten_cubic(pos, (x1, y1), (x2, y2), (x, y)))
            prev_ctrl = (x2, y2)
            pos = (x, y)
            prev_cmd = "S"
        elif c == "Q":
            x1, y1, x, y = (num() for _ in range(4))
            if rel:
                x1, y1 = pos[0] + x1, pos[1] + y1
                x, y = pos[0] + x, pos[1] + y
            if not cur:
                cur = [pos]
            c1 = (pos[0] + 2 / 3 * (x1 - pos[0]), pos[1] + 2 / 3 * (y1 - pos[1]))
            c2 = (x + 2 / 3 * (x1 - x), y + 2 / 3 * (y1 - y))
            cur.extend(_flatten_cubic(pos, c1, c2, (x, y)))
            prev_ctrl = (x1, y1)
            pos = (x, y)
            prev_cmd = "Q"
        elif c == "T":
            x, y = num(), num()
            if rel:
                x, y = pos[0] + x, pos[1] + y
            if prev_cmd in ("Q", "T") and prev_ctrl is not None:
                x1, y1 = 2 * pos[0] - prev_ctrl[0], 2 * pos[1] - prev_ctrl[1]
            else:
                x1, y1 = pos
            if not cur:
                cur = [pos]
            c1 = (pos[0] + 2 / 3 * (x1 - pos[0]), pos[1] + 2 / 3 * (y1 - pos[1]))
            c2 = (x + 2 / 3 * (x1 - x), y + 2 / 3 * (y1 - y))
            cur.extend(_flatten_cubic(pos, c1, c2, (x, y)))
            prev_ctrl = (x1, y1)
            pos = (x, y)
            prev_cmd = "T"
        elif c == "A":
            rx, ry, phi, laf, sf, x, y = (num() for _ in range(7))
            if rel:
                x, y = pos[0] + x, pos[1] + y
            if not cur:
                cur = [pos]
            cur.extend(_flatten_arc(pos, rx, ry, phi, bool(laf), bool(sf), (x, y)))
            pos = (x, y)
            prev_ctrl = None
            prev_cmd = "A"
        else:  # pragma: no cover
            raise SystemExit(f"unsupported path command {cmd!r}")
    if len(cur) > 1:
        subpaths.append(cur)
    return subpaths


# --------------------------------------------------------------------------- #
# rasteriser
# --------------------------------------------------------------------------- #
def _scaled(points, scale: float, ox: float = 0.0, oy: float = 0.0):
    return [(p[0] * scale + ox, p[1] * scale + oy) for p in points]


def render_vector(vec: Vector, size: int, scale_factor: float = SS) -> Image.Image:
    """Render into an RGBA image `size` x `size` px (viewport kept square)."""
    px = size * scale_factor
    scale = px / vec.viewport_w
    canvas = Image.new("RGBA", (px, px), (0, 0, 0, 0))

    for p in vec.paths:
        if not p.data:
            continue
        subpaths = path_to_subpaths(p.data)
        if not subpaths:
            continue
        ox, oy = p.translate[0] * scale, p.translate[1] * scale
        if p.fill is not None and p.fill[3] > 0:
            mask = Image.new("L", (px, px), 0)
            draw = ImageDraw.Draw(mask)
            if p.even_odd and len(subpaths) > 1:
                acc = Image.new("1", (px, px), 0)
                for sp in subpaths:
                    if len(sp) < 2:
                        continue
                    one = Image.new("1", (px, px), 0)
                    ImageDraw.Draw(one).polygon(_scaled(sp, scale, ox, oy), fill=1)
                    acc = Image.eval(__import__("PIL.ImageChops", fromlist=["xor"]).xor(acc, one), lambda v: v)
                mask = acc.convert("L").point(lambda v: 255 if v else 0)
            else:
                for sp in subpaths:
                    if len(sp) < 2:
                        continue
                    draw.polygon(_scaled(sp, scale, ox, oy), fill=255)
            layer = Image.new("RGBA", (px, px), p.fill)
            layer.putalpha(mask)
            canvas = Image.alpha_composite(canvas, layer)
        if p.stroke is not None and p.stroke[3] > 0 and p.stroke_width > 0:
            mask = Image.new("L", (px, px), 0)
            draw = ImageDraw.Draw(mask)
            r = p.stroke_width * scale / 2.0
            for sp in subpaths:
                pts = _scaled(sp, scale, ox, oy)
                for a, b in zip(pts, pts[1:]):
                    draw.line([a, b], fill=255, width=max(1, int(round(p.stroke_width * scale))))
                if p.cap_round:
                    for pt in (pts[0], pts[-1]):
                        draw.ellipse([pt[0] - r, pt[1] - r, pt[0] + r, pt[1] + r], fill=255)
                # round joins
                for pt in pts[1:-1]:
                    draw.ellipse([pt[0] - r, pt[1] - r, pt[0] + r, pt[1] + r], fill=255)
            layer = Image.new("RGBA", (px, px), p.stroke)
            layer.putalpha(mask)
            canvas = Image.alpha_composite(canvas, layer)

    return canvas.resize((size, size), Image.LANCZOS)


def flatten(img: Image.Image, bg: tuple[int, int, int, int]) -> Image.Image:
    base = Image.new("RGBA", img.size, bg)
    return Image.alpha_composite(base, img)


def circle_mask(size: int, inset_ratio: float = 0.0) -> Image.Image:
    m = Image.new("L", (size * 4, size * 4), 0)
    pad = size * 4 * inset_ratio
    ImageDraw.Draw(m).ellipse([pad, pad, size * 4 - pad - 1, size * 4 - pad - 1], fill=255)
    return m.resize((size, size), Image.LANCZOS)


def squircle_mask(size: int, radius_ratio: float = 0.42, inset_ratio: float = 0.0) -> Image.Image:
    m = Image.new("L", (size * 4, size * 4), 0)
    pad = size * 4 * inset_ratio
    box = [pad, pad, size * 4 - pad - 1, size * 4 - pad - 1]
    radius = (box[2] - box[0]) * radius_ratio
    ImageDraw.Draw(m).rounded_rectangle(box, radius=radius, fill=255)
    return m.resize((size, size), Image.LANCZOS)


def _font(px: int):
    for name in ("segoeui.ttf", "arial.ttf", "DejaVuSans.ttf"):
        for base in (Path("C:/Windows/Fonts"), Path("/usr/share/fonts")):
            candidate = base / name
            if candidate.exists():
                try:
                    return ImageFont.truetype(str(candidate), px)
                except OSError:
                    pass
    return ImageFont.load_default()


def safe_zone_fraction(alpha: Image.Image, size: int, center_dp: float = CANVAS_DP / 2) -> float:
    """Largest distance (dp) from the canvas centre reached by an opaque pixel."""
    px_per_dp = size / CANVAS_DP
    worst = 0.0
    data = alpha.load()
    for y in range(size):
        for x in range(size):
            if data[x, y] < 8:
                continue
            dx = (x + 0.5) / px_per_dp - center_dp
            dy = (y + 0.5) / px_per_dp - center_dp
            worst = max(worst, math.hypot(dx, dy))
    return worst


def main(argv: list[str] | None = None) -> int:
    here = Path(__file__).resolve().parent.parent
    ap = argparse.ArgumentParser()
    ap.add_argument("--res-dir", default=str(here / "app/src/main/res"))
    ap.add_argument("--out-dir", default=str(here / "docs/screenshots"))
    ap.add_argument("--size", type=int, default=512)
    ap.add_argument("--foreground", default=None, help="override foreground vector (design iteration)")
    ap.add_argument("--background", default=None, help="override background vector (design iteration)")
    args = ap.parse_args(argv)

    res = Path(args.res_dir)
    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    size = args.size

    fg = load_vector(Path(args.foreground) if args.foreground else res / "drawable/ic_launcher_foreground.xml")
    bg = load_vector(Path(args.background) if args.background else res / "drawable/ic_launcher_background.xml")
    mono = load_vector(res / "drawable/ic_launcher_monochrome.xml")

    fg_img = render_vector(fg, size)
    bg_img = render_vector(bg, size)
    mono_img = render_vector(mono, size)

    full = flatten(Image.alpha_composite(bg_img, fg_img), (0, 0, 0, 255))
    full.save(out / "app-icon.png", "PNG")

    # Round mask, drawn on a neutral launcher-ish field so the white ring of the
    # mask is obvious without an actual home screen.
    field = Image.new("RGBA", (size, size), (232, 234, 237, 255))
    circle = circle_mask(size)
    masked = full.copy()
    masked.putalpha(circle)
    field.alpha_composite(masked)
    field.convert("RGB").save(out / "app-icon-round.png", "PNG")

    # Themed/monochrome layer, as Android 13+ tints it (single colour on a system
    # wallpaper-ish background). Keeps the shape honest for themed icons.
    themed_bg = Image.new("RGBA", (size, size), (32, 34, 38, 255))
    tinted = Image.new("RGBA", (size, size), (216, 226, 255, 255))
    tinted.putalpha(mono_img.getchannel("A"))
    themed = Image.alpha_composite(themed_bg, tinted)
    themed_mask = squircle_mask(size)
    themed_masked = themed.copy()
    themed_masked.putalpha(themed_mask)
    check = Image.new("RGBA", (size, size), (232, 234, 237, 255))
    check.alpha_composite(themed_masked)
    check.convert("RGB").save(out / "app-icon-monochrome.png", "PNG")

    # Legibility ladder at the sizes a launcher actually draws (48dp is nominal).
    ladder_sizes = [144, 96, 72, 48]
    gap = 26
    ladder = Image.new(
        "RGBA",
        (sum(ladder_sizes) + gap * (len(ladder_sizes) + 1), max(ladder_sizes) + gap * 2),
        (236, 238, 240, 255),
    )
    x = gap
    for s in ladder_sizes:
        scaled = full.resize((s, s), Image.LANCZOS)
        scaled.putalpha(circle_mask(s))
        ladder.alpha_composite(scaled, (x, gap + (max(ladder_sizes) - s) // 2))
        x += s + gap
    ladder.convert("RGB").save(out / "app-icon-sizes.png", "PNG")

    # Safe-zone diagnostic: foreground on a dim field, 66dp mask circle + squircle,
    # 108dp canvas border and centre crosshair.
    diag = Image.new("RGBA", (size, size), (14, 42, 32, 255))
    dim_bg = flatten(bg_img, (14, 42, 32, 255))
    diag = Image.blend(diag, dim_bg, 0.55)
    diag = Image.alpha_composite(diag, fg_img)
    d = ImageDraw.Draw(diag)
    k = size / CANVAS_DP
    c = size / 2
    r33 = SAFE_ZONE_RADIUS_DP * k
    d.ellipse([c - r33, c - r33, c + r33, c + r33], outline=(255, 255, 255, 255), width=max(1, size // 256))
    d.ellipse([c - r33 / 2, c - r33 / 2, c + r33 / 2, c + r33 / 2], outline=(255, 255, 255, 90), width=1)
    d.line([c, 0, c, size], fill=(255, 255, 255, 60), width=1)
    d.line([0, c, size, c], fill=(255, 255, 255, 60), width=1)
    d.rectangle([1, 1, size - 2, size - 2], outline=(255, 255, 255, 40), width=1)
    label = _font(max(11, size // 26))
    d.text((8, 8), "108dp canvas", font=label, fill=(255, 255, 255, 200))
    d.text((8, size - size // 22), "66dp safe circle (r=33)", font=label, fill=(255, 255, 255, 200))
    diag.convert("RGB").save(out / "app-icon-safe-zone.png", "PNG")

    fg_alpha = fg_img.getchannel("A")
    worst = safe_zone_fraction(fg_alpha, size)
    print(f"foreground safe-zone reach: {worst:.2f}dp from centre (mask radius {SAFE_ZONE_RADIUS_DP}dp)")
    if worst > SAFE_ZONE_RADIUS_DP:
        print("SAFE ZONE VIOLATION: the cat would be clipped by a round mask", file=sys.stderr)
    # bottom-left placement audit: compare mass in the two halves
    px = fg_alpha.load()
    left = sum(1 for y in range(size) for x in range(size // 2) if px[x, y] > 40)
    right = sum(1 for y in range(size) for x in range(size // 2, size) if px[x, y] > 40)
    top = sum(1 for y in range(size // 2) for x in range(size) if px[x, y] > 40)
    bottom = sum(1 for y in range(size // 2, size) for x in range(size) if px[x, y] > 40)
    print(f"ink distribution: left {left} right {right} / top {top} bottom {bottom}")
    print(f"wrote {out}/app-icon.png, app-icon-round.png, app-icon-monochrome.png, app-icon-safe-zone.png")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
