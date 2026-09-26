# Wakeel architecture audit

Date: 2026-09-23. Scope: the repository as it existed at `3c6e175` (`com.example` single-module Android app, ~12k lines of Kotlin). This document was written before the rebuild. It describes what was actually in the code, not what the UI claimed.

## 1. Current architecture

Single Gradle module `:app`. Namespace `com.example`. Application id `com.aistudio.mizan.erpgov`. No dependency injection. `MainActivity` constructs `MizanDatabase` and `MizanRepository` and installs one `MizanViewModel` for the whole product.

Logical packages, not enforced boundaries:

| Package | Role in practice |
| --- | --- |
| `model` | Enums, proposal/receipt DTOs, SHA-256 helpers |
| `control` | In-process policy ladder and tool registry |
| `decision` | Keyword scoring presented as decision signals |
| `execution` | `ExecutionGateway` proposing and "executing" |
| `connectors` | `ErpConnector` plus `Odoo19Json2Connector` and `ErpNextConnector` |
| `connectors.odoo` | Real XML-RPC serializer/parser and a Retrofit service for legacy JSON-RPC / XML-RPC |
| `data` | Repository that owns policy, connectors, users, tenants, and seed data |
| `data.local` | Room v1, `exportSchema = false`, `fallbackToDestructiveMigration()` |
| `evidence` | Local SHA-256 chain |
| `auth` | `BiometricPrompt` wrapper plus a simulated proof |
| `viewmodel` | One ViewModel owning language, theme, tenant, role, agent timeline, Odoo session, biometrics, policy simulator, and every screen's data |
| `ui` | Compose screens and a glass theme cloned from ChatGPT/Apple palettes |
| `MainActivity` | Navigation `when (selectedNavIndex)`, tenant switcher, role switcher |

There is no backend, no environment split, and no module boundary. The Android process is the policy engine, the ERP, the auditor, and the identity provider.

Data flow today:

```text
User text
  → keyword parser in MizanViewModel (invents missing ids and amounts)
  → ExecutionGateway.proposeExecution
  → PolicyEngine (in-process)
  → UI approval
  → connector writes a Room row
  → connector reads that same row back
  → TrustReceipt labeled verified / tamper-proof
```

## 2. Major problems

1. **The client is the authority.** A compromised or modified APK can approve, "execute", and mint receipts. There is no service that can refuse.
2. **ERP success is fabricated.** `Odoo19Json2Connector` does not call JSON-2. It inserts an `ErpOrderEntity` and returns `Success`. `verifyRecord` reads that same table. The class name claims Odoo 19 JSON-2.
3. **Credentials are ordinary state.** `OdooSession.apiKeyOrPassword` is retained and restored from seed (`odoo_sec_key_449102830192_tenant_a`). A bearer map lives in the connector. The password is embedded in XML-RPC bodies and can be logged by the BODY interceptor.
4. **Identity is a dropdown.** Four hardcoded principals. Role and tenant switching are production UI, not a demo boundary.
5. **One ViewModel and one Activity own the product.** Navigation is an integer. Process death drops in-memory leases and the agent timeline. Execution ids are regenerated (`EXE-${traceId.takeLast(8)}`) and do not match the id stored at propose time.
6. **Global queries.** `getAllAuditRecords`, `getAllTrustReceipts`, `getAllExecutions`, `getAllReconciliationItems`, and `getOrderById` without tenant scope. `allReceipts` is collected in the ViewModel.
7. **Destructive migration.** `fallbackToDestructiveMigration()` plus `exportSchema = false`. An upgrade deletes the ledger.
8. **False certainty.** Copy and models say "Authoritatively Verified", "tamper-proof", "100%". `simulateTamperScenario()` returns a hardcoded failure. `testOdooPing()` reports `Response Latency: 24ms` when the call fails. `verifyChainIntegrityWithProgress` inserts `delay(45)` per block. Intent parsing delays 800ms and invents `SO-2026-094` and `16500.0`.
9. **Quick sale bypasses policy.** `createQuickErpOrder` writes a confirmed sale and an audit event with no approval.
10. **Legacy RPC is the live path, JSON-2 is a label.** Business code reaches `OdooXmlRpcRepository` from the ViewModel. The JSON-2 connector ignores its API client.
11. **Tool arguments are `Map<String, String>`** and JSON is built by string concatenation. Audit payloads are concatenated with `:` separators, so field boundaries are not unambiguous.
12. **SoD is incomplete.** L4 is described as dual approval but `validateSeparationOfDuties` accepts a single manager. L3 destructive policy does not check the actor's role at evaluation time; the role check happens only at approval, and a sales rep can be selected as the approver from the same dropdown.
13. **Biometric unlock is a boolean.** `isBiometricSessionUnlocked` starts as `true`. Enforcement can be toggled off in the UI. The proof is not bound to an operation or a freshness window.
14. **Design system is a palette, not a system.** Screens reach for `LocalLiquidGlass` and arbitrary dp. Shape language is 24–32dp pills. Six accent colors. Glass is the default surface, including Material `surface`. Typography requests Bold from a single Regular font file.
15. **Strings live in composables.** `if (isArabic)` is the localization strategy. Layout direction is a boolean, not a locale. Technical ids are not forced LTR.
16. **Navigation is not adaptive.** Six bottom destinations on every width. No list-detail. No typed routes. No restoration.
17. **Release build is not a release.** `isMinifyEnabled = false`. Debug signing points at a missing `debug.keystore`. Release signing requires env vars and a missing keystore, so `assembleRelease` cannot succeed as committed. Firebase App Check debug and an unused Gemini secrets hook are on the classpath. No CI.

