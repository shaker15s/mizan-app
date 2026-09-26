package app.mizan.domain.agent

/**
 * Numbers and money the way people actually write them in Arabic.
 *
 * ١٠ and ۱۰ and 10 are the same number. "ألف ونص" is 1500. "خمسة آلاف جنيه"
 * and "5000 EGP" and "٥,٠٠٠ ج.م" are the same amount. A parser that handles
 * only one of those spellings makes the product unusable for the people it is
 * built for, and a parser that guesses turns a wrong number into a wrong
 * order -- so every function here returns null rather than an approximation it
 * cannot justify.
 */
data class ParsedQuantity(
    val value: Long,
    val approximate: Boolean,
    val evidence: String,
)

/** How a person qualified the amount. It changes what they meant. */
enum class TaxNote {
    NET,
    INCLUSIVE,
    EXCLUSIVE,
    ;

    companion object {
        fun fromText(text: String): TaxNote? {
            val normalized = ArabicText.normalize(text)
            return when {
                normalized.contains("بدون الضريبه") || normalized.contains("قبل الضريبه") ||
                    normalized.contains("excl") || normalized.contains("before tax") -> EXCLUSIVE
                normalized.contains("بالضريبه") || normalized.contains("شامل الضريبه") ||
                    normalized.contains("مع الضريبه") || normalized.contains("incl") -> INCLUSIVE
                normalized.contains("صافي") || normalized.contains("الصافي") ||
                    normalized.contains("net") -> NET
                else -> null
            }
        }
    }
}

data class ParsedAmount(
    val value: Long,
    val currency: String?,
    val approximate: Boolean,
    val taxNote: TaxNote?,
    val evidence: String,
)

object ArabicQuantities {

    /** Written numbers, keyed by their normalised spelling. */
    private val WORD_NUMBERS: Map<String, Long> = buildMap {
        val pairs = listOf(
            "صفر" to 0L, "واحد" to 1L, "واحده" to 1L, "اثنين" to 2L, "اتنين" to 2L, "اثنتين" to 2L,
            "ثلاثه" to 3L, "تلاته" to 3L, "اربعه" to 4L, "خمسه" to 5L, "سته" to 6L,
            "سبعه" to 7L, "ثمانيه" to 8L, "تمانيه" to 8L, "تسعه" to 9L, "عشره" to 10L,
            "احد عشر" to 11L, "اتناشر" to 12L, "اثناشر" to 12L, "ثلاثه عشر" to 13L,
            "اربعه عشر" to 14L, "خمسه عشر" to 15L, "سته عشر" to 16L, "سبعه عشر" to 17L,
            "ثمانيه عشر" to 18L, "تسعه عشر" to 19L, "عشرين" to 20L, "ثلاثين" to 30L,
            "اربعين" to 40L, "خمسين" to 50L, "ستين" to 60L, "سبعين" to 70L,
            "ثمانين" to 80L, "تسعين" to 90L, "مائه" to 100L, "مايه" to 100L, "مئه" to 100L,
            "one" to 1L, "two" to 2L, "three" to 3L, "four" to 4L, "five" to 5L,
            "six" to 6L, "seven" to 7L, "eight" to 8L, "nine" to 9L, "ten" to 10L,
            "twenty" to 20L, "thirty" to 30L, "forty" to 40L, "fifty" to 50L,
            "sixty" to 60L, "seventy" to 70L, "eighty" to 80L, "ninety" to 90L,
            "hundred" to 100L,
        )
        for ((word, value) in pairs) put(ArabicText.normalize(word), value)
    }

    /** Multipliers. "ألفين" is a dual, not a multiplier with a missing number. */
    private val MULTIPLIERS: Map<String, Long> = buildMap {
        val pairs = listOf(
            "الف" to 1_000L, "الاف" to 1_000L, "الفين" to 2_000L, "ألفين" to 2_000L,
            "مليون" to 1_000_000L, "مليونين" to 2_000_000L, "ملايين" to 1_000_000L,
            "مليار" to 1_000_000_000L, "بليون" to 1_000_000_000L,
            "thousand" to 1_000L, "k" to 1_000L, "million" to 1_000_000L, "m" to 1_000_000L,
        )
        for ((word, value) in pairs) put(ArabicText.normalize(word), value)
    }

    private val APPROXIMATE_MARKERS = listOf(
        "حوالي", "حوالى", "تقريبا", "تقريبًا", "نحو", "approximately", "about", "roughly",
    )

