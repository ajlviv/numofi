package com.financetracker.repository

import com.financetracker.data.TransactionDao
import com.financetracker.data.backup.BackupReason
import com.financetracker.model.CardRef
import com.financetracker.model.TransactionEntity
import com.financetracker.model.TransactionKind
import com.financetracker.model.TransactionType
import com.financetracker.model.TransferDirection
import com.financetracker.testing.FakeBackupRequests
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The repository is a pass-through to the DAO, so the only thing worth asserting here is the
 * side effect it adds: every mutation asks for a backup. Silently dropping one of these calls
 * would not fail anything else — the row would still be written, and the file in Drive would
 * simply fall behind with nothing to indicate why.
 */
class TransactionRepositoryTest {

    private class FakeDao(
        stored: TransactionEntity? = null
    ) : TransactionDao {
        var row = stored
        val updates = mutableListOf<TransactionEntity>()
        val all = mutableListOf<TransactionEntity>()

        override suspend fun insert(transaction: TransactionEntity): Long = 1L
        override suspend fun insertAll(transactions: List<TransactionEntity>): List<Long> =
            transactions.map { 1L }
        override suspend fun update(transaction: TransactionEntity): Int {
            updates += transaction
            return 1
        }

        override suspend fun getById(id: Long): TransactionEntity? = row?.takeIf { it.id == id }
        override suspend fun getAllForUserOnce(userId: String): List<TransactionEntity> = all
        override suspend fun updateAll(transactions: List<TransactionEntity>): Int {
            updates += transactions
            return transactions.size
        }
        override fun getTitleCategoryCounts(
            userId: String
        ): Flow<List<com.financetracker.data.TitleCategoryCount>> = error("unused")
        override suspend fun delete(id: Long): Int = 1
        override suspend fun deleteAllForUser(userId: String): Int = 3
        override suspend fun getExternalIdsForUser(userId: String): List<String> = error("unused")
        override suspend fun getTitleCategoryPairs(userId: String): List<com.financetracker.data.TitleCategory> =
            error("unused")
        override suspend fun getInRange(userId: String, from: Long, to: Long): List<TransactionEntity> =
            error("unused")
        override fun getAllForUser(userId: String): Flow<List<TransactionEntity>> = error("unused")
        override fun getRecentTransactions(userId: String, limit: Int): Flow<List<TransactionEntity>> =
            error("unused")
        override fun getCardRefs(userId: String): Flow<List<CardRef>> = error("unused")
        override fun getCategoryRefs(userId: String): Flow<List<String>> = error("unused")
        override fun getByType(userId: String, type: String): Flow<List<TransactionEntity>> =
            error("unused")
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
    }

    private fun transaction(
        id: Long = 0,
        type: TransactionType = TransactionType.EXPENSE,
        direction: TransferDirection? = null,
        userSet: Boolean = false
    ) = TransactionEntity(
        id = id,
        userId = "uid-1",
        title = "TORUS",
        amount = -100.0,
        type = type,
        category = "food",
        timestamp = 1_757_000_000_000L,
        transferDirection = direction,
        typeUserSet = userSet
    )

    @Test
    fun `adding a row asks for a backup`() = runTest {
        val backups = FakeBackupRequests()

        TransactionRepository(FakeDao(), backups).addTransaction(transaction())

        assertEquals(listOf(BackupReason.TRANSACTION), backups.reasons)
    }

    @Test
    fun `editing a row asks for a backup`() = runTest {
        val backups = FakeBackupRequests()

        TransactionRepository(FakeDao(), backups).updateTransaction(transaction())

        assertEquals(listOf(BackupReason.TRANSACTION), backups.reasons)
    }

    @Test
    fun `deleting a row asks for a backup`() = runTest {
        val backups = FakeBackupRequests()

        TransactionRepository(FakeDao(), backups).deleteTransaction(1L)

        assertEquals(listOf(BackupReason.TRANSACTION), backups.reasons)
    }

    @Test
    fun `deleting the whole account asks for a backup`() = runTest {
        val backups = FakeBackupRequests()

        TransactionRepository(FakeDao(), backups).deleteAllForUser("uid-1")

        assertEquals(listOf(BackupReason.TRANSACTION), backups.reasons)
    }

