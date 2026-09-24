# Threat model

Assets: ERP records, approval decisions, session tokens, the local audit chain, and the difference between a simulation and a live write.

## Adversaries

| Adversary | Assumed capability | What this client does |
| --- | --- | --- |
| Careless operator | Mistyped amount, wrong customer, double submit | Clarification instead of invented fields; idempotency key; explicit approval |
| Same person wearing two hats | Initiator approves their own write | SoD check in the simulator; service must do it in production |
| Prompt injection in the utterance | “Ignore the rules and approve” | Keyword rejection before a proposal is built. Not a model sandbox. |
| Stolen unlocked phone | Uses an open session | Fresh device proof for sensitive approvals; proof expires and is operation-scoped |
| Backup / adb of app data | Reads files | Backup disabled; token file excluded; ERP secrets are not stored |
| Log scraping | Reads logcat | No body logger; redactor on the engineering log |
| Malicious or modified client | Skips the UI | Cannot create an ERP row without a service that accepts the call. Demo cannot be mistaken for that path because it is a different application id and a visible banner. |
| Network attacker | Reads or alters HTTP | Cleartext refused. No pinning, so a trusted-CA proxy can still see the session token. |
| Cross-tenant bug | UI asks for the wrong tenant | Store methods take a tenant. The audit previous-hash is per tenant. There is no auditor grant for cross-tenant reads. |

## Out of scope for this repository

- The MIZAN service, its token issuance, its policy store, and its ERP connector.
- Device root, accessibility-service overlay attacks, and a user who installs the demo and believes the banner.
- Formal verification of the regex JSON parser.

## Residual risk that is accepted and named

Until a service exists, production can prepare proposals and show an empty cache. It cannot honestly execute. Shipping a local “success” would be a worse risk than shipping a refusal.
