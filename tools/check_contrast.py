#!/usr/bin/env python3
"""Checks the contrast of every theme preset against WCAG 2.1.

A preset is not finished because it looks nice on a phone in a bright room.
It is finished when the text on it is readable, which is a number, not an
opinion. This script reads the palettes straight out of Tokens.kt -- the same
file the app compiles -- reproduces what `materialize()` derives, composites
every colour over the surface it is actually drawn on, and computes the WCAG
contrast ratio for the pairs the UI really uses.

Thresholds follow WCAG 2.1 AA:

    4.5 : 1   body text
    3.0 : 1   large text, icons, and the hairlines that separate controls

Run it after touching a palette. It fails the build when a preset regresses.

    python3 tools/check_contrast.py
    python3 tools/check_contrast.py --verbose   # every pair, not just failures
"""

from __future__ import annotations

import argparse
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOKENS = os.path.join(
    ROOT, "design", "src", "main", "kotlin", "app", "mizan", "design", "token", "Tokens.kt"
)

PALETTE = re.compile(r"private fun (\w+?)(Light|Dark)\(\) = PaletteSeed\((.*?)\n\)", re.S)
STATUS = re.compile(r"private object (Light|Dark)Status \{(.*?)\n\}", re.S)
COLOR = re.compile(r"(\w+)\s*=\s*Color\(0x([0-9A-Fa-f]{8})\)")

BODY = 4.5
LARGE = 3.0


def parse_argb(value: str) -> tuple[int, int, int, int]:
    return (
        int(value[2:4], 16),
        int(value[4:6], 16),
        int(value[6:8], 16),
        int(value[0:2], 16),
    )


def read_tokens() -> tuple[dict, dict]:
    source = open(TOKENS, encoding="utf-8").read()

    palettes: dict[str, dict] = {}
    for name, mode, body in PALETTE.findall(source):
        palettes.setdefault(name, {})[mode.lower()] = {
            key: parse_argb(value) for key, value in COLOR.findall(body)
        }

    status: dict[str, dict] = {}
    for mode, body in STATUS.findall(source):
        status[mode.lower()] = {key: parse_argb(value) for key, value in COLOR.findall(body)}

    return palettes, status


def over(foreground: tuple[int, int, int, int], background: tuple[int, int, int, int]) -> tuple[int, int, int, int]:
    """Source-over composite. Everything in MIZAN is drawn on something."""
    fr, fg, fb, fa = foreground
    br, bg, bb, ba = background
    alpha = fa / 255.0
    if alpha >= 1.0:
        return (fr, fg, fb, 255)
    return (
        round(fr * alpha + br * (1 - alpha)),
        round(fg * alpha + bg * (1 - alpha)),
        round(fb * alpha + bb * (1 - alpha)),
        round(ba + fa * (1 - ba / 255.0)),
    )


def luminance(color: tuple[int, int, int, int]) -> float:
    def channel(value: int) -> float:
        c = value / 255.0
        return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4

    r, g, b, _ = color
    return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)


def contrast(a: tuple[int, int, int, int], b: tuple[int, int, int, int]) -> float:
    la, lb = luminance(a), luminance(b)
    lighter, darker = max(la, lb), min(la, lb)
    return (lighter + 0.05) / (darker + 0.05)


def hex_of(color: tuple[int, int, int, int]) -> str:
    return "#%02X%02X%02X" % color[:3]


def materialize(seed: dict, status: dict, is_dark: bool) -> dict:
    """Mirrors PaletteSeed.materialize(), including the derived roles."""
    ink_alpha = 0.16 if is_dark else 0.11
    colors = dict(seed)
    colors["glassBorder"] = (seed["ink"][0], seed["ink"][1], seed["ink"][2], round(255 * ink_alpha))
    colors["border"] = (seed["ink"][0], seed["ink"][1], seed["ink"][2], round(255 * (0.13 if is_dark else 0.07)))
    colors["borderStrong"] = (seed["ink"][0], seed["ink"][1], seed["ink"][2], round(255 * (0.26 if is_dark else 0.15)))
    colors["accentMuted"] = (
        seed["accent"][0],
        seed["accent"][1],
        seed["accent"][2],
        round(255 * (0.18 if is_dark else 0.11)),
    )
    colors.update(status)
    return colors


