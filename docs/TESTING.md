# Testing

Tests exist. They were not executed in the environment that produced this tree: there is no JDK, no Android SDK, and no Gradle wrapper.

## What is written

| Test | What it locks |
| --- | --- |
| `domain/.../PolicyAndSoDTest.kt` | Reads, auditor denial, currency ladder, destructive manager rule, dual approval, credit bump that does not lower L4 |
| `domain/.../ExecutionInvariantsTest.kt` | Happy path, timeout-after-send is ambiguous, verified is not re-executed, idempotency block and replay, tenant-scoped keys, recovery of unknown dispatch, proof freshness |
| `domain/.../InterpreterAndAuditTest.kt` | Missing fields are questions, injection is rejected, chain detects a changed copy |
| `integration/.../IntegrationTest.kt` | Redaction, writes are not retried, JSON-2 URL rejects cleartext, XML-RPC escapes and parses a fault, HTTP 200 `accepted` is not verification |

## What is not covered

- Room migration against a version-1 fixture. The SQL is in `MIGRATION_1_2`. It has not been run.
- Screenshot tests. The old greeting screenshot was removed with `com.example`. No new golden was captured, because nothing was rendered.
- Instrumented UI tests. The old `ExampleInstrumentedTest` was removed. No replacement was run.
- Performance. No baseline profile, no macrobenchmark, no startup number other than a local elapsed timestamp the device can show in Account. That number is not a benchmark.

## How to run, once a toolchain exists

```text
./gradlew :domain:test :integration:test
./gradlew :app:assembleDemoDebug :app:assembleProductionRelease
```

Do not treat a green local demo as evidence that production talks to an ERP.
