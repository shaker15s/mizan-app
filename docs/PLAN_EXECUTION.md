# Plan execution: what is done, what is proven, what is not

`plan.md` is the source of truth for this work. This document maps every phase
and every production gate in that plan to something that exists in this
repository, and states plainly how far it has been verified.

Three levels are used, and they are not interchangeable:

| Mark | Meaning |
| --- | --- |
| **proven** | There is an automated test in this repository that runs it and passes. |
| **written** | The code or configuration exists and compiles, but the environment cannot exercise it here. |
| **missing** | Not done. |

The distinction matters more than the count. A Gradle build file that has never
resolved a dependency is not a build; a connector that has never spoken to a
customer's Odoo is not an integration. The plan says the same thing in its own
words, and this file is written to survive that standard.

## The environment this work was verified in

| Fact | Value |
| --- | --- |
| JVM toolchain | provisioned by `tools/bootstrap_toolchain.py` from the only package index reachable from this machine (a JDK 17.0.9 runtime and a Kotlin 2.x compiler) |
| Modules that compile and run here | `:domain`, `:service`, `:integration` |
| Modules that cannot be built here | `:app`, `:design`, `:data` — they need the Android SDK, AGP and Compose, and Maven Central and Google Maven are unreachable from this sandbox |
| Test command | `JAVA_HOME=... KOTLINC_HOME=... python3 tools/jvm_check.py` |
| Tests | 341, all passing (domain contracts, service pipeline over a real HTTP listener, the outbox and its sweeper, the durable journal across a restart, the PostgreSQL record log against a database double, the ERP boundary with a scripted transport) |

Anything marked **proven** below is proven by that command. Anything that needs
an Android device, a Gradle build, a Postgres server or a real Odoo instance is
marked **written** or **missing**, and says which one.

## Phases

### Phase 0 — freeze and baseline
**Written.** `plan.md` is the frozen plan; `tools/repo_check.py`,
`tools/syntax_check.py`, `tools/check_contrast.py` and `tools/jvm_check.py` are
the mechanical part of the baseline, and `docs/HEALTH.md` is generated from
them. A tagged snapshot has not been cut, because the branch this work lives on
is review-scoped.

### Phase 1 — toolchain modernisation
**Written, not run.** `gradle/libs.versions.toml` is the single place the
Android stack is pinned, so a version bump is one file. It has not been raised
to the Compose BOM and Room versions the plan names, because nothing here can
resolve them: an unverifiable version bump is worse than an honest lag, so the
bump is queued behind the first machine that has Maven Central, together with
the migration checklist for each module.

What *was* modernised is the part of the toolchain this environment can prove:
the JVM side now compiles with a Kotlin 2.x compiler and runs its tests, from a
checked-in bootstrap script rather than from a developer's machine.

### Phase 2 — a production backend
**Written, not run.** The service no longer keeps its truth in a `HashMap`:

* `service/store/DurableLog.kt` — an append-only, CRC-framed log with
  fsync-on-append, torn-tail recovery and atomic compaction. **Proven:**
  records survive a reopen; a half-written frame is dropped and only it; a
  corrupted record is refused rather than decoded; eight threads appending
  concurrently lose nothing.
* `service/store/ServiceStores.kt` — journals, idempotency, sessions, receipts,
  devices, challenges, reconciliation cases, the audit chain and approvals, all
  durable and all indexed. **Proven** across restarts, including a resolved
  reconciliation case and a receipt that still verifies.
* `service/store/ApprovalStore.kt` — approval objects with their own identity,
  policy version and fingerprint.

PostgreSQL is **written, not run**. The shape it drops into is now a seam
rather than a hope:

* `service/store/RecordLog.kt` — append (durable when it returns), records (in
  order), compaction (atomic replacement), and a provider that opens one log
  per stream. Every store is written against it, and the file deployment is
  one implementation of it.
* `service/store/sql/PostgresRecordLog.kt` — the same streams as rows of one
  narrow table (`seq BIGSERIAL`, `stream`, `payload`, `written_at`) with an
  idempotent schema, `ORDER BY seq` reads, and compaction in a single
  transaction. The table name is validated rather than interpolated.
