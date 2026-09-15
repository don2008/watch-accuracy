"""Generate labelled synthetic watch dials for offline layout/time recognition.

The output contains four layout families used by the Android reader:
classic, small_seconds, regulator and jump_hour.  Labels are stored as JSONL
and include both the displayed time and the centres/radii of relevant dials.
"""

from __future__ import annotations

import argparse
import json
import math
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageFont


SIZE = 512


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    name = "DejaVuSans-Bold.ttf" if bold else "DejaVuSans.ttf"
    try:
        return ImageFont.truetype(name, size)
    except OSError:
        return ImageFont.load_default()


def point(cx: float, cy: float, radius: float, angle_deg: float) -> tuple[float, float]:
    angle = math.radians(angle_deg - 90)
    return cx + math.cos(angle) * radius, cy + math.sin(angle) * radius


def hand(draw: ImageDraw.ImageDraw, centre: tuple[int, int], angle: float,
         length: float, width: int, colour: tuple[int, int, int]) -> None:
    cx, cy = centre
    ex, ey = point(cx, cy, length, angle)
    bx, by = point(cx, cy, length * .14, angle + 180)
    draw.line((bx, by, ex, ey), fill=colour, width=width)
    draw.ellipse((cx - width, cy - width, cx + width, cy + width), fill=colour)


