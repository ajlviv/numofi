package com.financetracker.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises [AppDatabase.MIGRATION_6_7] against a database shaped like the real v6 one.
 *
 * Hand-built for the same reason as [Migration56Test]: `exportSchema = false` leaves no
 * schema file for MigrationTestHelper to open. The v6 file is the v5 one plus the transfer
 * direction column and the two bond tables, so the transactions schema here is the v6 shape
 * and the migration under test is the one that changes it.
 *
 * The centre of gravity is that a percentage price means the same thing in money after the
 * upgrade as it did before it. `pricePercent` of 99.5 against a 1000 nominal was 995.00
 * spent per bond; that has to read back as a `price` of 995.0, or every stored trade silently
 * changes value. The transactions table must come out byte-identical, exactly as in v5→v6.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Migration67Test {

    private lateinit var db: SupportSQLiteDatabase

    private val transactionColumns =
        "id, userId, title, amount, type, category, timestamp, note, externalId, " +
            "source, currencyCode, bankCode, cardLabel, searchText, transferDirection"

    private val syncedSearchText = "мобільний оператор note one grocery monobank"

    @Before
    fun setUp() {
        val config = SupportSQLiteOpenHelper.Configuration
            .builder(ApplicationProvider.getApplicationContext())
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(6) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `transactions` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`userId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                            "`amount` REAL NOT NULL, `type` TEXT NOT NULL, " +
                            "`category` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, " +
                            "`note` TEXT, `externalId` TEXT, `source` TEXT, " +
                            "`currencyCode` TEXT, `bankCode` TEXT, `cardLabel` TEXT, " +
                            "`searchText` TEXT, `transferDirection` TEXT)"
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
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `bonds` (" +
                            "`isin` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                            "`nominalUAH` REAL NOT NULL, `couponPercent` REAL, " +
                            "`couponPeriodMonths` INTEGER, `maturityDate` INTEGER, " +
                            "PRIMARY KEY(`isin`))"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `bond_trades` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`isin` TEXT NOT NULL, `side` TEXT NOT NULL, " +
                            "`quantity` INTEGER NOT NULL, `pricePercent` REAL NOT NULL, " +
                            "`accruedInterestUAH` REAL NOT NULL, `commissionUAH` REAL NOT NULL, " +
                            "`tradeDate` INTEGER NOT NULL, `bankCode` TEXT, " +
                            "`transactionId` INTEGER, " +
                            "FOREIGN KEY(`isin`) REFERENCES `bonds`(`isin`) " +
                            "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                            "FOREIGN KEY(`transactionId`) REFERENCES `transactions`(`id`) " +
                            "ON UPDATE NO ACTION ON DELETE CASCADE )"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_bond_trades_isin` " +
                            "ON `bond_trades` (`isin`)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_bond_trades_transactionId` " +
                            "ON `bond_trades` (`transactionId`)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        db = FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase

        db.execSQL(
            "INSERT INTO transactions ($transactionColumns) VALUES " +
                "(1,'uid-1','МОБІЛЬНИЙ ОПЕРАТОР',10.0,'EXPENSE','grocery',1000," +
                "'note one','mo_1','monobank','UAH','mo','535129****5783'," +
                "'$syncedSearchText',NULL)," +
                "(2,'uid-1','ОвДП 24/Б',1990.0,'TRANSFER','investments',2000," +
                "'Купівля 2 шт. @ 99.5%',NULL,NULL,'UAH','mo',NULL," +
                "'овдп 24/б купівля 2 шт @ 995% investments monobank'," +
                "'OUT')"
        )
    }

    private fun insertBond(isin: String, name: String, nominal: Double) {
        db.execSQL(
            "INSERT INTO bonds (isin, name, nominalUAH) VALUES ('$isin','$name',$nominal)"
        )
    }

    private fun insertTrade(
        id: Long,
        isin: String,
        pricePercent: Double,
        accrued: Double = 0.0,
        commission: Double = 0.0
    ) {
        db.execSQL(
            "INSERT INTO bond_trades " +
                "(id, isin, side, quantity, pricePercent, accruedInterestUAH, commissionUAH, tradeDate) " +
                "VALUES ($id,'$isin','BUY',1,$pricePercent,$accrued,$commission,1700000000000)"
        )
    }

    @Test
    fun `percentage prices keep their value once they become money`() {
        insertBond("UA9000012345", "ОвДП 24/Б", 1000.0)
        insertBond("UA9000065432", "ОвДП 26/В", 1000.0)
        insertTrade(1, "UA9000012345", pricePercent = 99.5)
        insertTrade(2, "UA9000065432", pricePercent = 101.0)

        AppDatabase.MIGRATION_6_7.migrate(db)

        // 99.5% of 1000 was 995.00 spent per bond; 101% was 1010.00. The stored price after
        // the upgrade is the same money, spelled without the percentage.
        val prices = db.query("SELECT isin, price FROM bond_trades ORDER BY id").use { c ->
            buildList { while (c.moveToNext()) add(c.getDouble(1) to c.getString(0)) }
        }
        assertEquals(listOf(995.0, 1010.0), prices.map { it.first })
    }

    @Test
    fun `a price outside the usual nominal converts against the bond it belongs to`() {
        insertBond("UA9000012345", "ОвДП 24/Б", 1000.0)
        insertBond("UA9000077777", "ОвДП 27/Г", 500.0)
        insertTrade(1, "UA9000012345", pricePercent = 102.0)
        insertTrade(2, "UA9000077777", pricePercent = 96.0)

        AppDatabase.MIGRATION_6_7.migrate(db)

        val byIsin = db.query("SELECT isin, price FROM bond_trades").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getDouble(1)) }
        }.toMap()
        assertEquals(1020.0, byIsin["UA9000012345"]!!, 0.0)
        // 96% of a 500 nominal is 480.00, not 960.00 — the join must be against that bond's
        // own nominal, not some assumed 1000.
        assertEquals(480.0, byIsin["UA9000077777"]!!, 0.0)
    }

    @Test
    fun `accrual and commission carry over unchanged`() {
        insertBond("UA9000012345", "ОвДП 24/Б", 1000.0)
        insertTrade(1, "UA9000012345", pricePercent = 99.5, accrued = 40.0, commission = 12.0)

        AppDatabase.MIGRATION_6_7.migrate(db)

        val row = db.query(
            "SELECT price, accruedInterest, commission FROM bond_trades"
        ).use { c ->
            c.moveToFirst()
            listOf(c.getDouble(0), c.getDouble(1), c.getDouble(2))
        }
        assertEquals(listOf(995.0, 40.0, 12.0), row)
    }

    @Test
    fun `every bond becomes UAH denominated, keeping its nominal`() {
        insertBond("UA9000012345", "ОвДП 24/Б", 1000.0)
        insertBond("UA9000099999", "ОвДП 29/Д", 2000.0)

        AppDatabase.MIGRATION_6_7.migrate(db)

        val bonds = db.query("SELECT isin, nominal, nominalCurrency FROM bonds ORDER BY isin").use { c ->
            buildList { while (c.moveToNext()) add("${c.getString(0)}|${c.getDouble(1)}|${c.getString(2)}") }
        }
        // The only bonds that can exist in a v6 file are the ones the app let you record,
        // which were all UAH by the design of the old unit. Filing them as UAH is a restate
        // of that, not a guess about the user's intent.
        assertEquals(listOf("UA9000012345|1000.0|UAH", "UA9000099999|2000.0|UAH"), bonds)
    }

    @Test
    fun `no transaction row changes in any column`() {
        insertBond("UA9000012345", "ОвДП 24/Б", 1000.0)
        insertTrade(1, "UA9000012345", pricePercent = 99.5)

        val before = rows()

        AppDatabase.MIGRATION_6_7.migrate(db)

        assertEquals(before, rows())
    }

    @Test
    fun `search text stays byte identical, so every row stays findable`() {
        insertBond("UA9000012345", "ОвДП 24/Б", 1000.0)
        insertTrade(1, "UA9000012345", pricePercent = 99.5)

        val before = rows().map { it.substringAfterLast("|") }

        AppDatabase.MIGRATION_6_7.migrate(db)

        assertEquals(before, rows().map { it.substringAfterLast("|") })
        val findable = db.query(
            "SELECT COUNT(*) FROM transactions WHERE searchText LIKE '%monobank%'"
        ).use { if (it.moveToNext()) it.getInt(0) else 0 }
        assertEquals(2, findable)
    }

    @Test
    fun `the temporary tables do not survive`() {
        insertBond("UA9000012345", "ОвДП 24/Б", 1000.0)
        insertTrade(1, "UA9000012345", pricePercent = 99.5)

        AppDatabase.MIGRATION_6_7.migrate(db)

        val tables = db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        // Room validates against the schema after the migration, and a leftover `_old` table
        // is a schema Room knows nothing about. Leaving one behind is a crash on the next
        // open of the database, so dropping it is part of the result, not a detail.
        assertTrue(tables.none { it.contains("_old") })
        assertTrue(tables.contains("bonds"))
        assertTrue(tables.contains("bond_trades"))
    }

    @Test
    fun `the isin and transaction indices are recreated on the new table`() {
        insertBond("UA9000012345", "ОвДП 24/Б", 1000.0)
        insertTrade(1, "UA9000012345", pricePercent = 99.5)

        AppDatabase.MIGRATION_6_7.migrate(db)

        val indexes = db.query("PRAGMA index_list(bond_trades)").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
        }
        assertTrue(indexes.contains("index_bond_trades_isin"))
        assertTrue(indexes.contains("index_bond_trades_transactionId"))
    }

    @Test
    fun `a trade of a bond with a blank price converts to zero rather than failing`() {
        // A zero price was recordable in v6 (validation rejected it, but the column accepted
        // it), and the migration must not explode on the datum before refusing it.
        insertBond("UA9000012345", "ОвДП 24/Б", 1000.0)
        insertTrade(1, "UA9000012345", pricePercent = 0.0)

        AppDatabase.MIGRATION_6_7.migrate(db)

        val price = db.query("SELECT price FROM bond_trades").use { if (it.moveToNext()) it.getDouble(0) else -1.0 }
        assertEquals(0.0, price, 0.0)
    }

    private fun rows() = db.query(
        "SELECT $transactionColumns FROM transactions ORDER BY id"
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add((0..14).joinToString("|") { if (c.isNull(it)) "~" else c.getString(it) })
            }
        }
    }
}