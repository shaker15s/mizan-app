# Contributing

## Before you start

You need a JDK 17 and the Android SDK. The Gradle wrapper is committed, so use
`./gradlew` and never a locally installed Gradle: the build pins Gradle 9.3.1
and AGP 9.1.1.

```bash
./gradlew :domain:test :integration:test :service:test
./gradlew :app:assembleDemoDebug
python3 tools/repo_check.py
```

## Rules that are not negotiable

1. **Do not let the device authorize a write.** Policy on the phone is a
   preview. Only a service may cause an ERP side effect.
2. **Do not invent evidence.** A write that was not read back is `accepted`.
   An uncertain write opens reconciliation. Neither is `verified`.
3. **Never drop history.** No `fallbackToDestructiveMigration()`. Schema
   changes get a migration and a schema export.
4. **Keep the simulator in the demo flavor.** Nothing simulated may reach
   `src/main`, `src/staging`, or `src/production`. New simulation entry points
   go behind `SimulationDirectory`.
5. **Tenant scope everything.** A store method a screen can reach takes a
   `TenantId`. There is no unscoped list.
6. **Say what you did not verify.** A claim in a doc must be something that
   was measured or run. Otherwise it goes under "not verified".

## Style

- Kotlin official style, four spaces, 120 columns (`.editorconfig`).
- Prefer an explicit type on a public function.
- No wildcard imports.
- Comments explain *why*, and every class that makes a safety claim states the
  boundary it enforces.
- Money is `Money`, never a `Double`. Amounts cross the wire as minor units.

## Tests

- Pure logic belongs in `:domain` and is tested there, without Android.
- Anything that crosses HTTP is tested against a real listener on an ephemeral
  port, not against a mocked call.
- A test that asserts a success must also assert what was *not* claimed: for a
  write, that is the read-back.

## Pull requests

One concern per pull request. Describe what you ran. If you could not run
something, say so in the description and in `docs/ENGINEERING_REPORT.md`.
CI must be green: static checks, JVM tests, Android assemble and lint.