## 3. Technical debt

- KSP `2.3.5` does not match Kotlin `2.2.10` (the matching release is `2.2.10-2.0.2`). The project has no `gradlew` or wrapper jar, so it has not been shown to build.
- Compose BOM `2024.09.00` is two years behind the current BOM. Room `2.7.0` is behind `2.8.x`.
- `com.example` namespace versus application id `com.aistudio.mizan.erpgov`.
- Repository constructs connectors, policy, audit, and XML-RPC inside its constructor. Tests cannot replace the ERP without replacing the world.
- Execution lease map is in-memory only.
- Idempotency key is computed and stored, then ignored on replay. A second approval creates another random order id.
- `argumentsJson` is not canonical (map iteration order).
- Currency is a hard-coded `"USD"` or `"EGP"` string. Policy thresholds compare a raw `Double` to dollar amounts regardless of currency.
- Seed data inserts audit events that claim `ODOO_SESSION_AUTHENTICATE` / `serverVersion: 19.0+e` without a network call.
- Commented-out dependencies and an AI Studio `metadata.json` capability `MAJOR_CAPABILITY_SERVER_SIDE_GEMINI_API` with no Gemini call site.
- Screenshot test covers a greeting/top bar, not the product states.

## 4. Security risks

| Risk | Evidence | Impact |
| --- | --- | --- |
| Client-side authorization | `PolicyEngine` result is what allows `executeTool` | Modified client authorizes anything |
| Credential in memory and seed | `OdooSession.apiKeyOrPassword`, `bearerTokenVault` | Theft via logs, backup, heap, screenshots of debug UI |
| Secret-capable logging | `HttpLoggingInterceptor.Level.BODY` when enabled | Passwords in logcat |
| XML injection in helper | `OdooXmlRpcHelper.buildAuthXml` interpolates db/login/password; the serializer path does escape | Helper is unsafe if used |
| Backup allows app data | `allowBackup=true`, extraction rules are the sample TODO | Ledger and any future token leave the device |
| No cleartext ban | Manifest has no network security config | A future http base URL would be accepted |
| Cross-tenant reads | unscoped DAOs | A UI bug or malicious build lists every tenant |
| Biometric bypass | starts unlocked; toggle disables enforcement | Sensitive actions have no fresh user presence |
| Prompt injection is a keyword list only | and does not stop execution, only "escalates" | Injected text still becomes a proposal |
| Fake audit authenticity | local chain presented as universal proof | Operators trust a database the user can edit |
| Hardcoded production-looking hosts | `https://alamal.odoo.com`, `odoo_alamal_prod` | Confuses demo with a real tenant and ships identifiers |

