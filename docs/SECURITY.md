# Security

This document states what the code enforces. It does not claim the product is secure against a modified APK, a compromised device, or a missing server.

## Authority

The production client is not the authority. `RemoteExecutionAuthority` refuses a write when `MIZAN_API_BASE_URL` (or a user-entered HTTPS override) is missing, when the URL is not `https://`, or when there is no session token. Dual approval (L4) is refused on the device if a second approver was not supplied. The production UI cannot appoint that second person; the service must.

Demo execution is a different class. It is compiled only into the demo flavor, labeled in the UI, and writes `SIM-` ids with `EvidenceOrigin.SIMULATION`.

## Credentials

ERP passwords and API keys are not fields in client state, Room, or the session store. Sign-in sends the workspace password once in the HTTPS body and does not retain it. `SessionTokenStore` uses `EncryptedSharedPreferences`. If that store cannot be opened, sign-in fails instead of falling back to plaintext.

Backup is off. `backup_rules.xml` and `data_extraction_rules.xml` exclude `mizan_session.xml` and the Room databases. Cleartext is off in the manifest and in the network security config.

## Re-authentication

A proof is bound to actor, tenant, and operation id. L2 expires in 3 minutes. L3 and above expire in 60 seconds. A previous unlock is not reused. `SIMULATED` proofs are rejected unless the demo flavor constructed the policy with `acceptSimulated = true`. The simulated check is a button, not a silent success.

## Separation of duties

`SeparationOfDuties` rejects the initiator as approver above L1, requires a manager for L3, and requires two privileged approvers for L4. The demo authority calls it before writing. The production authority still sends the approver ids to the service; the service must enforce the same rule. A local preview is not that enforcement.

## Logging

`MizanLog` passes messages through `Redactor` and drops debug in release. The HTTP client does not attach a body logger. Redaction is a backstop, not a reason to log secrets.

## Local audit

Each tenant has its own hash chain. A matching chain means the rows on this device still hash together. The UI copy says that. It is not a server-authored or externally witnessed log. `IntegrityClass.LOCAL_ONLY` is what receipts store today.

## Known limits

- A modified production APK can still show a fake UI. It cannot mint an ERP record unless the service accepts its token. There is no certificate pinning and no Play Integrity check in this tree.
- The JSON field extractor is a regex for a flat contract. Nested or escaped bodies fail closed rather than being trusted.
- `SessionApi` does not verify a server signature on the role it receives. The service is trusted to name the role. That is only as strong as the service, which is not shipped here.
