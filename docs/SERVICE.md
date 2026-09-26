# Wakeel reference service

`:service` is the authority the Android client was written to talk to. It is a
server. It is not packaged into the application, and the application never
depends on it.

The point of this module is honesty: before it existed, the client refused to
impersonate an ERP but had nothing to refuse *to*. Now there is a service that
decides, writes, and reads back, and a test suite that proves the contract.

```text
Phone                    :service                     ERP adapter
  prepare  ──HTTPS──▶   /v1/sessions  ─────────────▶   (none)
  propose  ──HTTPS──▶   /v1/executions ─────────────▶  in-memory ledger
  verify   ◀────────    read-back      ◀─────────────  read-back
```

The bundled ERP adapter is **in memory**. It is not Odoo, it does not call
Odoo, and a green test here proves nothing about a customer's ERP.

## Run it

```bash
./gradlew :service:run --args="--port 8080 --host 127.0.0.1"
```

The service speaks plain HTTP. `MizanApiClient` refuses to send a write to any
URL that is not `https://`, and that refusal is a security property. To point a
device at a local service, terminate TLS in front of it:

```bash
# example, not a recommendation of a specific vendor
caddy reverse-proxy --from https://mizan.local.test --to 127.0.0.1:8080
# then
export Wakeel_API_BASE_URL=https://mizan.local.test
./gradlew :app:assembleStagingDebug
```

## Accounts

Four labeled demo accounts exist so the contract can be exercised. Replace
them through `ServiceConfig.users` before any deployment; they are not
credentials, they are fixtures.

| email | actor | role |
| --- | --- | --- |
| `rep@mizan.test` | `USR-REP` | `SALES_REP` |
| `manager@mizan.test` | `USR-MGR` | `SALES_MANAGER` |
| `finance@mizan.test` | `USR-FIN` | `FINANCE_APPROVER` |
| `auditor@mizan.test` | `USR-AUD` | `AUDITOR` |

Passwords are in `ServiceAuthority.demoUsers()` and are verified with PBKDF2
(`120 000` iterations, per-user salt, constant-time comparison). Only the
SHA-256 of a session token is kept, so a heap dump does not yield a usable
credential. Five failed sign-ins lock the account for five minutes, and a
locked account answers exactly like a wrong password.

## Contract

### `POST /v1/sessions`

```jsonc
// request
{ "email": "rep@mizan.test", "password": "..." }
// 200
{ "token": "...", "actorId": "USR-REP", "displayName": "Amr Kamel",
  "role": "SALES_REP", "tenantId": "sim-alamal", "tenantLabel": "Al-Amal Trading",
  "expiresAtEpochMillis": 1700000000000 }
// 401 for unknown account, wrong password, or lockout — one answer for all three
```

### `POST /v1/executions`

Headers: `Authorization: Bearer <token>`, `Idempotency-Key`, `X-Trace-Id`, and
optionally `X-Mizan-Simulate: ambiguous` (tests only; disable with
`--no-simulate`).

```jsonc
// request
{ "executionId": "EXE-1", "proposalId": "PRP-1", "tenantId": "sim-alamal",
  "tool": "sales.order.create_draft", "toolVersion": "2.1.0",
  "approverId": "USR-MGR",
  "arguments": { "amountMinor": "250000", "currency": "USD",
                 "customerName": "Acme Corp", "itemsSummary": "10 laptops" } }

// 200 verified — only after a separate read-back
{ "status": "verified", "executionId": "EXE-1", "erpRecordId": "SO-1001",
  "erpModel": "sale.order", "verification": "READ_BACK", "summary": "state=draft ..." }

// 200 accepted — applied, not read back
{ "status": "accepted", "executionId": "EXE-1", "messageCode": "ACCEPTED_NOT_VERIFIED" }

// 200 ambiguous — applied, but the caller must not assume; opens reconciliation
{ "status": "ambiguous", "executionId": "EXE-1",
  "messageCode": "SERVICE_AMBIGUOUS", "candidates": "SO-1001,SO-1002" }

// 200 failed — the ERP refused; nothing was written
{ "status": "failed", "executionId": "EXE-1", "messageCode": "ORDER_NOT_FOUND" }

// 400 malformed body · 401 no or expired session · 403 another tenant
// 409 idempotency key reused with different arguments · 422 refused by policy
```

`status` is the only field the client treats as decisive, and an unknown value
is a refusal, never a success.

### `GET /v1/health`, `GET /v1/audit?tenant=…`, `GET /v1/erp?tenant=…`

Read-only, session required for the last two, and both refuse a tenant that is
not the caller's. `/v1/audit` returns the service-side hash chain with
`chainIntact`, which is computed by the same `ChainVerifier` the device uses.

## What the service re-decides

The phone evaluates policy before it asks. The service evaluates it again,
because a preview is not an authorization:

1. the tool exists and the version matches the contract;
2. the tenant in the body is the tenant of the token;
3. the ERP adapter supports the tool;
4. the idempotency key is unknown, or known with identical arguments;
5. policy allows the actor, the tool, and the amount;
6. separation of duties is satisfied by the approver that was sent;
7. the write is applied and then **read back** — only a successful read-back
   produces `verified`.

Every decision is appended to a per-tenant hash chain authored by the service
(`IntegrityClass.SERVER_AUTHORED`). That chain proves the service's own history
is internally consistent. It is not an external witness and not a legal record.

## Tests

```bash
./gradlew :service:test
```

`MizanServiceHttpTest` starts a real listener on an ephemeral port and covers
sign-in, throttling, verified writes, idempotent replay, key reuse, separation
of duties, auditor refusal, tenant isolation, the ambiguous path, the invoice
and payment chain, cancellation, the audit chain, and the health endpoint.
