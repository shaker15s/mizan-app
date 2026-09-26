package app.mizan.service.store

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32

/**
 * An append-only log that survives a process death.
 *
 * The plan asks for a durable journal, a durable idempotency index and durable
 * sessions. This is the file layer all of them sit on, and it makes one
 * promise: a record that [append] returned from is on disk, and a record that
 * was half-written when the process died is discarded rather than believed.
 *
 * Frame format:
 *
 * ```text
 * MIZANLOG1\n
 * <crc32-hex> <payload-length-hex>\n
 * <payload bytes>\n
 * ```
 *
 * The CRC covers the payload. On open, frames are read in order and the first
 * frame whose length or CRC does not hold ends the readable region, and the
 * file is truncated there. That is the difference between recovering and
 * guessing: a torn tail is dropped and nothing after it is interpreted.
 *
 * [append] forces the bytes to the device before returning, so a machine that
 * loses power after a write still has the write. Replacing this with
 * PostgreSQL keeps the same callers; the file log is what makes the durability
 * claim testable on a machine with no database.
 */
class DurableLog(
    private val path: Path,
    private val forceOnAppend: Boolean = true,
) : AutoCloseable {

    private var file: RandomAccessFile
    private var channel: FileChannel
    private val records = ArrayList<String>()
    private var readableBytes: Long = 0

    init {
        path.parent?.let { Files.createDirectories(it) }
        file = RandomAccessFile(path.toFile(), "rw")
        channel = file.channel
        recover()
    }

    /** Number of records that survived recovery. */
    val count: Int get() = records.size

    val sizeBytes: Long get() = readableBytes

    @Synchronized
    fun append(record: String): Int {
        val payload = record.toByteArray(StandardCharsets.UTF_8)
        val header = "%08x %08x\n".format(crc32(payload), payload.size)
        val frame = ByteBuffer.allocate(header.length + payload.size + 1)
        frame.put(header.toByteArray(StandardCharsets.US_ASCII))
        frame.put(payload)
        frame.put('\n'.code.toByte())
        frame.flip()
        channel.position(readableBytes)
        while (frame.hasRemaining()) channel.write(frame)
        if (forceOnAppend) channel.force(true)
        readableBytes += (header.length + payload.size + 1)
        records += record
        return records.size - 1
    }

    @Synchronized
    fun records(): List<String> = ArrayList(records)

    @Synchronized
    fun get(index: Int): String? = records.getOrNull(index)

    /**
     * Replaces the file with a snapshot of [state]. The snapshot is written to
     * a temporary file, forced, and renamed into place: a crash during
     * compaction leaves the old log intact rather than a half-written one.
     */
    @Synchronized
    fun compact(state: List<String>) {
        val temporary = path.resolveSibling(path.fileName.toString() + ".compact")
        RandomAccessFile(temporary.toFile(), "rw").use { handle ->
            handle.setLength(0)
            handle.write(MAGIC.toByteArray(StandardCharsets.US_ASCII))
            for (record in state) {
                val payload = record.toByteArray(StandardCharsets.UTF_8)
                val header = "%08x %08x\n".format(crc32(payload), payload.size)
                handle.write(header.toByteArray(StandardCharsets.US_ASCII))
                handle.write(payload)
                handle.write('\n'.code)
            }
            handle.fd.sync()
        }
        channel.close()
        file.close()
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        file = RandomAccessFile(path.toFile(), "rw")
        channel = file.channel
        records.clear()
        readableBytes = 0
        recover()
    }

    private fun recover() {
        val bytes = Files.readAllBytes(path)
        if (bytes.isEmpty()) {
            writeMagic()
            return
        }
        val magic = MAGIC.toByteArray(StandardCharsets.US_ASCII)
        if (bytes.size < magic.size || !bytes.copyOfRange(0, magic.size).contentEquals(magic)) {
            // A file that is not ours must not be silently overwritten.
            throw IllegalStateException("$path is not a Mizan log")
        }
        var position = magic.size
        var good = position.toLong()
        while (position < bytes.size) {
            val newline = indexOf(bytes, '\n'.code.toByte(), position)
            if (newline < 0) break
            val header = String(bytes, position, newline - position, StandardCharsets.US_ASCII)
            val parts = header.trim().split(' ')
            if (parts.size != 2) break
            val expectedCrc = parts[0].toLongOrNull(16) ?: break
            val length = parts[1].toIntOrNull(16) ?: break
            val payloadStart = newline + 1
            val payloadEnd = payloadStart + length
            if (payloadEnd >= bytes.size || bytes[payloadEnd] != '\n'.code.toByte()) break
            val payload = bytes.copyOfRange(payloadStart, payloadEnd)
            if (crc32(payload) != expectedCrc) break
            records += String(payload, StandardCharsets.UTF_8)
            position = payloadEnd + 1
            good = position.toLong()
        }
        readableBytes = good
        if (good < bytes.size) {
            // Keep the good prefix and make the file match it, so the next
            // append cannot resurrect the bad bytes.
            channel.truncate(good)
            channel.force(true)
        }
        channel.position(good)
    }

    private fun writeMagic() {
        val magic = MAGIC.toByteArray(StandardCharsets.US_ASCII)
        channel.write(ByteBuffer.wrap(magic))
        channel.force(true)
        readableBytes = magic.size.toLong()
        channel.position(readableBytes)
    }

    private fun indexOf(bytes: ByteArray, needle: Byte, from: Int): Int {
        var index = from
        while (index < bytes.size) {
            if (bytes[index] == needle) return index
            index++
        }
        return -1
    }

    private fun crc32(payload: ByteArray): Long = CRC32().apply { update(payload) }.value

    override fun close() {
        runCatching { channel.close() }
        runCatching { file.close() }
    }

    companion object {
        const val MAGIC = "MIZANLOG1\n"
    }
}
