package com.financetracker.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.financetracker.data.AppDatabase
import com.financetracker.model.BankCodeGenerator
import com.financetracker.model.BankNames
import com.financetracker.model.SearchText
import com.financetracker.model.TransactionEntity
import com.financetracker.model.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import com.financetracker.data.backup.BackupReason
import com.financetracker.testing.FakeBackupRequests
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Random

/**
 * Exercises [BankRepository] against a real SQLite database.
 *
 * The rules worth testing here are the ones a fake would happily agree with regardless of
 * whether the behaviour is right: that reordering is a real two-row swap inside a
 * transaction, that a generated code colliding retries rather than overwriting a bank the
 * user can see, and that a name already in use is refused. Uniqueness in particular is
 * decided in Kotlin, because SQLite's `lower()` folds ASCII only and would accept
 * "ПриватБанк" beside "приватбанк".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BankRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: BankRepository
    private lateinit var backups: FakeBackupRequests

    private fun open(codes: BankCodeGenerator = BankCodeGenerator(Random(1234))) {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = BankRepository(db.bankDao(), codes, backups)
    }

    @Before
    fun setUp() {
        backups = FakeBackupRequests()
        open()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** A random source that hands out a fixed sequence, so collisions are reproducible. */
    private class ScriptedRandom(private val draws: List<ByteArray>) : Random() {
        private var index = 0
        override fun nextBytes(buf: ByteArray) {
            draws.getOrElse(index) { draws.last() }.copyInto(buf)
            index++
        }
    }
    private suspend fun seedTransactions(vararg bankCodes: String?) {
        bankCodes.forEachIndexed { index, code ->
            db.transactionDao().insert(
                TransactionEntity(
                    userId = "uid-1",
                    title = "Row $index",
                    amount = 10.0,
                    type = TransactionType.EXPENSE,
                    category = "other",
                    timestamp = 1_757_000_000_000L + index,
                    bankCode = code
                )
            )
        }
    }

    private suspend fun activeNames() = repository.activeBanks.first().map { it.displayName }

    private suspend fun allNames() = repository.banks.first().map { it.displayName }

    // MARK: - Backing up

    @Test
    fun `adding a bank asks for a backup`() = runTest {
        repository.add("Sense Bank")

        assertEquals(listOf(BackupReason.BANK), backups.reasons)
    }

    @Test
    fun `a bank refused for a duplicate name asks for no backup`() = runTest {
        repository.add("Sense Bank")
        val result = repository.add("SENSE BANK")

        // Nothing was written, so the bank list in Drive is already correct.
        assertTrue(result is AddBankResult.NameTaken)
        assertEquals(listOf(BackupReason.BANK), backups.reasons)
    }

    @Test
    fun `renaming a bank asks for a backup`() = runTest {
        val added = repository.add("Sense Bank") as AddBankResult.Added
        backups.reasons.clear()

        repository.rename(added.bank.code, "Feel Bank")

        assertEquals(listOf(BackupReason.BANK), backups.reasons)
    }

    @Test
    fun `archiving a bank that does not exist asks for no backup`() = runTest {
        repository.setArchived("not-a-bank", true)

        assertEquals(emptyList<BackupReason>(), backups.reasons)
    }

    @Test
    fun `archiving a bank that is already archived asks for no backup`() = runTest {
        val added = repository.add("Sense Bank") as AddBankResult.Added
        repository.setArchived(added.bank.code, true)
        backups.reasons.clear()

        repository.setArchived(added.bank.code, true)

        assertEquals(emptyList<BackupReason>(), backups.reasons)
    }

    @Test
    fun `a move at the end of the list asks for no backup`() = runTest {
        val added = repository.add("Sense Bank") as AddBankResult.Added
        backups.reasons.clear()

        repository.moveDown(added.bank.code)

        // Already last, so the order in the file is unchanged.
        assertEquals(emptyList<BackupReason>(), backups.reasons)
    }

    @Test
    fun `a new bank gets a generated code the user never sees`() = runTest {
        val result = repository.add("Sense Bank")

        val bank = (result as AddBankResult.Added).bank
        assertTrue("code should be opaque, was ${bank.code}", bank.code.startsWith("bank-"))
        assertEquals("Sense Bank", bank.displayName)
        assertEquals(listOf("Sense Bank"), allNames())
    }

    @Test
    fun `a new bank is appended after the ones already there`() = runTest {
        repository.add("First")
        repository.add("Second")

        assertEquals(listOf("First", "Second"), allNames())
    }

    @Test
    fun `a name that is only whitespace is refused`() = runTest {
        assertEquals(AddBankResult.BlankName, repository.add("   "))
        assertTrue(allNames().isEmpty())
    }

    @Test
    fun `a name already in use is refused however it is capitalised`() = runTest {
        repository.add("Sense Bank")

        assertEquals(AddBankResult.NameTaken, repository.add("sense bank"))
        assertEquals(listOf("Sense Bank"), allNames())
    }

    @Test
    fun `a Cyrillic name already in use is refused whatever its case`() = runTest {
        // The reason this is decided in Kotlin: SQL lower() would fold these to the same
        // value only if it handled Cyrillic, which it does not.
        repository.add("ПриватБанк")

        assertEquals(AddBankResult.NameTaken, repository.add("приватбанк"))
        assertEquals(listOf("ПриватБанк"), allNames())
    }

    @Test
    fun `a name differing only by surrounding whitespace is the same name`() = runTest {
        repository.add("Sense Bank")

        assertEquals(AddBankResult.NameTaken, repository.add("  Sense Bank  "))
    }

    @Test
    fun `a generated code that is already taken is retried rather than overwriting`() = runTest {
        val generator = ScriptedRandom(
            listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        )
        open(BankCodeGenerator(generator))
        repository.add("First")
        // The generator hands out the same first code again, so the second add collides.
        val second = repository.add("Second")

        assertEquals("bank-010203", repository.banks.first().first { it.displayName == "First" }.code)
        assertEquals("bank-040506", (second as AddBankResult.Added).bank.code)
        assertEquals(listOf("First", "Second"), allNames())
    }

    @Test
    fun `a code that cannot be minted is reported rather than blamed on the name`() = runTest {
        // Every attempt draws the same three bytes, so no number of retries can help. The
        // answer has to say so on its own: telling the user to choose a different name would
        // send them after a problem they do not have.
        val generator = ScriptedRandom(List(BankRepository.CODE_ATTEMPTS) { byteArrayOf(1, 2, 3) })
        open(BankCodeGenerator(generator))
        repository.add("First")

        assertEquals(AddBankResult.CodeUnavailable, repository.add("Second"))
        // Nothing was written on the way to giving up, so the list is still just the one.
        assertEquals(listOf("First"), allNames())
    }

    @Test
    fun `a blank name is still refused before any code is drawn`() = runTest {
        // Checked ahead of the code so an exhausted generator cannot turn a blank name into
        // the wrong error.
        val generator = ScriptedRandom(List(BankRepository.CODE_ATTEMPTS) { byteArrayOf(1, 2, 3) })
        open(BankCodeGenerator(generator))
        repository.add("First")

        assertEquals(AddBankResult.BlankName, repository.add("   "))
    }

    @Test
    fun `renaming leaves the code alone`() = runTest {
        val bank = (repository.add("Old Name") as AddBankResult.Added).bank

        repository.rename(bank.code, "New Name")

        val stored = repository.banks.first().single()
        assertEquals("New Name", stored.displayName)
        assertEquals(bank.code, stored.code)
    }

    @Test
    fun `renaming a bank does not rewrite the rows that already name it`() = runTest {
        // The stored haystack is frozen at write time, so a rename must not touch it. Were it
        // rewritten, what past rows match on would change under the user, and every renamed
        // bank would need a data migration to go with it.
        val bank = (repository.add("Old Name") as AddBankResult.Added).bank
        db.transactionDao().insert(
            TransactionEntity(
                userId = "uid-1",
                title = "Coffee",
                amount = 5.0,
                type = TransactionType.EXPENSE,
                category = "food",
                timestamp = 0,
                bankCode = bank.code,
                searchText = SearchText.of(
                    "Coffee",
                    null,
                    "food",
                    BankNames.ref(bank.code, repository.names.first()),
                    null
                )
            )
        )

        repository.rename(bank.code, "New Name")

        val stored = db.transactionDao().getAllForUser("uid-1").first().single()
        assertTrue(
            "stored haystack was rewritten: ${stored.searchText}",
            stored.searchText!!.contains("old name")
        )
        // The name resolves to the new one everywhere it is read live.
        assertEquals("New Name", repository.nameOf(bank.code))
    }

    @Test
    fun `renaming to a different casing of the bank's own name is allowed`() = runTest {
        val bank = (repository.add("Sense Bank") as AddBankResult.Added).bank

        assertTrue(repository.rename(bank.code, "SENSE BANK"))
        assertEquals(listOf("SENSE BANK"), allNames())
    }

    @Test
    fun `renaming onto another bank's name is refused and changes nothing`() = runTest {
        repository.add("First")
        val second = (repository.add("Second") as AddBankResult.Added).bank

        assertFalse(repository.rename(second.code, "first"))
        assertEquals(listOf("First", "Second"), allNames())
    }

    @Test
    fun `renaming to a blank name is refused`() = runTest {
        val bank = (repository.add("Sense Bank") as AddBankResult.Added).bank

        assertFalse(repository.rename(bank.code, "  "))
        assertEquals(listOf("Sense Bank"), allNames())
    }

    @Test
    fun `archiving removes a bank from the import picker but not from the list`() = runTest {
        val bank = (repository.add("Sense Bank") as AddBankResult.Added).bank

        repository.setArchived(bank.code, true)

        assertTrue(activeNames().isEmpty())
        assertEquals(listOf("Sense Bank"), allNames())
    }

    @Test
    fun `restoring an archived bank brings it back to the import picker`() = runTest {
        val bank = (repository.add("Sense Bank") as AddBankResult.Added).bank
        repository.setArchived(bank.code, true)

        repository.setArchived(bank.code, false)

        assertEquals(listOf("Sense Bank"), activeNames())
    }

    @Test
    fun `an archived bank is labelled so the filter can say why it is still there`() = runTest {
        val bank = (repository.add("Sense Bank") as AddBankResult.Added).bank
        repository.setArchived(bank.code, true)

        val stored = repository.banks.first().single()
        assertEquals("Sense Bank (archived)", stored.label())
    }

    @Test
    fun `moving a bank up swaps it with the one above`() = runTest {
        repository.add("First")
        repository.add("Second")
        val third = (repository.add("Third") as AddBankResult.Added).bank

        assertTrue(repository.moveUp(third.code))

        assertEquals(listOf("First", "Third", "Second"), allNames())
    }

    @Test
    fun `moving a bank down swaps it with the one below`() = runTest {
        repository.add("First")
        val first = repository.banks.first().first { it.displayName == "First" }
        repository.add("Second")

        assertTrue(repository.moveDown(first.code))

        assertEquals(listOf("Second", "First"), allNames())
    }

    @Test
    fun `moving the top bank up changes nothing`() = runTest {
        repository.add("First")
        repository.add("Second")
        val first = repository.banks.first().first { it.displayName == "First" }

        assertFalse(repository.moveUp(first.code))

        assertEquals(listOf("First", "Second"), allNames())
    }

    @Test
    fun `moving the bottom bank down changes nothing`() = runTest {
        repository.add("First")
        repository.add("Second")
        val second = repository.banks.first().first { it.displayName == "Second" }

        assertFalse(repository.moveDown(second.code))

        assertEquals(listOf("First", "Second"), allNames())
    }

    @Test
    fun `moving a bank that does not exist changes nothing`() = runTest {
        repository.add("First")

        assertFalse(repository.moveUp("bank-nope"))
        assertFalse(repository.moveDown("bank-nope"))

        assertEquals(listOf("First"), allNames())
    }

    @Test
    fun `repeated moves do not leave two banks sharing a position`() = runTest {
        repository.add("First")
        repository.add("Second")
        repository.add("Third")
        val third = repository.banks.first().first { it.displayName == "Third" }

        repeat(2) { repository.moveUp(third.code) }

        assertEquals(
            listOf(0, 1, 2),
            repository.banks.first().map { it.position }
        )
        assertEquals(listOf("Third", "First", "Second"), allNames())
    }

    @Test
    fun `the filter offers an archived bank that transactions still reference`() = runTest {
        val bank = (repository.add("Sense Bank") as AddBankResult.Added).bank
        seedTransactions(bank.code)
        repository.setArchived(bank.code, true)

        assertEquals(listOf("Sense Bank (archived)"), repository.filterBanks.first().map { it.label() })
    }

    @Test
    fun `the filter drops an archived bank that nothing references`() = runTest {
        val bank = (repository.add("Sense Bank") as AddBankResult.Added).bank
        repository.setArchived(bank.code, true)

        assertTrue(repository.filterBanks.first().isEmpty())
    }

    @Test
    fun `the filter offers a live bank even if it has no transactions yet`() = runTest {
        repository.add("Sense Bank")

        // Otherwise a bank added seconds ago would be invisible in the filter, which is
        // the opposite of what the user just asked for.
        assertEquals(listOf("Sense Bank"), repository.filterBanks.first().map { it.label() })
    }

    @Test
    fun `a hand-entered row does not make a bank look referenced`() = runTest {
        repository.add("Sense Bank")
        seedTransactions(null)

        assertTrue(repository.filterBanks.first().isNotEmpty())
        assertEquals(listOf("Sense Bank"), repository.filterBanks.first().map { it.label() })
    }

    @Test
    fun `names resolve to the display name`() = runTest {
        val bank = (repository.add("Sense Bank") as AddBankResult.Added).bank

        assertEquals("Sense Bank", repository.nameOf(bank.code))
    }

    @Test
    fun `a row with no bank reads as manual`() = runTest {
        assertEquals("Manual", repository.nameOf(null))
    }

    @Test
    fun `a code that is not in the list falls back to the code itself`() = runTest {
        // Never blank and never "Manual": a dangling reference must stay distinguishable
        // from a genuinely hand-entered row.
        assertEquals("bank-gone", repository.nameOf("bank-gone"))
    }

    @Test
    fun `an archived bank still resolves to its plain name`() = runTest {
        val bank = (repository.add("Sense Bank") as AddBankResult.Added).bank
        repository.setArchived(bank.code, true)

        // The "(archived)" marker belongs to the filter list. A transaction row reading
        // "Sense Bank (archived)" would be wrong.
        assertEquals("Sense Bank", repository.nameOf(bank.code))
    }

    @Test
    fun `an unknown bank resolves to nothing rather than a broken placeholder`() = runTest {
        assertNull(repository.names.first()["bank-gone"])
    }
}
