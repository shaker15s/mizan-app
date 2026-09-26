#!/usr/bin/env python3
"""Builds a brand contact sheet from the real tokens.

It reads the palettes out of `design/.../token/Tokens.kt`, so the sheet cannot
drift from the app: change a colour in Kotlin and the next run of this script
shows the new colour. Requires Pillow; renders nothing else.

    python3 tools/render_preview.py
"""

from __future__ import annotations

import os
import re
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOKENS = os.path.join(ROOT, "design", "src", "main", "kotlin", "app", "mizan", "design", "token", "Tokens.kt")


def read_palettes():
    source = open(TOKENS, encoding="utf-8").read()
    palettes = {}
    pattern = re.compile(r"private fun (\w+?)(Light|Dark)\(\) = PaletteSeed\((.*?)\n\)", re.S)
    for name, mode, body in pattern.findall(source):
        colors = {key: value for key, value in re.findall(r"(\w+)\s*=\s*Color\(0x([0-9A-Fa-f]{8})\)", body)}
        palettes.setdefault(name, {})[mode.lower()] = colors
    return palettes


def rgba(hex_value):
    raw = hex_value
    return (
        int(raw[2:4], 16),
        int(raw[4:6], 16),
        int(raw[6:8], 16),
        int(raw[0:2], 16),
    )


def main():
    try:
        palettes = read_palettes()
    except FileNotFoundError:
        print("Tokens.kt not found", file=sys.stderr)
        return 1
    if not palettes:
        print("no palettes parsed; check the Tokens.kt structure", file=sys.stderr)
        return 1

    # Read the order out of Tokens.kt so a new preset appears here without
    # anyone remembering to edit this file.
    source = open(TOKENS, encoding="utf-8").read()
    preset_ids = re.findall(r'const val (\w+) = "(\w+)"', source)
    id_to_palette = {
        "cyber_mizan": "cyber",
        "emerald_gov": "emerald",
        "royal_indigo": "indigo",
        "sovereign_gold": "gold",
        "crimson_ledger": "crimson",
        "obsidian_dark": "obsidian",
        "aurora_glass": "aurora",
        "arctic_prism": "arctic",
        "basalt_neutral": "basalt",
        "sandstone_amber": "sandstone",
    }
    names, labels = [], {}
    for constant, preset_id in preset_ids:
        palette = id_to_palette.get(preset_id)
        if palette and palette in palettes:
            names.append(palette)
            labels[palette] = constant.replace("_", " ").title()

    card_w, card_h = 210, 150
    gap = 16
    margin = 32
    header = 190
    columns = 3
    rows = (len(names) + columns - 1) // columns
    width = margin * 2 + columns * card_w + (columns - 1) * gap
    height = header + rows * card_h + (rows - 1) * gap + margin

    font = ImageFont.load_default()
    sheet = Image.new("RGBA", (width, height), (11, 15, 22, 255))
    draw = ImageDraw.Draw(sheet)

    # header: icon, wordmark, one line of intent
    icon_path = os.path.join(ROOT, "brand", "preview-launcher-512.png")
    if os.path.exists(icon_path):
        icon = Image.open(icon_path).convert("RGBA").resize((128, 128), Image.LANCZOS)
        sheet.alpha_composite(icon, (margin, 30))
    draw.text((margin + 152, 56), "MIZAN", font=font, fill=(230, 245, 250, 255))
    draw.text(
        (margin + 152, 76),
        "Governed agentic ERP. The device prepares. The service decides.",
        font=font,
        fill=(150, 175, 190, 255),
    )
    draw.text(
        (margin + 152, 96),
        "Identity, palettes and motion generated from the source tokens.",
        font=font,
        fill=(110, 135, 150, 255),
    )

    for index, name in enumerate(names):
        column = index % columns
        row = index // columns
        x = margin + column * (card_w + gap)
        y = header + row * (card_h + gap)
        light = palettes.get(name, {}).get("light", {})
        dark = palettes.get(name, {}).get("dark", {})

        # card split: top half light, bottom half dark, with a mock button
        for half, palette in ((0, light), (1, dark)):
            if not palette:
                continue
            top = y + half * (card_h // 2)
            bottom = y + (half + 1) * (card_h // 2)
            draw.rectangle(
                [x, top, x + card_w, bottom],
                fill=rgba(palette.get("background", "FF000000")),
            )
            # mock surface card
            draw.rounded_rectangle(
                [x + 12, top + 12, x + card_w - 12, bottom - 12],
                radius=14,
                fill=rgba(palette.get("surface", "FF000000")),
            )
            # mock title line
            draw.rounded_rectangle(
                [x + 24, top + 26, x + 120, top + 34],
                radius=4,
                fill=rgba(palette.get("textPrimary", "FFFFFFFF")),
            )
            # mock body lines
            for line in range(2):
                draw.rounded_rectangle(
                    [x + 24, top + 42 + line * 10, x + 150 - line * 30, top + 48 + line * 10],
                    radius=3,
                    fill=rgba(palette.get("textSecondary", "FF888888")),
                )
            # the action: a gradient-filled pill in the accent
            accent = rgba(palette.get("accent", "FF00FF00"))
            secondary = rgba(palette.get("accentSecondary", accent))
            pill = Image.new("RGBA", (96, 26), (0, 0, 0, 0))
            pill_draw = ImageDraw.Draw(pill)
            for pixel in range(96):
                t = pixel / 95.0
                pill_draw.line(
                    [(pixel, 0), (pixel, 26)],
                    fill=(
                        int(secondary[0] + (accent[0] - secondary[0]) * t),
                        int(secondary[1] + (accent[1] - secondary[1]) * t),
                        int(secondary[2] + (accent[2] - secondary[2]) * t),
                        255,
                    ),
                )
            pill = pill.crop((0, 0, 96, 26))
            mask = Image.new("L", (96, 26), 0)
            ImageDraw.Draw(mask).rounded_rectangle([0, 0, 95, 25], radius=13, fill=255)
            sheet.paste(pill, (x + 24, top + 66), mask)

        draw.rounded_rectangle([x, y, x + card_w, y + card_h], radius=16, outline=(90, 110, 125, 255), width=1)
        draw.text((x + 12, y + card_h - 16), labels.get(name, name), font=font, fill=(200, 220, 230, 255))

    out = os.path.join(ROOT, "brand", "preview-design-system.png")
    sheet.save(out)
    print(f"wrote {os.path.relpath(out, ROOT)} ({sheet.size[0]}x{sheet.size[1]})")
    print("presets rendered:", ", ".join(labels[n] for n in names))
    return 0


if __name__ == "__main__":
    sys.exit(main())
