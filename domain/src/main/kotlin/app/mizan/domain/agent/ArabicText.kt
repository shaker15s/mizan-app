package app.mizan.domain.agent

/**
 * Arabic normalisation, shared by entity resolution and the interpreter.
 *
 * Two names that a person reads as the same name are the same name here:
 * "شركة النور للتجارة" and "شركه النور للتجاره" and "ٱلنّور" all normalise to
 * one key. That is what lets a search find the customer without the model
 * inventing a match.
 */
object ArabicText {

    /** Arabic-Indic and extended Arabic-Indic digits, plus the Arabic decimal separator. */
    private val DIGIT_MAP = mapOf(
        '\u0660' to '0', '\u0661' to '1', '\u0662' to '2', '\u0663' to '3', '\u0664' to '4',
        '\u0665' to '5', '\u0666' to '6', '\u0667' to '7', '\u0668' to '8', '\u0669' to '9',
        '\u06F0' to '0', '\u06F1' to '1', '\u06F2' to '2', '\u06F3' to '3', '\u06F4' to '4',
        '\u06F5' to '5', '\u06F6' to '6', '\u06F7' to '7', '\u06F8' to '8', '\u06F9' to '9',
    )

    private val DIACRITICS = Regex("[\u064B-\u065F\u0670\u06D6-\u06ED]")
    private val TATWEEL = Regex("\u0640")

    private val COMPANY_WORDS = setOf(
        "شركة", "شركه", "مؤسسة", "مؤسسه", "موسسة", "مجموعة", "مجموعه",
        "مصنع", "متجر", "مخازن", "معرض", "مكتب", "بنك",
        "company", "co", "co.", "ltd", "ltd.", "llc", "inc", "inc.", "corp", "gmbh",
    )

    /** Digits to ASCII, so one parser handles ١٠ and 10 and ۱۰. */
    fun normalizeDigits(text: String): String = buildString(text.length) {
        for (ch in text) append(DIGIT_MAP[ch] ?: ch)
    }

    /**
     * Case folds Latin, strips diacritics and tatweel, unifies the letter
     * forms that readers treat as one, and collapses spaces.
     */
    fun normalize(text: String): String {
        val digits = normalizeDigits(text)
        val stripped = DIACRITICS.replace(TATWEEL.replace(digits, ""), "")
        val unified = buildString(stripped.length) {
            for (ch in stripped) {
                append(
                    when (ch) {
                        '\u0623', '\u0625', '\u0622', '\u0671' -> '\u0627' // أ إ آ ٱ -> ا
                        '\u0649' -> '\u064A' // ى -> ي
                        '\u0626' -> '\u064A' // ئ -> ي
                        '\u0624' -> '\u0648' // ؤ -> و
                        '\u0629' -> '\u0647' // ة -> ه
                        '\u06A9' -> '\u0643' // ک -> ك
                        '\u06CC' -> '\u064A' // ی -> ي
                        else -> ch
                    },
                )
            }
        }
        return unified.lowercase().replace(Regex("\\s+"), " ").trim()
    }

    /**
     * The key two versions of a customer's name share. Company-type words are
     * dropped because "شركة النور" and "النور" are the same customer to a
     * reader and different strings to a database.
     */
    fun entityKey(text: String): String {
        val normalized = normalize(text)
        val words = normalized.split(' ').filter { it.isNotEmpty() && it !in COMPANY_WORDS }
        // Never reduce a name to nothing: a customer literally named "شركة"
        // keeps its name rather than becoming an empty key.
        return if (words.isEmpty()) normalized else words.joinToString(" ")
    }

    /** Whole-word containment that respects Arabic word boundaries. */
    fun containsWord(haystack: String, needle: String): Boolean {
        if (needle.isBlank()) return false
        val words = haystack.split(' ').toMutableList()
        for (index in words.indices) {
            // A definite article is a prefix, not a word: "النور" contains "نور".
            words[index] = words[index].removePrefix("ال")
        }
        return words.contains(needle) || haystack.contains(" $needle ") ||
            haystack.startsWith("$needle ") || haystack.endsWith(" $needle")
    }
}
