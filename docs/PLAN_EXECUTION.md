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
| Tests | 521, all passing (domain contracts, service pipeline over a real HTTP listener, the outbox and its sweeper, the durable journal across a restart, the PostgreSQL record log against a database double, the ERP boundary with a scripted transport) |

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

XML-RPC appears nowhere on a write path. It is now a **read-only migration
adapter** rather than a sentence in a document: `service/erp/OdooLegacyWire.kt`
encodes and decodes the legacy protocol (hand-written XML-RPC, faults mapped to
the same reason codes), and `service/erp/OdooLegacyConnector.kt` implements the
connector contract against a pre-19 installation for one purpose -- reading a
customer's data while the ERP is on its way to 19.

Its write methods return `NotSupported` with the missing capability, so the
authority hides the action instead of offering one that would fail. That is the
design decision worth naming: XML-RPC forces a multi-step business operation
into several independent calls, each its own transaction, none of them atomic
-- the exact thing the JSON-2 connector removed by asking Odoo to run one
server-side method. Reintroducing that so the app "also works on 17" would put
a half-applied sales order behind an approval that says it happened.

**Proven**: the call shape carries the credential, the model and the
keyword arguments; a fault becomes a reason code and never a value; the reader
understands arrays, structs, many2one pairs, floats-as-strings, entities and
`nil`, and reports anything else as unreadable rather than reading it as
"nothing found"; money is converted to minor units once (2500.50 major is
250050 minor, and a missing amount is `Malformed`, not zero); the uid is
cached, dropped when the ERP reports an expired session, never cached after a
refused login; and every write is refused with the capability that is missing.

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
* Receipts: a verified write gets a signed receipt naming the fields the
  read-back actually matched; editing any field breaks the signature; a
  rotated key still verifies old receipts and reports them as retired.
  The signed body is published field for field, so a device can rebuild it;
  `ReceiptWire.SIGNED_FIELDS` is the contract, a service test fails if the wire
  stops carrying one of those fields, and the client parses the same list.
  **Proven** against a real Ed25519 key over a real listener.
* Receipt verification on the device: a pinned public key (compiled into the
  app, never fetched) verifies the signature; the claims are checked against
  the execution, tenant, fingerprint and record the device asked about; an
  HMAC-signed receipt is reported as `UNVERIFIABLE_SHARED_SECRET` rather than
  shown as proven, because a phone that holds the signing secret can mint any
  receipt it likes. **Proven** in the JVM suite; the Android pin arrives
  through `BuildConfig.RECEIPT_PUBLIC_KEY`.
* Reconciliation: an uncertain write opens a case with candidates, survives a
  restart, and a person can resolve it as linked to a record or as never
  performed — with a message key, never a sentence. **Proven.**

### Phase 6 — UX rebuild
**Not rebuilt, but one screen is now live rather than decorative.**

The plan's Phase 6 is a rebuild of eleven screens; that remains Android-side
work this environment cannot run or see. What changed is the screen the plan
calls out as the most important one: the **approval surface is wired to the
service**.

* `integration/api/ApprovalsBoard.kt` is the join that was missing. It reads
  the queue from `GovernanceApiClient`, and turns what the service said into one
  of four honest states -- unread, loaded, refused-with-a-code, or
  `Unconfigured` when the build has no service at all. An empty list is never
  used to mean "we could not ask".
* It computes the standing of each row from the service's own facts: pending
  and named for you, pending and named for someone else (and it says who),
  past the expiry the service reported, granted, refused, invalidated, consumed.
  It sorts what needs you first.
* The decision path is fixed: read the row, ask the service for a challenge
  bound to *that approval's* fingerprint, sign the bytes with the device key,
  then grant. A build with no key refuses (`DEVICE_KEY_NOT_ENROLLED`) instead
  of sending an unsigned grant, and a challenge the service refuses is reported
  with the service's own code.
* `app/security/DeviceKeyStore.kt` is the device half: a non-exportable Ed25519
  key in the Android keystore with P-256 as the fallback, generated once and
  never rotated silently.
