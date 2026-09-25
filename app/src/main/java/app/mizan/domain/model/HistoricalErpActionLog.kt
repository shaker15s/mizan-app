package app.mizan.domain.model

import app.mizan.domain.execution.ExecutionPhase
import java.time.Instant

/**
 * Immutable read-only model representing a historical ERP action log item.
 * Explicitly binds the executed command to its transaction status, timestamp,
 * and cryptographic verification hash.
 */
data class HistoricalErpActionLog(
    val commandId: String,
    val traceId: String,
    val tool: ToolName,
    val intent: String,
    val phase: ExecutionPhase,
    val timestamp: Instant,
    val formattedTimestamp: String,
    val relativeTime: String,
    val verificationHash: String,
    val shortHash: String,
    val previousHash: String?,
    val erpRecordId: String?,
    val actorName: String,
    val actorRole: String,
    val canonicalPayload: String,
    val isSimulation: Boolean,
)
