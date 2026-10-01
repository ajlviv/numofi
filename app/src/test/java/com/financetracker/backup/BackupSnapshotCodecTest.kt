package com.financetracker.backup

import com.financetracker.data.backup.BackupSnapshot
import com.financetracker.data.backup.BackupSnapshotCodec
import com.financetracker.data.backup.RecurringPaymentRow
import com.financetracker.data.backup.TransactionRow
import com.financetracker.data.backup.UnreadableBackupException
import com.financetracker.data.backup.UnsupportedFormatException
import com.financetracker.model.BankEntity
import com.financetracker.model.BondEntity
import com.financetracker.model.BondTradeEntity
import com.financetracker.model.BondTradeSide
import com.financetracker.model.RepeatFrequency
import com.financetracker.model.TransactionType
import com.financetracker.model.TransferDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The file has to survive a round trip, and the parts most likely not to are the ones the
 * codec treats as ordinary: an absent value, a non-Latin string, and an enum.
 *
 * Nothing here needs a device, which is the point of the export being a data class over the
 * entities rather than anything tied to a database or a content provider.
 */
class BackupSnapshotCodecTest {

    private val codec = BackupSnapshotCodec()

    private fun snapshot() = BackupSnapshot(
        format = BackupSnapshot.FORMAT,
        appVersion = "1.0",
        createdAt = 1_700_000_123_456L,
        uid = "uid-1",
        transactions = listOf(
            TransactionRow(
                id = 7,
                title = "Кава вулична",
                amount = 120.5,
                type = TransactionType.EXPENSE,
                category = "groceries",
                timestamp = 1_700_000_000_000L,
                note = null,
                externalId = "mo_42",
                source = "monobank",
                currencyCode = "UAH",
                bankCode = "mo",
                cardLabel = "•••• 1234",
                searchText = "кава вулична groceries monobank •••• 1234",
                transferDirection = null
            ),
            TransactionRow(
                id = 8,
                title = "ОвДП 24/Б",
                amount = 3060.0,
                type = TransactionType.TRANSFER,
                category = "investments",
                timestamp = 1_700_000_500_000L,
                transferDirection = TransferDirection.OUT
            )
        ),
        banks = listOf(
            BankEntity("mo", "Monobank", 0, archived = true, builtIn = true),
            BankEntity("uk", "Укрсиббанк", 1)
        ),
        bonds = listOf(
            BondEntity(
                isin = "UA3501234567",
                name = "ОвДП 24/Б",
                nominal = 1000.0,
                nominalCurrency = "UAH",
                couponPercent = 9.5,
                couponPeriodMonths = 3,
                maturityDate = 1_900_000_000_000L
            )
        ),
        bondTrades = listOf(
            BondTradeEntity(
                id = 1,
                isin = "UA3501234567",
                side = BondTradeSide.BUY,
                quantity = 3,
                price = 1020.0,
                accruedInterest = 12.34,
                commission = 5.0,
                tradeDate = 1_700_000_500_000L,
                bankCode = "mo",
                transactionId = 8
            )
        ),
        recurringPayments = listOf(
            RecurringPaymentRow(
                id = 3,
                title = "Оренда",
                amount = 12_000.0,
                type = TransactionType.EXPENSE,
                category = "other",
                currencyCode = "UAH",
                frequency = RepeatFrequency.MONTHLY,
                startDate = 1_800_000_000_000L
            )
        )
    )

    @Test
    fun `a snapshot survives being written and read back`() {
        val original = snapshot()

        val parsed = codec.decode(codec.encode(original))

        assertEquals(original, parsed)
    }

    @Test
    fun `a row with nothing optional set comes back as nulls rather than as zeros`() {
        // A hand-entered row has no external id, source, bank, card, note or direction. Read
        // back as an empty string or a zero the row would look imported and directionless in a
        // way the stored original is not.
        val bare = TransactionRow(
            title = "Manual",
            amount = 1.0,
            type = TransactionType.INCOME,
            category = "other",
            timestamp = 1_700_000_000_000L
        )

        val parsed = codec.decode(codec.encode(snapshot().copy(transactions = listOf(bare))))
            .transactions
            .single()

        assertEquals(bare, parsed)
    }

