# Testing

Tests exist, and the three JVM modules now run them:

```bash
python3 tools/bootstrap_toolchain.py   # provisions a JDK and a Kotlin compiler
JAVA_HOME=<toolchain>/jdk/jdk4py/java-runtime KOTLINC_HOME=<toolchain>/kotlinc \
  python3 tools/jvm_check.py           # 414 tests, all passing
```

The Android modules still have not been assembled: no Android SDK and no
dependency resolver are reachable from the environment that produced this tree.
Every claim below therefore separates what runs from what is merely written.

## What is written

| Test | What it locks |
| --- | --- |
| `domain/.../PolicyAndSoDTest.kt` | Reads, auditor denial, currency ladder, destructive manager rule, dual approval, credit bump that does not lower L4 |
| `domain/.../ExecutionInvariantsTest.kt` | Happy path, timeout-after-send is ambiguous, verified is not re-executed, idempotency block and replay, tenant-scoped keys, recovery of unknown dispatch, proof freshness |
| `domain/.../InterpreterAndAuditTest.kt` | Missing fields are questions, injection is rejected, chain detects a changed copy |
| `integration/.../IntegrationTest.kt` | Redaction, writes are not retried, JSON-2 URL rejects cleartext, XML-RPC escapes and parses a fault, HTTP 200 `accepted` is not verification |
| `integration/.../RedactorTest.kt` | Bearer, Basic, named secrets and bare JWTs are masked; ordinary text is untouched; redaction is idempotent |
| `integration/.../MizanApiContractTest.kt` | The request the device builds: POST path, session headers, canonical argument names, no credential in the body, cleartext and missing token refused before a request is built |
| `service/.../MizanServiceHttpTest.kt` | Real listener on an ephemeral port: sign-in and throttling, verified write after read-back, idempotent replay, key reuse is a 409, separation of duties, auditor refusal, tenant isolation, the ambiguous path, invoice and payment chain, cancellation blocks invoicing, audit chain, health |
| `service/.../JsonTest.kt` | Round trip, escaping, nested arrays, and that eight malformed bodies are rejected instead of guessed at |
| `service/.../ServiceUnitTest.kt` | Password hashing and salting, token hashing, session expiry without extension, audit chain linkage, tenant scoping of the ledger and the ERP adapter, read-back semantics |
| `service/.../ContractParityTest.kt` | The service parses exactly what the phone canonicalises: every tool's argument names, the byte-for-byte idempotency material, and the wire names |
| `domain/.../MoneyAndCanonicalTest.kt` | Money parsing rejects negative and scientific-notation amounts, formatting is locale independent, canonical JSON sorts keys and escapes control characters, idempotency keys are deterministic and tenant scoped |
| `domain/.../ExecutionJournalTest.kt` | The stage machine's *refusals*: no dispatch before authorisation, `VERIFIED` unreachable without a read-back, an uncertain write may only move towards reconciliation, a terminal entry cannot be edited, and a failed read is a failure rather than an ambiguity |
| `domain/.../ReceiptsAndDevicesTest.kt` | A receipt stops verifying the moment one field changes; a rotated key still verifies old receipts and says they are old; a device signature is bound to one proposal and is refused the second time; enrolled keys must parse; Ed25519 and P-256 both work |
| `service/.../DurableStoresTest.kt` | A half-written frame is dropped and only it; a corrupted record is not decoded; the journal, idempotency index, receipts, devices, approvals and reconciliation cases all survive a restart, including a receipt that still verifies |
| `service/.../OdooJson2ConnectorTest.kt` | The JSON-2 request shape; 401/403/404/4xx named; **a 5xx or 429 during a write is `Unknown`, during a read it is retryable**; a lost answer is `Unknown` and an unmade connection is retryable; money converted once; read-back fields and their absences |
| `service/.../GovernedExecutionTest.kt` | The whole governed pipeline over a real listener: device proof required and single-use, approval identity validated against proposal revision, fingerprint and policy version, signed receipts that verify, reconciliation opened, listed and resolved by a person, idempotent replay that returns the same receipt, rate limiting with `Retry-After`, tenant isolation on every route |
| `service/.../OutboxTest.kt` | The retry queue: an entry survives a restart, backoff grows and is capped, five attempts then a person, an in-flight entry is recovered after a crash, the worker refuses to re-dispatch a write, an unknown actor and a final refusal are dead-lettered, the service's own sweeper drains the queue, and `/v1/health` reports it |
| `service/.../JournalDurabilityTest.kt` | A second service over the same directory: a read's record and a verified write's record (with its receipt) are still there, the same key replays the same answer without a second ERP call, and a definite failure is re-evaluated rather than blocked |
| `service/.../PostgresStoreTest.kt` | The SQL deployment: the schema it creates, every column the statements name exists in the DDL, reads come back in sequence order, a compaction is one transaction that rolls back whole on failure, and a whole service runs on the SQL path while a second process reads its journal and replays its key |
| `domain/.../RecoveryAttentionAndFreshnessTest.kt` | An expired lease never resends, unknown dispatch is treated as sent, a live lease and a terminal execution are left alone, attention ordering and its failure cap, proof freshness windows and expiry, risk classification |

## What is not covered

- Anything Android: no instrumented test, no screenshot golden, no lint run.
- PostgreSQL itself. The record log, the schema, the statements and the
  transaction boundaries are written and tested against a database double; the
  driver, the server, the pool, failover and backup are not. There is no
  PostgreSQL in this environment to run them against.
- A real Odoo. The connector is exercised against a scripted transport that
  answers with the bytes Odoo 19 sends; that is not the same as an integration.
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
