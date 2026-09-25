# Architecture

Date: 2026-09-23. This describes the tree as written, not a target that is only planned.

## Modules

```text
:domain        JVM. Models, policy, risk, state machine, idempotency, interpreter, audit hash.
:integration   JVM. MIZAN HTTP client, redaction, Odoo JSON-2 request builder, legacy XML-RPC parser.
:data          Room 2, tenant-scoped stores, encrypted session token.
:design        Tokens and components. No business rules.
:app           Shell, screens, flavors, composition root.
:service       JVM server. The reference authority. Not packaged into the app.
```

```text
app → design, domain, data, integration
data → domain
integration → domain
service → domain
```

The UI does not import a DAO. The production and staging source sets do not include the simulator. The demo source set does not include the remote authority.

## Composition root

`MizanApplication` builds `AppGraph`. There is no Hilt. AGP 9.1.1 and current Hilt do not combine reliably; an explicit graph is the dependency injection that exists.

## Flavors

`app/build.gradle.kts` declares one dimension, `channel`, with three flavors.

| flavor | application id | `DEMO_MODE` | service URL | simulator |
| --- | --- | --- | --- | --- |
| demo | `app.mizan.demo` | true | empty | compiled in from `src/demo` |
| staging | `app.mizan.staging` | false | `MIZAN_API_BASE_URL` | absent |
| production | `app.mizan` | false | `MIZAN_API_BASE_URL` | absent |

Two functions are supplied per flavor, each in its own source set:

- `createAuthority`: demo → `SimulatedExecutionAuthority`, staging and
  production → `RemoteExecutionAuthority`.
- `createSimulationDirectory`: demo → `MizanSimulationDirectory`, staging and
  production → `NoSimulationDirectory`.

`SimulationDirectory` is the seam. A non-demo build links an empty
implementation, so no screen can reach a simulated actor, tenant, or ledger
row. `RemoteExecutionAuthority` stays in `main` on purpose: it is the client of
the service and it refuses a write when the URL is missing or not HTTPS, so
shipping it everywhere is safe and shipping the simulator is not.

## Pipeline

```text
Intent (local rules)
  → Proposal (policy preview + risk classification)
  → Approval (separation of duties + fresh device proof)
  → ExecutionAuthority
  → Verification or reconciliation
  → Local receipt and local audit row
```

In production the policy result is a preview (`policyIsPreview = true`). The service is the authority. In demo the same evaluator is the simulator, and the UI says so.

## Trust boundary

Production:

```text
Phone
  → HTTPS MIZAN service (POST /v1/sessions, POST /v1/executions)
    → service policy, approval, idempotency, read-back
      → ERP
```

A reference implementation of that service is now in this repository, in
`:service`. It is a JVM server with an in-memory ERP adapter, not a production
deployment and not Odoo. See `docs/SERVICE.md`. A blank or non-HTTPS URL refuses the write. The phone never sends an ERP password.

Demo:

```text
Phone
  → local policy
  → SimulatedExecutionAuthority
  → Room rows with origin = SIMULATION
```

A banner stays on screen while a simulation session is open.

## Execution

`ExecutionStateMachine` is consulted before a write. Illegal paths are refused. After a request is marked sent, a timeout, an I/O failure, or an HTTP 5xx becomes uncertain and opens a reconciliation case. It is not retried. A person may link a candidate or close the question. That decision is `LINKED_UNVERIFIED` or `CLOSED_UNVERIFIED`. It is not a read-back. Process death runs `recoverExpiredLeases`, which moves an expired in-flight lease to reconciliation and does not send it again.

Idempotency keys are SHA-256 of tenant, tool, tool version, and canonical arguments. A verified claim replays the existing result. An ambiguous or in-flight claim is blocked.

## Identity

A remote session is an actor id, role, tenant id, and a token from `POST /v1/sessions`. The token is the only secret the phone keeps, in encrypted preferences excluded from backup. Demo identity is four labeled simulation actors. Role switching exists only in the demo flavor and does not exist as a production control.

## What is intentionally absent

- No Odoo call from the client.
- No Gemini or Firebase.
- No unscoped “list every tenant” on the store interfaces. The audit chain’s previous hash is the previous event **of that tenant**.
- No `fallbackToDestructiveMigration()`.
- No measured baseline profile. None was recorded.
- The `:service` module is not a deployment. It holds a reference ERP adapter
  in memory and labeled demo accounts, and it answers plain HTTP because a TLS
  terminator belongs in front of it.
