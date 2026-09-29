package com.financetracker.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises [AppDatabase.MIGRATION_4_5] against a database shaped like the real v4 one.
 *
 * The v4 schema is built by hand rather than through room-testing's MigrationTestHelper,
 * because this project sets `exportSchema = false` and so has no exported schema file for
 * the helper to open. This follows `Migration34Test`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Migration45Test {

    private lateinit var db: SupportSQLiteDatabase

    private val v4Columns =
        "id, userId, title, amount, type, category, timestamp, note, externalId, " +
            "source, currencyCode, bankCode, cardLabel, searchText"

    /**
     * A row whose haystack embeds the bank name, which is what makes the "untouched"
     * assertion below mean something. The name is the old `BankCode.label()` output,
     * lowercased, exactly as v4 would have written it.
     */
    private val syncedSearchText = "мобільний оператор note one grocery monobank"

    @Before
    fun setUp() {
        val config = SupportSQLiteOpenHelper.Configuration
            .builder(ApplicationProvider.getApplicationContext())
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(4) {
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
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        db = FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase

        db.execSQL(
            "INSERT INTO transactions ($v4Columns) VALUES " +
                "(1,'uid-1','МОБІЛЬНИЙ ОПЕРАТОР',10.0,'EXPENSE','grocery',1000," +
                "'note one','mo_1','monobank','UAH','mo','535129****5783'," +
                "'$syncedSearchText')," +
                "(2,'uid-1','АПТЕКА',20.0,'EXPENSE','health',2000," +
                "NULL,'uk_2','import_','UAH','uk',NULL,'аптека health ukrsibbank')," +
                // A hand-entered row: no bank, and so nothing in its haystack to preserve.
                "(3,'uid-1','Lunch',30.0,'EXPENSE','food',3000," +
                "NULL,NULL,NULL,NULL,NULL,NULL,'lunch food')"
        )
    }

    private fun banks() = db.query(
        "SELECT code, displayName, position, archived, builtIn FROM banks ORDER BY position"
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    "${c.getString(0)}|${c.getString(1)}|${c.getInt(2)}|" +
                        "${c.getInt(3)}|${c.getInt(4)}"
                )
            }
        }
    }

    private fun searchTexts() = db.query("SELECT searchText FROM transactions ORDER BY id").use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }

    private fun rowCount() = db.query("SELECT COUNT(*) FROM transactions").use { c ->
        if (c.moveToNext()) c.getInt(0) else 0
    }

    @Test
    fun `the three banks the app can attribute on its own are seeded in order`() {
        AppDatabase.MIGRATION_4_5.migrate(db)

        assertEquals(
            listOf(
                "mo|Monobank|0|0|1",
                "uk|Ukrsibbank|1|0|1",
                "pb|PrivatBank|2|0|1"
            ),
            banks()
        )
    }

    @Test
    fun `no stored row is rewritten by the migration`() {
        // The entire reason the seeded display names are byte-identical to the old labels:
        // if they were not, every existing row would need its haystack rebuilt in Kotlin,
        // because SQLite's lower() cannot fold the Cyrillic in these rows.
        val before = searchTexts()

        AppDatabase.MIGRATION_4_5.migrate(db)

        assertEquals(before, searchTexts())
        assertEquals(3, rowCount())
    }

    @Test
    fun `a synced row stays findable by its bank name after the upgrade`() {
        AppDatabase.MIGRATION_4_5.migrate(db)

        // Explicit rather than implied by the test above: this is the user-visible
        // consequence, and it is what silently breaks if a seed name is ever edited.
        val found = db.query(
            "SELECT COUNT(*) FROM transactions WHERE searchText LIKE '%monobank%'"
        ).use { if (it.moveToNext()) it.getInt(0) else 0 }

        assertEquals(1, found)
        assertEquals(syncedSearchText, searchTexts().first())
    }

    @Test
    fun `seeded banks are not archived, so the import picker offers them immediately`() {
        AppDatabase.MIGRATION_4_5.migrate(db)

        val active = db.query("SELECT COUNT(*) FROM banks WHERE archived = 0").use {
            if (it.moveToNext()) it.getInt(0) else 0
        }
        assertEquals(3, active)
    }

    @Test
    fun `the seeded codes are the ones the detector and sync service key off`() {
        AppDatabase.MIGRATION_4_5.migrate(db)

        // BankDetector matches on UKRSIBBANK/PRIVATBANK and BankSyncService writes
        // MONOBANK, so seeding different strings would silently disable both.
        assertEquals(
            listOf(
                com.financetracker.model.BankCode.MONOBANK,
                com.financetracker.model.BankCode.UKRSIBBANK,
                com.financetracker.model.BankCode.PRIVATBANK
            ),
            db.query("SELECT code FROM banks ORDER BY position").use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
        )
    }
}
