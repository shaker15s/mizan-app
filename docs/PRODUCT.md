# Product

MIZAN is a bilingual (Arabic and English) client for governed ERP operations. A person describes what they want. The app turns that into a specific action, shows the rule and the risk classification, and does not carry it out until the right person confirms it. A checked result means the important fields were read back. An uncertain result is not marked failed and is not sent again.

## What a person can do

| Place | What it is for |
| --- | --- |
| Home | What needs attention, recent operations, and what this device actually knows about the connection |
| Agent | Prepare a request. Missing fields become questions. Nothing is invented. |
| Operations | The state of requests this device has stored |
| Reconciliation | Requests that left the device whose result cannot be proven |
| Evidence | Receipts, and a local hash-chain check that does not claim to be a notary |
| Rules | The rules this device can explain |
| Account | Language, theme, reduced motion, security notes, connection, sign-out |
| Search | Saved rows on this device only |

## What “checked” means

| Label | Meaning |
| --- | --- |
| Checked inside the simulation | The demo wrote a local row, read it back, and compared customer, amount, status, and origin. It is not an ERP record. |
| Checked against the ERP record | The MIZAN service returned `verified` with a record id and model. The phone did not perform that read itself. |
| Accepted, not yet checked | The service accepted the request. The ERP record is not confirmed. |
| Uncertain | The request may have been accepted. It is not retried. |
| Saved copy | A lookup against rows already on the phone. Not a live ERP query. |

## What this product is not

- Not an ERP. Odoo JSON-2 and the legacy XML-RPC parser live in `:integration` for a future service. The Android graph does not call them.
- Not a model. Intent parsing is deterministic rules. There is no thought trace and no confidence score.
- Not a legal policy engine for a customer. Demo thresholds are labeled simulation thresholds. A currency with no ladder is not treated as dollars.
- Not available as a Play update to `com.aistudio.mizan.erpgov`. The application id is now `app.mizan` (demo `.demo`, staging `.staging`). No listing was found to preserve.

## Languages

English is `values/`. Arabic is `values-ar/`. The system locale is the default. Account can pin either language. Hashes, ids, and URLs are laid out left-to-right even in Arabic.