Threat detail is expanded in `docs/THREAT_MODEL.md` after the rebuild. Absolute security is not claimed.

## 5. UX problems

- First screen is a metric dashboard (the 1,674-line `DashboardScreen`) rather than "what needs me".
- Agent shows a fabricated thought trace (`thoughtProcessEn`) and a streaming flag. That is presented as reasoning. It is a template.
- Proposal UI is fed raw tool names and stringly arguments. Financial meaning is secondary.
- Approval can be completed by switching the role dropdown to a manager. That teaches the wrong model of authority.
- Errors and successes share the same timeline types loosely. Ambiguous, failed, and policy-denied are not visually distinct systems.
- Empty states and offline states are not first-class. Connectivity is not modeled.
- Six tabs expose Pipeline, Governance, Evidence as peer concepts a clerk must learn.
- Copy over-claims: verified, tamper-proof, cryptographic authority, 100%.
- RTL is a boolean flip. Hashes and ids are not isolated as LTR.
- Touch, focus, and TalkBack are incidental. Status is largely color (teal, orange, coral, gold, purple, green).
- Motion blocks the task (`delay(800)`, `delay(45)`, `delay(600)`).
- No onboarding of what "verified" and "uncertain" mean.
- Tablet is the phone layout with more width.

## 6. Performance risks

- Entire audit ledger loaded to verify, on the main-adjacent scope, with an artificial delay per row.
- Unpaged `Flow<List<...>>` for every table, including cross-tenant.
- Large composables (`DashboardScreen` 1,674 lines, `AgentScreen` 895) recompose from a god ViewModel; any session toggle invalidates every screen.
- Glass colors are translucent surfaces stacked with 12dp shadow on the navigation bar. Blur is approximated by alpha, but translucency is the default, not the exception.
- BODY logging and Moshi reflection (`KotlinJsonAdapterFactory`) on the client path.
- No baseline profile, no macrobenchmark, no startup trace. Debug-mode "feel" is the only signal, and it is padded with delays.
- Release shrinking is off, so the APK keeps unused Firebase and extended icons without R8.

## 7. Scalability risks

- One module, one ViewModel, one DAO. A second ERP or a real backend has nowhere to go that the UI cannot import.
- Tool contracts cannot express schema, idempotency, or verification strategy. Adding a field means another string key.
- No pagination, no cursor, no sync metadata. The design assumes dozens of rows.
- Tenant id is a column, not an authorization scope.
- Policy thresholds are code constants in USD. A second country requires an `if`.
- Room version 1 with destructive fallback cannot carry history forward.
- No trace model that survives process death. In-memory leases vanish, and the code will execute again.

## 8. Migration strategy

Do not preserve the dishonest path behind a compatibility flag.

