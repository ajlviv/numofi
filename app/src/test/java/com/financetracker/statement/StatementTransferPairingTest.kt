package com.financetracker.statement

import com.financetracker.data.TransactionDao
import com.financetracker.data.backup.BackupReason
import com.financetracker.data.statement.StatementImportService
import com.financetracker.data.statement.StatementRow
import com.financetracker.model.BankCode
import com.financetracker.model.BankCodeGenerator
import com.financetracker.model.BankEntity
import com.financetracker.model.CardRef
import com.financetracker.model.SearchText
import com.financetracker.model.TransactionEntity
import com.financetracker.model.TransactionType
import com.financetracker.model.TransferDirection
import com.financetracker.repository.BankRepository
import com.financetracker.testing.FakeBackupRequests
import com.financetracker.testing.FakeBankDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.util.Random

/**
 * A transfer whose two legs sit in two different banks' statements.
 *
 * The pairing in [com.financetracker.model.TransferPairing] was written for bank sync, where
 * one provider reports both legs of a movement in the same response and they land within
 * minutes. A statement is the other case: the sending bank debits, the receiving bank
 * credits overnight, and the two rows are in different files a user imports at different
 * times. Measured across a real import the debit precedes the credit by 500 to 1326 minutes.
 *
 * These are the cases that gap produces, plus what must stay untouched beside them.
 */
class StatementTransferPairingTest {

    private class FakeDao : TransactionDao {
        val stored = mutableListOf<TransactionEntity>()
        var nextId = 1L
        val updates = mutableListOf<TransactionEntity>()

        override suspend fun insert(transaction: TransactionEntity): Long {
            stored += transaction.copy(id = nextId++)
            return nextId - 1
        }

        override suspend fun getExternalIdsForUser(userId: String) = stored.mapNotNull { it.externalId }

        override suspend fun getTitleCategoryPairs(userId: String) =
            stored.map { com.financetracker.data.TitleCategory(it.title, it.category) }

        override suspend fun getById(id: Long) = stored.firstOrNull { it.id == id }

        override suspend fun getInRange(userId: String, from: Long, to: Long) =
            stored.filter { it.timestamp in from..to }

        override suspend fun update(transaction: TransactionEntity): Int {
            updates += transaction
            val index = stored.indexOfFirst { it.id == transaction.id }
            stored[index] = transaction
            return 1
        }

        fun of(title: String) = stored.first { it.title == title }

        override suspend fun insertAll(transactions: List<TransactionEntity>) = error("unused")
        override suspend fun delete(id: Long) = error("unused")
        override suspend fun deleteAllForUser(userId: String) = error("unused")
        override fun getAllForUser(userId: String): Flow<List<TransactionEntity>> = error("unused")
        override fun getFilteredQuery(
            userId: String,
            bankCodes: List<String>,
            bankCount: Int,
            cardLabels: List<String>,
            cardCount: Int,
            types: List<TransactionType>,
            typeCount: Int,
            categories: List<String>,
            categoryCount: Int,
            fromMillis: Long?,
            toMillis: Long?,
            search: String?
        ): Flow<List<TransactionEntity>> = error("unused")
        override fun getCardRefs(userId: String): Flow<List<CardRef>> = error("unused")
        override fun getCategoryRefs(userId: String): Flow<List<String>> = error("unused")
        override fun getByType(userId: String, type: String): Flow<List<TransactionEntity>> = error("unused")
        override fun getRecentTransactions(userId: String, limit: Int): Flow<List<TransactionEntity>> = error("unused")
    }

    private fun bankRepository() =
        BankRepository(FakeBankDao(BankEntity.BUILT_IN), BankCodeGenerator(Random(1)), FakeBackupRequests())

    /**
     * A statement line, signed the way a bank prints it: negative for money out.
     *
     * The card is left null because a UA statement usually has no card column, and the rows
     * that arrived from a real import carry no card on either leg.
     */
    private fun line(
        amount: String,
        bank: String?,
        title: String,
        time: Long
    ) = StatementRow(
        timestamp = time,
        description = title,
        amount = BigDecimal(amount),
        currencyCode = "UAH",
        balance = null,
        rowIndex = 0,
        cardLabel = null,
        bankCode = bank
    )

    private val depart = 1_757_000_000_000L
    private fun hoursLater(n: Long) = depart + n * 3_600_000L

    private val outgoing = "Б/г перерахування на рахунок в іншому банку"
    private val incoming = "Від: Нога Андрій Володимирович"

