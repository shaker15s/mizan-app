package app.mizan.domain.security

/**
 * A secret that does not print itself.
 *
 * ERP API keys, receipt signing secrets and device enrolment tokens are held
 * in a char array with no `toString` that reveals them, so a log line, an
 * exception message or a debugger view of a data class cannot leak the value.
 * The array can be wiped when the secret is no longer needed.
 */
class SecretMaterial private constructor(private val chars: CharArray) {

    val length: Int get() = chars.size

    val isBlank: Boolean get() = chars.isEmpty()

    fun reveal(): String = String(chars)

    /** Overwrites the buffer. After this the secret is unusable, by design. */
    fun clear() {
        chars.fill('\u0000')
    }

    override fun toString(): String = "SecretMaterial(redacted)"

    override fun equals(other: Any?): Boolean =
        other is SecretMaterial && other.chars.contentEquals(chars)

    override fun hashCode(): Int = chars.contentHashCode()

    companion object {
        fun of(value: String): SecretMaterial = SecretMaterial(value.toCharArray())

        fun empty(): SecretMaterial = SecretMaterial(CharArray(0))
    }
}
