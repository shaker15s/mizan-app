package app.mizan.service.authority

import app.mizan.domain.model.Money
import app.mizan.service.json.text
import app.mizan.service.json.wholeOrNumericText
import app.mizan.service.protocol.ExecutionRequest
import app.mizan.service.protocol.MizanContract

/**
 * The amount a request names, read one way in every code path.
 *
 * The wire carries money as a decimal string; anything that is not a number in
 * that position is not zero, it is absent, and the two must never be confused
 * because the confusion decides an approval level.
 */
internal fun ExecutionRequest.moneyOrNull(): Money? {
    val minor = arguments.wholeOrNumericText(MizanContract.ArgumentField.AMOUNT_MINOR) ?: return null
    val currency = arguments.text(MizanContract.ArgumentField.CURRENCY) ?: return null
    return runCatching { Money(minor, currency) }.getOrNull()
}

/** The amount in minor units, without asserting a currency. */
internal fun ExecutionRequest.amountMinorOrNull(): Long? =
    arguments.wholeOrNumericText(MizanContract.ArgumentField.AMOUNT_MINOR)