    @Test
    fun `non-latin text is written as characters rather than escape sequences`() {
        val json = codec.encode(snapshot())

        assertTrue(json.contains("Кава вулична"))
        assertTrue(json.contains("ОвДП 24/Б"))
        assertTrue(json.contains("•••• 1234"))
    }

    @Test
    fun `the format number travels in the file so a reader can tell what it has`() {
        val json = codec.encode(snapshot().copy(format = 1))

        // A reader decides what it can do with a file from this number alone, so it has to
        // survive the trip rather than be re-derived from whatever the current build is.
        assertEquals(1, codec.decode(json).format)
    }

    @Test
    fun `a file written before transactions lost their user id still reads`() {
        // Exactly the shape of the first file this app ever wrote: format 1, with the account
        // repeated on every row. It has to keep working, or a user who took a backup and then
        // updated the app would find their only copy of their data unreadable.
        val v1 = """
            {
              "format": 1,
              "appVersion": "1.0",
              "createdAt": 1790754829382,
              "uid": "ajlviv@gmail.com",
              "transactions": [
                {
                  "amount": 10573.0,
                  "category": "investments",
                  "currencyCode": "UAH",
                  "id": 141,
                  "searchText": "ranch купівля 10 шт. @ 1,032.00 ₴ investments monobank",
                  "timestamp": 1790629200000,
                  "title": "ranch",
                  "type": "TRANSFER",
                  "userId": "ajlviv@gmail.com"
                }
              ],
              "banks": [],
              "bonds": [],
              "bondTrades": []
            }
        """.trimIndent()

        val parsed = codec.decode(v1)

        assertEquals(1, parsed.format)
        assertEquals("ajlviv@gmail.com", parsed.uid)
        val row = parsed.transactions.single()
        assertEquals(141L, row.id)
        assertEquals("ranch", row.title)
        // The account on the row was the same as the one at the root, so dropping it lost
        // nothing — which is the only reason the format could be changed at all.
        assertEquals("ajlviv@gmail.com", parsed.uid)
    }

    @Test
    fun `a file from a future format is refused rather than half read`() {
        val future = codec.encode(snapshot().copy(format = BackupSnapshot.FORMAT + 1))

        // Silently reading a newer format is how a file gets half-restored: the reader would
        // take the fields it recognises and invent nulls for the ones it does not.
        assertThrows(UnsupportedFormatException::class.java) { codec.decode(future) }
    }

    @Test
    fun `a file that is not json at all is refused`() {
        // Wrapped rather than left as Gson's own exception, so a caller catching one type
        // catches the other case too.
        assertThrows(UnreadableBackupException::class.java) { codec.decode("not a backup") }
    }

    @Test
    fun `an empty file is refused rather than read as an empty backup`() {
        // The dangerous reading is the one that succeeds: restoring nothing and reporting
        // "0 restored" would look like a working restore of a file that holds everything.
        assertThrows(UnreadableBackupException::class.java) { codec.decode("") }
    }

    @Test
    fun `a recurring schedule survives the round trip`() {
        val parsed = codec.decode(codec.encode(snapshot()))

        val schedule = parsed.recurringPayments!!.single()
        assertEquals("Оренда", schedule.title)
        assertEquals(RepeatFrequency.MONTHLY, schedule.frequency)
        assertEquals(12_000.0, schedule.amount, 1e-9)
    }

    @Test
    fun `a file written before schedules existed still reads`() {
        // Format 2 predates the schedules key. The reader has to treat the absent field as
        // none rather than fail, or a user's year-old backup would stop restoring.
        val v2 = """
            {
              "format": 2,
              "appVersion": "1.0",
              "createdAt": 0,
              "uid": "uid-1",
              "transactions": [],
              "banks": [],
              "bonds": [],
              "bondTrades": []
            }
        """.trimIndent()

        val parsed = codec.decode(v2)

        assertEquals(2, parsed.format)
        assertNull(parsed.recurringPayments)
    }
}
