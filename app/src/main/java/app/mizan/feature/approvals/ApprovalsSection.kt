package app.mizan.feature.approvals

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import app.mizan.R
import app.mizan.design.component.MizanKeyValue
import app.mizan.design.component.MizanPrimaryButton
import app.mizan.design.component.MizanSecondaryButton
import app.mizan.design.component.ShapeCard
import app.mizan.design.component.mizanGlassPane
import app.mizan.design.token.Space
import app.mizan.design.theme.LocalMizanColors
import app.mizan.graph.AppGraph
import app.mizan.integration.api.ApprovalAction
import app.mizan.integration.api.ApprovalRow
import app.mizan.integration.api.ApprovalsBoard
import app.mizan.integration.api.ApprovalsState
import app.mizan.ui.standingLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The approvals that are waiting, read from the service that holds them.
 *
 * The governance screen described the rules, and the service enforced them.
 * This is the part in between: the queue of approvals that actually exist,
 * with the person, the level, the fingerprint and the expiry the service
 * reported, and the two answers a person can give.
 *
 * Three things it refuses to do, because each one is a way a screen lies:
 *
 *  - it never renders an unread queue as an empty one. Loading, "no service is
 *    configured", "the service answered something unreadable" and "nothing
 *    needs you" are four different sentences and it shows the right one;
 *  - it never decides. The service chose the level, the fingerprint and the
 *    expiry, and an answer is sent only for a row the service itself called
 *    pending;
 *  - it never grants without a proof. The device key signs the challenge the
 *    service issued for this approval, and a build with no key says so instead
 *    of offering a button that cannot finish.
 */
@Composable
fun ApprovalsSection(graph: AppGraph) {
    val colors = LocalMizanColors.current
    val scope = rememberCoroutineScope()
    val board = remember { graph.approvals }

    var state by remember { mutableStateOf<ApprovalsState>(ApprovalsState.Unread) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        state = withContext(Dispatchers.IO) { board.refresh() }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .mizanGlassPane(ShapeCard)
            .padding(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.approval_live_heading),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
            )
            if (busy) {
                Text(
                    text = stringResource(R.string.approval_working),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                )
            }
        }

        when (val current = state) {
            ApprovalsState.Unread -> Text(
                text = stringResource(R.string.approval_reading),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )

            ApprovalsState.Unconfigured -> Text(
                text = stringResource(R.string.approval_no_service),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )

            is ApprovalsState.Failed -> Text(
                // A code, not a sentence: the service's own message key, or
                // this client's when the answer was not readable at all.
                text = stringResource(R.string.approval_failed, current.code),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )

            is ApprovalsState.Loaded -> {
                if (current.rows.isEmpty()) {
                    Text(
                        text = if (current.filteredBy == null) {
                            stringResource(R.string.approval_none)
                        } else {
                            stringResource(R.string.approval_none_filtered)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                    )
                }
                current.rows.forEach { row ->
                    ApprovalCard(
                        board = board,
                        row = row,
                        busy = busy,
                        onAnswer = { answer ->
                            if (busy) return@ApprovalCard
                            busy = true
                            message = null
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { answer() }
                                message = when (result) {
                                    is ApprovalAction.Answered -> stateMessage(
                                        R.string.approval_recorded,
                                        result.approval.state.name,
                                    )
                                    is ApprovalAction.Refused -> stateMessage(
                                        R.string.approval_refused,
                                        result.code,
                                    )
                                }
                                state = withContext(Dispatchers.IO) { board.refresh() }
                                busy = false
                            }
                        },
                    )
                }
            }
        }

        message?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }

        MizanSecondaryButton(
            text = stringResource(R.string.approval_refresh),
            onClick = {
                if (busy) return@MizanSecondaryButton
                busy = true
                message = null
                scope.launch {
                    state = withContext(Dispatchers.IO) { board.refresh() }
                    busy = false
                }
            },
        )
    }
}

/**
 * One approval, with what the service said about it and nothing else.
 *
 * The card shows what a person needs to decide: who asked, the level the
 * policy demanded, the fingerprint they are signing, and how long is left. The
 * buttons appear only where an answer is possible, so the screen cannot offer
 * an approval to the wrong person and then explain a refusal.
 */
@Composable
private fun ApprovalCard(
    board: ApprovalsBoard,
    row: ApprovalRow,
    busy: Boolean,
    onAnswer: (suspend () -> ApprovalAction) -> Unit,
) {
    val colors = LocalMizanColors.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        MizanKeyValue(stringResource(R.string.approval_id_label), row.id, mono = true)
        // The initiator and the level are identifiers the service chose, not
        // prose: a screen that translated "L2_PRIVILEGED" would be inventing a
        // policy name the policy does not use.
        MizanKeyValue(
            stringResource(R.string.approval_requested_by),
            row.approval.initiatorId + " \u00b7 " + row.approval.requiredLevel.name,
        )
        MizanKeyValue(
            stringResource(R.string.approval_fingerprint),
            board.shortFingerprint(row),
            mono = true,
        )
        MizanKeyValue(stringResource(R.string.approval_expires), remaining(row))
        MizanKeyValue(stringResource(R.string.approval_standing), standingLabel(row.standing))

        if (row.standing == ApprovalRow.Standing.AWAITING_OTHERS && row.awaiting.isNotEmpty()) {
            MizanKeyValue(stringResource(R.string.approval_waiting_on), row.awaiting.joinToString(", "))
        }

        board.proofRequirement(row)?.let { missing ->
            Text(
                text = stringResource(R.string.approval_needs_device, missing),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }

        if (row.standing == ApprovalRow.Standing.ANSWERABLE) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                MizanPrimaryButton(
                    text = stringResource(R.string.approval_approve),
                    onClick = { if (!busy) onAnswer { board.grant(row.id) } },
                    enabled = !busy,
                )
                MizanSecondaryButton(
                    text = stringResource(R.string.approval_refuse),
                    onClick = { if (!busy) onAnswer { board.refuse(row.id, "REFUSED_BY_APPROVER") } },
                    enabled = !busy,
                )
            }
        }
    }
}

/**
 * How long is left, from the service's own expiry.
 *
 * It is a duration, not a sentence: the units come from the string resources
 * so both locales read naturally, and the number is what the service said
 * rather than a rounded reassurance.
 */
@Composable
private fun remaining(row: ApprovalRow): String {
    val millis = row.millisecondsToExpiry
    if (millis <= 0L) return stringResource(R.string.approval_expired_now)
    val minutes = millis / 60_000L
    // The units are resources too: "2h 15m" is a sentence in this language's
    // grammar, and the Arabic screen reads it differently.
    return when {
        minutes >= 60 -> stringResource(
            R.string.approval_remaining_hours_minutes,
            (minutes / 60).toInt(),
            (minutes % 60).toInt(),
        )
        minutes >= 1 -> stringResource(R.string.approval_remaining_minutes, minutes.toInt())
        else -> stringResource(R.string.approval_remaining_seconds, (millis / 1000).toInt())
    }
}

@Composable
private fun stateMessage(@StringRes resId: Int, value: String): String = stringResource(resId, value)
