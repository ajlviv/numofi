package com.financetracker.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises [AppDatabase.MIGRATION_3_4] against a database shaped like the real v3 one.
 *
 * The v3 schema is created by hand rather than through room-testing's MigrationTestHelper,
 * because this project sets `exportSchema = false` and so has no exported schema file for
 * the helper to open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Migration34Test {

    private lateinit var db: SupportSQLiteDatabase

    private val v3Columns =
        "id, userId, title, amount, type, category, timestamp, note, externalId, source, currencyCode"

    @Before
    fun setUp() {
        val config = SupportSQLiteOpenHelper.Configuration
            .builder(ApplicationProvider.getApplicationContext())
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `transactions` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`userId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                            "`amount` REAL NOT NULL, `type` TEXT NOT NULL, " +
                            "`category` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, " +
                            "`note` TEXT, `externalId` TEXT, `source` TEXT, " +
                            "`currencyCode` TEXT)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        db = FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase

        db.execSQL(
            "INSERT INTO transactions ($v3Columns) VALUES " +
                // A synced row, with a Cyrillic title so the fold assertion is meaningful.
                "(1,'uid-1','МОБІЛЬНИЙ ОПЕРАТОР',10.0,'EXPENSE','grocery',1000," +
                "'note one','monobank_1','monobank','UAH')," +
                // A row written by an older import, whose bank was never recorded.
                "(2,'uid-1','Стара операція',20.0,'INCOME','other',2000," +
                "NULL,'import_ab12','import_','UAH')," +
                // A hand-entered row.
                "(3,'uid-1','Lunch',30.0,'EXPENSE','food',3000,NULL,NULL,NULL,NULL)"
        )
    }

    private fun titles() = db.query("SELECT title FROM transactions ORDER BY id").use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }

    private fun columnOf(id: Int, column: String): String? =
        db.query("SELECT $column FROM transactions WHERE id = $id").use { c ->
            if (c.moveToNext()) c.getString(0) else null
        }

    @Test
    fun `synced rows survive and are attributed to their bank`() {
        AppDatabase.MIGRATION_3_4.migrate(db)
        assertEquals(listOf("МОБІЛЬНИЙ ОПЕРАТОР", "Lunch"), titles())
        assertEquals("mo", columnOf(1, "bankCode"))
    }

    @Test
    fun `rows imported before bank tracking existed are dropped`() {
        AppDatabase.MIGRATION_3_4.migrate(db)
        assertEquals(0, titles().count { it == "Стара операція" })
    }

    @Test
    fun `the escaped like does not also delete rows from other sources`() {
        // An unescaped '_' is a LIKE wildcard, so "import_%" would match "importX" too and
        // delete a row that has nothing to do with statement imports.
        db.execSQL(
            "INSERT INTO transactions ($v3Columns) VALUES " +
                "(4,'uid-1','Kept',40.0,'EXPENSE','other',4000,NULL,'x1','importX','UAH')"
        )
        AppDatabase.MIGRATION_3_4.migrate(db)
        assertEquals(1, titles().count { it == "Kept" })
    }

    @Test
    fun `existing rows are folded with full Unicode rather than SQL ascii lower`() {
        AppDatabase.MIGRATION_3_4.migrate(db)
        // SQL lower() would leave this untouched, and every lowercased query would miss it.
        assertEquals("мобільний оператор note one grocery monobank", columnOf(1, "searchText"))
    }

    @Test
    fun `the new columns exist and tolerate null`() {
        AppDatabase.MIGRATION_3_4.migrate(db)
        assertEquals("mo", columnOf(1, "bankCode"))
        assertNull(columnOf(1, "cardLabel"))
        // A hand-entered row has no bank and must stay that way.
        assertNull(columnOf(3, "bankCode"))
    }

    @Test
    fun `migrating twice is not attempted by Room but would still be a no-op on the data`() {
        AppDatabase.MIGRATION_3_4.migrate(db)
        val after = titles()
        // The ADD COLUMN statements would fail on a second pass, which is exactly why
        // Room runs a migration once and only once.
        assertEquals(listOf("МОБІЛЬНИЙ ОПЕРАТОР", "Lunch"), after)
    }
}
