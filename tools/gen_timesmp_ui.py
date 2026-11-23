#!/usr/bin/env python3
"""Generate TimeSMP custom container *top* backgrounds.

Only the top part of a chest screen is themed: the header and the container
rows. The player inventory half stays vanilla (`generic_54` v=126..222), as
most servers do.

Sizes: 6 rows = 176x125, 3 rows = 176x71 (17px header + rows*18).
Canvas: 256x256, content at (0,0), transparent elsewhere.
"""
from __future__ import annotations

import os
from pathlib import Path
from PIL import Image, ImageDraw

W = 176
INK = (7, 11, 20, 255)
PANEL = (15, 23, 42, 255)
SLOT = (8, 13, 24, 255)
SLOT_HI = (30, 41, 59, 255)
SLOT_LO = (2, 6, 14, 255)
CREAM = (245, 230, 200, 255)
TEXT = (51, 65, 85, 255)

ACCENTS = {
    "auction": (245, 158, 11, 255),
    "auction_items": (251, 191, 36, 255),
    "auction_cells": (249, 115, 22, 255),
    "auction_detail": (245, 158, 11, 255),
    "auction_mine": (167, 139, 250, 255),
    "buy": (56, 189, 248, 255),
    "buy_mine": (14, 165, 233, 255),
    "buy_detail": (56, 189, 248, 255),
    "sell": (52, 211, 153, 255),
    "tpa": (34, 211, 238, 255),
    "tpa_requests": (6, 182, 212, 255),
    "claimmap": (16, 185, 129, 255),
    "claim_manage": (5, 150, 105, 255),
    "mail": (248, 113, 113, 255),
    "price": (234, 179, 8, 255),
    "price_detail": (202, 138, 4, 255),
    "home": (251, 146, 60, 255),
    "help": (148, 163, 184, 255),
}

ICONS = {
    "auction": "gavel",
    "auction_items": "gavel",
    "auction_cells": "gavel",
    "auction_detail": "gavel_coin",
    "auction_mine": "star",
    "buy": "cart",
    "buy_mine": "cart",
    "buy_detail": "cart_check",
    "sell": "coin",
    "tpa": "warp",
    "tpa_requests": "mail_arrow",
    "claimmap": "map",
    "claim_manage": "shield",
    "mail": "mail",
    "price": "tag",
    "price_detail": "tag",
    "home": "home",
    "help": "book",
}


def blend(a, b, t):
    return tuple(int(a[i] * (1 - t) + b[i] * t) for i in range(4))


def icon(d, kind, x, y, c):
    """12x12 pixel icon at (x, y)."""
    dark = (15, 23, 42, 255)
    light = (254, 243, 199, 255)
    if kind == "gavel":
        d.rectangle([x + 2, y + 8, x + 9, y + 9], fill=(180, 83, 9, 255))
        d.rectangle([x + 7, y + 1, x + 11, y + 5], fill=c)
        d.rectangle([x + 6, y + 2, x + 6, y + 6], fill=light)
    elif kind == "gavel_coin":
        d.rectangle([x + 1, y + 8, x + 7, y + 9], fill=(180, 83, 9, 255))
        d.rectangle([x + 6, y + 1, x + 10, y + 5], fill=c)
        d.ellipse([x + 7, y + 6, x + 11, y + 10], fill=(251, 191, 36, 255))
    elif kind == "cart":
        d.rectangle([x + 2, y + 3, x + 10, y + 7], fill=c)
        d.rectangle([x + 1, y + 2, x + 9, y + 2], fill=light)
        d.line([x + 1, y + 3, x + 1, y + 1], fill=c)
        d.ellipse([x + 3, y + 8, x + 5, y + 10], fill=c)
        d.ellipse([x + 8, y + 8, x + 10, y + 10], fill=c)
    elif kind == "cart_check":
        d.rectangle([x + 1, y + 3, x + 8, y + 6], fill=c)
        d.line([x + 1, y + 3, x + 1, y + 1], fill=c)
        d.ellipse([x + 2, y + 7, x + 4, y + 9], fill=c)
        d.ellipse([x + 6, y + 7, x + 8, y + 9], fill=c)
        d.line([x + 8, y + 8, x + 10, y + 6], fill=light)
        d.line([x + 10, y + 6, x + 11, y + 9], fill=light)
    elif kind == "coin":
        d.ellipse([x + 2, y + 2, x + 10, y + 10], fill=c)
        d.ellipse([x + 4, y + 4, x + 8, y + 8], fill=(254, 240, 138, 255))
        d.rectangle([x + 6, y + 4, x + 6, y + 8], fill=(180, 83, 9, 255))
    elif kind == "warp":
        d.line([x + 2, y + 6, x + 9, y + 6], fill=c, width=2)
        d.line([x + 6, y + 3, x + 9, y + 6], fill=c)
        d.line([x + 9, y + 6, x + 6, y + 9], fill=c)
        d.line([x + 10, y + 2, x + 10, y + 10], fill=light)
    elif kind == "mail":
        d.rectangle([x + 1, y + 3, x + 11, y + 9], fill=c)
        d.line([x + 1, y + 3, x + 6, y + 7], fill=dark)
        d.line([x + 6, y + 7, x + 11, y + 3], fill=dark)
    elif kind == "mail_arrow":
        d.rectangle([x + 1, y + 4, x + 8, y + 10], fill=c)
        d.line([x + 1, y + 4, x + 4, y + 7], fill=dark)
        d.line([x + 4, y + 7, x + 8, y + 4], fill=dark)
        d.line([x + 9, y + 2, x + 11, y + 2], fill=light)
        d.line([x + 11, y + 2, x + 9, y + 4], fill=light)
    elif kind == "map":
        d.rectangle([x + 1, y + 2, x + 11, y + 10], fill=(254, 243, 199, 255))
        for gx in range(2, 11, 3):
            d.line([x + gx, y + 2, x + gx, y + 10], fill=c)
        for gy in range(4, 10, 3):
            d.line([x + 1, y + gy, x + 11, y + gy], fill=c)
    elif kind == "shield":
        d.polygon([(x + 2, y + 1), (x + 10, y + 1), (x + 10, y + 6),
                   (x + 6, y + 11), (x + 2, y + 6)], fill=c)
        d.line([x + 6, y + 3, x + 6, y + 8], fill=light)
    elif kind == "tag":
        d.polygon([(x + 1, y + 5), (x + 5, y + 1), (x + 11, y + 1),
                   (x + 11, y + 7), (x + 7, y + 11), (x + 1, y + 5)], fill=c)
        d.ellipse([x + 8, y + 3, x + 9, y + 4], fill=dark)
    elif kind == "home":
        d.polygon([(x + 6, y + 1), (x + 11, y + 6), (x + 1, y + 6)], fill=c)
        d.rectangle([x + 3, y + 6, x + 9, y + 11], fill=(254, 243, 199, 255))
        d.rectangle([x + 5, y + 8, x + 7, y + 11], fill=(120, 53, 15, 255))
    elif kind == "book":
        d.rectangle([x + 2, y + 1, x + 10, y + 11], fill=c)
        d.rectangle([x + 2, y + 1, x + 4, y + 11], fill=light)
        d.line([x + 5, y + 4, x + 9, y + 4], fill=dark)
        d.line([x + 5, y + 6, x + 9, y + 6], fill=dark)
    elif kind == "star":
        d.polygon([(x + 6, y + 1), (x + 7, y + 5), (x + 11, y + 6),
                   (x + 7, y + 7), (x + 6, y + 11), (x + 5, y + 7),
                   (x + 1, y + 6), (x + 5, y + 5)], fill=c)