1. Move platform-independent rules to `:domain` (policy, risk, state machine, canonical idempotency, interpreter, audit hash, re-authentication policy).
2. Move Odoo transports to `:integration`. JSON-2 is the primary transport. XML-RPC becomes a legacy adapter. Neither is bound into the production Android graph.
3. Move Room to `:data`. Explicit `1 → 2` migration. No destructive fallback. Tenant-scoped queries. Schema export enabled.
4. Production execution goes through `ExecutionAuthority`. If the Wakeel service URL is absent, writes fail closed. The device does not invent an ERP id.
5. Demo is a flavor (`app.mizan.demo`) with a visible simulation banner, simulated ids prefixed `SIM-`, and role switching that cannot exist in the production source set.
6. Replace the Activity `when` and the god ViewModel with a NavHost and screen state holders.
7. Replace the glass palette with the Wakeel design system, then rebuild screens from those components.
8. Keep the XML-RPC serializer/parser behavior (including XXE hardening). Retarget tests at `:integration`.
9. Package: `com.example` → `app.mizan`. Application id: `com.aistudio.mizan.erpgov` → `app.mizan` (demo suffix `.demo`). No Play listing exists to preserve. Firebase is unused and removed, so the old application id is not load-bearing.
10. Toolchain: stay on AGP 9.1.1 / Kotlin 2.2.10 (AGP 9.1.1's supported Kotlin line). Fix KSP to `2.2.10-2.0.2`. Upgrade Compose BOM and Room only. Do not jump to Kotlin 2.4.20 without an AGP that declares support.

User-visible behavior that is valid and kept: bilingual product, approval ladder idea, separation of duties, ambiguous outcome instead of blind retry, local hash chain as a *local* integrity check, tool list (stock, customer, draft order, cancel, invoice, payment, summary), light and dark themes.

User-visible behavior that is removed: fake Odoo latency, fake tamper report, quick sale that skips policy, thought-trace theater, "100%" copy, role switching outside demo, seeded "authenticated to Odoo" audit events.

## 9. Target architecture

```text
:domain          pure JVM. Rules, models, state machine, repository interfaces.
:integration     pure JVM. JSON-2, legacy XML-RPC, HTTP, redaction, Wakeel API client.
:data            Room, tenant-scoped repositories, encrypted session token store.
:design          tokens and components. No business rules.
:app             shell, navigation, screen state holders, flavors.
```

Dependency direction:

```text
app → design
app → domain
app → data → domain
app → integration → domain
```

Forbidden and not present in the new graph:

```text
UI → DAO
UI → OdooXmlRpcRepository
screen → security authority
production flavor → simulated ERP
```

Production trust boundary:

```text
Android client
  → authenticated Wakeel API (ExecutionAuthority)
    → server policy / approval / idempotency
      → ERP connector (JSON-2 preferred, legacy RPC isolated)
        → ERP
```

The server is not in this repository. The client therefore refuses writes when the service is not configured. That is a limitation, not a fake success. See `docs/ARCHITECTURE.md`.

Demo trust boundary, always labeled:

```text
Android client
  → local policy (advisory and, in this flavor only, the simulator)
  → SimulatedExecutionAuthority
  → Room rows with origin = SIMULATION
```

## 10. Explicit decisions and why

| Decision | Why |
| --- | --- |
| Composition root (`AppGraph`) instead of Hilt | AGP 9.1 and Hilt 2.59+ have published transform failures (`google/dagger` #5261, #5099). A flavor-specific graph is explicit, testable, and replaceable later. DI still exists. |
| Kotlin 2.2.10 retained | AGP 9.1.1 release notes list KGP 2.2.10 as minimum and default. Compose BOM and Room can move without a Kotlin jump. |
| minSdk 26 | `java.time` and a stable Keystore story without desugaring. The product is not shipping to API 24. |
| Production client never holds ERP credentials | The previous design failed the "compromised client" bar. Session tokens, if any, go to EncryptedSharedPreferences and are excluded from backup. |
| Local policy is a preview in production | Showing the ladder helps the user. Calling it authorization would be a lie. |
| Currency with no configured ladder escalates | Do not treat EGP as USD. Do not invent a legal threshold. |
| L4 requires two approvers, neither the initiator | The old L4 check was a single dropdown. Dual approval means two people. |
| Ambiguous after send, failed before send | Timeout is not failure. Retry is not the default. |
| Hash chain labeled local | A SHA-256 column detects local edits. It is not an external notary. |
| Glass only on the navigation / command dock | Performance and readability. No `RenderEffect` blur. |
| System locale by default | Arabic stays first-class. Forcing Arabic was a demo choice, not a global architecture. |
| English `values/` plus `values-ar/` | Removes `if (isArabic)` from product logic. |
| No Gemini dependency | Nothing called it. Shipping Firebase AI and App Check debug to look capable violates the truth principle. |
| Baseline profile not fabricated | No device in this environment. The benchmark class is real; the numbers are not invented. |

Known gap, stated plainly: until a Wakeel service exists, the production app can prepare proposals and show cached data, and it cannot honestly execute an ERP write. The demo flavor is how the operational UX is exercised.