    private val CURRENCY_WORDS: Map<String, String> = buildMap {
        val pairs = listOf(
            "جنيه" to "EGP", "جنية" to "EGP", "ج.م" to "EGP", "ج م" to "EGP", "egp" to "EGP",
            "دولار" to "USD", "دولارا" to "USD", "$" to "USD", "usd" to "USD",
            "يورو" to "EUR", "€" to "EUR", "eur" to "EUR",
            "ريال" to "SAR", "sar" to "SAR",
            "درهم" to "AED", "aed" to "AED",
            "دينار" to "KWD", "kwd" to "KWD",
            "جنيه استرليني" to "GBP", "gbp" to "GBP", "pound" to "GBP",
        )
        for ((word, code) in pairs) put(ArabicText.normalize(word), code)
    }

    private val DIGITS = Regex("\\d[\\d,]*(\\.\\d+)?")

    /** The currency a sentence names, or null when it names none. */
    fun currencyIn(text: String): String? {
        val normalized = ArabicText.normalize(text)
        for ((word, code) in CURRENCY_WORDS) {
            if (normalized.contains(word)) return code
        }
        return null
    }

    fun isApproximate(text: String): Boolean {
        val normalized = ArabicText.normalize(text)
        return APPROXIMATE_MARKERS.any { normalized.contains(ArabicText.normalize(it)) }
    }

    /**
     * Parses the amount a sentence states. Returns null when there is no
     * number, or when the number cannot be read without guessing.
     */
    fun parseAmount(text: String): ParsedAmount? {
        val normalized = ArabicText.normalizeDigits(text)
        val currency = currencyIn(text)
        val approximate = isApproximate(text)
        val taxNote = TaxNote.fromText(text)
        val quantity = parseQuantity(text) ?: return null
        return ParsedAmount(
            value = quantity.value,
            currency = currency,
            approximate = quantity.approximate || approximate,
            taxNote = taxNote,
            evidence = quantity.evidence,
        )
    }

    /**
     * Parses a quantity with an optional multiplier: "4500", "٤٥٠٠", "خمسة
     * آلاف", "ألف ونص". The "ونص" suffix adds half of the multiplier, which is
     * how "ألف ونص" becomes 1500 rather than 1000.5.
     */
    fun parseQuantity(text: String): ParsedQuantity? {
        val normalized = ArabicText.normalizeDigits(text)
        val lower = normalized.lowercase()
        val normalizedWords = ArabicText.normalize(text)
        val halves = normalizedWords.contains("ونص") || normalizedWords.contains("و نص") ||
            normalizedWords.contains("ونصف") || lower.contains("and a half")

        val digitMatch = DIGITS.find(normalized)
        if (digitMatch != null) {
            val raw = digitMatch.value.replace(",", "")
            val asLong = raw.toLongOrNull()
            val multiplier = multiplierNear(normalized, digitMatch.range.last)
            val base = asLong?.let { it * (multiplier ?: 1L) }
            if (base != null) {
                val withHalf = if (halves) base + (multiplier ?: 1L) / 2 else base
                return ParsedQuantity(withHalf, isApproximate(text), raw)
            }
        }

        val words = normalizedWords.split(' ').filter { it.isNotBlank() }
        var wordValue: Long? = null
        var multiplier: Long? = null
        for (word in words) {
            // "لعشرة حواسيب" is "for ten laptops": the preposition is glued to
            // the number in Arabic, so the number has to be read through it.
            val bare = word.removePrefix("ل")
            val number = WORD_NUMBERS[word] ?: WORD_NUMBERS[bare]
            if (number != null && wordValue == null) {
                wordValue = number
                continue
            }
            val scale = MULTIPLIERS[word] ?: MULTIPLIERS[bare]
            if (scale != null) {
                multiplier = scale
                break
            }
        }
        if (multiplier != null) {
            val base = if (multiplier > 1_000L && wordValue != null) wordValue * multiplier else multiplier
            val withHalf = if (halves) base + multiplier / 2 else base
            return ParsedQuantity(withHalf, isApproximate(text), "word+multiplier")
        }
        if (wordValue != null) return ParsedQuantity(wordValue, isApproximate(text), "word")
        return null
    }

    /** A multiplier that follows the digits ("10k", "٣ آلاف"). */
    private fun multiplierNear(text: String, afterIndex: Int): Long? {
        val tail = text.substring((afterIndex + 1).coerceAtMost(text.length)).trim()
        if (tail.isEmpty()) return null
        val first = tail.split(' ').firstOrNull() ?: return null
        val normalizedTail = ArabicText.normalize(first)
        for ((word, scale) in MULTIPLIERS) {
            if (normalizedTail == word || normalizedTail.startsWith(word)) return scale
        }
        return null
    }
}
