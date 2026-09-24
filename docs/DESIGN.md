# Design system

The system lives in `:design`. Screens use those components. They do not pick ad-hoc colors.

## Tokens

Warm paper and ink. Brass (`accent`) is the action, not a decoration on every surface. Status uses a badge and a word, not color alone. Spacing is `Space`. Motion durations are in `Motion` and collapse to 1 ms when reduced motion is on or the system animator scale is 0.

Light and dark are full palettes, selected from Account or the system theme.

## Type

Plus Jakarta Sans for English, Cairo for Arabic, JetBrains Mono for ids and hashes. The bundled files are a single weight. Hierarchy is size and color. The theme does not request a bold the files do not contain.

## Components

`MizanSurface`, `MizanSectionHeader`, `MizanStatusBadge`, `MizanBanner`, primary / secondary / danger / ghost buttons (minimum 48 dp), empty / error / loading states, list rows, command field, icon button with a content description, `LtrText` for technical strings, key-value rows, and the glass dock.

Glass is the dock and the bottom bar. It is not the default page surface. There is no blur.

## Layout

Under 600 dp, five destinations in a bottom bar. From 600 dp, a rail. From 840 dp, the rail shows reconciliation and rules as well, and operations uses a list-detail split.

## Accessibility

Buttons meet 48 dp. Icons that navigate have content descriptions. Status is a word plus a mark. Reduced motion is a preference. This is not a completed TalkBack audit; no device pass was run.
