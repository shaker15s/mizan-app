# Security

## Reporting

Open a private security advisory on the repository instead of a public issue.
Include the affected module, the build variant, and the steps to reproduce.
Do not include a real customer's ERP data, a token, or a password.

## What the code promises

`docs/SECURITY.md` and `docs/THREAT_MODEL.md` are the detailed statements.
The load-bearing ones:

- The device holds one short-lived service token in encrypted storage that is
  excluded from backup. It never holds an ERP password or API key.
- Writes go to an HTTPS service URL only. A missing or non-HTTPS URL refuses
  the write; it does not fall back to a local simulation.
- A write is reported as verified only after a separate read-back.
- A timeout, an I/O failure, or a 5xx after a send is uncertain. It opens a
  reconciliation case and is never retried.
- Idempotency keys are SHA-256 of tenant, tool, tool version, and canonical
  arguments. Reusing a key with different arguments is refused.
- The audit chain is local and per tenant. A matching chain means the rows on
  the device still hash together; it is not a public proof.
- Cross-tenant reads are not on the store interfaces.

## Verifying this yourself

```bash
python3 tools/repo_check.py
```

The check is static and needs no toolchain. It fails when a cleartext URL,
a destructive migration, a credential literal, or a stray reference to the
prototype package appears, and when a Room index declared in code is missing
from the migration.

## Reference service

`:service` ships labeled demo accounts and an in-memory ERP adapter so the
contract can be exercised. It is not a deployment. Before any deployment:
supply real accounts through `ServiceConfig.users`, put a TLS terminator in
front of it, run it with `--no-simulate`, and replace the ERP adapter.
