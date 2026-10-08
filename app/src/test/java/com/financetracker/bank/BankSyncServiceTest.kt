package com.financetracker.bank

import com.financetracker.data.TransactionDao
import com.financetracker.data.bank.BankAccount
import com.financetracker.data.bank.BankAuth
import com.financetracker.data.bank.BankProvider
import com.financetracker.data.bank.BankSyncService
import com.financetracker.data.bank.BankTransaction
import com.financetracker.data.backup.BackupReason
import com.financetracker.model.BankCode
import com.financetracker.model.BankCodeGenerator
import com.financetracker.model.BankEntity
import com.financetracker.model.CardRef
import com.financetracker.model.TransactionEntity
import com.financetracker.model.TransactionType
import com.financetracker.model.TransferDirection
import com.financetracker.repository.BankRepository
import com.financetracker.testing.FakeBackupRequests
import com.financetracker.testing.FakeBankDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.util.Random

class BankSyncServiceTest {

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

        /** Applies the whole row the way Room's `@Update` does: by primary key. */
        override suspend fun update(transaction: TransactionEntity): Int {
            val index = stored.indexOfFirst { it.id == transaction.id }
            if (index < 0) return 0
            stored[index] = transaction
            return 1
        }

        // Unused by the sync path.
        override suspend fun getInRange(userId: String, from: Long, to: Long) = emptyList<TransactionEntity>()
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
            fromMillis: Long?,
            toMillis: Long?,
            search: String?
        ): Flow<List<TransactionEntity>> = error("unused")
        override fun getCardRefs(userId: String): Flow<List<CardRef>> = error("unused")
        override fun getByType(userId: String, type: String): Flow<List<TransactionEntity>> = error("unused")
        override fun getRecentTransactions(userId: String, limit: Int): Flow<List<TransactionEntity>> = error("unused")
    }

    private fun account(
        id: String = "acc-1",
        maskedPan: List<String> = listOf("535129****5783")
    ) = BankAccount(
        id = id,
        bankId = "monobank",
        name = "MBUA5390134",
        type = "personal",
        currencyCode = 980,
        balance = BigDecimal("100.00"),
        creditLimit = null,
        maskedPan = maskedPan,
        iban = null
    )

    private fun provider(
        accounts: List<BankAccount>,
        transactions: List<BankTransaction> = defaultTransactions(),
        byAccount: Map<String, List<BankTransaction>> = emptyMap()
    ) = object : BankProvider {
        override val id = "monobank"
        override val displayName = "Monobank"
        override suspend fun isConfigured(auth: BankAuth?) = true
        override suspend fun getAccounts(auth: BankAuth) = accounts
        override suspend fun getTransactions(
            auth: BankAuth,
            accountId: String,
            from: Long,
            to: Long
        ) = byAccount[accountId] ?: transactions
    }

    private fun bankTransaction(
        id: String,
        amount: String,
        timestamp: Long = 1_757_000_000_000,
        isHold: Boolean = false,
        description: String = "TORUS"
    ) = BankTransaction(
        id = id,
        accountId = "acc-1",
        timestamp = timestamp,
        description = description,
        amount = BigDecimal(amount),
        currencyCode = 980,
        balanceAfter = null,
        comment = "coffee",
        counterName = null,
        isHold = isHold,
        mcc = 5411
    )

    private fun defaultTransactions() =
        listOf(bankTransaction(id = "1", amount = "-100.00"))

    private val auth = BankAuth.PersonalToken("token")

    /**
     * The sync path only reads the bank list, to resolve the bank's code into the name it
     * bakes into the row's haystack, so the built-ins are enough.
     */
    private fun bankRepository() =
        BankRepository(FakeBankDao(BankEntity.BUILT_IN), BankCodeGenerator(Random(1)), FakeBackupRequests())

    @Test
    fun `a sync that stored rows asks for a backup`() = runTest {
        val backups = FakeBackupRequests()
        BankSyncService(FakeDao(), bankRepository(), backups)
            .sync(provider(listOf(account())), auth, "uid-1", 0, 1_757_000_000_000)

        assertEquals(listOf(BackupReason.BANK_SYNC), backups.reasons)
    }

    @Test
    fun `a sync that found nothing new asks for no backup`() = runTest {
        val dao = FakeDao()
        val backups = FakeBackupRequests()
        val service = BankSyncService(dao, bankRepository(), backups)
        service.sync(provider(listOf(account())), auth, "uid-1", 0, 1_757_000_000_000)

        // A sync that imported nothing usually means every statement has been seen already,
        // and the file in Drive is already the state this device is in.
        val second = service.sync(provider(listOf(account())), auth, "uid-1", 0, 1_757_000_000_000)

        assertEquals(0, second.imported)
        assertEquals(listOf(BackupReason.BANK_SYNC), backups.reasons)
    }

    @Test
    fun `a synced row records the bank and the masked card`() = runTest {
        val dao = FakeDao()
        BankSyncService(dao, bankRepository(), FakeBackupRequests())
            .sync(provider(listOf(account())), auth, "uid-1", 0, 1_757_000_000_000)

        val row = dao.stored.single()
        assertEquals(BankCode.MONOBANK, row.bankCode)
        assertEquals("535129****5783", row.cardLabel)
    }

    @Test
    fun `an account with no masked number falls back to its name`() = runTest {
        val dao = FakeDao()
        BankSyncService(dao, bankRepository(), FakeBackupRequests()).sync(
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
        BankSyncService(dao, bankRepository(), FakeBackupRequests()).sync(
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
        BankSyncService(dao, bankRepository(), FakeBackupRequests()).sync(provider(listOf(account())), auth, "uid-1", 0, 1_757_000_000_000)

        val text = dao.stored.single().searchText!!
        assertTrue(text.contains("torus"))
        assertTrue(text.contains("coffee"))
        assertTrue(text.contains("monobank"))
        assertTrue(text.contains("535129"))
    }

// MARK: - Pending authorisations

    /**
     * A range short enough to be one statement request.
     *
     * The whole history since the epoch spans hundreds of provider-sized windows and this
     * fake returns the same list for every one of them, so a range that wide makes a single
     * transaction look like hundreds of duplicates and any count over it meaningless.
     */
    private val windowFrom = 1_756_900_000_000L

    @Test
    fun `a pending authorisation is not imported at all`() = runTest {
        val dao = FakeDao()
        BankSyncService(dao, bankRepository(), FakeBackupRequests()).sync(
            provider(listOf(account()), listOf(bankTransaction(id = "9", amount = "-3000.00", isHold = true))),
            auth,
            "uid-1",
            windowFrom,
            1_757_000_000_000
        )

        assertTrue(dao.stored.isEmpty())
    }

    @Test
    fun `a purchase held then settled is imported once, not twice`() = runTest {
        // The whole reason holds are skipped. Monobank reports the authorisation and the
        // settled purchase as two items under two ids, so id-keyed dedupe cannot merge them
        // and the ledger ends up with the same dinner twice — a row corruption no later
        // sync can detect or undo.
        val dao = FakeDao()
        BankSyncService(dao, bankRepository(), FakeBackupRequests()).sync(
            provider(
                listOf(account()),
                listOf(
                    bankTransaction(id = "hold-1", amount = "-3000.00", timestamp = windowFrom, isHold = true),
                    bankTransaction(id = "settled-1", amount = "-3000.00", timestamp = 1_757_000_000_000)
                )
            ),
            auth,
            "uid-1",
            windowFrom,
            1_757_000_000_000
        )

        assertEquals(1, dao.stored.size)
        assertEquals(3000.0, dao.stored.single().amount, 0.0)
    }

    @Test
    fun `a sync that saw only pending authorisations asks for no backup`() = runTest {
        val backups = FakeBackupRequests()
        val result = BankSyncService(FakeDao(), bankRepository(), backups).sync(
            provider(listOf(account()), listOf(bankTransaction(id = "9", amount = "-3000.00", isHold = true))),
            auth,
            "uid-1",
            windowFrom,
            1_757_000_000_000
        )

        // Nothing was written, so the file in Drive is already the state this device is in.
        assertEquals(0, result.imported)
        assertTrue(backups.reasons.isEmpty())
    }

    @Test
    fun `an authorisation that was never seen is not counted as a duplicate`() = runTest {
        // It is not a duplicate: it was never stored. Folding it into the duplicate count
        // would tell the user they already have a purchase they never had, which is the one
        // reading that would make the skip look like a sync that had silently dropped
        // something they can see.
        val result = BankSyncService(FakeDao(), bankRepository(), FakeBackupRequests()).sync(
            provider(listOf(account()), listOf(bankTransaction(id = "9", amount = "-3000.00", isHold = true))),
            auth,
            "uid-1",
            windowFrom,
            1_757_000_000_000
        )

        assertEquals(0, result.skippedDuplicates)
    }

    @Test
    fun `a hold does not suppress the settled purchase that arrives later`() = runTest {
        // The skip is on the authorisation, not on the money: a purchase the bank has settled
        // must still land once the hold clears, even though it is the same shop for the same
        // amount. Keyed on the bank's id, so the two can never be confused for each other.
        val dao = FakeDao()
        val service = BankSyncService(dao, bankRepository(), FakeBackupRequests())

        service.sync(
            provider(listOf(account()), listOf(bankTransaction(id = "hold-1", amount = "-3000.00", isHold = true))),
            auth,
            "uid-1",
            windowFrom,
            1_757_000_000_000
        )
        val result = service.sync(
            provider(listOf(account()), listOf(bankTransaction(id = "settled-1", amount = "-3000.00"))),
            auth,
            "uid-1",
            windowFrom,
            1_757_000_000_000
        )

        assertEquals(1, result.imported)
        assertEquals(1, dao.stored.size)
    }

    // MARK: - Transfers between own accounts

    private val cardA = account(id = "acc-1", maskedPan = listOf("535129****5783"))
    private val cardB = account(id = "acc-2", maskedPan = listOf("537541****1234"))

    /** The two legs of a 20000 move from [cardA] to [cardB], as the bank reports them. */
    private fun transferLegs() = mapOf(
        "acc-1" to listOf(bankTransaction(id = "out-1", amount = "-20000.00", timestamp = windowFrom)),
        "acc-2" to listOf(bankTransaction(id = "in-1", amount = "20000.00", timestamp = windowFrom))
    )

    private suspend fun syncTransfer(dao: FakeDao) =
        BankSyncService(dao, bankRepository(), FakeBackupRequests()).sync(
            provider(listOf(cardA, cardB), byAccount = transferLegs()),
            auth,
            "uid-1",
            windowFrom,
            1_757_000_000_000
        )

    private fun FakeDao.byExternalId() = stored.associateBy { it.externalId }

    @Test
    fun `a transfer between two own accounts is not recorded as income and spending`() = runTest {
        // The headline figures the dashboard leads with. A move between the user's own cards
        // is neither, and filing it as both reports a payment received and a payment made
        // that never happened.
        val dao = FakeDao()
        syncTransfer(dao)

        val rows = dao.byExternalId()
        assertEquals(TransactionType.TRANSFER, rows.getValue("monobank_out-1").type)
        assertEquals(TransactionType.TRANSFER, rows.getValue("monobank_in-1").type)
    }

    @Test
    fun `each leg of a transfer is given the direction money actually moved`() = runTest {
        val dao = FakeDao()
        syncTransfer(dao)

        val rows = dao.byExternalId()
        assertEquals(TransferDirection.OUT, rows.getValue("monobank_out-1").transferDirection)
        assertEquals(TransferDirection.IN, rows.getValue("monobank_in-1").transferDirection)
    }

    @Test
    fun `pairing a transfer leaves both amounts as the bank reported them`() = runTest {
        // Only the type is inferred. The money is never recomputed, so a transfer cannot
        // change a figure the bank already stated.
        val dao = FakeDao()
        syncTransfer(dao)

        val rows = dao.byExternalId()
        assertEquals(20_000.0, rows.getValue("monobank_out-1").amount, 0.0)
        assertEquals(20_000.0, rows.getValue("monobank_in-1").amount, 0.0)
    }

    @Test
    fun `a payment received and made on the same card are left as income and spending`() = runTest {
        // Money that never left the user's holdings. Two rows on one account are a receipt
        // and a payment, and calling them a transfer would understate income for a month
        // that happened to contain both.
        val dao = FakeDao()
        BankSyncService(dao, bankRepository(), FakeBackupRequests()).sync(
            provider(
                listOf(cardA),
                byAccount = mapOf(
                    "acc-1" to listOf(
                        bankTransaction(id = "in-1", amount = "20000.00", timestamp = windowFrom),
                        bankTransaction(id = "out-1", amount = "-20000.00", timestamp = windowFrom)
                    )
                )
            ),
            auth,
            "uid-1",
            windowFrom,
            1_757_000_000_000
        )

        val rows = dao.byExternalId()
        assertEquals(TransactionType.INCOME, rows.getValue("monobank_in-1").type)
        assertEquals(TransactionType.EXPENSE, rows.getValue("monobank_out-1").type)
    }

    @Test
    fun `a transfer is paired once and not reconsidered by a later sync`() = runTest {
        // The pass looks only at what a run imported. A row the user has since corrected by
        // hand must not be relabelled back by the next sync over the same period.
        val dao = FakeDao()
        val service = BankSyncService(dao, bankRepository(), FakeBackupRequests())

        service.sync(provider(listOf(cardA, cardB), byAccount = transferLegs()), auth, "uid-1", windowFrom, 1_757_000_000_000)
        val corrected = dao.byExternalId().getValue("monobank_in-1")
        dao.update(corrected.copy(type = TransactionType.INCOME, transferDirection = null))

        service.sync(provider(listOf(cardA, cardB), byAccount = transferLegs()), auth, "uid-1", windowFrom, 1_757_000_000_000)

        assertEquals(TransactionType.INCOME, dao.byExternalId().getValue("monobank_in-1").type)
    }

    @Test
    fun `pairing a transfer asks for a backup`() = runTest {
        // Rows changed, so the file in Drive has to follow. The sync already imported them,
        // so the existing trigger covers it — asserted because a relabelled row that never
        // reaches the backup is a row a restore would undo.
        val backups = FakeBackupRequests()
        BankSyncService(FakeDao(), bankRepository(), backups).sync(
            provider(listOf(cardA, cardB), byAccount = transferLegs()),
            auth,
            "uid-1",
            windowFrom,
            1_757_000_000_000
        )

        assertEquals(listOf(BackupReason.BANK_SYNC), backups.reasons)
    }
}