def dial(draw: ImageDraw.ImageDraw, centre: tuple[int, int], radius: int,
         fg: tuple[int, int, int], accent: tuple[int, int, int], numerals: bool = True) -> None:
    cx, cy = centre
    draw.ellipse((cx-radius, cy-radius, cx+radius, cy+radius), outline=fg, width=max(2, radius//45))
    for minute in range(60):
        major = minute % 5 == 0
        outer = point(cx, cy, radius * .94, minute * 6)
        inner = point(cx, cy, radius * (.80 if major else .88), minute * 6)
        draw.line((*inner, *outer), fill=accent if major else fg, width=max(1, radius//55 if major else 1))
    if numerals and radius > 70:
        numeral_font = font(max(10, radius // 13))
        for hour in range(1, 13):
            x, y = point(cx, cy, radius * .69, hour * 30)
            text = str(hour)
            box = draw.textbbox((0, 0), text, font=numeral_font)
            draw.text((x-(box[2]-box[0])/2, y-(box[3]-box[1])/2), text, fill=fg, font=numeral_font)


def base_image(rng: random.Random) -> tuple[Image.Image, ImageDraw.ImageDraw, tuple[int, int, int], tuple[int, int, int]]:
    light = rng.random() < .66
    bg = tuple(rng.randint(220, 250) for _ in range(3)) if light else tuple(rng.randint(8, 38) for _ in range(3))
    fg = tuple(rng.randint(5, 45) for _ in range(3)) if light else tuple(rng.randint(215, 250) for _ in range(3))
    accent_options = [(190, 35, 35), (25, 105, 190), (220, 135, 20), fg]
    accent = rng.choice(accent_options)
    image = Image.new("RGB", (SIZE, SIZE), bg)
    return image, ImageDraw.Draw(image), fg, accent


def render(layout: str, hour: int, minute: int, second: int, rng: random.Random) -> tuple[Image.Image, dict]:
    image, draw, fg, accent = base_image(rng)
    c = (SIZE // 2, SIZE // 2)
    r = rng.randint(205, 230)
    dial(draw, c, r, fg, accent, numerals=rng.random() < .6)
    regions: dict[str, dict] = {"main": {"cx": c[0]/SIZE, "cy": c[1]/SIZE, "r": r/SIZE}}

    hour_angle = (hour % 12) * 30 + minute * .5
    minute_angle = minute * 6 + second * .1
    second_angle = second * 6

    if layout == "classic":
        hand(draw, c, hour_angle, r*.50, max(7, r//25), fg)
        hand(draw, c, minute_angle, r*.75, max(5, r//35), fg)
        hand(draw, c, second_angle, r*.84, max(2, r//85), accent)
    elif layout == "small_seconds":
        hand(draw, c, hour_angle, r*.50, max(7, r//25), fg)
        hand(draw, c, minute_angle, r*.75, max(5, r//35), fg)
        sc = (c[0], int(c[1] + r*.47)); sr = int(r*.22)
        dial(draw, sc, sr, fg, accent, numerals=False)
        hand(draw, sc, second_angle, sr*.72, max(2, sr//18), accent)
        regions["seconds"] = {"cx": sc[0]/SIZE, "cy": sc[1]/SIZE, "r": sr/SIZE}
    elif layout == "regulator":
        hand(draw, c, minute_angle, r*.78, max(5, r//35), fg)
        hc = (c[0], int(c[1] - r*.43)); sc = (c[0], int(c[1] + r*.47))
        sr = int(r*.23)
        dial(draw, hc, sr, fg, accent, numerals=False)
        dial(draw, sc, sr, fg, accent, numerals=False)
        hand(draw, hc, hour_angle, sr*.68, max(3, sr//13), accent)
        hand(draw, sc, second_angle, sr*.72, max(2, sr//18), accent)
        regions["hours"] = {"cx": hc[0]/SIZE, "cy": hc[1]/SIZE, "r": sr/SIZE}
        regions["seconds"] = {"cx": sc[0]/SIZE, "cy": sc[1]/SIZE, "r": sr/SIZE}
    elif layout == "jump_hour":
        hand(draw, c, minute_angle, r*.74, max(5, r//35), fg)
        if rng.random() < .5:
            hand(draw, c, second_angle, r*.83, max(2, r//85), accent)
        else:
            sc = (c[0], int(c[1] + r*.45)); sr = int(r*.20)
            dial(draw, sc, sr, fg, accent, numerals=False)
            hand(draw, sc, second_angle, sr*.72, max(2, sr//18), accent)
            regions["seconds"] = {"cx": sc[0]/SIZE, "cy": sc[1]/SIZE, "r": sr/SIZE}
        ww, wh = int(r*.42), int(r*.25)
        x0, y0 = c[0]-ww//2, int(c[1]-r*.57)-wh//2
        draw.rounded_rectangle((x0, y0, x0+ww, y0+wh), radius=8, outline=fg, width=4)
        txt = str(hour if hour else 12)
        window_font = font(max(18, int(wh*.58)), bold=True)
        box = draw.textbbox((0, 0), txt, font=window_font)
        draw.text((c[0]-(box[2]-box[0])/2, y0+(wh-(box[3]-box[1]))/2-box[1]),
                  txt, fill=accent, font=window_font)
        regions["hour_window"] = {"x": x0/SIZE, "y": y0/SIZE, "w": ww/SIZE, "h": wh/SIZE}
    else:
        raise ValueError(layout)

    # Mild camera-domain randomisation without destroying hand geometry.
    if rng.random() < .7:
        image = image.rotate(rng.uniform(-18, 18), resample=Image.Resampling.BICUBIC, fillcolor=image.getpixel((0, 0)))
    image = ImageEnhance.Contrast(image).enhance(rng.uniform(.75, 1.25))
    image = ImageEnhance.Brightness(image).enhance(rng.uniform(.72, 1.18))
    if rng.random() < .35:
        image = image.filter(ImageFilter.GaussianBlur(rng.uniform(.2, 1.1)))
    label = {
        "layout": layout, "hour": hour, "minute": minute, "second": second,
        "hour_angle": hour_angle, "minute_angle": minute_angle, "second_angle": second_angle,
        "regions": regions,
    }
    return image, label


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--count-per-layout", type=int, default=1000)
    parser.add_argument("--seed", type=int, default=20260915)
    args = parser.parse_args()
    rng = random.Random(args.seed)
    args.output.mkdir(parents=True, exist_ok=True)
    labels = args.output / "labels.jsonl"
    layouts = ("classic", "small_seconds", "regulator", "jump_hour")
    with labels.open("w", encoding="utf-8") as out:
        for layout in layouts:
            folder = args.output / layout
            folder.mkdir(exist_ok=True)
            for index in range(args.count_per_layout):
                hour, minute, second = rng.randrange(1, 13), rng.randrange(60), rng.randrange(60)
                image, label = render(layout, hour, minute, second, rng)
                relative = f"{layout}/{index:06d}.jpg"
                image.save(args.output / relative, quality=90)
                label["file"] = relative
                out.write(json.dumps(label, separators=(",", ":")) + "\n")
    print(f"Generated {len(layouts) * args.count_per_layout} images in {args.output}")


if __name__ == "__main__":
    main()
