# Changelog

All notable changes. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
This project is not publicly released yet, so versions here are build
identifiers, not promises.

## [Unreleased] — 2026-09-26

### Identity

- The app is **Wakeel** (وكيل), a personal assistant for systems and ERP for
  companies, in Arabic and in English. Every user-visible "MIZAN" is gone: the
  launcher name, the sign-in copy, the audit PDF, the biometric prompt, the
  health row, the prompt sent to a model, and the `User-Agent`.
- New mark: the و of وكيل -- a loop with a tail -- wired to two nodes, inside
  the seal. The agent carries the accent, the systems carry the only cool
  colour in the mark. `tools/render_brand.py` grew two primitives (a stroked
  arc, and a capsule defined by its two ends) so the raster, the vector and the
  in-app canvas cannot drift apart. `MizanMark` / `MizanHeroEmblem` are now
  `WakeelMark` / `WakeelEmblem`, and `MizanRobotScale` is `WakeelMascot`.
- `WAKEEL_API_BASE_URL` and `WAKEEL_ENV` replace the build variables of the old
  name. Package, module and Gradle project names keep the `mizan` namespace:
  renaming a shipped package is a migration, not a rebrand.

### Verified, not asserted

- **22 use cases** -- the things a company actually does, from signing in to
  cancelling an order to reading the audit trail -- now run against a real
  listener on a real port. `service/src/test/kotlin/.../UseCaseMatrixTest.kt`.
- **The assistant's understanding** is asserted in both languages:
  `domain/src/test/kotlin/.../AgentUnderstandingTest.kt`, 22 cases, including
  Arabic-Indic digits, polite English, and two injection attempts.
- `:integration` compiles and its tests run for the first time. Its OkHttp
  dependency cannot be downloaded here, so `tools/jvm_stubs/` stands in the
  slice of it the module uses. Nothing in the stub performs I/O.
- Tests: 163, passing: 163 (was 74).

### Fixed

- Required arguments were validated *after* the approval ladder, so an order
  with no amount answered `SOD_ROLE_INSUFFICIENT` instead of `MISSING_AMOUNT`.
- An invoice was judged by its own request body, which carries no amount, so
  every invoice was amountless and therefore low risk however large the order
  behind it was. The authority now reads the order's amount from the ERP.
- `سداد فاتورة INV-2026-9021 بمبلغ 850 دولار` paid 2,026: both money
  extractors took the first number in the sentence, which was the year inside
  the invoice id.
- The "lean" prompt was 914 characters and ~240 estimated tokens against its
  own 750 / 160 budget. It now says the same thing in 483 characters.
- The client's thousands pattern matched zero comma groups, so it read "450"
  out of "4500" and left a stray "0" to be read as the amount. The domain
  pattern was already right; the client's now is too, and both are tested.
- A vague request produced a clarification that asked nothing, so the app
  rendered an empty list of questions.
- "Is SKU-DESK-01 available?" became a draft order: it names no tool keyword.
- `سجل سداد ... على الفاتورة` was read as an invoice because invoice words were
  tried before payment words.
- The items marker only matched أصناف with the hamza.
- `for Acme Corp 1,250.50 USD` named the customer "Acme Corp 1".
- A summary ignored the period: "this month" and "today" both answered
  `current`.
- Two existing service tests encoded the amountless-invoice behaviour and were
  updated; one contract test expected a quoted number the writer stopped
  emitting.

### Design

- Every tappable surface now answers the finger. `mizanPressable` existed and
  was called nowhere; the shared list row, the section action, the suggestion
  rows, the sign-out row, and every screen-level tap go through `mizanTap`
  (spring scale, ripple, one haptic tick).
- `mizanLiveBorder`: a highlight that walks once around the border of the one
  thing on the screen that is waiting on a person. Used by the attention card
  on Home, and only there.
- A glass pane marked interactive now presses as well as refracting light.
- The agent's newest line reveals instead of appearing, and while the request
  is being read, three dots breathe -- with a string resource, so it is
  translated.
- The agent's empty state shows the mark instead of the mascot, and its copy
  comes from the string resources in both languages.

### Docs

- `docs/USE_CASES.md`: the twenty scenarios, each mapped to the test that
  asserts it, and an explicit list of what is not verified.

### The assistant's front door

- `AiClientUseCasesTest` exercises `MizanAiIntegrationClient.parseErpAction` --
  what the app calls -- with thirteen sentences: Egyptian colloquial Arabic the
  rules do not cover, the injection refusal before anything is interpreted, a
  secret in the sentence that must not stop the answer, a tool this ERP cannot
  perform, and the cost the call reports.

## [Unreleased] — 2026-09-25

### Brand

- New identity: a balance at rest inside a seal, replacing the robot-scale
  mark. The geometry lives once in `tools/render_brand.py`, which writes the
  SVG sources, the five mipmap densities, the adaptive icon layers, and the
  in-app vector. The icon is now generated, not hand-edited.
