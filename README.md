# Wakeel (وكيل)

Android client for a governed agentic ERP, plus the reference service it talks
to. Wakeel is a personal assistant for systems and ERP: a person says what they
want in Arabic or in English, the app turns it into a proposal, shows the rule
that governs it, and waits for the authority. The phone prepares a request, it
does not authorize an ERP write by itself.

- **Demo** (`app.mizan.demo`) is a labeled simulation. Record ids start with
  `SIM-`. Nothing in that flavor is an ERP record.
- **Staging and production** call a Wakeel service over HTTPS. If that URL is
  missing, writes are refused. The app does not talk to Odoo.
- **`:service`** is the reference authority: a dependency-free JVM server that
  decides, writes to an in-memory ERP adapter, and reads back.

The audit of the previous prototype is `docs/ARCHITECTURE_AUDIT.md`. What this
tree actually does is `docs/ENGINEERING_REPORT.md`. The HTTP contract is
`docs/SERVICE.md`. The static health report is `docs/HEALTH.md`. The twenty
things a company does with the app -- each one asserted by a test -- are
`docs/USE_CASES.md`.

## Layout

```text
:domain        JVM. Models, policy, risk, state machine, idempotency, interpreter, audit hash.
:integration   JVM. Wakeel HTTP client, redaction, Odoo JSON-2 request builder, legacy XML-RPC parser.
:data          Room 2, tenant-scoped stores, encrypted session token.
:design        Tokens and components. No business rules.
:app           Shell, screens, flavors, composition root.
:service       JVM server. The reference authority. Not part of the app.
tools/         Dependency-free static checks.
docs/          Architecture, threat model, testing, service contract, health.
```

## Build

A JDK 17 and the Android SDK are required. The Gradle wrapper is committed, so
the only thing you need is the toolchain.

```bash
./gradlew :domain:test :integration:test :service:test   # JVM modules
./gradlew :app:assembleDemoDebug                         # simulation build
./gradlew :app:assembleStagingDebug                      # service build, needs a URL
./gradlew :app:assembleProductionRelease                 # signed only if the keystore env is set
python3 tools/repo_check.py                              # static check, no toolchain needed
```

`Wakeel_API_BASE_URL` supplies the service URL for staging and production at
build time. It must be an `https://` URL; the client refuses anything else.

## Run the reference service

```bash
./gradlew :service:run --args="--port 8080"
```

It answers plain HTTP, and the client only writes over HTTPS, so put a TLS
terminator in front of it before pointing a device at it. `docs/SERVICE.md`
has the contract, the curl examples, and the labeled demo accounts.

## Invariants

These are not style preferences. `tools/repo_check.py` fails the build when one
breaks, and the CI runs it on every push:

- no Odoo call from the client, and no ERP credential on the device;
- no destructive Room migration, and every index declared in code is created
  by the migration;
- the simulator exists only in the demo flavor;
- a write is `verified` only after a separate read-back; an uncertain write
  opens reconciliation and is never retried;
- an unknown `status` from the service is a refusal, never a success;
- the app never depends on the server module.

## Status

The three JVM modules are compiled and tested here, with a real Kotlin compiler
and a real JVM: **341 tests, all passing** (`python3 tools/jvm_check.py`). They
cover the policy engine, the interpreter, the audit chain, the AI client, the
HTTP contract, and the reference service end to end -- including the governed
pipeline: the execution journal and its state machine, durable stores that
survive a torn write and a restart, a retry outbox that the service drains
itself, device-bound proofs over real signatures, signed receipts,
reconciliation, the PostgreSQL record-log deployment, and the ERP boundary
where a lost answer must never become a retry.

`python3 tools/bootstrap_toolchain.py` provisions that JDK and Kotlin compiler
on a machine that has neither, so the check above runs anywhere. `tools/syntax_check.py`
parses every Kotlin file, and `tools/check_contrast.py` re-derives the palettes
and checks WCAG AA.

The Android app has not been assembled: this environment has no Android SDK, so
`:app:assemble*`, lint and instrumentation are unrun. The first green check from
a real toolchain is still the one to trust for the UI. `docs/USE_CASES.md`
states what the tests prove and what they cannot.

`docs/ENGINEERING_REPORT.md` keeps the exact list of what is verified and what
is not. `docs/HEALTH.md` is a static score, not a build result.
