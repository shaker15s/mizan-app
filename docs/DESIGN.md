# Design system

The system lives in `:design`. Screens use those components and tokens. They do
not pick ad-hoc colors, sizes, or durations.

Everything below is what the code does, not what it intends to do.

## Brand

**MIZAN** is Arabic for a balance. The mark is a balance at rest inside a seal:
a beam on a fulcrum, two pans, a pillar and a plinth. It is symmetric, because
an instrument that is being read is not leaning. It is not a robot, a chat
bubble, or a letterform.

The geometry lives in one place and is authored twice from it:

```text
tools/render_brand.py   ──▶  brand/mizan-mark.svg          (source, 64 unit square)
                       ──▶  brand/mizan-launcher.svg       (108 unit adaptive icon)
                       ──▶  app/.../mipmap-*/ic_launcher*.png     (5 densities)
                       ──▶  app + design: drawable/ic_mizan_mark.xml
                       ──▶  app: drawable/ic_launcher_foreground.xml
                            app: drawable/ic_launcher_background.xml
```

`python3 tools/render_brand.py` regenerates all of it with no external tool:
the rasteriser is in the script, supersampled 3x, with a source-over composite
and a real PNG writer. `python3 tools/render_brand.py --check` verifies sizes.
`python3 tools/render_preview.py` renders `brand/preview-design-system.png`
from the palettes parsed out of `Tokens.kt`, so the sheet cannot drift.

In the app, `MizanMark` draws the same 64 unit geometry on a Canvas, which is
why it is crisp at 18 dp in a row and at 160 dp on a hero. Its only motion is
the beam settling by 1.4 degrees: a metronome for "this is being weighed",
never a progress indicator. `MizanHeroEmblem` puts it in a frosted squircle
with an accent aura. `MizanWordmark` pairs the mark with tracked type — the
bundled faces are one weight, and a logotype faked in a weight the file does
not contain looks worse than honest type.

## Tokens

`MizanColors` is a bag of *roles*, not hues: `background`, `surface`,
`surfaceElevated`, `glass`, `textPrimary/Secondary/Tertiary`, `border`,
`borderStrong`, `focus`, `accent` + `onAccent` + `accentMuted` +
`accentSecondary` + `accentTertiary`, `userBubble`, the four status families
(`success`, `warning`, `danger`, `info`, each with an `on-` and a `-Container`),
`neutral`, and `scrim`.

A preset supplies a `PaletteSeed` — accent trio, surfaces, glass, text, ink,
and the user bubble. Borders, containers, scrim, focus and the muted accent are
**derived**, so a palette cannot be half finished.

| preset | character |
| --- | --- |
| `cyber_mizan` | the default: cyan to teal on deep ink |
| `emerald_gov` | governance green, calmer and more institutional |
| `royal_indigo` | indigo and violet, for long audit sessions |
| `sovereign_gold` | brass on paper, warm and formal |
| `crimson_ledger` | finance rose, high contrast |
| `obsidian_dark` | true black with an ice accent, for OLED |
| `system_dynamic` | Material You from Android 12, mapped onto MIZAN roles |

Every preset defines **both** a light and a dark palette. There is no preset
that silently falls back to another one, and `normalizePreset` maps anything
unknown back to the default rather than rendering a half-themed screen.

`system_dynamic` is only offered on API 31+, and the dynamic scheme is mapped
onto MIZAN roles instead of used raw, so `accentMuted` and `dangerContainer`
still belong to the app's semantic set rather than to a wallpaper.

## Gradients

Gradients are tokens, not decoration: `accentBrush()` (three stops, diagonal)
and `heroBrush()` (a vertical accent wash). The primary button uses
`accentBrush()`, the hero emblem uses a radial aura. Nothing else gradients.

## Type

Plus Jakarta Sans for English, Cairo for Arabic, JetBrains Mono for ids and
hashes. Each file is a single weight, so hierarchy is size, colour, spacing and
tracking — never a synthesized bold. `displayLarge` through `labelSmall` are
defined, and letter spacing tightens as the size grows.

Arabic raises line height (26 sp against 22 sp for body) because the script
needs it, and it switches the whole family, not just the strings.

## Motion

`design/.../motion/Interactions.kt` is the only place interaction is defined:

| API | what it does |
| --- | --- |
| `mizanTap` | spring scale (0.97), a ripple, one haptic tick. Every tappable surface |
| `mizanPressable` | the same spring for a container that hosts its own controls |
| `mizanReveal(index)` | staggered fade + 18 px rise + 1.5% settle, 26 ms per item |
| `mizanShimmer` | a sheen across a placeholder, not a spinner for unasked content |
| `mizanPulse` | a slow breath on something waiting for a person |
| `mizanGlow` | one radial gradient behind a hero element; no blur, no shadow node |
| `rememberMizanHaptics` | tap, select and reject as named gestures |

`mizanBounceClick`, which screens already used, now delegates to `mizanTap`, so
there is one press implementation and not two that drift.

Durations come from `Motion.millis(MotionToken, reduced)` and collapse to 1 ms
under reduced motion. Springs are `MizanPressSpring` (bouncy, for touch) and
`MizanValueSpring` (no overshoot: money should not bounce).

Screen transitions combine slide, fade and a 2% scale, entering in 320 ms and
leaving in 160 ms: a back press is answered faster than a forward one.

## Components

`MizanSurface`, `MizanGlassCard`, `MizanSectionHeader`, `MizanStatusBadge` (its
dot breathes on warning and danger), `MizanBanner` (a rounded card that reveals
instead of popping), primary / secondary / danger / ghost buttons (48 dp
minimum, gradient on primary, no layout jump when the loader appears), empty,
error and loading states (the loading state shows the *shape* of what is
coming), list rows, command field, icon button with a content description,
`LtrText` for technical strings, key-value rows, and the glass dock.

Glass is the dock and the bottom bar. It is not the default page surface. There
is no blur: on a low-end device a blur is a dropped frame, and this app is
meant to be used while standing in a warehouse.

## Layout

Under 600 dp, five destinations in a bottom bar. From 600 dp, a rail. From
840 dp the rail shows reconciliation and rules as well and operations uses a
list-detail split. `Space` is 4/8/12/16/20/24/32/48.

## Accessibility

Buttons meet 48 dp. Icons that navigate have content descriptions. Status is a
word plus a mark, never colour alone. Reduced motion is a preference and is
honoured by every animation in the motion package. Contrast is a property of
the palette derivation, not of a per-screen decision.

This is not a completed TalkBack audit; no device pass was run.

## What is deliberately absent

- No blur, no glass on content surfaces, no parallax.
- No animation on a value the user is meant to trust. A number that counts up
  is decoration; an amount either is verified or it is not, and the UI says
  which.
- No synthesized font weights, no fake logotype.
- No decorative gradient on anything but the primary action and the hero.