- `MizanMark` draws the same geometry on a Canvas, so it is crisp at any size.
  Its beam settles by 1.4 degrees; that is the only motion in the logo.
- `MizanHeroEmblem` and `MizanWordmark` for headers and empty states.

### Design system

- `MizanColors` gained `accentSecondary` and `accentTertiary`, so gradients are
  tokens. `accentBrush()` and `heroBrush()` are the only two.
- Seven real presets, each with a complete light and dark palette: Cyber,
  Emerald, Royal Indigo, Sovereign Gold, Crimson Ledger, Obsidian (true black),
  and Material You from Android 12. Previously two of the four presets fell
  back to the base palette and two of them did nothing at all.
- Palettes are derived from a `PaletteSeed`, so borders, containers, focus and
  scrim cannot be forgotten.
- Typography now defines display and headline steps with tracking that
  tightens as size grows.

### Motion

- `design/.../motion/Interactions.kt`: `mizanTap`, `mizanPressable`,
  `mizanReveal`, `mizanShimmer`, `mizanPulse`, `mizanGlow`, and named haptics.
  `mizanBounceClick` now delegates to `mizanTap`, so there is one press.
- Buttons are 48 dp, the primary button carries the accent gradient, and the
  row no longer jumps when the loader appears.
- Banners reveal as rounded cards. The loading state shows the shape of what is
  coming. Status dots breathe on warning and danger.
- Screen transitions add a 2% scale and leave faster than they arrive.
- Theme preset tiles are data driven, animate their selection, and the
  Material You tile only appears on Android 12 and later.

### Tooling

- `tools/render_brand.py` (icons and vectors, no external tools) and
  `tools/render_preview.py` (a contact sheet rendered from the real palettes).
- `tools/repo_check.py` now fails on ambiguous imports — two imports with the
  same simple name — which is a compile error that a static check can catch.

### Glass

- `design/.../component/Glass.kt`: one glass system. A pane is four things at
  once -- it refracts the page wash with a render effect on Android 12+, it is
  tinted vertically, it carries a specular sheen that leans toward the finger
  while it is touched, and it has a hairline edge that is brighter at the
  top-left than at the bottom-right. Below Android 12 the blur is dropped and
  the tint is raised.
- Two entry points. `MizanGlassSurface` (and `Dock`, `Overlay`, `TopBar`,
  `Chip`) refract, and are used where there are a few per screen.
  `Modifier.mizanGlassPane` gives the same tint, sheen and edge without the
  blur, because a render effect per card is a dropped frame on a mid-range
  phone.
- The shell owns the wash the panes refract: `ProvideMizanBackdrop`,
  `MizanBackdrop` and `MizanBackdropLights` at the root, and the scaffold is
  transparent so the wash shows through.
- Thirty-seven hand-rolled translucent rectangles across seven screens now call
  one of the two.

### Themes

- Four presets join the seven: Aurora Glass, Arctic Prism, Basalt Neutral and
  Sandstone Amber. Eleven real choices, each with its own light and dark
  palette.
- `tools/check_contrast.py` reads the palettes out of `Tokens.kt`, reproduces
  what `materialize()` derives, composites every translucent role over the
  surface it is drawn on, and computes the WCAG ratio for the 17 pairs the UI
  draws. It found the meta text under 4.5:1 in nine presets; those tertiary
  colours moved along their own hue until every pair cleared AA. Worst pair is
  now 4.32:1, on a decorative accent wash.

### Motion

- Lists stagger in: Home's metric cards and attention rows, the evidence
  receipts, the operations records and the reconciliation cases. `mizanReveal`
  existed and nothing used it.

### Fixed

- `sd_triangle` in `tools/render_brand.py` decided "inside" by requiring three
  cross products to be non-negative, which holds for only one winding order.
  The fulcrum was drawn with the other order, so it was never inside: every
  icon shipped so far had a hollow, hairline fulcrum. The test is now winding
  agnostic.
- `mark_shapes()` and `mark_vector()` listed the same balance twice in two
  notations, so the PNGs and the vector drawables could drift. Both now read
  one `MARK_PARTS` list.
- `:service` could not compile. `ServiceAuthority` imported `RiskInput` from
  `app.mizan.domain.model`; it lives in `app.mizan.domain.risk`.
  `ExecutionMessages` called `JsonValue.Obj.field` without importing the
  extension. `ServiceUnitTest` built a stored password form with an unescaped
  `$`, which Kotlin reads as string interpolation.
- The client canonicalises numbers as strings (`"amountMinor":"250000"`); the
  service only accepted a JSON number, so every write was refused with
  `MISSING_AMOUNT`. The service now reads both shapes and refuses anything
  else.

### Tooling

- `tools/jvm_check.py` compiles `:domain` and `:service`, main and test
  sources, with a real Kotlin compiler and runs the JUnit suites on a real
  JVM. It needs a JDK and kotlinc and nothing else.
