package app.mizan.service.authority

import app.mizan.domain.execution.ExecutionJournal
import app.mizan.domain.execution.JournalEvent
import app.mizan.domain.model.ExecutionId
import app.mizan.domain.model.Money
import app.mizan.domain.model.ReconciliationCase
import app.mizan.domain.model.ReconciliationStatus
import app.mizan.domain.model.TenantId
import app.mizan.domain.model.ToolName
import app.mizan.domain.receipt.ReceiptClaims
import app.mizan.domain.receipt.ReceiptSigner
import app.mizan.domain.receipt.VerificationFingerprint
import app.mizan.domain.tool.ToolDefinition
import app.mizan.service.erp.ErpConnector
import app.mizan.service.erp.ErpRecord
import app.mizan.service.erp.ErpResult
import app.mizan.service.json.text
import app.mizan.service.protocol.ExecutionOutcome
import app.mizan.service.protocol.ExecutionRequest
import app.mizan.service.protocol.MizanContract
import app.mizan.service.store.ServiceStores

/**
 * The part of the authority that touches the ERP.
 *
 * It is separated from the part that *decides* because the two have different
 * failure modes. Deciding is pure and can be re-read from the journal; this
 * class is the only place where a write actually happens, and every branch
 * below exists to answer one question honestly: did the ERP do it, or not, or
 * do we not know?
 *
 * The three rules it implements:
 *
 *  - a write is verified only after a *separate* read whose fields are compared
 *    with what the request promised;
 *  - a write whose answer was lost is ambiguous, never a failure and never a
 *    retry, and it opens a reconciliation case with the candidates a person
 *    needs;
 *  - a read is dispatched and accepted, and it never claims verification,
 *    because there is nothing to verify.
 */
