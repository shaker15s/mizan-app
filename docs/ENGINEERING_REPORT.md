# Engineering report

Date: 2026-09-23. Branch work on the prototype at `3c6e175`.

## Verdict

The prototype’s dishonest path is gone: the client no longer pretends to be Odoo, no longer stores ERP credentials, no longer uses a destructive migration, and no longer presents a local hash as a public proof. A production-shaped boundary is in the code. A production ERP integration is not, because the service is not in this repository and this environment could not compile or run the app.

This is not “done” in the sense of a verified release. It is a rebuilt client with documented limits.

## What changed

- Five modules: `domain`, `integration`, `data`, `design`, `app`.
- Package `app.mizan`. Application id `app.mizan`, with demo and staging suffixes.
- Flavors. Demo simulates. Staging and production refuse writes without an HTTPS service URL.
- Explicit `AppGraph`. No Hilt, because AGP 9.1.1 and current Hilt fail together.
- Policy, SoD, idempotency, state machine, recovery, and re-authentication are pure JVM and unit-tested in source. Those tests were not run here.
- Room v2 migration preserves legacy rows, uses `XXX` for unknown currency, and scopes queries by tenant.
- Screens: onboarding, sign-in, home, agent, operations, reconciliation, evidence, rules, account, security, connection, search. English and Arabic resources. Light, dark, and system theme. Reduced motion.
- `com.example` and the prototype tests were deleted.

## What was not verified

- No JDK in this environment. `./gradlew` does not exist. Assemble, unit tests, lint, and instrumented tests were not run. Do not treat this report as a green build.
- No device. No screenshot. No startup measurement. No frame-time number. Account can show a local elapsed timestamp later; that is not a published metric.
- `MIGRATION_1_2` was not executed against a version-1 database.
- The Wakeel HTTP contract (`POST /v1/sessions`, `POST /v1/executions`) is what the client sends. No server implements it here. A 200 body without the expected fields is refused, not treated as success.

## Honesty constraints that the code follows

- Simulation results use `VerificationKind.SIMULATED_READ_BACK` after a local read-back comparison. They are not labeled ERP verification.
- HTTP 200 `accepted` is `AcceptedUnverified`.
- HTTP 5xx and I/O errors after a send are uncertain and open reconciliation. They are not retried.
- A local chain match is `CHAIN_INTACT_LOCAL`.
- Unknown stored tools become `ToolName.UNKNOWN`, not a fake sales summary.
- Production L4 is not completed by a role dropdown.

## Continuation, 2026-09-24

Still not compiled. This pass closed behavioral gaps, not a build:

- Production can prepare a write and send it to the service. Unknown ERP support is no longer treated as “this tool does not exist.” The service still decides.
- A second send of the same key is blocked locally before HTTP if the first is in flight, uncertain, or only human-resolved.
- Linking a reconciliation candidate records a person and a note. The phase is `LINKED_UNVERIFIED`. It is not a read-back. Closing without a record is `CLOSED_UNVERIFIED`, not failed and not checked.
- A session with a known expiry is cleared when that time passes. A missing expiry is not invented.
- Network status updates from `ConnectivityManager`. Refresh on the connection screen rechecks only that. Backend and ERP stay unknown.
- Arabic draft wording stops the customer name at the amount instead of swallowing the rest of the sentence.

## Remaining work, in order

1. Add the Gradle wrapper on a machine that can fetch it, then compile demo and production.
2. Run the JVM tests and a Room migration test against a captured version-1 file.
3. Implement the Wakeel service that this client already refuses to impersonate.
4. Only then point `Wakeel_API_BASE_URL` at it and prove a read-back on a non-production ERP.

## Continuation, 2026-09-25

This pass added the missing pieces from the list above. It still did not
compile anything: this environment has no JDK, no Android SDK, and no access
to Maven Central or the Gradle distribution service, so dependency resolution
is impossible. Everything below is reasoned and reviewed, not measured.

- **The Gradle wrapper exists now.** `gradlew`, `gradlew.bat`, and
  `gradle/wrapper/gradle-wrapper.jar` were taken from the official Gradle
  source tag `v9.3.1`, matching `gradle-wrapper.properties`. The jar was
  verified to contain `org/gradle/wrapper/GradleWrapperMain.class`.
- **The service exists now** (`:service`, JVM only, zero new dependencies).
  See `docs/SERVICE.md`. It decides, writes to an in-memory ERP adapter, and
  reads back. Its tests start a real listener; they have not been run here.
- **A compile-breaking defect was fixed.** `Money.majorUnitsFormatted()` was
  called by `MizanAiIntegrationClient` and `MizanAiIntegrationTest` but was
  never declared. That single missing function meant `:integration` and
  `:app` could not compile at all. It is now declared and covered.
- **Flavors are real.** `demo`, `staging`, and `production` now carry their own
  `DEMO_MODE`, `Wakeel_ENV`, and `API_BASE_URL`, and the simulator lives in
  `src/demo`. Before this, `build.gradle.kts` had a single variant with
  `DEMO_MODE = true` hard-coded, so the documented staging and production
  channels did not exist as builds.
- **Canonical JSON now escapes control characters**, so the output is JSON and
  the hash is reproducible by any conforming parser.
- **Negative and scientific-notation amounts are refused**, and policy answers
  a negative amount with `POL-NEGATIVE-AMOUNT` instead of letting it fall
  through the threshold ladder as a small value.
- **`tools/repo_check.py`** is a static check that needs no toolchain. It
  re-checks the invariants the documentation claims, verifies that every Room
  index declared in `Entities.kt` is created by `MIGRATION_1_2`, catches
  credential literals, and writes `docs/HEALTH.md`. It was run here against
  this tree: 88/100, no errors, four warnings, all of them the same finding —
  four UI files are longer than 800 lines (`HistoricalErpActionLogsList`,
  `Agent`, `Account`, `Components`). Errors fail the check; warnings do not.
  That number is a static score, not a build result.

### Still not verified

- Nothing was compiled, assembled, linted, or executed.
- The `:service` tests were never run, so the HTTP tests are unverified.
- The Android instrumented tests still do not exist, and the Room migration
  still has not been executed against a captured version-1 file.
- No APK, no device, no screenshot, no startup number.

### Next, in order

1. On a machine with a JDK and the Android SDK: `./gradlew :domain:test
   :integration:test :service:test`, then `./gradlew
   :app:assembleDemoDebug :app:assembleProductionRelease`.
2. Run the service behind TLS and point a staging build at it, then prove one
   read-back end to end on a non-production ERP.
3. Replace the in-memory ERP adapter with the real connector, behind the same
   interface, without changing the contract.
