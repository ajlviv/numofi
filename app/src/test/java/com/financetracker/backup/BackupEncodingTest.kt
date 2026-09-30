package com.financetracker.backup

import com.financetracker.data.backup.BackupEncoding
import com.financetracker.data.backup.UnreadableBackupException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-disk encoding, independent of any `DocumentsProvider`.
 *
 * Reading has to accept two things, which is the whole reason this class sniffs rather than
 * assuming: the files this app wrote before it compressed anything, and the ones it writes now.
 * A user upgrading the app already has the first kind sitting in Drive, and a restore that
 * only understood the second kind would tell them their backup was corrupt.
 */
class BackupEncodingTest {

    @Test
    fun `gzip round-trips`() {
        val text = """{"format":2,"transactions":[{"title":"Кава"}]}"""

        val encoded = BackupEncoding.compress(text)

        assertTrue("should not be stored as plain text", encoded.size > 0)
        assertEquals(text, BackupEncoding.decode(encoded))
    }

    @Test
    fun `plain json is recognised as not gzip`() {
        assertFalse(BackupEncoding.isGzip("""{"format":2}""".toByteArray()))
    }

    @Test
    fun `gzip is recognised from its magic bytes`() {
        assertTrue(BackupEncoding.isGzip(BackupEncoding.compress("{}")))
    }

    @Test
    fun `a file written before compression still reads back`() {
        // The bytes are exactly what the old exporter put in Drive, and this is the only
        // reason a restore works on a file the user has had for months.
        val old = """{"format":1,"uid":"uid-1","transactions":[]}""".toByteArray()

        assertEquals(String(old), BackupEncoding.decode(old))
    }

    @Test
    fun `an empty file decodes to itself rather than throwing`() {
        // Decoding is not parsing. An empty file is not a valid backup, and refusing it here
        // would report the wrong reason: it is the codec's job to say a backup is unreadable.
        assertEquals("", BackupEncoding.decode(ByteArray(0)))
    }

    @Test
    fun `a file that expands past the limit is refused rather than expanded`() {
        // Four megabytes of one character is a few kilobytes compressed, which is the shape
        // of a hostile file: tiny to fetch, enormous to expand. The file being restored is
        // whatever the user picked out of their Drive, which anyone with access to that Drive
        // could have written, so the size of what comes out is not this app's to trust.
        val bomb = BackupEncoding.compress("x".repeat(4 * 1024 * 1024))

        val error = runCatching { BackupEncoding.decode(bomb, limit = 64 * 1024) }.exceptionOrNull()

        assertTrue("expected a refusal, got $error", error is UnreadableBackupException)
    }

    @Test
    fun `a file just under the limit is read`() {
        val text = "x".repeat(200_000)

        assertEquals(text, BackupEncoding.decode(BackupEncoding.compress(text), limit = 300_000))
    }

    @Test
    fun `non-ascii survives the round trip`() {
        // The haystack is baked in lower case and keeps Ukrainian, so this is the ordinary
        // case rather than an edge one.
        val text = """{"searchText":"1053 item new test приватбанк україна"}"""

        assertEquals(text, BackupEncoding.decode(BackupEncoding.compress(text)))
    }

    @Test
    fun `a snapshot-sized file compresses to a fraction of its size`() {
        // The reason any of this exists. Gzip carries roughly twenty bytes of header, so a
        // file of a handful of rows comes out slightly *larger* than it went in — which is
        // why the assertion is against a realistic snapshot rather than a toy one, and why
        // the code does not try to decide when compression is worth it.
        val snapshot = buildString {
            append("""{"format":2,"transactions":[""")
            repeat(500) { i ->
                append("""{"id":$i,"title":"ATB MARKET 1234","amount":123.45,"type":"EXPENSE",""")
                append(""""externalId":"uk_import_${"0".repeat(64)}","searchText":"atb market groceries uk"},$i""")
                if (i < 499) append(',')
            }
            append("]}")
        }

        val encoded = BackupEncoding.compress(snapshot)

        assertTrue(
            "expected well under half, got ${encoded.size} of ${snapshot.length}",
            encoded.size < snapshot.length / 2
        )
        assertEquals(snapshot, BackupEncoding.decode(encoded))
    }
}