internal class ExecutionPipeline(
    private val connector: ErpConnector,
    private val stores: ServiceStores?,
    private val signer: ReceiptSigner?,
    private val clock: () -> Long,
    private val book: JournalBook,
    /**
     * Told about a read the ERP could not answer, so the question is asked
     * again later instead of being dropped.
     *
     * Reads only. A write that failed uncertainly is ambiguous and belongs to
     * reconciliation; queueing it would be a duplicate-record generator.
     */
    private val retryHook: RetryHook? = null,
) {

    /**
     * Remembers that a read should be attempted again.
     *
     * It is a one-method interface rather than a queue because the pipeline
     * must not know how the retry is stored, and because a test can assert
     * that the hook was called without standing up a store.
     */
    fun interface RetryHook {
        fun schedule(request: ExecutionRequest, actorId: String, errorCode: String, delayMillis: Long)
    }

    fun execute(
        request: ExecutionRequest,
        tool: ToolName,
        definition: ToolDefinition?,
        simulateAmbiguous: Boolean,
        journal: ExecutionJournal,
    ): ExecutionOutcome = when (tool) {
        ToolName.STOCK_AVAILABILITY -> {
            val sku = request.arguments.text(MizanContract.ArgumentField.SKU)
                ?: return Outcomes.rejected(request, "MISSING_SKU", 422)
            readThrough(
                request = request,
                journal = journal,
                call = { connector.checkStock(request.tenantId, sku) },
                describe = {
                    ReadSummary(
                        recordId = it.sku,
                        model = MizanContract.ErpModel.STOCK,
                        summary = "available=${it.availableQty} reserved=${it.reservedQty}",
                    )
                },
            )
        }

        ToolName.CUSTOMER_SEARCH -> {
            val query = request.arguments.text(MizanContract.ArgumentField.QUERY)
                ?: return Outcomes.rejected(request, "MISSING_QUERY", 422)
            readThrough(
                request = request,
                journal = journal,
                call = { connector.findCustomer(request.tenantId, query) },
                describe = { matches ->
                    ReadSummary(
                        recordId = matches.firstOrNull()?.recordId ?: "none",
                        model = MizanContract.ErpModel.CUSTOMER,
                        summary = "matches=${matches.size}",
                    )
                },
            )
        }

        ToolName.SALES_SUMMARY -> {
            val period = request.arguments.text(MizanContract.ArgumentField.PERIOD) ?: "current"
            readThrough(
                request = request,
                journal = journal,
                call = { connector.salesSummary(request.tenantId, period) },
                describe = { ReadSummary(period, MizanContract.ErpModel.ANALYTICS, it) },
            )
        }

        ToolName.CREATE_DRAFT_ORDER -> {
            val name = request.arguments.text(MizanContract.ArgumentField.CUSTOMER_NAME)
                ?: return Outcomes.rejected(request, "MISSING_CUSTOMER", 422)
            val amount = request.moneyOrNull() ?: return Outcomes.rejected(request, "MISSING_AMOUNT", 422)
            val items = request.arguments.text(MizanContract.ArgumentField.ITEMS_SUMMARY)
                ?: return Outcomes.rejected(request, "MISSING_ITEMS", 422)
            val dispatching = book.advance(journal, JournalEvent.DISPATCH_STARTED)
            val write = connector.createDraftOrder(request.tenantId, name, amount, items)
            finish(request, write, dispatching, simulateAmbiguous, expectedFields = mapOf(
                "amountMinor" to amount.minorUnits.toString(),
                "currency" to amount.currency,
            ))
        }

        ToolName.CANCEL_ORDER -> {
            val orderId = request.arguments.text(MizanContract.ArgumentField.ORDER_ID)
                ?: return Outcomes.rejected(request, "MISSING_ORDER_ID", 422)
            val reason = request.arguments.text(MizanContract.ArgumentField.REASON)
                ?: return Outcomes.rejected(request, "MISSING_REASON", 422)
            val dispatching = book.advance(journal, JournalEvent.DISPATCH_STARTED)
            finish(
                request,
                connector.cancelOrder(request.tenantId, orderId, reason),
                dispatching,
                simulateAmbiguous,
                expectedFields = emptyMap(),
            )
        }

        ToolName.CREATE_INVOICE -> {
            val orderId = request.arguments.text(MizanContract.ArgumentField.ORDER_ID)
                ?: return Outcomes.rejected(request, "MISSING_ORDER_ID", 422)
            val dispatching = book.advance(journal, JournalEvent.DISPATCH_STARTED)
            finish(
                request,
                connector.createInvoice(request.tenantId, orderId),
                dispatching,
                simulateAmbiguous,
                expectedFields = emptyMap(),
            )
        }

        ToolName.REGISTER_PAYMENT -> {
            val invoiceId = request.arguments.text(MizanContract.ArgumentField.INVOICE_ID)
                ?: return Outcomes.rejected(request, "MISSING_INVOICE_ID", 422)
            val amount = request.moneyOrNull() ?: return Outcomes.rejected(request, "MISSING_AMOUNT", 422)
            val dispatching = book.advance(journal, JournalEvent.DISPATCH_STARTED)
            finish(
                request,
                connector.registerPayment(request.tenantId, invoiceId, amount),
                dispatching,
                simulateAmbiguous,
                expectedFields = mapOf(
                    "amountMinor" to amount.minorUnits.toString(),
                    "currency" to amount.currency,
                ),
            )
        }

        ToolName.UNKNOWN -> Outcomes.rejected(request, "TOOL_UNKNOWN", 422)
    }

    fun finish(
        request: ExecutionRequest,
        result: ErpResult<app.mizan.service.erp.ErpRecord>,
        journal: ExecutionJournal,
        simulateAmbiguous: Boolean,
        expectedFields: Map<String, String>,
    ): ExecutionOutcome {
        val write = when (result) {
            is ErpResult.Ok -> result.value
            is ErpResult.Refused -> return failed(request, writeFailureCode(result.reasonCode), journal)
            is ErpResult.NotSupported -> return failed(request, "TOOL_NOT_SUPPORTED_BY_ERP", journal)
            is ErpResult.Unavailable -> return failed(request, result.reasonCode, journal)
            is ErpResult.Malformed -> return ambiguous(request, journal, result.reasonCode, emptyList())
            is ErpResult.Unknown -> return ambiguous(request, journal, result.reasonCode, emptyList())
        }

        val accepted = book.advance(journal, JournalEvent.DISPATCH_ACCEPTED)
        if (simulateAmbiguous) {
            // The write is already applied. That is the dangerous case this
            // status exists for: the ERP may hold the record and the caller
            // does not know. It opens reconciliation; it does not retry.
            return ambiguous(
                request,
                accepted,
                "SERVICE_AMBIGUOUS",
                connector.candidates(request.tenantId, write.model, 5),
                recordId = write.recordId,
                model = write.model,
            )
        }
        val verifying = book.advance(accepted, JournalEvent.VERIFICATION_STARTED)
        return when (val read = connector.readBack(request.tenantId, write.model, write.recordId)) {
            is ErpResult.Ok -> {
                val comparison = VerificationFingerprint.compare(expectedFields, read.value.fields)
                when {
                    // The record exists and the fields the request promised
                    // are present and equal: this is the only verified path.
                    comparison.mismatched.isEmpty() && comparison.missing.isEmpty() -> verified(
                        request = request,
                        journal = verifying,
                        write = write,
                        read = read.value,
                        verifiedFields = comparison.matched.ifEmpty { read.value.fields.keys.sorted() },
                    )
                    // A record came back that disagrees with what was asked
                    // for. That is not success, and it is not nothing either.
                    else -> ambiguous(
                        request,
                        verifying,
                        "VERIFICATION_FIELD_MISMATCH",
                        listOf(write.recordId),
                        recordId = write.recordId,
                        model = write.model,
                    )
                }
            }
            is ErpResult.Refused -> failedWithRecord(request, verifying, write, "ACCEPTED_NOT_VERIFIED")
            else -> Outcomes.accepted(
                request,
                "ACCEPTED_NOT_VERIFIED",
                write.recordId,
                write.model,
                "the write was not read back",
            )
        }
    }

    fun verified(
        request: ExecutionRequest,
        journal: ExecutionJournal,
        write: app.mizan.service.erp.ErpRecord,
        read: app.mizan.service.erp.ErpRecord,
        verifiedFields: List<String>,
    ): ExecutionOutcome {
        val verifiedJournal = book.advance(journal, JournalEvent.VERIFIED)
        val stored = verifiedJournal.copy(
            erpModel = write.model,
            erpRecordId = write.recordId,
        )
        stores?.journals?.save(stored)

        val receipt = signer?.let { signing ->
            val claims = ReceiptClaims(
                receiptId = "RCT-" + request.executionId.removePrefix("EXE-"),
                executionId = request.executionId,
                tenantId = request.tenantId,
                actorId = stored.actorId.value,
                approverIds = listOfNotNull(request.approverId),
                proposalFingerprint = stored.proposalFingerprint,
                tool = stored.tool.wire,
                toolVersion = stored.toolVersion,
                catalogVersion = stored.catalogVersion,
                policyVersionId = stored.policyVersionId,
                policyHash = stored.policyHash,
                approvalLevel = stored.approvalLevel,
                inputHash = stored.canonicalInputHash,
                erpModel = write.model,
                erpRecordId = write.recordId,
                verificationHash = VerificationFingerprint.of(write.model, write.recordId, read.fields),
                verifiedFields = verifiedFields,
                issuedAtMillis = clock(),
                traceId = request.traceId,
            )
            signing.sign(claims)
        }
        if (receipt != null) stores?.receipts?.save(receipt)

        return ExecutionOutcome.Verified(
            executionId = request.executionId,
            erpRecordId = write.recordId,
            erpModel = write.model,
            summary = read.summary.ifBlank { write.fields.entries.joinToString(" ") { "${it.key}=${it.value}" } },
            receiptId = receipt?.claims?.receiptId,
            receiptSignature = receipt?.signature,
            receiptKeyId = receipt?.keyId,
            verifiedFields = verifiedFields,
        )
    }

    fun ambiguous(
        request: ExecutionRequest,
        journal: ExecutionJournal,
        reasonCode: String,
        candidates: List<String>,
        recordId: String? = null,
        model: String? = null,
    ): ExecutionOutcome {
        // REQUIRE_RECONCILIATION is legal from DISPATCHING, ACCEPTED and
        // VERIFYING alike, so an uncertain write reaches the same stage
        // whichever earlier step discovered it.
        val moved = book.advance(journal, JournalEvent.REQUIRE_RECONCILIATION)
        val stored = moved.copy(
            candidateIds = candidates,
            erpModel = model ?: moved.erpModel,
            erpRecordId = recordId ?: moved.erpRecordId,
            errorCode = reasonCode,
        )
        stores?.journals?.save(stored)
        stores?.reconciliations?.upsert(
            ReconciliationCase(
                id = "REC-" + request.executionId.removePrefix("EXE-"),
                executionId = ExecutionId(request.executionId),
                traceId = app.mizan.domain.model.TraceId(request.traceId),
                tenantId = TenantId(request.tenantId),
                tool = ToolName.fromWire(request.toolWire) ?: ToolName.UNKNOWN,
                intent = request.toolWire,
                idempotencyKey = stored.idempotencyKey,
                candidateRecordIds = candidates,
                status = ReconciliationStatus.OPEN,
                notes = reasonCode,
                openedAt = book.now(),
            ),
        )
        return ExecutionOutcome.Ambiguous(
            executionId = request.executionId,
            candidateRecordIds = candidates,
            reasonCode = reasonCode,
            possibleRecordId = recordId,
            possibleModel = model,
        )
    }

    /** Nothing was written and the ERP said why. */
    fun failed(
        request: ExecutionRequest,
        code: String,
        journal: ExecutionJournal,
    ): ExecutionOutcome {
        stores?.journals?.save(book.advance(journal, JournalEvent.FAILED).copy(errorCode = code))
        return Outcomes.failed(request, code)
    }

    fun failedWithRecord(
        request: ExecutionRequest,
        journal: ExecutionJournal,
        write: app.mizan.service.erp.ErpRecord,
        messageCode: String,
    ): ExecutionOutcome {
        val stored = journal.copy(erpModel = write.model, erpRecordId = write.recordId)
        stores?.journals?.save(stored)
        return Outcomes.accepted(request, messageCode, write.recordId, write.model, "the record was written and not read back")
    }

    data class ReadSummary(val recordId: String, val model: String, val summary: String)

    /**
     * A read is still a call to the ERP, so it is still dispatched as far as
     * the journal is concerned: a person reading the history must be able to
     * see that the device asked and what came back. What a read never does is
     * claim verification -- it is accepted, which is exactly what it is.
     */
    fun <T> readThrough(
        request: ExecutionRequest,
        journal: ExecutionJournal,
        call: () -> ErpResult<T>,
        describe: (T) -> ReadSummary,
    ): ExecutionOutcome {
        val dispatching = book.advance(journal, JournalEvent.DISPATCH_STARTED)
        val result = call()
        return when (result) {
            is ErpResult.Ok -> {
                val summary = describe(result.value)
                val accepted = book.advance(dispatching, JournalEvent.DISPATCH_ACCEPTED)
                stores?.journals?.save(
                    accepted.copy(erpModel = summary.model, erpRecordId = summary.recordId),
                )
                Outcomes.accepted(request, "READ_RESULT", summary.recordId, summary.model, summary.summary)
            }
            // The ERP answered, and the answer was "no such record". That is
            // a failed read, not an empty result: a client that renders it as
            // "zero available" would be inventing stock levels.
            is ErpResult.Refused -> failed(request, result.reasonCode, dispatching)
            is ErpResult.NotSupported -> failed(request, "TOOL_NOT_SUPPORTED_BY_ERP", dispatching)
            is ErpResult.Unavailable -> {
                // The ERP is down or rate-limiting us. Nothing was read, and
                // nothing was written: ask again later rather than leave the
                // person with an empty answer they might mistake for zero.
                retryHook?.schedule(
                    request = request,
                    actorId = journal.actorId.value,
                    errorCode = result.reasonCode,
                    delayMillis = result.retryAfterMillis ?: 0L,
                )
                failed(request, result.reasonCode, dispatching)
            }
            is ErpResult.Malformed -> failed(request, result.reasonCode, dispatching)
            is ErpResult.Unknown -> failed(request, "ERP_UNKNOWN_ANSWER", dispatching)
        }
    }

    fun writeFailureCode(reasonCode: String): String = when (reasonCode) {
        "ORDER_NOT_FOUND" -> "ORDER_NOT_FOUND"
        "ORDER_NOT_FOUND_OR_CANCELLED" -> "ORDER_NOT_FOUND_OR_CANCELLED"
        "PAYMENT_REFUSED" -> "PAYMENT_REFUSED"
        else -> reasonCode
    }
}