    /**
     * Seeds a table with both legs already stored, fingerprints and all.
     *
     * Built by running the real import path into a throwaway table rather than by hand, so
     * the rows a later import compares against are byte-identical to the ones it would write.
     * That is what makes the re-import a re-import: a hand-written externalId would not match
     * the fingerprint and the file would insert a second copy instead.
     */
    private suspend fun storedPair(dao: FakeDao) {
        val reference = FakeDao()
        val service = StatementImportService(reference, bankRepository(), FakeBackupRequests())
        service.import("uid-1", listOf(line("-20000.00", BankCode.UKRSIBBANK, outgoing, depart)))
        service.import("uid-1", listOf(line("20000.00", BankCode.MONOBANK, incoming, hoursLater(14))))

        // Reset to what the rows looked like before pairing existed, which is the state an
        // upgrade leaves an existing table in. Only the fingerprints are borrowed. The type is
        // restored per leg rather than by what pairing made of it, so the arrival is an
        // INCOME again and there is something for the import under test to find.
        reference.stored.forEach { row ->
            dao.stored += row.copy(
                id = dao.nextId++,
                type = if (row.title == incoming) TransactionType.INCOME else TransactionType.EXPENSE,
                transferDirection = null
            )
        }
    }

    /** As [storedPair], but with the credit leg absent so nothing can be paired. */
    private suspend fun storedDebit(dao: FakeDao) {
        val reference = FakeDao()
        StatementImportService(reference, bankRepository(), FakeBackupRequests())
            .import("uid-1", listOf(line("-20000.00", BankCode.UKRSIBBANK, outgoing, depart)))

        reference.stored.forEach { dao.stored += it.copy(id = dao.nextId++) }
    }

    // MARK: - The two legs, an overnight gap apart

    @Test
    fun `a debit and the credit that follows it overnight are one transfer`() = runTest {
        val dao = FakeDao()
        val service = StatementImportService(dao, bankRepository(), FakeBackupRequests())

        // The sending bank's statement, imported first and on its own.
        service.import(
            "uid-1",
            listOf(line("-20000.00", BankCode.UKRSIBBANK, outgoing, depart))
        )
        assertEquals(TransactionType.EXPENSE, dao.of(outgoing).type)

        // The receiving bank's statement, imported later, carrying only the arriving leg.
        service.import(
            "uid-1",
            listOf(line("20000.00", BankCode.MONOBANK, incoming, hoursLater(14)))
        )

        assertEquals(TransactionType.TRANSFER, dao.of(outgoing).type)
        assertEquals(TransferDirection.OUT, dao.of(outgoing).transferDirection)
        assertEquals(TransactionType.TRANSFER, dao.of(incoming).type)
        assertEquals(TransferDirection.IN, dao.of(incoming).transferDirection)
    }

    @Test
    fun `the arriving leg alone is enough to relabel the leg already stored`() = runTest {
        val dao = FakeDao()
        val service = StatementImportService(dao, bankRepository(), FakeBackupRequests())

        service.import("uid-1", listOf(line("-20000.00", BankCode.UKRSIBBANK, outgoing, depart)))
        val storedId = dao.of(outgoing).id

        service.import("uid-1", listOf(line("20000.00", BankCode.MONOBANK, incoming, hoursLater(14))))

        // The stored row is updated in place rather than reinserted, so its id is stable and
        // anything holding a reference to it — a bond trade, a search result — stays valid.
        assertEquals(storedId, dao.of(outgoing).id)
        assertEquals(1, dao.stored.count { it.title == outgoing })
    }

    @Test
    fun `both legs in one file are one transfer`() = runTest {
        val dao = FakeDao()
        StatementImportService(dao, bankRepository(), FakeBackupRequests()).import(
            "uid-1",
            listOf(
                line("-20000.00", BankCode.UKRSIBBANK, outgoing, depart),
                line("20000.00", BankCode.MONOBANK, incoming, hoursLater(14))
            )
        )

        assertEquals(TransferDirection.OUT, dao.of(outgoing).transferDirection)
        assertEquals(TransferDirection.IN, dao.of(incoming).transferDirection)
    }

    @Test
    fun `an amount is untouched, since a transfer does not change it`() = runTest {
        val dao = FakeDao()
        StatementImportService(dao, bankRepository(), FakeBackupRequests()).import(
            "uid-1",
            listOf(
                line("-20000.00", BankCode.UKRSIBBANK, outgoing, depart),
                line("20000.00", BankCode.MONOBANK, incoming, hoursLater(14))
            )
        )

        // Nothing here may put a figure in the ledger the bank did not state.
        assertEquals(20000.0, dao.of(outgoing).amount, 0.0)
        assertEquals(20000.0, dao.of(incoming).amount, 0.0)
    }

    // MARK: - What the wider window must not swallow

    @Test
    fun `two equal payments days apart are not a transfer`() = runTest {
        val dao = FakeDao()
        StatementImportService(dao, bankRepository(), FakeBackupRequests()).import(
            "uid-1",
            listOf(
                line("-20000.00", BankCode.UKRSIBBANK, outgoing, depart),
                // Received three days later: outside the window, and a coincidence of
                // amount is not evidence of a transfer.
                line("20000.00", BankCode.MONOBANK, incoming, depart + 3 * 86_400_000L)
            )
        )

        assertEquals(TransactionType.EXPENSE, dao.of(outgoing).type)
        assertEquals(TransactionType.INCOME, dao.of(incoming).type)
    }

