package com.financetracker.backup

import com.financetracker.data.backup.BackupSnapshot
import com.financetracker.data.backup.BackupSnapshotCodec
import com.financetracker.model.BankEntity
import com.financetracker.model.BondEntity
import com.financetracker.model.BondTradeEntity
import com.financetracker.model.BondTradeSide
import com.financetracker.model.TransactionEntity
import com.financetracker.model.TransactionType
import com.financetracker.model.TransferDirection
import org.junit.Assert.assertEquals
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
            TransactionEntity(
                id = 7,
                userId = "uid-1",
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
            TransactionEntity(
                id = 8,
                userId = "uid-1",
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
        val bare = TransactionEntity(
            userId = "uid-1",
            title = "Manual",
            amount = 1.0,
            type = TransactionType.INCOME,
            category = "other"
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
        val json = codec.encode(snapshot().copy(format = 7))

        assertEquals(7, codec.decode(json).format)
    }
}
