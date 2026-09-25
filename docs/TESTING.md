# Testing

Tests exist. The Gradle wrapper now exists, but no JVM, Android SDK, or dependency resolver was available in the environment that produced this tree, so they have still not been executed.

## What is written

| Test | What it locks |
| --- | --- |
| `domain/.../PolicyAndSoDTest.kt` | Reads, auditor denial, currency ladder, destructive manager rule, dual approval, credit bump that does not lower L4 |
| `domain/.../ExecutionInvariantsTest.kt` | Happy path, timeout-after-send is ambiguous, verified is not re-executed, idempotency block and replay, tenant-scoped keys, recovery of unknown dispatch, proof freshness |
| `domain/.../InterpreterAndAuditTest.kt` | Missing fields are questions, injection is rejected, chain detects a changed copy |
| `integration/.../IntegrationTest.kt` | Redaction, writes are not retried, JSON-2 URL rejects cleartext, XML-RPC escapes and parses a fault, HTTP 200 `accepted` is not verification |
| `service/.../MizanServiceHttpTest.kt` | Real listener on an ephemeral port: sign-in and throttling, verified write after read-back, idempotent replay, key reuse is a 409, separation of duties, auditor refusal, tenant isolation, the ambiguous path, invoice and payment chain, cancellation blocks invoicing, audit chain, health |
| `service/.../JsonTest.kt` | Round trip, escaping, nested arrays, and that eight malformed bodies are rejected instead of guessed at |
| `service/.../ServiceUnitTest.kt` | Password hashing and salting, token hashing, session expiry without extension, audit chain linkage, tenant scoping of the ledger and the ERP adapter, read-back semantics |

## What is not covered

- Room migration against a version-1 fixture. The SQL is in `MIGRATION_1_2`. It has not been run.
- Screenshot tests. The old greeting screenshot was removed with `com.example`. No new golden was captured, because nothing was rendered.
- Instrumented UI tests. The old `ExampleInstrumentedTest` was removed. No replacement was run.
- Performance. No baseline profile, no macrobenchmark, no startup number other than a local elapsed timestamp the device can show in Account. That number is not a benchmark.

## How to run, once a toolchain exists

```text
./gradlew :domain:test :integration:test :service:test
./gradlew :app:assembleDemoDebug :app:assembleStagingDebug :app:assembleProductionRelease
./gradlew :app:lintDemoDebug :data:lintDebug :design:lintDebug
python3 tools/repo_check.py        # static, needs no toolchain
```

The service tests bind to `127.0.0.1` on port 0, so they do not need a fixed
port and cannot collide with a running instance.

Do not treat a green local demo as evidence that production talks to an ERP.
