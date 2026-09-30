package com.financetracker.data.backup

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * How a backup is turned into the bytes that sit in Drive, and back.
 *
 * A snapshot is JSON, and the JSON is the thing worth keeping: it is a logical export, so it
 * outlives a schema change, and a person can open it and see what is in it. Compression is
 * applied outside that, so the shape of the data is unchanged and the file is still a backup
 * rather than an archive.
 *
 * ## Why gzip
 *
 * One file, so gzip rather than zip: there is no second entry for a container to describe and
 * no dependency to add. It is in the JDK and it is a single stream.
 *
 * ## Why it is worth it
 *
 * A few years of a few banks is a couple of megabytes of JSON, and the exporter runs on every
 * change the user makes. At that size an uncompressed upload is tens of megabytes of mobile
 * data a day, spent recording that a purchase was added. The JSON is highly repetitive —
 * every row repeats the same key names, and the bank-assigned `externalId` is a SHA-256 per
 * row — which is exactly the shape gzip is good at, and it takes a multi-year file from
 * megabytes to a couple of hundred kilobytes.
 *
 * ## What is given up
 *
 * A gzipped file cannot be read in a text editor. That is a real loss and the reason the
 * uncompressed form is still what [BackupSnapshotCodec] produces and what every test asserts
 * on: the export stays inspectable in tests and through [decode] even though what reaches
 * Drive is compressed.
 *
 * ## What reading accepts
 *
 * Both. Sniffing rather than assuming is not optional: a user who upgrades the app already
 * has an uncompressed backup in Drive, and a restore that only understood the new form would
 * report their own backup as corrupt. See [isGzip].
 */
object BackupEncoding {

    /**
     * Decompressed size past which a file is refused.
     *
     * A legitimate snapshot is a couple of megabytes; a decade of heavy use is not ten times
     * that. Everything above it is a file whose contents are not a backup, and the file being
     * decoded is one the user picked out of a Drive folder that anybody with access to it
     * could have written. Unbounded expansion is the one thing a compressed file can do that
     * a JSON file cannot, so the bound belongs here rather than in the caller's hope.
     */
    const val MAX_DECOMPRESSED_BYTES = 64L * 1024 * 1024

    private const val GZIP_MAGIC_1 = 0x1f
    private const val GZIP_MAGIC_2 = 0x8b
    private const val CHUNK = 8 * 1024

    /**
     * Whether [bytes] are gzip rather than the plain JSON of an older backup.
     *
     * The first two bytes of a gzip stream are fixed by the format, which is what makes this
     * possible at all: there is no filename or extension to go on, because the user may have
     * renamed the file or picked it from somewhere the extension means nothing.
     */
    fun isGzip(bytes: ByteArray): Boolean =
        bytes.size >= 2 &&
            bytes[0] == GZIP_MAGIC_1.toByte() &&
            bytes[1] == GZIP_MAGIC_2.toByte()

    fun compress(text: String): ByteArray {
        val out = ByteArrayOutputStream(text.length / 4)
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    /**
     * The JSON in [bytes], whether it arrived compressed or not.
     *
     * @throws UnreadableBackupException if the bytes are gzip but do not decode within
     *   [limit] — a truncated or hostile stream, and not something a caller should be
     *   left to discover as an [OutOfMemoryError].
     */
    fun decode(bytes: ByteArray, limit: Long = MAX_DECOMPRESSED_BYTES): String =
        if (isGzip(bytes)) inflate(bytes, limit) else String(bytes, Charsets.UTF_8)

    /**
     * Decompresses, counting as it goes rather than trusting the stream's own size field.
     *
     * The trailer's `ISIZE` is a 32-bit value that wraps, and it is only written once the
     * whole thing has been produced, so it cannot be used to refuse a stream before the
     * memory is already spent.
     */
    private fun inflate(bytes: ByteArray, limit: Long): String {
        val out = ByteArrayOutputStream()
        var total = 0L
        try {
            GZIPInputStream(bytes.inputStream()).use { input ->
                val buffer = ByteArray(CHUNK)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > limit) {
                        // No cause: nothing went wrong, the file is simply not a backup. Its
                        // own text is what the user is shown, and the one shared by every
                        // unreadable case is the truthful one.
                        throw UnreadableBackupException()
                    }
                    out.write(buffer, 0, read)
                }
            }
        } catch (e: IOException) {
            // A truncated stream, or bytes that only start like gzip. Either way the file is
            // not a backup this app can read, which is what the caller needs to be told; the
            // cause is kept because "unexpected end of stream" is worth having in a report.
            throw UnreadableBackupException(e)
        }
        // The Charset overload of toString is API 33 and this app's floor is 26. UTF-8 is one
        // of the three encodings every JVM is required to support, so this cannot fail.
        return out.toString("UTF-8")
    }
}