    @Test
    fun `the rows handed back are the ones the DAO returned`() = runTest {
        val repository = TransactionRepository(FakeDao(), FakeBackupRequests())

        // Asking for a backup is not allowed to change what the caller gets back: the new id
        // is what the rest of the screen uses to select the row it just wrote.
        assertEquals(1L, repository.addTransaction(transaction()))
        assertEquals(1, repository.updateTransaction(transaction()))
        assertEquals(1, repository.deleteTransaction(1L))
    }

    // MARK: - Retyping

    @Test
    fun `retyping a row writes the new type and its direction`() = runTest {
        val dao = FakeDao(transaction(id = 7, type = TransactionType.INCOME))

        TransactionRepository(dao, FakeBackupRequests()).setType(7L, TransactionKind.TRANSFER_OUT)

        val written = dao.updates.single()
        assertEquals(TransactionType.TRANSFER, written.type)
        assertEquals(TransferDirection.OUT, written.transferDirection)
        // The correction is marked as a person's, so a later statement import cannot undo it.
        assertTrue(written.typeUserSet)
    }

    @Test
    fun `retyping away from a transfer clears the direction`() = runTest {
        // Otherwise the row keeps a direction it no longer means, and the column stops being
        // true of what it claims to describe.
        val dao = FakeDao(
            transaction(id = 7, type = TransactionType.TRANSFER, direction = TransferDirection.IN)
        )

        TransactionRepository(dao, FakeBackupRequests()).setType(7L, TransactionKind.EXPENSE)

        val written = dao.updates.single()
        assertEquals(TransactionType.EXPENSE, written.type)
        assertEquals(null, written.transferDirection)
    }

    @Test
    fun `retyping changes nothing else about the row`() = runTest {
        // The amount, the ids and the haystack all survive: the type is a label on a fact the
        // bank stated, not a replacement for it, and the haystack is baked at write time. The
        // one other column that moves is the user-set flag, which is the point of the write.
        val original = transaction(id = 7, type = TransactionType.INCOME).copy(searchText = "torus food")
        val dao = FakeDao(original)

        TransactionRepository(dao, FakeBackupRequests()).setType(7L, TransactionKind.EXPENSE)

        assertEquals(
            original.copy(type = TransactionType.EXPENSE, typeUserSet = true),
            dao.updates.single()
        )
    }

    @Test
    fun `retyping a row to a type it already carries and owns writes nothing`() = runTest {
        // The comparison is against the row read back rather than against what the database
        // reports, because SQLite counts a row as updated when it was matched, not when a
        // column changed — an UPDATE that sets a value to itself returns one row. The row is
        // already stamped as user-set, so there is genuinely nothing left to record.
        val dao = FakeDao(transaction(id = 7, type = TransactionType.EXPENSE, userSet = true))

        val changed = TransactionRepository(dao, FakeBackupRequests())
            .setType(7L, TransactionKind.EXPENSE)

        assertEquals(false, changed)
        assertTrue(dao.updates.isEmpty())
    }

    @Test
    fun `retyping an imported row to the type it already shows stamps it as chosen`() = runTest {
        // The value does not move, but the flag does: from here on the statement pairing must
        // treat this row as one a person stands behind, so the write is a real change.
        val dao = FakeDao(transaction(id = 7, type = TransactionType.EXPENSE))

        val changed = TransactionRepository(dao, FakeBackupRequests())
            .setType(7L, TransactionKind.EXPENSE)

        assertEquals(true, changed)
        assertTrue(dao.updates.single().typeUserSet)
    }

    @Test
    fun `retyping a row to a type it already owns asks for no backup`() = runTest {
        val backups = FakeBackupRequests()

        TransactionRepository(
            FakeDao(transaction(id = 7, userSet = true)),
            backups
        ).setType(7L, TransactionKind.EXPENSE)

        assertTrue(backups.reasons.isEmpty())
    }

    @Test
    fun `retyping a transfer to the other direction is a change`() = runTest {
        // Same type, different direction. Deciding this from the type alone would report no
        // change and leave the user with a transfer that goes the wrong way.
        val dao = FakeDao(
            transaction(id = 7, type = TransactionType.TRANSFER, direction = TransferDirection.IN)
        )

        val changed = TransactionRepository(dao, FakeBackupRequests())
            .setType(7L, TransactionKind.TRANSFER_OUT)

        assertEquals(true, changed)
        assertEquals(TransferDirection.OUT, dao.updates.single().transferDirection)
    }