    @Test
    fun `an interbank payment with no matching credit stays an expense`() = runTest {
        val dao = FakeDao()
        StatementImportService(dao, bankRepository(), FakeBackupRequests()).import(
            "uid-1",
            // Money sent to an account the app does not hold. Only one leg will ever exist.
            listOf(line("-50000.00", BankCode.UKRSIBBANK, outgoing, depart))
        )

        assertEquals(TransactionType.EXPENSE, dao.of(outgoing).type)
        assertNull(dao.of(outgoing).transferDirection)
    }

    @Test
    fun `an income and an expense of equal amount on one account are not a transfer`() = runTest {
        val dao = FakeDao()
        StatementImportService(dao, bankRepository(), FakeBackupRequests()).import(
            "uid-1",
            listOf(
                // Same bank and no card on either, so these are one account: a refund and a
                // payment. Pairing them would invent a transfer out of two real rows.
                line("-20000.00", BankCode.UKRSIBBANK, "ПЛАТІЖ", depart),
                line("20000.00", BankCode.UKRSIBBANK, "ПОВЕРНЕННЯ", hoursLater(2))
            )
        )

        assertEquals(TransactionType.EXPENSE, dao.of("ПЛАТІЖ").type)
        assertEquals(TransactionType.INCOME, dao.of("ПОВЕРНЕННЯ").type)
    }

    @Test
    fun `differing amounts are not paired however close in time`() = runTest {
        val dao = FakeDao()
        StatementImportService(dao, bankRepository(), FakeBackupRequests()).import(
            "uid-1",
            listOf(
                line("-20000.00", BankCode.UKRSIBBANK, outgoing, depart),
                line("19999.00", BankCode.MONOBANK, incoming, hoursLater(1))
            )
        )

        assertEquals(TransactionType.EXPENSE, dao.of(outgoing).type)
        assertEquals(TransactionType.INCOME, dao.of(incoming).type)
    }

    // MARK: - Repeating an import

    @Test
    fun `importing the same file twice does not pair a row with itself`() = runTest {
        val dao = FakeDao()
        val service = StatementImportService(dao, bankRepository(), FakeBackupRequests())
        val rows = listOf(line("-20000.00", BankCode.UKRSIBBANK, outgoing, depart))

        service.import("uid-1", rows)
        val second = service.import("uid-1", rows)

        // The fingerprint catches the second pass before any pairing can see it, and a debit
        // that has not been credited yet is not a transfer.
        assertEquals(0, second.imported)
        assertEquals(TransactionType.EXPENSE, dao.of(outgoing).type)
        assertEquals(1, dao.stored.size)
    }

    @Test
    fun `a paired leg is not offered up for pairing again`() = runTest {
        val dao = FakeDao()
        val service = StatementImportService(dao, bankRepository(), FakeBackupRequests())

        service.import("uid-1", listOf(line("-20000.00", BankCode.UKRSIBBANK, outgoing, depart)))
        service.import("uid-1", listOf(line("20000.00", BankCode.MONOBANK, incoming, hoursLater(14))))

        val updatesAfterPairing = dao.updates.size
        // A third file that lands nearby must leave the finished pair alone, or the two
        // legs would be rewritten on every subsequent import.
        service.import(
            "uid-1",
            listOf(line("-500.00", BankCode.MONOBANK, "КУПІВЛЯ", hoursLater(15)))
        )

        assertEquals(TransferDirection.OUT, dao.of(outgoing).transferDirection)
        assertEquals(TransferDirection.IN, dao.of(incoming).transferDirection)
        assertEquals(updatesAfterPairing, dao.updates.count { it.id in setOf(dao.of(outgoing).id, dao.of(incoming).id) })
    }

    @Test
    fun `an import of only duplicates asks for no backup`() = runTest {
        val backups = FakeBackupRequests()
        val service = StatementImportService(FakeDao(), bankRepository(), backups)
        val rows = listOf(line("-20000.00", BankCode.UKRSIBBANK, outgoing, depart))
        service.import("uid-1", rows)

        val second = service.import("uid-1", rows)

        assertEquals(0, second.imported)
        assertEquals(listOf(BackupReason.IMPORT), backups.reasons)
    }

    // MARK: - Both banks' files already stored

