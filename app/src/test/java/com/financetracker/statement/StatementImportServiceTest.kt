package com.financetracker.statement

import com.financetracker.data.TransactionDao
import com.financetracker.data.backup.BackupReason
import com.financetracker.data.statement.StatementImportService
import com.financetracker.data.statement.StatementRow
import com.financetracker.model.BankCode
import com.financetracker.model.BankCodeGenerator
import com.financetracker.model.BankEntity
import com.financetracker.model.CardRef
import com.financetracker.model.TransactionEntity
import com.financetracker.model.TransactionType
import com.financetracker.repository.BankRepository
import com.financetracker.testing.FakeBackupRequests
import com.financetracker.testing.FakeBankDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.util.Random

class StatementImportServiceTest {

    private class FakeDao : TransactionDao {
        val stored = mutableListOf<TransactionEntity>()
        var nextId = 1L

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

        fun replace(entity: TransactionEntity) {
            val index = stored.indexOfFirst { it.id == entity.id }
            stored[index] = entity
        }

        // Unused by the import path.
        override suspend fun insertAll(transactions: List<TransactionEntity>) = error("unused")
        override suspend fun update(transaction: TransactionEntity) = error("unused")
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
            fromMillis: Long?,
            toMillis: Long?,
            search: String?
        ): Flow<List<TransactionEntity>> = error("unused")
        override fun getCardRefs(userId: String): Flow<List<CardRef>> = error("unused")
        override fun getByType(userId: String, type: String): Flow<List<TransactionEntity>> = error("unused")
        override fun getRecentTransactions(userId: String, limit: Int): Flow<List<TransactionEntity>> = error("unused")
    }

    private fun row(
        amount: String,
        bank: String?,
        card: String? = "535129****5783",
        description: String = "TORUS",
        day: String = "01.09.2026"
    ) = StatementRow(
        timestamp = com.financetracker.data.statement.StatementParser
            .parseDate("$day 12:00:00")!!,
        description = description,
        amount = BigDecimal(amount),
        currencyCode = "UAH",
        balance = null,
        rowIndex = 0,
        cardLabel = card,
        bankCode = bank
    )

    /**
     * The import path only reads the bank list, to resolve the chosen code into the name it
     * bakes into the row's haystack. The built-ins cover every bank the detector returns.
     */
    private fun bankRepository() =
        BankRepository(FakeBankDao(BankEntity.BUILT_IN), BankCodeGenerator(Random(1)), FakeBackupRequests())

    @Test
    fun `the chosen bank and card are stored on every row`() = runTest {
        val dao = FakeDao()
        val result = StatementImportService(dao, bankRepository(), FakeBackupRequests()).import(
            "uid-1",
            listOf(row("-100.00", BankCode.UKRSIBBANK))
        )

        assertEquals(1, result.imported)
        assertEquals("uk", dao.stored.single().bankCode)
        assertEquals("535129****5783", dao.stored.single().cardLabel)
    }

    @Test
    fun `a fresh import asks for a backup`() = runTest {
        val backups = FakeBackupRequests()
        StatementImportService(FakeDao(), bankRepository(), backups).import(
            "uid-1",
            listOf(row("-100.00", BankCode.UKRSIBBANK))
        )

        assertEquals(listOf(BackupReason.IMPORT), backups.reasons)
    }

    @Test
    fun `an import that only skipped duplicates asks for no backup`() = runTest {
        val dao = FakeDao()
        val backups = FakeBackupRequests()
        val service = StatementImportService(dao, bankRepository(), backups)
        val rows = listOf(row("-100.00", BankCode.UKRSIBBANK))
        service.import("uid-1", rows)

        // Nothing was written, so the file in Drive already says everything this device
        // knows. Uploading it again would be a rewrite of identical content.
        val second = service.import("uid-1", rows)

        assertEquals(0, second.imported)
        assertEquals(listOf(BackupReason.IMPORT), backups.reasons)
    }

    @Test
    fun `the stored row is searchable by its bank and card`() = runTest {
        val dao = FakeDao()
        StatementImportService(dao, bankRepository(), FakeBackupRequests())
            .import("uid-1", listOf(row("-100.00", BankCode.UKRSIBBANK)))
        val text = dao.stored.single().searchText!!
        assertTrue(text.contains("torus"))
        assertTrue(text.contains("ukrsibbank"))
        assertTrue(text.contains("535129"))
    }

    @Test
    fun `the same payment at two banks is two rows, not one`() = runTest {
        val dao = FakeDao()
        val service = StatementImportService(dao, bankRepository(), FakeBackupRequests())
        service.import("uid-1", listOf(row("-100.00", BankCode.UKRSIBBANK)))
        service.import("uid-1", listOf(row("-100.00", BankCode.MONOBANK)))

        assertEquals(2, dao.stored.size)
        assertNotEquals(dao.stored[0].externalId, dao.stored[1].externalId)
    }

    @Test
    fun `an unattributable file is still imported, under its own namespace`() = runTest {
        val dao = FakeDao()
        val result = StatementImportService(dao, bankRepository(), FakeBackupRequests())
            .import("uid-1", listOf(row("-100.00", null)))

        assertEquals(1, result.imported)
        assertNull(dao.stored.single().bankCode)
        assertTrue(dao.stored.single().externalId!!.startsWith("unknown_"))
    }

    @Test
    fun `a blank card cell is stored as no card rather than an empty label`() = runTest {
        val dao = FakeDao()
        StatementImportService(dao, bankRepository(), FakeBackupRequests()).import(
            "uid-1",
            listOf(row("-100.00", BankCode.UKRSIBBANK, card = "  "))
        )
        assertNull(dao.stored.single().cardLabel)
    }

    @Test
    fun `re-importing the same statement adds nothing and changes nothing`() = runTest {
        val dao = FakeDao()
        val service = StatementImportService(dao, bankRepository(), FakeBackupRequests())
        val rows = listOf(row("-100.00", BankCode.UKRSIBBANK))
        service.import("uid-1", rows)
        val before = dao.stored.toList()

        val second = service.import("uid-1", rows)

        assertEquals(0, second.imported)
        assertEquals(1, second.duplicatesSkipped)
        assertEquals(before, dao.stored)
    }

    @Test
    fun `an overlapping statement never modifies a row that is already stored`() = runTest {
        val dao = FakeDao()
        val service = StatementImportService(dao, bankRepository(), FakeBackupRequests())
        service.import(
            "uid-1",
            listOf(row("-100.00", BankCode.UKRSIBBANK), row("-50.00", BankCode.UKRSIBBANK, day = "02.09.2026"))
        )

        // A correction the user made by hand, which a re-import must not undo.
        val edited = dao.stored.first().copy(category = "manual-fix", note = "checked")
        dao.replace(edited)

        val later = listOf(
            row("-100.00", BankCode.UKRSIBBANK),
            row("-50.00", BankCode.UKRSIBBANK, day = "02.09.2026"),
            row("-25.00", BankCode.UKRSIBBANK, day = "03.09.2026")
        )
        val result = service.import("uid-1", later)

        // Only the genuinely new 03.09 line is added; the two overlapping ones are skipped.
        assertEquals(1, result.imported)
        assertEquals(2, result.duplicatesSkipped)
        assertEquals(3, dao.stored.size)
        assertEquals(edited, dao.stored.first { it.externalId == edited.externalId })
    }

    @Test
    fun `importing the same file twice is reported as duplicates, not new rows`() = runTest {
        val dao = FakeDao()
        val service = StatementImportService(dao, bankRepository(), FakeBackupRequests())
        val rows = listOf(row("-100.00", BankCode.UKRSIBBANK), row("-50.00", BankCode.UKRSIBBANK, day = "02.09.2026"))

        assertEquals(2, service.import("uid-1", rows).imported)
        val second = service.import("uid-1", rows)

        assertEquals(0, second.imported)
        assertEquals(2, second.duplicatesSkipped)
        assertEquals(2, dao.stored.size)
    }

    @Test
    fun `a statement line already stored by bank sync is not added again`() = runTest {
        val dao = FakeDao()
        // Pretend sync had already stored this payment. The statement line is from the
        // same bank, which is the case cross-source deduplication exists for.
        val syncedAt = row("-100.00", BankCode.MONOBANK).timestamp
        dao.stored += TransactionEntity(
            id = 99,
            userId = "uid-1",
            title = "TORUS",
            amount = 100.0,
            type = TransactionType.EXPENSE,
            category = "mcc_5411",
            timestamp = syncedAt,
            externalId = "monobank_77",
            source = "monobank",
            currencyCode = "UAH",
            bankCode = BankCode.MONOBANK
        )

        val result = StatementImportService(dao, bankRepository(), FakeBackupRequests())
            .import("uid-1", listOf(row("-100.00", BankCode.MONOBANK)))

        assertEquals(0, result.imported)
        assertEquals(1, result.alreadySynced)
        assertEquals(1, dao.stored.size)
    }

    @Test
    fun `an identical payment at a different bank is kept, not swallowed as a duplicate`() = runTest {
        val dao = FakeDao()
        val syncedAt = row("-100.00", BankCode.MONOBANK).timestamp
        dao.stored += TransactionEntity(
            id = 99,
            userId = "uid-1",
            title = "TORUS",
            amount = 100.0,
            type = TransactionType.EXPENSE,
            category = "mcc_5411",
            timestamp = syncedAt,
            externalId = "monobank_77",
            source = "monobank",
            currencyCode = "UAH",
            bankCode = BankCode.MONOBANK
        )

        // Same amount, same day, same description, but a different institution: these are
        // two real payments and both have to be stored.
        val result = StatementImportService(dao, bankRepository(), FakeBackupRequests())
            .import("uid-1", listOf(row("-100.00", BankCode.UKRSIBBANK)))

        assertEquals(1, result.imported)
        assertEquals(0, result.alreadySynced)
        assertEquals(2, dao.stored.size)
    }

    @Test
    fun `an unattributed file is still protected from double counting`() = runTest {
        val dao = FakeDao()
        val syncedAt = row("-100.00", BankCode.MONOBANK).timestamp
        dao.stored += TransactionEntity(
            id = 99,
            userId = "uid-1",
            title = "TORUS",
            amount = 100.0,
            type = TransactionType.EXPENSE,
            category = "mcc_5411",
            timestamp = syncedAt,
            externalId = "monobank_77",
            source = "monobank",
            currencyCode = "UAH",
            bankCode = BankCode.MONOBANK
        )

        // The file's bank was never established, so the comparison stays permissive and
        // the row is treated as the same payment rather than double counted.
        val result = StatementImportService(dao, bankRepository(), FakeBackupRequests())
            .import("uid-1", listOf(row("-100.00", null)))

        assertEquals(0, result.imported)
        assertEquals(1, result.alreadySynced)
    }

    @Test
    fun `an imported row reuses the category the user gave the same title before`() = runTest {
        val dao = FakeDao()
        dao.stored += TransactionEntity(
            id = 99,
            userId = "uid-1",
            title = "TORUS",
            amount = 100.0,
            type = TransactionType.EXPENSE,
            category = "Groceries",
            timestamp = row("-100.00", BankCode.UKRSIBBANK, day = "01.08.2026").timestamp,
            externalId = "old-1",
            source = "uk",
            currencyCode = "UAH",
            bankCode = BankCode.UKRSIBBANK
        )
        StatementImportService(dao, bankRepository(), FakeBackupRequests()).import(
            "uid-1",
            listOf(row("-5.00", BankCode.UKRSIBBANK, day = "02.09.2026")),
            listOf("Groceries", "Other")
        )

        assertEquals("Groceries", dao.stored.last().category)
    }

    @Test
    fun `a preview override wins over the suggestion`() = runTest {
        val dao = FakeDao()
        StatementImportService(dao, bankRepository(), FakeBackupRequests()).import(
            "uid-1",
            listOf(row("-100.00", BankCode.UKRSIBBANK)),
            listOf("Groceries", "Other"),
            mapOf(0 to "Кава")
        )

        assertEquals("Кава", dao.stored.single().category)
    }

    @Test
    fun `an unknown title falls back to Other and stays searchable by it`() = runTest {
        val dao = FakeDao()
        StatementImportService(dao, bankRepository(), FakeBackupRequests()).import(
            "uid-1",
            listOf(row("-100.00", BankCode.UKRSIBBANK, description = "SOMETHING NEW")),
            listOf("Groceries", "Other")
        )

        val stored = dao.stored.single()
        assertEquals("Other", stored.category)
        assertTrue(stored.searchText!!.contains("other"))
    }
}
