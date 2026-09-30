package com.financetracker.repository

import com.financetracker.data.TransactionDao
import com.financetracker.data.backup.BackupReason
import com.financetracker.model.CardRef
import com.financetracker.model.TransactionEntity
import com.financetracker.model.TransactionType
import com.financetracker.testing.FakeBackupRequests
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The repository is a pass-through to the DAO, so the only thing worth asserting here is the
 * side effect it adds: every mutation asks for a backup. Silently dropping one of these calls
 * would not fail anything else — the row would still be written, and the file in Drive would
 * simply fall behind with nothing to indicate why.
 */
class TransactionRepositoryTest {

    private class FakeDao : TransactionDao {
        override suspend fun insert(transaction: TransactionEntity): Long = 1L
        override suspend fun insertAll(transactions: List<TransactionEntity>): List<Long> =
            transactions.map { 1L }
        override suspend fun update(transaction: TransactionEntity): Int = 1
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

    private fun transaction() = TransactionEntity(
        userId = "uid-1",
        title = "TORUS",
        amount = -100.0,
        type = TransactionType.EXPENSE,
        category = "food",
        timestamp = 1_757_000_000_000L
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
}