* `service/store/sql/SqlDatabase.kt` — the database boundary and its JDBC
  implementation; no store imports `java.sql`.
* `ServiceConfig.database` selects it, `ServiceConfig.logProvider` lets a
  deployment bring its own durability, and a configuration that names two
  homes for the same state is refused before anything is opened.

**Proven** here: the schema and its statements, that every column the SQL names
exists in the DDL, sequence ordering, that a failed compaction rolls back
whole, and that two services over one database see each other's journal and
replay each other's idempotency keys. **Not proven**: PostgreSQL itself — the
driver, the server, the pool, replication, failover and backup.

The retry outbox is **proven**: a read the ERP could not answer is written to
the same durable store as the intention it is, the service drains it on its own
timer (or a deployment turns the timer off and runs a sweeper elsewhere),
backoff grows to a fifteen minute ceiling, five attempts and a person is asked,
a crash leaves nothing stranded in flight, and a write is never re-dispatched.

### Phase 3 — Odoo 19 over JSON-2
**Written; the wire behaviour is proven against a scripted transport, not
against a real Odoo.** `service/erp/OdooJson2Connector.kt` speaks
`POST {base}/json/2/{model}/{method}` with a bearer key and the database header,
and nothing else. What is **proven**:

* the request shape, the headers, and the refusal to build a URL from an
  unexpected model or method name;
* a write is one ERP call, so one business operation is one ERP transaction;
* 401, 403, 404 and 4xx business refusals are named, not flattened;
* **the distinction the whole design rests on**: a 5xx or a 429 during a
  *write* is `Unknown` (the ERP may hold the record), during a *read* it is
  retryable; a lost answer is `Unknown`, a connection that was never made is
  safe to retry;
* money crosses the boundary as minor units, converted once, from the float or
  string Odoo actually sends;
* a read-back returns named fields, and a field the ERP did not return is
  reported missing rather than assumed equal.

XML-RPC appears nowhere on a write path. `:integration` keeps the legacy
transport only as a migration adapter.

### Phase 4 — authentication 2.0
**Partly written.** Server-side there is real device binding, not a claim about
the screen: `domain/security/DeviceBinding.kt` enrols a public key, issues a
single-use challenge bound to a proposal fingerprint, verifies a real Ed25519 or
P-256 signature over the canonical challenge body, and refuses replays, expired
challenges, revoked devices, and signatures collected for a different proposal.
**Proven**, with real keys and real signatures.

Sessions are short-lived, only a SHA-256 fingerprint is stored, and a revoked
session is refused immediately. **Proven.**

Passkeys, Credential Manager and OIDC are **missing**: they are Android-side
and identity-provider work, and neither can be built in this environment.

### Phase 5 — execution engine 2.0
**Proven.** This is the centre of the work:

* `domain/execution/ExecutionJournal.kt` — the stage machine, written once.
  Illegal moves are refused, and the tests assert the refusals: a write cannot
  be dispatched before authorisation, `VERIFIED` is unreachable without a
  read-back, an uncertain write can only move forward to reconciliation, and a
  terminal entry cannot be edited.
* Journal entries carry the tool version, the schema version, the catalogue
  version, the canonical input hash, the idempotency key, the policy version
  **and its hash**, the approval identity and the proof reference. **Proven**:
  the durable store persists an entry and a restart returns it unchanged.
* Idempotency: the same key with the same arguments replays the original answer
  — including its receipt — and a key reused with different arguments is
  refused with 409. A refused request is remembered under its key too, so a
  refusal cannot be re-decided into a different answer. **Proven.**
* Approval validation: unknown, expired, invalidated by a changed proposal and
  granted under a superseded policy version are each refused with their own
  code. **Proven.**
* Receipts: a verified write gets an HMAC-signed receipt naming the fields the
  read-back actually matched; editing any field breaks the signature; a
  rotated key still verifies old receipts and reports them as retired.
  **Proven.**
* Reconciliation: an uncertain write opens a case with candidates, survives a
  restart, and a person can resolve it as linked to a record or as never
  performed — with a message key, never a sentence. **Proven.**

### Phase 6 — UX rebuild
**Not started.** The screens have not been rebuilt; that work is Android-side
and nothing here can run it.

