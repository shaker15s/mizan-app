# Accessibility and localisation

Two of the plan's gates are about the app but are usually filed under "device
work": **gate 13, full Arabic localisation**, and **gate 15, a real
accessibility audit**. They are not the same problem as running the app on a
phone. A large part of both is static — a tree either has a hardcoded string in
it or it does not — and that part is now checked on every run instead of being
promised for later.

    python3 tools/strings_check.py            # errors and warnings
    python3 tools/strings_check.py --strict   # warnings fail too
    python3 tools/strings_check.py --selftest # prove the checks catch planted cases
    python3 tools/extract_strings.py --dry-run # what still needs extracting
    python3 tools/extract_strings.py --apply   # move it into the resources

## What is checked

**Localisation.** Every English string has an Arabic twin, the two format
positionally the same way, and — the check that stands in for the compiler this
environment does not have — every `R.string.name` referenced from Kotlin is
actually defined. That last one found `R.string.cancel`, referenced by the
biometric gate and defined nowhere: a build break in two files that only a
device build would have caught.

**Hardcoded text.** No user-facing sentence lives in Kotlin. A literal that a
person reads is an error; a test tag, an animation key, a snake_case token and
an upper-case code are not. The check ran at 183 sites when it was written and
now runs at zero, which is the point: the count is the deliverable.

**Touch targets.** A clickable surface smaller than 48dp fails. Only the
clickable's own modifier chain counts, so the 18dp icon inside a 48dp button is
not a finding — three controls (a 28dp clear button and two 32dp icon buttons)
were.

**Text size.** Anything below 11sp is a warning: a 9sp caption is unreadable for
a large share of the people this app is for. Fifteen were, and are now at the
floor.

**Layout.** The manifest must declare RTL support and must not lock an
orientation (which is also what breaks tablet and foldable layouts), and text
must be sized in `sp` so it follows the system font scale.

## What is not checked, and where the gaps are recorded

An audit that claims more than it ran is worse than no audit, so these are open
statements rather than implied coverage:

* **TalkBack and screen-reader order.** The check counts icons that are silent
  to a screen reader and reports them as notes; it cannot know whether silence
  is correct because a label sits next to the icon, or wrong because it does
  not. Six files have icons where every one of them is silent.
* **Real contrast under both themes.** `tools/check_contrast.py` covers the
  token pairs; a rendered gradient, a glass pane over a photo, and a disabled
  state are not token pairs.
* **Font scale at 1.3 and 2.0.** The check enforces `sp` and a floor; it cannot
  see a layout that clips when the text grows.
* **RTL mirroring of the layout itself.** The manifest declares support; whether
  an icon that means "back" points the right way in Arabic, and whether the
  charts mirror, is a device claim.
* **Orientation and window-size behaviour.** The manifest does not lock an
  orientation; whether the tablet and foldable layouts actually rearrange is
  what the adaptive-layout work is for.
* **WCAG conformance.** Nothing here claims a level.

The statically checkable half is now a gate. The half that needs a device is
listed above so that it can be run deliberately rather than assumed.
