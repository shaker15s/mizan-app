package app.mizan.domain.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/**
 * Money in minor units. Never a bare Double, never an implied currency.
 * ISO-4217 alphabetic codes only. "XXX" is the ISO code for no currency and
 * is used for legacy rows whose currency was not stored.
 */
data class Money(
    val minorUnits: Long,
    val currency: String,
) : Comparable<Money> {
    init {
        require(currency.length == 3 && currency.all { it.isLetter() }) {
            "currency must be an ISO-4217 alphabetic code"
        }
        require(currency == currency.uppercase()) { "currency must be uppercase" }
    }

    operator fun plus(other: Money): Money {
        requireSameCurrency(other)
        return copy(minorUnits = minorUnits + other.minorUnits)
    }

    override fun compareTo(other: Money): Int {
        requireSameCurrency(other)
        return minorUnits.compareTo(other.minorUnits)
    }

    private fun requireSameCurrency(other: Money) {
        require(currency == other.currency) {
            "cannot combine $currency with ${other.currency}"
        }
    }

    fun format(locale: Locale): String {
        val major = BigDecimal.valueOf(minorUnits, 2)
        val number = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
            roundingMode = RoundingMode.UNNECESSARY
        }
        return number.format(major) + "\u00A0" + currency
    }

    companion object {
        fun parseMajor(raw: String, currency: String): Money? {
            val normalized = raw.trim().replace(",", "").replace(" ", "")
            if (normalized.isEmpty()) return null
            val decimal = normalized.toBigDecimalOrNull() ?: return null
            if (decimal.scale() > 2) return null
            val minor = decimal.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY)
            return Money(minor.longValueExact(), currency.uppercase())
        }
    }
}