    @Test
    fun `re-importing a file whose rows are all stored still pairs them`() = runTest {
        val dao = FakeDao()
        val service = StatementImportService(dao, bankRepository(), FakeBackupRequests())

        storedPair(dao)

        // Nothing is paired yet, so a pass that finds nothing cannot be mistaken for a pass
        // that did the work. Without this the test would also be satisfied by a seed whose
        // legs arrived already relabelled.
        assertNull(dao.of(incoming).transferDirection)
        assertEquals(TransactionType.INCOME, dao.of(incoming).type)

        val result = service.import(
            "uid-1",
            listOf(line("-20000.00", BankCode.UKRSIBBANK, outgoing, depart))
        )

        // Every line is a duplicate by fingerprint, so the count of imported rows is zero —
        // and the pair is still completed, because the counterpart is already in the table.
        assertEquals(0, result.imported)
        assertEquals(TransferDirection.OUT, dao.of(outgoing).transferDirection)
        assertEquals(TransferDirection.IN, dao.of(incoming).transferDirection)
    }

    @Test
    fun `a re-import that pairs asks for a backup, because it wrote`() = runTest {
        val backups = FakeBackupRequests()
        val dao = FakeDao()
        val service = StatementImportService(dao, bankRepository(), backups)

        storedPair(dao)

        service.import("uid-1", listOf(line("-20000.00", BankCode.UKRSIBBANK, outgoing, depart)))

        // The rows came from Drive's file as duplicates, but the relabelling is a real change
        // to what this device believes, so the backup in Drive would otherwise be stale.
        assertEquals(listOf(BackupReason.IMPORT), backups.reasons)
    }

    @Test
    fun `a re-import that pairs nothing asks for no backup`() = runTest {
        val backups = FakeBackupRequests()
        val dao = FakeDao()
        val service = StatementImportService(dao, bankRepository(), backups)

        // One leg only, and already stored: nothing is written and nothing is relabelled.
        storedDebit(dao)

        service.import("uid-1", listOf(line("-20000.00", BankCode.UKRSIBBANK, outgoing, depart)))

        assertEquals(listOf<BackupReason>(), backups.reasons)
    }

    // MARK: - What pairing must not touch

    @Test
    fun `a hand-entered row is not paired with a statement line`() = runTest {
        val dao = FakeDao()
        val typed = TransactionEntity(
            id = dao.nextId++,
            userId = "uid-1",
            title = "Від: Нога Андрій Володимирович",
            amount = 20_000.0,
            type = TransactionType.INCOME,
            category = "imported",
            timestamp = hoursLater(14),
            externalId = null,
            source = null,
            currencyCode = "UAH",
            bankCode = null,
            cardLabel = null,
            searchText = SearchText.of("Від: Нога Андрій Володимирович", null, "imported", null, null) // no bank
        )
        dao.stored += typed

        StatementImportService(dao, bankRepository(), FakeBackupRequests())
            .import("uid-1", listOf(line("-20000.00", BankCode.UKRSIBBANK, outgoing, depart)))

        // A row the user typed deliberately has no bank id to match on, so it is not a
        // counterpart the app may claim.
        assertEquals(TransactionType.INCOME, typed.type)
        assertEquals(TransactionType.EXPENSE, dao.of(outgoing).type)
    }

    @Test
    fun `a bond purchase recorded as a transfer is not relabelled`() = runTest {
        val dao = FakeDao()
        val trade = TransactionEntity(
            id = dao.nextId++,
            userId = "uid-1",
            title = incoming,
            amount = 20_000.0,
            type = TransactionType.TRANSFER,
            category = "investments",
            timestamp = hoursLater(14),
            externalId = "monobank_999",
            source = "monobank",
            currencyCode = "UAH",
            bankCode = BankCode.MONOBANK,
            cardLabel = null,
            transferDirection = TransferDirection.IN
        )
        dao.stored += trade

        StatementImportService(dao, bankRepository(), FakeBackupRequests())
            .import("uid-1", listOf(line("-20000.00", BankCode.UKRSIBBANK, outgoing, depart)))

        assertEquals(TransferDirection.IN, trade.transferDirection)
        assertEquals(TransactionType.EXPENSE, dao.of(outgoing).type)
    }

    @Test
    fun `a row in another currency is not paired with a hryvnia one`() = runTest {
        val dao = FakeDao()
        StatementImportService(dao, bankRepository(), FakeBackupRequests()).import(
            "uid-1",
            listOf(
                line("-20000.00", BankCode.UKRSIBBANK, outgoing, depart),
                line("20000.00", BankCode.MONOBANK, incoming, hoursLater(14)).copy(
                    currencyCode = "EUR"
                )
            )
        )

        // Reconciling two currencies needs a rate the app would have to supply, and a total
        // is only ever produced by converting through a rate it holds and can name.
        assertEquals(TransactionType.EXPENSE, dao.of(outgoing).type)
        assertEquals(TransactionType.INCOME, dao.of(incoming).type)
    }
}

private fun assertNull(value: Any?) = org.junit.Assert.assertNull("expected null but was $value", value)
