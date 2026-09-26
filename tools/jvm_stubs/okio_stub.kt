// Test-only stand-in for okio.Buffer.
//
// :integration uses exactly one okio type, and only to read back the body of a
// request it built. This file lets `tools/jvm_check.py` compile and run the
// module's tests on a plain JVM when the artifact cannot be downloaded.
//
// It is deliberately not in any Gradle source set: the real dependency is on
// the test classpath there, and having both would be a duplicate class.
package okio

class Buffer {
    private val contents = StringBuilder()

    fun writeUtf8(value: String) {
        contents.append(value)
    }

    fun readUtf8(): String = contents.toString()

    fun size(): Long = contents.length.toLong()

    override fun toString(): String = contents.toString()
}
