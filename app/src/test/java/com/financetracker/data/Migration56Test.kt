package com.financetracker.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises [AppDatabase.MIGRATION_5_6] against a database shaped like the real v5 one.
 *
 * Hand-built for the same reason as [Migration45Test]: `exportSchema = false` leaves no
 * schema file for MigrationTestHelper to open.
 *
 * The centre of gravity here is that no existing row may change. v6 adds a nullable column
 * and two new tables, and the only writing it does is a `CREATE`. Anything else — a
 * backfill, a `searchText` rebuild, a re-derivation of `type` — would be a silent rewrite of
 * data the user has been looking at for months.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Migration56Test {

    private lateinit var db: SupportSQLiteDatabase

    private val v5Columns =
        "id, userId, title, amount, type, category, timestamp, note, externalId, " +
            "source, currencyCode, bankCode, cardLabel, searchText"

    private val syncedSearchText = "мобільний оператор note one grocery monobank"

    @Before
    fun setUp() {
        val config = SupportSQLiteOpenHelper.Configuration
            .builder(ApplicationProvider.getApplicationContext())
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `transactions` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`userId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                            "`amount` REAL NOT NULL, `type` TEXT NOT NULL, " +
                            "`category` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, " +
                            "`note` TEXT, `externalId` TEXT, `source` TEXT, " +
                            "`currencyCode` TEXT, `bankCode` TEXT, `cardLabel` TEXT, " +
                            "`searchText` TEXT)"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `users` (" +
                            "`uid` TEXT NOT NULL, `email` TEXT NOT NULL, " +
                            "`displayName` TEXT, `photoUrl` TEXT, PRIMARY KEY(`uid`))"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `banks` (" +
                            "`code` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
                            "`position` INTEGER NOT NULL, `archived` INTEGER NOT NULL, " +
                            "`builtIn` INTEGER NOT NULL, PRIMARY KEY(`code`))"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        db = FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase

        db.execSQL(
            "INSERT INTO transactions ($v5Columns) VALUES " +
                "(1,'uid-1','МОБІЛЬНИЙ ОПЕРАТОР',10.0,'EXPENSE','grocery',1000," +
                "'note one','mo_1','monobank','UAH','mo','535129****5783'," +
                "'$syncedSearchText')," +
                "(2,'uid-1','Зарплата',5000.0,'INCOME','salary',2000," +
                "NULL,NULL,'monobank','UAH','mo',NULL,'зарплата salary monobank')," +
                "(3,'uid-1','Lunch',30.0,'EXPENSE','food',3000," +
                "NULL,NULL,NULL,NULL,NULL,NULL,'lunch food')"
        )
    }

    private fun rows() = db.query(
        "SELECT id, userId, title, amount, type, category, timestamp, note, externalId, " +
            "source, currencyCode, bankCode, cardLabel, searchText FROM transactions ORDER BY id"
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                // Null-safe: a hand-entered row has no note, no external id and no bank, and
                // getString on those returns null rather than "". Comparing the two as
                // unequal would be a difference that is not there.
                add((0..13).joinToString("|") { if (c.isNull(it)) "~" else c.getString(it) })
            }
        }
    }

    @Test
    fun `no existing row changes in any column`() {
        val before = rows()

        AppDatabase.MIGRATION_5_6.migrate(db)

        assertEquals(before, rows())
        assertEquals(3, rows().size)
    }

    @Test
    fun `search text stays byte identical, so every row stays findable`() {
        val before = rows().map { it.substringAfterLast("|") }

        AppDatabase.MIGRATION_5_6.migrate(db)

        assertEquals(before, rows().map { it.substringAfterLast("|") })
        // The user-visible consequence, stated separately: a bank rename or a migration that
        // touched the haystack would make a stored row unfindable by its own bank's name.
        val findable = db.query(
            "SELECT COUNT(*) FROM transactions WHERE searchText LIKE '%monobank%'"
        ).use { if (it.moveToNext()) it.getInt(0) else 0 }
        assertEquals(2, findable)
    }

    @Test
    fun `every existing row gets a null direction rather than a guessed one`() {
        AppDatabase.MIGRATION_5_6.migrate(db)

        val directions = db.query("SELECT transferDirection FROM transactions ORDER BY id").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

        // Not defaulted to anything. A direction invented at upgrade time would move cash on
        // rows that were never transfers, and the balance is what this screen is read for.
        assertEquals(listOf(null, null, null), directions)
    }

    @Test
    fun `the transfer column starts out null on a real row`() {
        AppDatabase.MIGRATION_5_6.migrate(db)

        val value = db.query(
            "SELECT transferDirection FROM transactions WHERE id = 1"
        ).use { if (it.moveToNext()) c0(it) else "missing" }

        assertNull(value)
    }

    private fun c0(c: android.database.Cursor): String? =
        if (c.isNull(0)) null else c.getString(0)

    @Test
    fun `the bond tables are created and start empty`() {
        AppDatabase.MIGRATION_5_6.migrate(db)

        assertEquals(0, count("bonds"))
        assertEquals(0, count("bond_trades"))
    }

    @Test
    fun `a bond round trips through the new table`() {
        AppDatabase.MIGRATION_5_6.migrate(db)

        db.execSQL(
            "INSERT INTO bonds (isin, name, nominalUAH, couponPercent, couponPeriodMonths, maturityDate) " +
                "VALUES ('UA9000012345','ОвДП 24/Б',1000.0,9.5,3,1900000000000)"
        )

        val row = db.query(
            "SELECT isin, name, nominalUAH, couponPercent, couponPeriodMonths, maturityDate FROM bonds"
        ).use { c ->
            c.moveToFirst()
            listOf(
                c.getString(0), c.getString(1), c.getDouble(2),
                c.getDouble(3), c.getInt(4), c.getLong(5)
            )
        }

        assertEquals(
            listOf("UA9000012345", "ОвДП 24/Б", 1000.0, 9.5, 3, 1900000000000L),
            row
        )
    }

    @Test
    fun `bond terms are optional, so a trade can be recorded with an ISIN alone`() {
        AppDatabase.MIGRATION_5_6.migrate(db)

        db.execSQL(
            "INSERT INTO bonds (isin, name, nominalUAH) VALUES ('UA9000099999','Overlay',1000.0)"
        )

        val row = db.query(
            "SELECT couponPercent, couponPeriodMonths, maturityDate FROM bonds"
        ).use { c ->
            c.moveToFirst()
            listOf(c.isNull(0), c.isNull(1), c.isNull(2))
        }

        assertEquals(listOf(true, true, true), row)
    }

    @Test
    fun `a trade with no accrual and no commission reads back as zero`() {
        AppDatabase.MIGRATION_5_6.migrate(db)

        db.execSQL("INSERT INTO bonds (isin, name, nominalUAH) VALUES ('UA9000012345','ОвДП',1000.0)")
        // Both are NOT NULL in the schema, so they are named explicitly here exactly as
        // Room's own insert does. The point of the test is that zero survives, not that the
        // column may be omitted.
        db.execSQL(
            "INSERT INTO bond_trades " +
                "(isin, side, quantity, pricePercent, accruedInterestUAH, commissionUAH, tradeDate) " +
                "VALUES ('UA9000012345','BUY',2,99.5,0.0,0.0,1700000000000)"
        )

        val row = db.query(
            "SELECT accruedInterestUAH, commissionUAH, bankCode, transactionId FROM bond_trades"
        ).use { c ->
            c.moveToFirst()
            listOf(c.getDouble(0), c.getDouble(1), c.isNull(2), c.isNull(3))
        }

        assertEquals(listOf(0.0, 0.0, true, true), row)
    }

    @Test
    fun `a trade cannot reference an instrument that is not there`() {
        AppDatabase.MIGRATION_5_6.migrate(db)
        db.execSQL("INSERT INTO bonds (isin, name, nominalUAH) VALUES ('UA9000012345','ОвДП',1000.0)")

        // Foreign keys are off by default in SQLite, so this has to be switched on for the
        // constraint to mean anything. A test that left them off would pass against a
        // migration whose foreign key clause had been dropped entirely.
        db.setForeignKeyConstraintsEnabled(true)
        try {
            val failed = runCatching {
                db.execSQL(
                    "INSERT INTO bond_trades " +
                        "(isin, side, quantity, pricePercent, accruedInterestUAH, commissionUAH, tradeDate) " +
                        "VALUES ('UA9000099999','BUY',1,99.5,0.0,0.0,1700000000000)"
                )
            }.isFailure

            assertTrue(failed)
        } finally {
            db.setForeignKeyConstraintsEnabled(false)
        }
    }

    @Test
    fun `deleting a bond takes its trades with it`() {
        AppDatabase.MIGRATION_5_6.migrate(db)
        db.execSQL("INSERT INTO bonds (isin, name, nominalUAH) VALUES ('UA9000012345','ОвДП',1000.0)")
        db.execSQL(
            "INSERT INTO bond_trades " +
                "(isin, side, quantity, pricePercent, accruedInterestUAH, commissionUAH, tradeDate) " +
                "VALUES ('UA9000012345','BUY',1,99.5,0.0,0.0,1700000000000)"
        )

        db.setForeignKeyConstraintsEnabled(true)
        try {
            db.execSQL("DELETE FROM bonds WHERE isin = 'UA9000012345'")
        } finally {
            db.setForeignKeyConstraintsEnabled(false)
        }

        // Without the cascade the trade would survive pointing at nothing, and the position
        // fold would fail on a row whose instrument no longer has terms.
        assertEquals(0, count("bond_trades"))
    }

    @Test
    fun `deleting a bond trade's cash row takes the trade with it`() {
        // The two rows are one fact in two places, so the migration has to hold them together
        // in both directions. Without the cascade on transactionId, deleting a bond purchase
        // from the transaction list would leave the trade behind: the cash row gone, the
        // position still counting the bonds, and nothing on screen able to explain it.
        AppDatabase.MIGRATION_5_6.migrate(db)
        db.execSQL("INSERT INTO bonds (isin, name, nominalUAH) VALUES ('UA9000012345','ОвДП',1000.0)")
        db.execSQL(
            "INSERT INTO bond_trades " +
                "(isin, side, quantity, pricePercent, accruedInterestUAH, commissionUAH, tradeDate, transactionId) " +
                "VALUES ('UA9000012345','BUY',1,99.5,0.0,0.0,1700000000000,2)"
        )

        db.setForeignKeyConstraintsEnabled(true)
        try {
            db.execSQL("DELETE FROM transactions WHERE id = 2")
        } finally {
            db.setForeignKeyConstraintsEnabled(false)
        }

        assertEquals(0, count("bond_trades"))
        assertEquals(2, count("transactions"))
    }

    @Test
    fun `a trade cannot claim a cash row that is not there`() {
        AppDatabase.MIGRATION_5_6.migrate(db)
        db.execSQL("INSERT INTO bonds (isin, name, nominalUAH) VALUES ('UA9000012345','ОвДП',1000.0)")

        db.setForeignKeyConstraintsEnabled(true)
        try {
            val failed = runCatching {
                db.execSQL(
                    "INSERT INTO bond_trades " +
                        "(isin, side, quantity, pricePercent, accruedInterestUAH, commissionUAH, tradeDate, transactionId) " +
                        "VALUES ('UA9000012345','BUY',1,99.5,0.0,0.0,1700000000000,999)"
                )
            }.isFailure
            assertTrue(failed)
        } finally {
            db.setForeignKeyConstraintsEnabled(false)
        }
    }

    @Test
    fun `the banks table is left exactly as the migration found it`() {
        // Seeded before the migration rather than after, so this is a single run. A second
        // ALTER TABLE ADD COLUMN is an error, and running a migration twice is not a state
        // Room can reach: it tracks the version and runs each step once.
        db.execSQL(
            "INSERT INTO banks (code, displayName, position, archived, builtIn) " +
                "VALUES ('mo','Monobank',0,0,1)"
        )

        AppDatabase.MIGRATION_5_6.migrate(db)

        val banks = db.query("SELECT code, displayName, position FROM banks").use { c ->
            buildList { while (c.moveToNext()) add("${c.getString(0)}|${c.getString(1)}|${c.getInt(2)}") }
        }
        assertEquals(listOf("mo|Monobank|0"), banks)
    }

    @Test
    fun `the isin index exists, because every position fold reads by instrument`() {
        AppDatabase.MIGRATION_5_6.migrate(db)

        val indexes = db.query("PRAGMA index_list(bond_trades)").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
        }

        assertTrue(indexes.contains("index_bond_trades_isin"))
        assertTrue(indexes.contains("index_bond_trades_transactionId"))
    }

    private fun count(table: String): Int =
        db.query("SELECT COUNT(*) FROM $table").use { if (it.moveToNext()) it.getInt(0) else 0 }
}
