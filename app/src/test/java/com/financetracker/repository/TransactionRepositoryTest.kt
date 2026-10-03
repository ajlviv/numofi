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

        override suspend fun insert(transaction: TransactionEntity): Long = 1L
        override suspend fun insertAll(transactions: List<TransactionEntity>): List<Long> =
            transactions.map { 1L }
        override suspend fun update(transaction: TransactionEntity): Int {
            updates += transaction
            return 1
        }

        override suspend fun getById(id: Long): TransactionEntity? = row?.takeIf { it.id == id }
        override suspend fun delete(id: Long): Int = 1
        override suspend fun deleteAllForUser(userId: String): Int = 3
        override suspend fun getExternalIdsForUser(userId: String): List<String> = error("unused")
        override suspend fun getInRange(userId: String, from: Long, to: Long): List<TransactionEntity> =
            error("unused")
        override fun getAllForUser(userId: String): Flow<List<TransactionEntity>> = error("unused")
        override fun getRecentTransactions(userId: String, limit: Int): Flow<List<TransactionEntity>> =
            error("unused")
        override fun getCardRefs(userId: String): Flow<List<CardRef>> = error("unused")
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
            fromMillis: Long?,
            toMillis: Long?,
            search: String?
        ): Flow<List<TransactionEntity>> = error("unused")
    }

    private fun transaction(
        id: Long = 0,
        type: TransactionType = TransactionType.EXPENSE,
        direction: TransferDirection? = null
    ) = TransactionEntity(
        id = id,
        userId = "uid-1",
        title = "TORUS",
        amount = -100.0,
        type = type,
        category = "food",
        timestamp = 1_757_000_000_000L,
        transferDirection = direction
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
        // bank stated, not a replacement for it, and the haystack is baked at write time.
        val original = transaction(id = 7, type = TransactionType.INCOME).copy(searchText = "torus food")
        val dao = FakeDao(original)

        TransactionRepository(dao, FakeBackupRequests()).setType(7L, TransactionKind.EXPENSE)

        assertEquals(original.copy(type = TransactionType.EXPENSE), dao.updates.single())
    }

    @Test
    fun `retyping a row to what it already is writes nothing`() = runTest {
        // The comparison is against the row read back rather than against what the database
        // reports, because SQLite counts a row as updated when it was matched, not when a
        // column changed — an UPDATE that sets a value to itself returns one row.
        val dao = FakeDao(transaction(id = 7, type = TransactionType.EXPENSE))

        val changed = TransactionRepository(dao, FakeBackupRequests())
            .setType(7L, TransactionKind.EXPENSE)

        assertEquals(false, changed)
        assertTrue(dao.updates.isEmpty())
    }

    @Test
    fun `retyping a row to what it already is asks for no backup`() = runTest {
        val backups = FakeBackupRequests()

        TransactionRepository(FakeDao(transaction(id = 7)), backups)
            .setType(7L, TransactionKind.EXPENSE)

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
}
