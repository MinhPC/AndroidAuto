"""Draws the integration's icon: a winding road from a start dot to a destination pin, in the launcher's colours.

Run it from the repository root; it writes custom_components/car_trips/brand/icon.png (256 px) and icon@2x.png
(512 px), which Home Assistant shows in the integration list, the add-integration dialog and on the device page.
The picture is drawn at four times the size and shrunk, which is what smooths the edges.

    python home_assistant/tools/make_icon.py
"""

from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageDraw

BACKGROUND = "#263238"  # the launcher's icon background
ROAD = "#FFFFFF"
ACCENT = "#4FC3F7"  # the launcher's accent

CANVAS = 1024  # drawn at 4 x the largest size, then shrunk
OUT = Path(__file__).resolve().parent.parent / "custom_components" / "car_trips" / "brand"


def bezier(p0, p1, p2, p3, steps=400):
    """Points along a cubic Bezier curve."""
    for i in range(steps + 1):
        t = i / steps
        u = 1 - t
        yield (
            u**3 * p0[0] + 3 * u**2 * t * p1[0] + 3 * u * t**2 * p2[0] + t**3 * p3[0],
            u**3 * p0[1] + 3 * u**2 * t * p1[1] + 3 * u * t**2 * p2[1] + t**3 * p3[1],
        )


def disc(draw, centre, radius, fill):
    x, y = centre
    draw.ellipse((x - radius, y - radius, x + radius, y + radius), fill=fill)


def draw_icon() -> Image.Image:
    image = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle((0, 0, CANVAS - 1, CANVAS - 1), radius=230, fill=BACKGROUND)

    start = (300, 770)
    tip = (724, 486)
    road_end = (724, 496)  # its round end reaches under the tip, so the pin sits on the road

    # The road: a thick line with round ends, drawn as a chain of discs along an S-shaped curve.
    for point in bezier(start, (300, 520), (724, 740), road_end):
        disc(draw, point, 34, ROAD)

    # Where it starts: a ring.
    disc(draw, start, 82, ACCENT)
    disc(draw, start, 38, BACKGROUND)

    # Where it goes: a map pin whose point is the end of the road.
    head, radius = (724, 306), 124
    distance = tip[1] - head[1]
    beta = math.acos(radius / distance)  # the angle at which the pin's sides leave the head
    side = (radius * math.sin(beta), radius * math.cos(beta))
    draw.polygon([(head[0] - side[0], head[1] + side[1]), tip, (head[0] + side[0], head[1] + side[1])], fill=ACCENT)
    disc(draw, head, radius, ACCENT)
    disc(draw, head, 50, BACKGROUND)
    return image


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    big = draw_icon()
    for size, name in ((256, "icon.png"), (512, "icon@2x.png")):
        big.resize((size, size), Image.LANCZOS).save(OUT / name, optimize=True)
        print(f"wrote {OUT / name}")


if __name__ == "__main__":
    main()