- `tools/syntax_check.py` parses every Kotlin file with the real parser and
  classifies what comes back: parse errors fail, unresolved references are
  counted and explained. `--self-test` feeds it a file with a missing brace
  and fails if the checker does not notice, because a checker that cannot
  fail is decoration.
- `tools/check_contrast.py` as described above.
- Android 13 themed icons: `ic_launcher_monochrome.xml` and adaptive icons
  under `mipmap-anydpi-v26` that reference background, foreground and
  monochrome. Before this the adaptive icon existed only as five densities of
  PNG.

### Verified

- `tools/jvm_check.py`: 74 tests, 74 passing, 0 failing, on a real JVM with a
  real Kotlin compiler. Four of the fixes above came out of that run.
- `tools/syntax_check.py`: 104 Kotlin files, 0 parse errors.
- `tools/check_contrast.py`: 10 presets, 360 pairs, all at or above AA.
- `tools/repo_check.py`: 88/100, 0 errors, 4 warnings (four UI files over 800
  lines).
- `tools/render_brand.py --check`: every launcher icon present and correctly
  sized.

### Unverified

No APK was assembled, no Compose preview was rendered, no frame was timed, and
no TalkBack pass was run. This environment has no Android SDK, no Google Maven
(`dl.google.com`), no Maven Central and no Gradle distribution: all four are
blocked here, and the GitHub token available to it may not push files under
`.github/workflows/`, so CI could not be enabled either. `docs/CI.md` carries
the workflow for a machine that can. The glass, the motion and the palettes
are derived and reviewable, not measured.

## [2.1.0] — 2026-09-25

### Added

- `:service`, the reference Wakeel authority service: a dependency-free JVM
  server (`POST /v1/sessions`, `POST /v1/executions`, `GET /v1/health`,
  `GET /v1/audit`, `GET /v1/erp`) with an in-memory ERP adapter, a
  service-side audit chain, PBKDF2 password verification, token hashing,
  login throttling, and an idempotency ledger. It is a server, not part of
  the Android application.
- The Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`). The tree
  could not be built before this; it still has not been *compiled* here
  (see Unverified below).
- Real product flavors: `demo`, `staging`, `production`, each with its own
  `DEMO_MODE`, `Wakeel_ENV`, and `API_BASE_URL`. The simulator now lives in
  `src/demo`, so a staging or production build cannot contain it.
- `SimulationDirectory`: the seam that keeps simulated actors, tenants, and
  ledger rows out of every non-demo build.
- `tools/repo_check.py`, a dependency-free static health check (invariants,
  wrapper, Room index coverage against `MIGRATION_1_2`, brace balance, file
  length, credential literals, module inventory) that writes `docs/HEALTH.md`.
  Errors fail; warnings only lower the score. On this tree: 88/100, four
  warnings, all of them UI files over 800 lines.
- Continuous integration: static checks, JVM tests, Android assemble and lint.
  The workflow lives in `docs/CI.md` because the sandbox token that produced
  this tree may not push files under `.github/workflows/`.
- `docs/SERVICE.md` (the HTTP contract) and `docs/HEALTH.md` (generated).
- Tests: `service` HTTP end-to-end tests, JSON parser tests, service unit
  tests, client/service contract parity, `MizanApiContractTest` (the request
  the device builds, without a network), `RedactorTest`, and domain tests for
  money, canonicalisation, recovery, attention, and proof freshness.

### Fixed

- `Money.majorUnitsFormatted()` was used by `MizanAiIntegrationClient` and by
  `MizanAiIntegrationTest` but never declared, so `:integration` and `:app`
  could not compile. It is now part of `Money`.
- `CanonicalJson` emitted raw control characters, producing strings that are
  not JSON and hashes that cannot be reproduced by another implementation.
  Control characters are now escaped, as are backspace and form feed.
- `Money.parseMajor` accepted negative and scientific-notation amounts; both
  are now rejected, and policy refuses a negative amount with
  `POL-NEGATIVE-AMOUNT` instead of treating it as a small value.
- `Redactor` now also masks `Basic` credentials and bare JWTs.

### Changed

- `versionCode 2` → `3`, `versionName 2.0.0` → `2.1.0`.
- `DemoSeed` became `MizanSimulationDirectory` and moved to the demo flavor;
  its fully qualified domain references were replaced with imports.
- The demo entry button is hidden when no simulation directory exists, so a
  production build cannot show a control that does nothing.

### Unverified

Nothing in this entry was compiled or executed: this environment has no JDK,
no Android SDK, and no dependency resolution. The fixes above are reasoned,
not measured.

## [2.0.0] — 2026-09-23

- Five modules, `app.mizan` package, policy and state machine, tenant-scoped
  Room stores, bilingual UI. See `docs/ENGINEERING_REPORT.md` for what was
  and was not verified at the time.
