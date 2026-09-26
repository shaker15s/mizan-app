# The twenty things a company does with this app

Every row below is a scenario someone in a company actually performs, what the
app has to answer, and the test that asserts it. The tests run against a real
listener on a real port (`MizanService`, `com.sun.net.httpserver`) and against
the real interpreter, on a real JVM:

```bash
python3 tools/jvm_check.py      # 414 tests, all passing
```

Twenty-two use cases are listed; the tests around them are more granular than
the list, so one row may name a test that covers neighbours too.

## Identity and access

| # | Use case | What must happen | Proven by |
| --- | --- | --- | --- |
| 1 | A rep opens the app and signs in | the service answers with their actor id, role and tenant; a wrong password and an unknown account give the same answer, and neither issues a token | `UseCaseMatrixTest.aRepSignsInAndTheServiceAnswersWithTheirIdentity` |
| 2 | Someone tries passwords against an account | after repeated failures the correct password is refused too, and the refusal does not say the account is locked | `UseCaseMatrixTest.repeatedWrongPasswordsLockTheAccountWithoutSayingSo` |
| 3 | A stolen, expired or invented token is presented | refused exactly like no token at all: `SESSION_EXPIRED`, nothing reaches the ERP | `UseCaseMatrixTest.anUnknownTokenIsRefusedExactlyLikeNoTokenAtAll` |
| 4 | Operations asks whether the service is up | `/v1/health` answers without a session | `UseCaseMatrixTest.anyoneCanAskWhetherTheServiceIsUp` |

## Writing to the ERP, under the ladder

The demo ladder is simulation configuration, and the UI says so. In USD:
L1 up to 1,000.00 (the author confirms it), L2 up to 10,000.00 (a privileged
approver who is not the author), L3 up to 25,000.00 (a sales manager), L4 above
that (two privileged approvers).

| # | Use case | What must happen | Proven by |
| --- | --- | --- | --- |
| 5 | A rep raises a small order | the author confirms it themselves; it is written, then read back, and only then reported `verified` | `UseCaseMatrixTest.aSmallOrderIsConfirmedByItsAuthorAndVerifiedByReadingItBack` |
| 6 | A rep raises an order above their own limit | a privileged approver who is not the author must be named | `UseCaseMatrixTest.aMidSizeOrderNeedsAPrivilegedApproverWhoIsNotTheAuthor` |
| 7 | The author tries to approve their own privileged write | refused with `SOD_SAME_ACTOR` | `UseCaseMatrixTest.theAuthorCannotApproveTheirOwnPrivilegedWrite` |
| 8 | Someone names an approver without the rank | refused with `SOD_ROLE_INSUFFICIENT`: naming does not confer rank | `UseCaseMatrixTest.namingAnApproverWithoutTheRankDoesNotRaiseTheRank` |
| 9 | An order above the dual threshold is submitted with one approver | refused with `SOD_NEED_SECOND_APPROVER`, never half-written | `UseCaseMatrixTest.anOrderAboveTheManagerThresholdNeedsTheManager` |
| 10 | An auditor opens the app | they can read the ERP and cannot change it: `AUDITOR_READONLY` on every write | `UseCaseMatrixTest.anAuditorCanReadTheErpAndCannotChangeIt` |
| 11 | An invoice is raised on a large order | the invoice carries no amount of its own, so the authority reads the order's amount from the ERP and judges the ladder on that | `UseCaseMatrixTest.anInvoiceInheritsTheApprovalLadderOfItsOrder` |

## Safety under repetition and uncertainty