def slot(d, x, y, accent, control=False):
    d.rectangle([x, y, x + 17, y + 17], fill=SLOT)
    d.rectangle([x, y, x + 17, y], fill=SLOT_HI)
    d.rectangle([x, y, x, y + 17], fill=SLOT_HI)
    d.rectangle([x + 17, y, x + 17, y + 17], fill=SLOT_LO)
    d.rectangle([x, y + 17, x + 17, y + 17], fill=SLOT_LO)
    if control:
        d.rectangle([x + 1, y + 1, x + 16, y + 1], fill=blend(SLOT_HI, accent, 0.35))
        d.rectangle([x + 1, y + 16, x + 16, y + 16], fill=blend(SLOT_LO, accent, 0.25))


def base(key: str, rows: int) -> Image.Image:
    h = 17 + rows * 18
    accent = ACCENTS.get(key, (245, 158, 11, 255))
    im = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)

    for y in range(h):
        t = y / max(1, h - 1)
        d.line([(0, y), (W - 1, y)], fill=blend(INK, PANEL, 0.35 + 0.35 * t))

    d.rectangle([0, 0, W - 1, h - 1], outline=INK, width=1)
    d.rectangle([1, 1, W - 2, h - 2], outline=accent, width=1)

    d.rectangle([2, 2, W - 3, 17], fill=CREAM)
    d.rectangle([2, 18, W - 3, 18], fill=accent)
    for x in range(2, W - 2, 8):
        d.line([(x, 2), (x + 4, 17)], fill=blend(CREAM, accent, 0.10))
    icon(d, ICONS.get(key, "star"), W - 18, 3, accent)
    for (rx, ry) in ((3, 3), (W - 4, 3)):
        d.rectangle([rx, ry, rx + 1, ry + 1], fill=accent)

    for r in range(rows):
        for c in range(9):
            slot(d, 8 + 18 * c, 18 + 18 * r, accent, control=(r == rows - 1))

    # Bottom edge: the vanilla player half starts here, so keep a crisp line.
    d.rectangle([1, h - 2, W - 2, h - 2], fill=accent)
    d.rectangle([2, h - 1, W - 3, h - 1], fill=INK)

    for y in range(20, h - 4, 12):
        d.rectangle([2, y, 2, y + 3], fill=accent)
        d.rectangle([W - 3, y, W - 3, y + 3], fill=accent)
    return im


def main() -> None:
    out = Path(os.environ.get("OUT", "generated"))
    out.mkdir(parents=True, exist_ok=True)
    for key in ACCENTS:
        rows = 3 if key.endswith("_detail") or key == "claim_manage" else 6
        base(key, rows).save(out / f"{key}.png")
    print(f"wrote {len(ACCENTS)} images to {out}")


if __name__ == "__main__":
    main()