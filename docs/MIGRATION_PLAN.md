# Migration plan

From the prototype at `3c6e175` (`com.example`, application id `com.aistudio.mizan.erpgov`) to this tree.

## Done in this tree

1. Rules moved to `:domain`. Policy, risk, state machine, canonical idempotency, interpreter, local hash, re-authentication policy.
2. Transports moved to `:integration`. The Android graph does not construct an Odoo client.
3. Room moved to `:data`. Explicit `1 → 2` migration. No destructive fallback. Tenant-scoped queries. Composite keys for cached ERP rows.
4. Production execution is `RemoteExecutionAuthority`. Missing service URL fails closed.
5. Demo is a flavor with a banner, `SIM-` ids, and role switching that is not in the production source set.
6. `com.example` sources and their tests were deleted.
7. Navigation is a `NavHost`. Screens have their own state holders. `AppGraph` is the composition root.
8. Strings are resources. Arabic is `values-ar`. System locale is the default.
9. Glass is limited to the command dock and the navigation bar. The design tokens live in `:design`.

## Not done, and not claimed

| Item | State |
| --- | --- |
| MIZAN service | Not in this repository. Production cannot execute an ERP write until it exists and `MIZAN_API_BASE_URL` points at it. |
| Odoo JSON-2 execution | Request builder exists. Nothing in the app calls it. |
| Sync | Snapshot type exists. No successful sync is recorded. |
| Second approver in production | The device refuses L4 without one and cannot appoint one. The service contract must carry the second approver. |
| Gradle wrapper and a green CI run | Not produced here. |
| Migration rehearsal on a version-1 database | SQL is written. It was not executed. |
| Play rollout | New application id. Not an update. |

## Operator-visible removals

Fake latency, fake tamper report, quick sale that skipped policy, thought-trace theater, “100%” and “tamper-proof” copy, role switching outside demo, seeded “authenticated to Odoo” audit events, and ERP credentials in client state.

## Kept

Bilingual product, approval ladder, separation of duties, ambiguous outcome instead of blind retry, local hash chain as a local integrity check, and the tool list: stock, customer, draft order, cancel, invoice, payment, summary.