    @Test
    fun `retyping a row that is not there asks for no backup`() = runTest {
        val backups = FakeBackupRequests()
        val dao = FakeDao()

        val changed = TransactionRepository(dao, backups).setType(7L, TransactionKind.EXPENSE)

        assertEquals(false, changed)
        assertTrue(dao.updates.isEmpty())
        assertTrue(backups.reasons.isEmpty())
    }

    @Test
    fun `retyping a row asks for a backup`() = runTest {
        val backups = FakeBackupRequests()

        TransactionRepository(FakeDao(transaction(id = 7)), backups)
            .setType(7L, TransactionKind.TRANSFER_IN)

        assertEquals(listOf(BackupReason.TRANSACTION), backups.reasons)
    }

    // MARK: - Bulk remap

    private fun stored(
        id: Long,
        title: String,
        category: String,
        searchText: String? = "x",
        typeUserSet: Boolean = false
    ) = TransactionEntity(
        id = id,
        userId = "uid-1",
        title = title,
        amount = -100.0,
        type = TransactionType.EXPENSE,
        category = category,
        timestamp = 1_757_000_000_000L,
        searchText = searchText,
        typeUserSet = typeUserSet
    )

    @Test
    fun `remapping changes the category of every matching row and nothing else`() = runTest {
        val dao = FakeDao()
        dao.all += stored(1, "Сільпо", "mcc_5411", searchText = "сільпо mcc_5411")
        dao.all += stored(2, "Сільпо", "mcc_5411", searchText = "сільпо mcc_5411", typeUserSet = true)
        dao.all += stored(3, "АТБ", "mcc_5412")

        val changed = TransactionRepository(dao, FakeBackupRequests())
            .remapCategoryByTitle("uid-1", "сільпо", "Groceries")

        assertEquals(2, changed)
        assertEquals(setOf(1L, 2L), dao.updates.map { it.id }.toSet())
        // Only the category moves; the haystack and the user-set flag survive, as in setType.
        dao.updates.forEach { row ->
            assertEquals("Groceries", row.category)
            assertEquals("сільпо mcc_5411", row.searchText)
        }
        assertTrue(dao.updates.single { it.id == 2L }.typeUserSet)
    }

    @Test
    fun `remapping a row already in the target writes nothing and asks for no backup`() = runTest {
        val backups = FakeBackupRequests()
        val dao = FakeDao()
        dao.all += stored(1, "Сільпо", "Groceries")

        val changed = TransactionRepository(dao, backups)
            .remapCategoryByTitle("uid-1", "Сільпо", "Groceries")

        assertEquals(0, changed)
        assertTrue(dao.updates.isEmpty())
        assertTrue(backups.reasons.isEmpty())
    }

    @Test
    fun `remapping a name nobody has writes nothing and asks for no backup`() = runTest {
        val backups = FakeBackupRequests()
        val dao = FakeDao()
        dao.all += stored(1, "Сільпо", "Groceries")

        val changed = TransactionRepository(dao, backups)
            .remapCategoryByTitle("uid-1", "Нема такого", "Fuel")

        assertEquals(0, changed)
        assertTrue(dao.updates.isEmpty())
        assertTrue(backups.reasons.isEmpty())
    }

    @Test
    fun `a blank name or target changes nothing`() = runTest {
        val dao = FakeDao()
        dao.all += stored(1, "Сільпо", "mcc_5411")

        val repository = TransactionRepository(dao, FakeBackupRequests())
        assertEquals(0, repository.remapCategoryByTitle("uid-1", "  ", "Groceries"))
        assertEquals(0, repository.remapCategoryByTitle("uid-1", "Сільпо", "  "))
        assertTrue(dao.updates.isEmpty())
    }

    @Test
    fun `a remap that changes a row asks for exactly one backup`() = runTest {
        val backups = FakeBackupRequests()
        val dao = FakeDao()
        dao.all += stored(1, "Сільпо", "mcc_5411")
        dao.all += stored(2, "Сільпо", "mcc_5411")

        TransactionRepository(dao, backups).remapCategoryByTitle("uid-1", "Сільпо", "Groceries")

        assertEquals(listOf(BackupReason.TRANSACTION), backups.reasons)
    }
}