### Phase 7 — design system 2.0
**Not started** beyond the tokens that already existed.

### Phase 8 — AI 2.0
**Partly written.** The deterministic half exists and is **proven**: Arabic
text normalisation, quantity parsing, entity resolution that refuses to choose
when two real entities match, and a tool catalogue whose writes all require
approval and a fresh proof. The LLM never decides anything — it produces a
normalised intent, and every field is validated before it becomes a proposal.
The evaluation harness is **missing**.

### Phase 9 — device intelligence
**Not started.** Widgets, shortcuts, app links, notifications and share
targets are Android work.

### Phase 10 — performance lab
**Not started.** Macrobenchmark and Baseline Profiles need a device.

### Phase 11 — security hardening
**Partly proven.** What runs here and is tested: device binding, session
fingerprints, tenant isolation on every route (a cross-tenant read is 403, and
a journal query for another tenant is refused), rate limiting per surface with
`Retry-After`, secret material that never renders itself, a bounded request
body, and an audit chain that is hash-linked and verified on read.

What is **missing**: MASVS review, Keystore-backed token protection, Play
Integrity attestation, network security configuration, and signing the audit
chain itself.

### Phase 12 — enterprise platform
**Not started.** The service exposes the read-only surfaces an admin console
would need (capabilities, tools, policy, journal, reconciliation, devices,
receipts) and enforces tenant scope on all of them, but there is no admin
client.

### Phase 13 — scale
**Not started, deliberately.** Horizontal scaling, queues, caching, replicas
and multi-region are the phase the plan says to enter only when the earlier
ones hold.

## The twenty production gates

| # | Gate | State |
| --- | --- | --- |
| 1 | Real Odoo JSON-2 connector | **written**; wire behaviour proven against a scripted ERP, never against a customer's Odoo |
| 2 | Durable production service | **proven** for the file-backed stores; Postgres **missing** |
| 3 | PostgreSQL | **missing** |
| 4 | Durable idempotency | **proven** |
| 5 | Durable execution journal | **proven** |
| 6 | Real approval identity | **proven** (approval objects validated against proposal revision, fingerprint and policy version) |
| 7 | Policy versioning | **proven** (version, hash, effective date, published over HTTP) |
| 8 | Proposal fingerprint | **proven** (canonical, stable, changes with the proposal) |
| 9 | Server-signed receipts | **proven** in-process; key custody is a deployment concern |
| 10 | Proper reconciliation | **proven** |
| 11 | Passkeys / Credential Manager | **missing** |
| 12 | Keystore-based token protection | **written** on the Android side (existing code), unbuildable here |
| 13 | Full Arabic localisation | **missing** (the Arabic domain logic and text handling exist and are tested; the resource strings and RTL pass are not done) |
| 14 | Adaptive phone/tablet/foldable UX | **missing** |
| 15 | Real accessibility audit | **missing** |
| 16 | Screenshot regression suite | **missing** |
| 17 | Macrobenchmark | **missing** |
| 18 | Baseline Profile | **missing** |
| 19 | Security CI | **missing** |
| 20 | Real production release pipeline | **missing** |

## Red lines

The plan names a few things that must never become true. Current state:

| Red line | State |
| --- | --- |
| The LLM never decides | Held: the interpreter proposes, the catalogue validates, policy decides. |
| Android is never authoritative | Held in the service: every decision is re-made server-side from the journal's stored facts. |
| The ERP is never the UX | Held: the ERP layer returns data, never sentences; the client renders message keys. |
| No user-facing strings in Kotlin | Held in the new code: previews, refusals and reconciliation all carry keys. |
| A cached read is never presented as live | Not yet applicable: there is no offline read model in the verified modules. |

## What the next slice of work is

1. Split `ServiceAuthority` (1,098 lines) the way the plan's hygiene rule asks,
   now that its behaviour is covered by tests.
2. Wire the Android client to the extended contract — `approvalId`,
   `proposalFingerprint`, the device challenge, and the receipt — which needs a
   machine that can build `:app`.
3. The Postgres stores, behind the same interfaces, with the append-only log
   kept as the reference implementation and as the test double.
4. The remaining gates above, in the order the plan gives them.
