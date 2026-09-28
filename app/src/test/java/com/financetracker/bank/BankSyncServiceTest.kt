package com.financetracker.bank

import com.financetracker.data.TransactionDao
import com.financetracker.data.bank.BankAccount
import com.financetracker.data.bank.BankAuth
import com.financetracker.data.bank.BankProvider
import com.financetracker.data.bank.BankSyncService
import com.financetracker.data.bank.BankTransaction
import com.financetracker.model.BankCode
import com.financetracker.model.CardRef
import com.financetracker.model.TransactionEntity
import com.financetracker.model.TransactionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class BankSyncServiceTest {

    private class FakeDao : TransactionDao {
        val stored = mutableListOf<TransactionEntity>()
        var nextId = 1L

        override suspend fun insert(transaction: TransactionEntity): Long {
            stored += transaction.copy(id = nextId++)
            return nextId - 1
        }

        override suspend fun getExternalIdsForUser(userId: String) = stored.mapNotNull { it.externalId }

        // Unused by the sync path.
        override suspend fun getInRange(userId: String, from: Long, to: Long) = emptyList<TransactionEntity>()
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

    private fun account(maskedPan: List<String> = listOf("535129****5783")) = BankAccount(
        id = "acc-1",
        bankId = "monobank",
        name = "MBUA5390134",
        type = "personal",
        currencyCode = 980,
        balance = BigDecimal("100.00"),
        creditLimit = null,
        maskedPan = maskedPan,
        iban = null
    )

    private fun provider(accounts: List<BankAccount>) = object : BankProvider {
        override val id = "monobank"
        override val displayName = "Monobank"
        override suspend fun isConfigured(auth: BankAuth?) = true
        override suspend fun getAccounts(auth: BankAuth) = accounts
        override suspend fun getTransactions(
            auth: BankAuth,
            accountId: String,
            from: Long,
            to: Long
        ) = listOf(
            BankTransaction(
                id = "1",
                accountId = "acc-1",
                timestamp = 1_757_000_000_000,
                description = "TORUS",
                amount = BigDecimal("-100.00"),
                currencyCode = 980,
                balanceAfter = null,
                comment = "coffee",
                counterName = null,
                isHold = false,
                mcc = 5411
            )
        )
    }

    private val auth = BankAuth.PersonalToken("token")

    @Test
    fun `a synced row records the bank and the masked card`() = runTest {
        val dao = FakeDao()
        BankSyncService(dao).sync(provider(listOf(account())), auth, "uid-1", 0, 1_757_000_000_000)

        val row = dao.stored.single()
        assertEquals(BankCode.MONOBANK, row.bankCode)
        assertEquals("535129****5783", row.cardLabel)
    }

    @Test
    fun `an account with no masked number falls back to its name`() = runTest {
        val dao = FakeDao()
        BankSyncService(dao).sync(
            provider(listOf(account(maskedPan = emptyList()))),
            auth,
            "uid-1",
            0,
            1_757_000_000_000
        )

        assertEquals("MBUA5390134", dao.stored.single().cardLabel)
    }

    @Test
    fun `blank masked pan entries are ignored rather than joined into a label`() = runTest {
        val dao = FakeDao()
        BankSyncService(dao).sync(
            provider(listOf(account(maskedPan = listOf("", "  ")))),
            auth,
            "uid-1",
            0,
            1_757_000_000_000
        )

        assertEquals("MBUA5390134", dao.stored.single().cardLabel)
    }

    @Test
    fun `a synced row is searchable by title bank and card`() = runTest {
        val dao = FakeDao()
        BankSyncService(dao).sync(provider(listOf(account())), auth, "uid-1", 0, 1_757_000_000_000)

        val text = dao.stored.single().searchText!!
        assertTrue(text.contains("torus"))
        assertTrue(text.contains("coffee"))
        assertTrue(text.contains("monobank"))
        assertTrue(text.contains("535129"))
    }
}