* The service side gained the binding that makes a queue possible:
  `POST /v1/approvals/{id}/challenge` issues a challenge for the approval's own
  fingerprint and the execution it carries, so a screen does not have to
  remember which execution a row belongs to. An approval now carries its
  `executionId` on the wire.

**Not done here:** the other ten screens, and the visual and adaptive work of
Phase 6 and 7, which need a device. The approvals section is rendered inside the
governance screen and its state machine is covered by tests; the pixels are not.

### The testing matrix — fuzz and concurrency

The plan names both categories explicitly ("دي مهمة جدًا للـparser … Concurrency
Testing ده ناقص في مشاريع كتير") and states the rule that makes the first one a
requirement rather than a nice-to-have: *never guess on malformed high-impact
input*.

* **Fuzz, on both sides of the model boundary.** `FuzzInterpreterTest` runs
  seeded corpora — Arabic, mixed numerals, punctuation, emoji, URLs, direction
  marks, zero-width joiners, 200,000-character inputs, combining marks, and
  injection phrases — through the interpreter, asserting properties rather than
  examples: no exception, deterministic answers, idempotent normalisation, a
  code on every refusal, and never a ready order whose amount was not in the
  text. `FuzzStructuredOutputTest` does the same to the model's own answer:
  truncated JSON, two documents concatenated, a markdown fence, nulls, wrong
  types, unknown tools, 2,000-level nesting, 500 KB payloads, and injection
  inside the arguments.
* **Both found real defects on the first run.** The Arabic thousands separator
  (U+066C) was not normalised to ASCII, so `١٥٬٠٠٠` parsed as *15* — a wrong
  order, which is worse than a failed parse, and exactly the failure mode the
  parser's own documentation claims to prevent. And the model boundary asked for
  the *currency* when the amount it had been given was the word "كثير": the
  "what is missing" list disagreed with the "is this complete" decision. Both
  are fixed, and the second is fixed at the root — the same function now decides
  both, and a stated zero is refused as firmly as a missing one.
* **Concurrency.** `ConcurrencyTest` covers the plan's scenarios one by one:
  two people answering the same approval at once, one approval and one
  idempotency key under concurrent use, an ERP answer that lands after the
  client gave up, a session that expires mid-write, a tenant that changes under
  a session, a policy that moves under an approval, and a restart after the
  write. The race that matters here does not look like an error: the last writer
  wins and a dual approval records one signature while reading as complete.
  `ApprovalDesk` now serialises that sequence per approval through `KeyedLocks`,
  a primitive with its own tests (mutual exclusion, no leak, no deadlock
  between two keys taken in two orders).
* **What is not covered:** the locks are per process. Two replicas behind one
  PostgreSQL — Phase 13 — need the database to carry the invariant, as a
  conditional update or a row lock. That is written down in `docs/SERVICE.md`
  as an open item rather than implied by the locks. Transport faults at the
  ERP boundary (timeouts, 5xx, 429, schema drift, auth expiry) are next.

### Phase 6 and 7 — the half of localisation and accessibility that is static

Gates 13 and 15 read as device work and were therefore not started. That was
the wrong reading of them. Whether a sentence is hardcoded in Kotlin, whether
every referenced resource exists, whether a button is 48dp and whether text
sits above a readable floor are properties of the tree, and the tree is exactly
what this environment has.

* **Gate 13, full Arabic localisation: done as far as static analysis can take
  it.** 193 literals across 17 files were moved out of Kotlin and into the
  resources, with the interpolations preserved as positional placeholders so a
  translator can move them. Both locales are in step and are checked every run;
  a literal is now an error, so the count cannot drift back up. The extraction
  is idempotent — running it again finds nothing — which is how "done" is
  defined here rather than by a one-off sweep.
* It also found a genuine build break: `R.string.cancel` was referenced by the
  biometric gate and defined nowhere. No compiler here could have said so.
* **Gate 15, accessibility: the static half is real, the device half is
  listed.** `tools/strings_check.py` enforces defined resources, paired
  locales, no hardcoded text, a 48dp touch target on every clickable surface,
  an 11sp text floor, `sp` over `dp`, RTL support and no orientation lock. Three
  undersized controls and fifteen sub-11sp labels were fixed. What is
  deliberately *not* claimed — screen-reader order, rendered contrast, font
  scale at 2.0, RTL mirroring, tablet/foldable rearrangement, WCAG conformance
  — is written down in `docs/ACCESSIBILITY.md` rather than implied.
* The tooling ships with self-tests (`--selftest`) proving the checks fire on
  planted violations, because a check that has never failed is a check nobody
  should trust.

### Phase 7 — design system 2.0
**Not started** beyond the tokens that already existed.

### Phase 8 — AI 2.0
**Partly proven, harness included.** The deterministic half exists and is
proven: Arabic text normalisation, quantity parsing, entity resolution that
refuses to choose when two real entities match, and a tool catalogue whose
writes all require approval and a fresh proof.

The evaluation harness now exists as `domain/ai`:

* `AiCorpus` is 36 hand-written cases across eleven categories — Egyptian
  Arabic, MSA, English, mixed, typos, ambiguous, malicious, long form,
  Arabic-Indic digits, currencies and ERP-content injection — each with the
  outcome, tool, arguments, missing fields or refusal code a correct system
  must produce. It is ground truth written from how people write, not from
  what the interpreter happened to do.
* `AiHarness` runs any number of providers over the corpus and reports
  accuracy, p50/p95 latency and token cost per provider and per category, plus
  a regression report (`fixed`, `broken`, cost and latency deltas) so a prompt
  or model change is a decision with numbers behind it.
* Providers: the deterministic interpreter, OpenAI, Anthropic and Gemini
  adapters (behind a `Transport` seam, so their envelopes are tested against
  scripted bytes), and a scripted double for the harness's own tests.
* Running it against the deterministic interpreter scored **21/36 (0.583)**,
  and the failures were real: no item extraction from "2,500 USD, 10 laptops",
  no Egyptian "المخزن"/"مبيعات"/"الغي", no "لشركة النور", Arabic word numbers
  behind a preposition, and a long sentence whose noun "customer" beat its verb
  "create". Fixing those took it to **36/36 (1.000)**. The number is a
  regression ratchet for this corpus, not an accuracy claim about any vendor's
  model.

The red line holds and is tested: a model that answers exactly what an attacker
asked for still goes through the guard, validation, policy and the ladder, and
the injection guard runs before the model is called as well as after it
answers.

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
Integrity attestation (all Android-side, all needing a device or a Play
account).

Gate 19 is **security CI**, and that part is now real rather than listed.
`tools/security_check.py` refuses a committed private key or vendor token, a
manifest that allows cleartext / sets debuggable / turns backups on / declares a
component without an exported state, a cleartext exemption for a non-loopback
host, a credential in a log line, plain `SharedPreferences` in a file that
handles a credential, a hardcoded non-loopback host in client code, a governed
route that never calls `authenticate()`, a snapshot dependency, and a
documented workflow that skips the repository's own checks.
`tools/release_check.py` covers gate 20's tree-side half: the production flavor
is not a demo build, the release build type does not fall back to the debug
key, minification and shrinking are on, the version must move forward, no
signing artifact is committed, and the release document states what happens to
the R8 mapping file and how a rollback works. Both were verified against
planted violations (a private key and a debug-signed release) before being
trusted, and both run in the documented CI.

The checks are static, and that is the honest limit: they cover a git tree, not
a running device. No Play Integrity verdict, no certificate pinning, no proof
that a keystore is hardware-backed exists in this environment, and the app does
not claim any of them.

The audit chain is now **sealed**: with `receiptKeyPair` configured, every row
is signed with the authority's Ed25519 key (`AuditSealer`), `GET /v1/audit`
reports `sealed`, `sealedRecords`, `verifiedSeals` and the `sealKeyId`, and the
verifier with a sealer refuses a chain of unsigned rows (`CHAIN_NOT_SEALED`)
instead of accepting them. The test that matters rewrites a row *and* every
hash after it: the rewritten chain verifies perfectly as a chain, and fails as
a sealed one (`CHAIN_SEAL_MISMATCH`). A hash chain is an internal-consistency
check; a seal is what says who wrote the rows.

### Phase 12 — enterprise platform
**The authority half is real; the console is not.**

The plan lists nine surfaces -- Admin Web, SSO, Organizations, Roles,
Permissions, Policies, Devices, Audit, ERP integrations -- and the service now
has the model and the routes for the ones that decide who may do what:

- **Organizations and members.** `domain/org/Organizations.kt` holds
  organizations, memberships, plans and status; `service/store/OrganizationStore.kt`
  is the durable log they are replayed from, so a grant survives a restart and
  a removed member does not come back.
- **Roles and permissions.** `Permission` is an eleven-name vocabulary
  (`OPERATIONS_READ` … `ERP_WRITE`), the built-in roles are bundles of it, and
  an organization can define, edit and delete its own roles. Every route asks a
  permission question, never "is this person an admin".
- **The admin API.** `/v1/admin/{organizations,members,roles,permissions,organization/sso,sso/resolve}`
  invites, activates, suspends, removes, re-roles, defines roles and maps SSO
  domains. Three rules are enforced rather than documented: the organization
  comes from the session and another tenant's organization is refused with 403
  `TENANT_MISMATCH`; nobody hands out a permission they do not hold
  (`ROLE_ESCALATION`); the last administrator cannot be suspended, removed or
  demoted (`LAST_ADMIN`).
- **Everything privileged is in the trail.** Every mutation and every refusal
  that got past authentication writes an audit row, so an administration
  surface with no audit trail is not possible by construction.
- **The reference deployment seeds itself.** The four demo accounts become
  members of `sim-alamal` at start-up -- a manager as its administrator, an
  auditor with read-only grants -- so the permission check has no special case
  for demo accounts.

**Not built here:** the Admin Web console itself (a client of these routes),
real SSO against an identity provider (the domain mapping and resolution rule
exist; token validation does not), and ERP credential management. The existing
read surfaces (policy, journal, reconciliation, devices, receipts, audit) are
what such a console would render for the remaining screens.

### Phase 13 — scale
**One piece is real, the rest is deliberately not started.** Horizontal
scaling, queues, caching, multi-region, sharding and disaster recovery belong
to a deployment this environment cannot run, and the plan says to enter them
only when the earlier phases hold.

What is real and proven is the one piece that would otherwise corrupt data the
moment a second replica exists: the outbox sweeper. Two processes sharing a
durable store would each drain the same due entries, and the entries are
idempotent by key, so the second dispatch "may be" harmless -- which is not a
property to build a queue on. `SweepLease` makes the sweep a durable,
expiring claim: a replica appends a claim, reads every claim in the round, and
the earliest live claim wins, ties broken by owner id, so two racing replicas
compute the same answer from the same records. A replica that dies mid-sweep
does not lock its peers out (`ttl` expires), a restarted replica sees what the
previous process claimed, and the claim stream is compacted so a year of uptime
does not turn a lease into a log that only grows. A single-process deployment
leaves `sweepLeaseMillis` at zero and keeps the old behaviour.

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
| 9 | Server-signed receipts | **proven**: Ed25519 signature over the published fields, rebuilt and verified from the wire by `ReceiptInspector`; key custody is a deployment concern |
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

1. The approval screen: `GovernanceApiClient` speaks the whole approval
   surface already, and nothing in `:app` calls it yet — a person still cannot
   open, read or answer an approval from the phone.
2. Wire device enrolment and the challenge into that same screen, so a grant
   that the ladder demands a device proof for can actually be granted.
3. The Postgres stores, behind the same interfaces, with the append-only log
   kept as the reference implementation and as the test double.
4. The remaining gates above, in the order the plan gives them.
