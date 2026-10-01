package com.financetracker.backup

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.financetracker.data.AppDatabase
import com.financetracker.data.backup.BackupEncoding
import com.financetracker.data.backup.BackupRestorer
import com.financetracker.data.backup.BackupSnapshotCodec
import com.financetracker.data.backup.BackupSnapshot
import com.financetracker.data.backup.RecurringPaymentRow
import com.financetracker.data.backup.RestoreResult
import com.financetracker.data.backup.TransactionRow
import com.financetracker.model.BankEntity
import com.financetracker.model.BondEntity
import com.financetracker.model.BondTradeEntity
import com.financetracker.model.BondTradeSide
import com.financetracker.model.RepeatFrequency
import com.financetracker.model.SearchText
import com.financetracker.model.TransactionEntity
import com.financetracker.model.TransactionType
import com.financetracker.model.TransferDirection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises [BackupRestorer] against a real database.
 *
 * The things worth testing here are all about the database rather than about Kotlin: which
 * rows end up present, that the trade-to-cash pairing survives an id renumbering, and that a
 * merge does not quietly double anything. A fake DAO would agree with all of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupRestorerTest {

    private lateinit var db: AppDatabase
    private lateinit var restorer: BackupRestorer

    private val me = "uid-me"
    private val them = "uid-them"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        restorer = BackupRestorer(db, com.financetracker.testing.FakeBackupRequests())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun snapshot(
        uid: String = me,
        transactions: List<TransactionRow> = emptyList(),
        banks: List<BankEntity> = emptyList(),
        bonds: List<BondEntity> = emptyList(),
        bondTrades: List<BondTradeEntity> = emptyList(),
        recurringPayments: List<RecurringPaymentRow> = emptyList()
    ) = BackupSnapshot(
        format = BackupSnapshot.FORMAT,
        appVersion = "1.0",
        createdAt = 0L,
        uid = uid,
        transactions = transactions,
        banks = banks,
        bonds = bonds,
        bondTrades = bondTrades,
        recurringPayments = recurringPayments
    )

    private fun row(
        id: Long = 0,
        title: String = "Кава",
        amount: Double = 10.0,
        type: TransactionType = TransactionType.EXPENSE,
        externalId: String? = null,
        timestamp: Long = 1_700_000_000_000L
    ) = TransactionRow(
        id = id,
        title = title,
        amount = amount,
        type = type,
        category = "groceries",
        timestamp = timestamp,
        externalId = externalId
    )

    private suspend fun stored(uid: String = me) = db.transactionDao().getAllForUser(uid).first()

    /** Unwraps the success case, so a test about counts does not repeat the cast five times. */
    private fun RestoreResult.done(): RestoreResult.Done {
        assertTrue("expected a restore, got $this", this is RestoreResult.Done)
        return this as RestoreResult.Done
    }

    @Test
    fun `a snapshot for another account is refused and writes nothing`() = runTest {
        val result = restorer.restore(snapshot(uid = them, transactions = listOf(row())), me)

        // The banks and bonds in a file have no account on them, so a file belonging to
        // someone else carries this device's second account's bond positions. Refusing the
        // whole file is the only way none of it lands.
        assertEquals(RestoreResult.WrongAccount(them), result)
        assertEquals(emptyList<TransactionEntity>(), stored())
        assertEquals(emptyList<BankEntity>(), db.bankDao().getAllOnce())
    }

    @Test
    fun `rows land under the signed-in account, not the one in the file`() = runTest {
        restorer.restore(snapshot(uid = me, transactions = listOf(row())), me)

        assertEquals(1, stored(me).size)
        assertEquals(emptyList<TransactionEntity>(), stored(them))
    }

    @Test
    fun `a row keeps everything it carried, including the baked search text`() = runTest {
        val baked = SearchText.of("Кава", null, "groceries", com.financetracker.model.BankRef("mo", "Monobank"), null)
        val row = row().copy(searchText = baked, bankCode = "mo", currencyCode = "UAH", note = "note")

        restorer.restore(snapshot(transactions = listOf(row)), me)

        val restored = stored().single()
        // The haystack is copied across rather than rebuilt: rebuilding it here would use the
        // bank name as it is now, and the whole app deliberately never rewrites this column.
        assertEquals(baked, restored.searchText)
        assertEquals("mo", restored.bankCode)
        assertEquals("UAH", restored.currencyCode)
        assertEquals("note", restored.note)
    }

    @Test
    fun `a trade and its cash row stay paired after the ids are renumbered`() = runTest {
        // The file's ids belong to the device that wrote it. On a device with rows of its own
        // they cannot be reused, so the cash row is inserted afresh and the trade is pointed
        // at whatever id that insert produced.
        db.transactionDao().insert(
            TransactionEntity(
                userId = me,
                title = "existing",
                amount = 1.0,
                type = TransactionType.EXPENSE,
                category = "other"
            )
        )
        val snapshot = snapshot(
            transactions = listOf(row(id = 141, title = "ranch", type = TransactionType.TRANSFER)),
            bonds = listOf(BondEntity("UA3501234567", "ОвДП 24/Б", 1000.0, "UAH")),
            bondTrades = listOf(
                BondTradeEntity(
                    id = 1,
                    isin = "UA3501234567",
                    side = BondTradeSide.BUY,
                    quantity = 10,
                    price = 1032.0,
                    accruedInterest = 22.0,
                    commission = 33.0,
                    tradeDate = 1_700_000_000_000L,
                    transactionId = 141
                )
            )
        )

        restorer.restore(snapshot, me)

        val trade = db.bondDao().getTrades().single()
        val cash = stored().single { it.title == "ranch" }
        // The pairing is the one relationship that cannot be recovered by matching rows, so
        // this is the assertion that matters most in the whole file.
        assertEquals(cash.id, trade.transactionId)
    }

    @Test
    fun `a row already in the database is skipped rather than added again`() = runTest {
        db.transactionDao().insert(
            TransactionEntity(
                userId = me,
                title = "Кава",
                amount = 10.0,
                type = TransactionType.EXPENSE,
                category = "groceries",
                externalId = "mo_import_abc"
            )
        )

        val result = restorer.restore(
            snapshot(transactions = listOf(row(externalId = "mo_import_abc"))),
            me
        )

        // Importing the same window twice has to be a no-op. This is what makes re-running a
        // restore safe, and it is why a bank-assigned id is the thing rows are matched on.
        assertEquals(1, stored().size)
        assertEquals(0, result.done().restored)
        assertEquals(1, result.done().skipped)
    }

    @Test
    fun `restoring the same file twice leaves the database as it was after the first`() = runTest {
        val file = snapshot(
            transactions = listOf(
                row(externalId = "mo_import_abc"),
                row(title = "Manual", amount = 99.0, externalId = null),
                row(title = "Manual too", amount = 5.0, externalId = null)
            )
        )

        val first = restorer.restore(file, me)
        val second = restorer.restore(file, me)

        assertEquals(3, first.done().restored)
        assertEquals(0, second.done().restored)
        // Hand-entered rows carry no bank id, so they are matched on their content. Without
        // that, every re-run would duplicate the rows a user typed in themselves.
        assertEquals(3, stored().size)
    }

    @Test
    fun `rows belonging to another account are not matched against this one's rows`() = runTest {
        db.transactionDao().insert(
            TransactionEntity(
                userId = them,
                title = "Кава",
                amount = 10.0,
                type = TransactionType.EXPENSE,
                category = "groceries",
                externalId = "mo_import_abc"
            )
        )

        restorer.restore(snapshot(transactions = listOf(row(externalId = "mo_import_abc"))), me)

        assertEquals(1, stored(me).size)
        assertEquals(1, stored(them).size)
    }

    @Test
    fun `a bank already here keeps its own name`() = runTest {
        db.bankDao().insert(BankEntity("mo", "Monobank", 0, builtIn = true))

        restorer.restore(
            snapshot(banks = listOf(BankEntity("mo", "MONOBANK", 0, builtIn = true))),
            me
        )

        // Not cosmetic. Every stored row's searchText bakes in the bank name as it was when
        // that row was written, and the seeded names have to stay byte-identical to what
        // BankCode.label() produced or restored rows stop matching their own bank rows.
        assertEquals("Monobank", db.bankDao().getByCode("mo")!!.displayName)
    }

    @Test
    fun `a bank missing here is added`() = runTest {
        restorer.restore(
            snapshot(banks = listOf(BankEntity("sense", "Sense Bank", 7, builtIn = false))),
            me
        )

        val bank = db.bankDao().getByCode("sense")
        assertNotNull(bank)
        assertEquals("Sense Bank", bank!!.displayName)
        assertEquals(false, bank.builtIn)
    }

    @Test
    fun `a bond already here keeps its stored terms`() = runTest {
        db.bondDao().insertIfAbsent(BondEntity("UA3501234567", "ОвДП 24/Б", 1000.0, "UAH", couponPercent = 9.5))

        restorer.restore(
            snapshot(
                bonds = listOf(
                    BondEntity("UA3501234567", "овдп 24/б", 1000.0, "UAH", couponPercent = 12.0)
                )
            ),
            me
        )

        // A later trade does not overwrite stored terms, and neither does a restore: the
        // user may have corrected the coupon since the file was written.
        assertEquals(9.5, db.bondDao().getByIsin("UA3501234567")!!.couponPercent!!, 0.0)
    }

    @Test
    fun `a bond missing here is added, and the trade can then point at it`() = runTest {
        val snapshot = snapshot(
            bonds = listOf(BondEntity("UA3501234567", "ОвДП 24/Б", 1000.0, "UAH")),
            bondTrades = listOf(
                BondTradeEntity(
                    isin = "UA3501234567",
                    side = BondTradeSide.BUY,
                    quantity = 1,
                    price = 1000.0,
                    tradeDate = 1_700_000_000_000L
                )
            )
        )

        restorer.restore(snapshot, me)

        // The trade's foreign key is on the isin, so the bond has to be in place first or the
        // insert is refused and the trade is lost.
        assertEquals(1, db.bondDao().getTrades().size)
    }

    @Test
    fun `a trade already in the database is not added again`() = runTest {
        // The cash row a trade belongs to is stamped with the trade's date, not with the
        // moment the row was written, so the same trade matches the same cash row on every
        // device. That shared stamp is the whole of the identity here.
        val tradeDate = 1_700_000_000_000L
        val cashId = db.transactionDao().insert(
            TransactionEntity(
                userId = me,
                title = "ranch",
                amount = 10573.0,
                type = TransactionType.TRANSFER,
                category = "investments",
                timestamp = tradeDate,
                externalId = null
            )
        )
        db.bondDao().insertIfAbsent(BondEntity("UA3501234567", "ranch", 1000.0, "UAH"))
        db.bondDao().insertTrade(
            BondTradeEntity(
                isin = "UA3501234567",
                side = BondTradeSide.BUY,
                quantity = 10,
                price = 1032.0,
                accruedInterest = 22.0,
                commission = 33.0,
                tradeDate = tradeDate,
                transactionId = cashId
            )
        )

        // Same trade, same cash row. The bond-trade pair has no external id of its own, so it
        // is recognised by the cash row it belongs to.
        val result = restorer.restore(
            snapshot(
                transactions = listOf(
                    row(
                        id = cashId,
                        title = "ranch",
                        amount = 10573.0,
                        type = TransactionType.TRANSFER,
                        timestamp = tradeDate,
                        externalId = null
                    )
                ),
                bonds = listOf(BondEntity("UA3501234567", "ranch", 1000.0, "UAH")),
                bondTrades = listOf(
                    BondTradeEntity(
                        isin = "UA3501234567",
                        side = BondTradeSide.BUY,
                        quantity = 10,
                        price = 1032.0,
                        accruedInterest = 22.0,
                        commission = 33.0,
                        tradeDate = tradeDate,
                        transactionId = cashId
                    )
                )
            ),
            me
        )

        assertEquals(1, db.bondDao().getTrades().size)
        assertEquals(0, result.done().trades)
    }

    @Test
    fun `a hand-entered row whose stamp was moved is treated as a different row`() = runTest {
        db.transactionDao().insert(
            TransactionEntity(
                userId = me,
                title = "Кава",
                amount = 10.0,
                type = TransactionType.EXPENSE,
                category = "groceries",
                timestamp = 1_700_000_000_000L,
                externalId = null
            )
        )

        // Same title, same amount, different minute. Collapsing these into one row would
        // quietly delete a real purchase, which is a worse failure than the duplicate that
        // matching on the bank's own id exists to prevent.
        restorer.restore(
            snapshot(
                transactions = listOf(
                    row(title = "Кава", timestamp = 1_700_000_060_000L, externalId = null)
                )
            ),
            me
        )

        assertEquals(2, stored().size)
    }

    @Test
    fun `a file with nothing in it restores nothing and says so`() = runTest {
        val result = restorer.restore(snapshot(), me)

        assertEquals(0, result.done().restored)
        assertTrue(!result.done().changed)
    }

    @Test
    fun `a restore asks for a backup of its own`() = runTest {
        val backups = com.financetracker.testing.FakeBackupRequests()
        val restorer = BackupRestorer(db, backups)

        restorer.restore(snapshot(transactions = listOf(row())), me)

        // Otherwise the file in Drive would still be the pre-restore state, and the next
        // upload would be the first thing that carried the new rows — which is right, but
        // only by luck of when the user happened to next change something.
        assertEquals(1, backups.reasons.size)
    }

    @Test
    fun `a restore that changed nothing asks for no backup`() = runTest {
        val backups = com.financetracker.testing.FakeBackupRequests()
        val restorer = BackupRestorer(db, backups)

        restorer.restore(snapshot(), me)

        assertEquals(emptyList<com.financetracker.data.backup.BackupReason>(), backups.reasons)
    }

    @Test
    fun `the account id of a row is taken from the signed-in one, never from the file`() = runTest {
        restorer.restore(snapshot(uid = me, transactions = listOf(row())), me)

        // Belt and braces on a property the row type already enforces: a file cannot name the
        // account its rows are written to, so there is nothing in it to write.
        assertEquals(me, stored().single().userId)
        assertNull(db.transactionDao().getAllForUser("someone else").first().firstOrNull())
    }

    @Test
    fun `a compressed backup and an uncompressed one restore identically`() = runTest {
        // Uploads used to be plain JSON and now are gzip. Both files are the user's, both are
        // in Drive, and a restore that only understood the new one would report the older as
        // corrupt — so the two are held to the same outcome here rather than trusting the
        // encoding tests to cover it.
        val snapshot = snapshot(
            transactions = listOf(row(externalId = "uk_import_abc"), row(title = "Manual"))
        )
        val codec = BackupSnapshotCodec()
        val text = codec.encode(snapshot)

        val plain = restorer.restore(snapshot, me)
        assertEquals(2, plain.done().restored)

        val other = BackupRestorer(
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(), AppDatabase::class.java
            ).allowMainThreadQueries().build(),
            com.financetracker.testing.FakeBackupRequests()
        )
        val compressed = other.restore(snapshot, me)

        assertEquals(plain, compressed)
    }

    @Test
    fun `a backup from before compression still decodes to a restorable snapshot`() = runTest {
        val legacy = """{"format":1,"appVersion":"1.0","createdAt":0,"uid":"$me",""" +
            """"transactions":[{"id":1,"userId":"$me","title":"Кава","amount":10.0,""" +
            """"type":"EXPENSE","category":"groceries","timestamp":1700000000000}],""" +
            """"banks":[],"bonds":[],"bondTrades":[]}"""

        // The whole path a user with a year-old backup in Drive takes: read the bytes as they
        // are, hand the text over, get a snapshot that restores.
        val result = restorer.restore(
            BackupSnapshotCodec().decode(BackupEncoding.decode(legacy.toByteArray())),
            me
        )

        assertEquals(1, result.done().restored)
        assertEquals(listOf("Кава"), stored().map { it.title })
    }

    // --- Recurring payment schedules (format 3) ---------------------------------------------

    private fun schedule(
        id: Long = 0,
        title: String = "Оренда",
        amount: Double = 12_000.0,
        frequency: RepeatFrequency = RepeatFrequency.MONTHLY
    ) = RecurringPaymentRow(
        id = id,
        title = title,
        amount = amount,
        type = TransactionType.EXPENSE,
        category = "other",
        currencyCode = "UAH",
        frequency = frequency,
        startDate = 1_000L
    )

    private suspend fun scheduleEntity(id: Long = 0, title: String = "Оренда") =
        schedule(title = title).toEntity(me, id)

    @Test
    fun `a schedule in the file that is not here is written for the account`() = runTest {
        val result = restorer.restore(
            snapshot(
                recurringPayments = listOf(
                    schedule(title = "Оренда"),
                    schedule(title = "Netflix", amount = 500.0)
                )
            ),
            me
        )

        assertEquals(2, result.done().recurring)
        assertTrue(result.done().changed)
        assertEquals(
            listOf("Оренда", "Netflix"),
            db.recurringPaymentDao().getAllForUserOnce(me).map { it.title }
        )
    }

    @Test
    fun `restoring the same file twice does not double the schedules`() = runTest {
        val file = snapshot(recurringPayments = listOf(schedule(title = "Оренда")))

        restorer.restore(file, me)
        val second = restorer.restore(file, me)

        assertEquals(0, second.done().recurring)
        assertEquals(1, db.recurringPaymentDao().getAllForUserOnce(me).size)
        // Nothing landed on the second pass, so nothing is uploaded.
        assertTrue(!second.done().changed)
    }

    @Test
    fun `a schedule already here keeps the id it has here`() = runTest {
        val existingId = db.recurringPaymentDao().insert(scheduleEntity(title = "Оренда"))

        val result = restorer.restore(
            snapshot(recurringPayments = listOf(schedule(id = 999, title = "Оренда"))),
            me
        )

        // The file's id is not reused: matching content is skipped, so no second row appears.
        assertEquals(0, result.done().recurring)
        assertEquals(
            listOf(existingId),
            db.recurringPaymentDao().getAllForUserOnce(me).map { it.id }
        )
    }

    @Test
    fun `a file with no schedules key still restores`() = runTest {
        // The shape of a v1 or v2 file: the field is absent, so the reader sees null and the
        // restore treats it as none rather than failing.
        val result = restorer.restore(snapshot(transactions = listOf(row())), me)

        assertEquals(1, result.done().restored)
        assertEquals(0, result.done().recurring)
    }

    @Test
    fun `a schedule archived on this device is not duplicated by a restore`() = runTest {
        val id = db.recurringPaymentDao().insert(scheduleEntity(title = "Оренда"))
        db.recurringPaymentDao().setArchived(id, true)

        // The file was taken before the archive: the same commitment, not marked archived.
        val result = restorer.restore(
            snapshot(recurringPayments = listOf(schedule(title = "Оренда"))),
            me
        )

        // Archiving is this device's ordering, exactly as a bank's flag is. Treating it as part
        // of the identity would put an un-archived twin beside the archived row, and the forecast
        // would count the same rent twice.
        assertEquals(0, result.done().recurring)
        val rows = db.recurringPaymentDao().getAllForUserOnce(me)
        assertEquals(1, rows.size)
        assertTrue(rows.single().archived)
    }

}