| # | Use case | What must happen | Proven by |
| --- | --- | --- | --- |
| 12 | A request is retried after a flaky connection | the same idempotency key replays the same answer; no second record is created | `UseCaseMatrixTest.retryingWithTheSameKeyReplaysTheAnswerAndCreatesNothing` |
| 13 | A key is reused for a different request | refused with `IDEMPOTENCY_KEY_REUSE`; the first record is not overwritten | `UseCaseMatrixTest.reusingAKeyForADifferentRequestIsRefused` |
| 14 | The ERP answers unclearly | the write lands and is reported `ambiguous` with candidates; it is never retried, and the client opens reconciliation | `UseCaseMatrixTest.anUnclearErpAnswerIsReportedAsAmbiguousAndTheWriteIsKept` |
| 15 | A request is aimed at another tenant | refused with `TENANT_MISMATCH` before the ERP hears it | `UseCaseMatrixTest.aRequestForAnotherTenantIsRefusedBeforeTheErpHearsIt` |
| 16 | A tool the contract does not define is requested | refused with `TOOL_UNKNOWN`, never guessed | `UseCaseMatrixTest.anUnknownToolIsRefusedInsteadOfGuessed` |
| 17 | A request is missing its amount | refused with `MISSING_AMOUNT`, not with the refusal code of the ladder it fell into | `UseCaseMatrixTest.aRequestMissingItsAmountIsRefusedInsteadOfDefaulted` |
| 18 | A negative amount is submitted | refused as `NEGATIVE_AMOUNT`: a negative amount is not a small amount | `UseCaseMatrixTest.aNegativeAmountIsRefusedAsANegativeAmount` |

## The money path

| # | Use case | What must happen | Proven by |
| --- | --- | --- | --- |
| 19 | Order, then invoice, then payment | each step is verified before the next is allowed | `UseCaseMatrixTest.orderInvoiceAndPaymentFormAVerifiedChain` |
| 20 | A payment is registered against an invoice that does not exist | `failed` with the reason, not a silent success | `UseCaseMatrixTest.aPaymentForAMissingInvoiceFailsWithTheReason` |
| 21 | An order is cancelled, then someone tries to invoice it | cancelling without a reason is refused; after cancelling, the invoice fails with `ORDER_NOT_FOUND_OR_CANCELLED` | `UseCaseMatrixTest.cancellingNeedsAReasonAndACancelledOrderCannotBeInvoiced` |
| 22 | An auditor reads the trail after all of that | the events are hash-linked, the chain verifies, and another tenant's trail is out of reach | `UseCaseMatrixTest.theAuditTrailIsHashLinkedAndScopedToTheTenant` |

## What the assistant understands

The interpreter is deterministic rules, not a model. That is a decision, and it
is why these cases can be asserted exactly. Twenty-three of them run in
`AgentUnderstandingTest`, and thirteen more run against the client the app
actually calls -- `AiClientUseCasesTest` -- which adds redaction, the injection
refusal, and the colloquial Arabic the rules do not cover:

- an order in English and in Arabic carries the same three things;
- a polite sentence still finds where the customer name ends, in both
  languages (`with an amount of`, `بقيمة`);
- Arabic-Indic digits are amounts: `١٢٠٠٠` and `12000` are the same number;
- thousands separators and decimals survive (`1,250.50`);
- an amount with no currency is a question, not a guess;
- a stock question names no tool keyword and is still a stock question
  (`Is SKU-CHAIR-99 available?`);
- `سجل سداد ... على الفاتورة` mentions the invoice and is a payment;
- the amount is the number next to the currency, not the year inside an
  invoice id (`سداد فاتورة INV-2026-9021 بمبلغ 850 دولار` pays 850);
- a cancel needs the order and the reason, and takes both from the sentence;
- an invoice without an order asks for one;
- a payment without an invoice asks for it;
- an injection is refused in both languages and never becomes a proposal,
  including one that arrives inside ERP text;
- a tool the ERP cannot perform is `Unsupported`, not `Ready`;
- a vague request asks a real question instead of inventing one;
- a summary keeps the period the person asked for (day, month, quarter, year).

## What is not verified here

- **No live model call.** The assistant's understanding is the deterministic
  interpreter above, and that is what is tested. `MizanAiIntegrationClient`
  also accepts an endpoint and an API key for a hosted model; no call to a
  hosted model was made from this environment, and none was reachable. The
  prompt that would be sent is asserted for size, token budget and content, not
  for the quality of a model's answer.
- **No device.** Nothing here was rendered, tapped, or timed on a phone. The
  Android app has not been assembled: this environment has no Android SDK.
- **No screen recording, no TalkBack pass, no frame budget measurement.**