def pairs_for(colors: dict, glass_over_surface: tuple[int, int, int, int]) -> list[tuple[str, str, str, float]]:
    """(foreground, background, description, threshold) for what the UI draws."""
    surface = colors["surface"]
    background = colors["background"]
    elevated = colors["surfaceElevated"]
    accent = colors["accent"]
    return [
        ("textPrimary", "surface", "body text on a card", BODY),
        ("textPrimary", "background", "body text on the page", BODY),
        ("textPrimary", "surfaceElevated", "body text on a raised card", BODY),
        ("textSecondary", "surface", "secondary text on a card", BODY),
        ("textSecondary", "background", "secondary text on the page", BODY),
        ("textTertiary", "surface", "meta text on a card", BODY),
        ("textTertiary", "surfaceElevated", "meta text on a raised card", BODY),
        ("onAccent", "accent", "label on a primary button", BODY),
        ("onUserBubble", "userBubble", "the user's own chat bubble", BODY),
        ("accent", "surface", "accent text and icons on a card", LARGE),
        ("accent", "background", "accent text and icons on the page", LARGE),
        ("success", "successContainer", "a green status badge", BODY),
        ("warning", "warningContainer", "an amber status badge", BODY),
        ("danger", "dangerContainer", "a red status badge", BODY),
        ("info", "infoContainer", "a blue status badge", BODY),
        ("danger", "surface", "a refusal message on a card", BODY),
        ("accent", "accentMuted", "accent on its own wash", LARGE),
    ]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--verbose", action="store_true", help="print every pair, not just the failures")
    args = parser.parse_args()

    palettes, status_sets = read_tokens()
    if not palettes:
        print("check_contrast: no palettes parsed from Tokens.kt", file=sys.stderr)
        return 2

    failures: list[str] = []
    checked = 0
    worst_overall = (99.0, "")

    print(f"{len(palettes)} presets, light and dark\n")
    header = f"{'preset':<18}{'mode':<7}{'worst':>7}   {'pair':<44} ratio"
    print(header)
    print("-" * len(header))

    for name in sorted(palettes):
        for mode in ("light", "dark"):
            seed = palettes[name].get(mode)
            if seed is None:
                failures.append(f"{name}/{mode}: no palette")
                continue
            colors = materialize(seed, status_sets[mode], mode == "dark")
            # Glass is translucent, so text over it must be checked over the
            # composited result, not over the raw glass colour.
            glass_over_surface = over(colors["glass"], colors["surface"])

            worst = (99.0, "")
            for foreground, background, description, threshold in pairs_for(colors, glass_over_surface):
                fg = colors.get(foreground)
                bg = glass_over_surface if background == "glass" else colors.get(background)
                if fg is None or bg is None:
                    continue
                if bg[3] < 255:
                    # A translucent role is a role over something: composite it
                    # on the surface before measuring, or the number is a lie.
                    bg = over(bg, colors["surface"])
                if fg[3] < 255:
                    fg = over(fg, bg)
                ratio = contrast(fg, bg)
                checked += 1
                label = f"{foreground} on {background}"
                if ratio < threshold:
                    failures.append(
                        f"{name}/{mode}: {label} is {ratio:.2f}:1, needs {threshold}:1 -- {description}"
                    )
                if ratio < worst[0]:
                    worst = (ratio, label)
                if args.verbose:
                    print(f"{name:<18}{mode:<7}{ratio:>7.2f}   {label:<44} {description}")

            # Text over a glass pane, which is the whole point of the new design.
            glass_ratio = contrast(colors["textPrimary"], glass_over_surface)
            checked += 1
            if glass_ratio < BODY:
                failures.append(
                    f"{name}/{mode}: textPrimary over glass is {glass_ratio:.2f}:1, needs {BODY}:1"
                )
            if glass_ratio < worst[0]:
                worst = (glass_ratio, "textPrimary over glass")

            flag = "  <-- below AA" if worst[0] < LARGE else ""
            print(f"{name:<18}{mode:<7}{worst[0]:>7.2f}   {worst[1]:<44}{flag}")
            if worst[0] < worst_overall[0]:
                worst_overall = (worst[0], f"{name}/{mode} {worst[1]}")

    print()
    print(f"pairs checked : {checked}")
    print(f"worst pair    : {worst_overall[0]:.2f}:1  ({worst_overall[1]})")

    if failures:
        print(f"\n{len(failures)} pair(s) below the threshold:")
        for failure in failures[:40]:
            print("  - " + failure)
        if len(failures) > 40:
            print(f"  ... and {len(failures) - 40} more")
        return 1

    print("\nOK: every preset meets WCAG AA for the pairs the UI draws.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
