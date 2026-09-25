# Changelog

All notable changes. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
This project is not publicly released yet, so versions here are build
identifiers, not promises.

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

### Unverified

Nothing in this entry was rendered on a device or compiled. No screenshots,
no frame timings, no TalkBack pass. The palettes and the icon are
mathematically derived and reviewable, not measured.

## [2.1.0] — 2026-09-25

### Added

- `:service`, the reference MIZAN authority service: a dependency-free JVM
  server (`POST /v1/sessions`, `POST /v1/executions`, `GET /v1/health`,
  `GET /v1/audit`, `GET /v1/erp`) with an in-memory ERP adapter, a
  service-side audit chain, PBKDF2 password verification, token hashing,
  login throttling, and an idempotency ledger. It is a server, not part of
  the Android application.
- The Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`). The tree
  could not be built before this; it still has not been *compiled* here
  (see Unverified below).
- Real product flavors: `demo`, `staging`, `production`, each with its own
  `DEMO_MODE`, `MIZAN_ENV`, and `API_BASE_URL`. The simulator now lives in
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
