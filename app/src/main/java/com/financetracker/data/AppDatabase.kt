package com.financetracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.financetracker.model.BankEntity
import com.financetracker.model.BankNames
import com.financetracker.model.BondEntity
import com.financetracker.model.BondTradeEntity
import com.financetracker.model.SearchText
import com.financetracker.model.TransactionEntity
import com.financetracker.model.UserEntity

@Database(
    entities = [
        TransactionEntity::class,
        UserEntity::class,
        BankEntity::class,
        BondEntity::class,
        BondTradeEntity::class
    ],
    version = 6,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao

    abstract fun userDao(): UserDao

    abstract fun bankDao(): BankDao

    abstract fun bondDao(): BondDao

    companion object {
        const val DATABASE_NAME = "finance_tracker.db"

        /**
         * The seeded bank names, for the one migration that folds a haystack before the
         * banks table exists. Same source as the v5 seed, so the two cannot drift.
         */
        val SEEDED_BANK_NAMES =
            BankEntity.BUILT_IN.associate { it.code to it.displayName }

        /**
         * Adds the bank-import columns. Nullable, so existing hand-entered rows stay
         * valid and are treated as having no external id.
         */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN externalId TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN source TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_transactions_userId_externalId " +
                        "ON transactions(userId, externalId)"
                )
            }
        }

        /**
         * Records the currency each amount is in. Existing rows are backfilled from
         * their bank import, because a UAH statement read on an en-US device would
         * otherwise be labelled in dollars.
         */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN currencyCode TEXT")
                db.execSQL(
                    "UPDATE transactions SET currencyCode = 'UAH' " +
                        "WHERE source LIKE 'monobank%'"
                )
            }
        }

        /**
         * Records which bank and card each row belongs to, plus the search haystack.
         *
         * Rows written by an older import are deleted rather than guessed at: their
         * external id is a hash of the row's own content, so the bank that produced the
         * file was never recorded and cannot be recovered. Rows that came from bank sync
         * are attributed and kept.
         */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN bankCode TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN cardLabel TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN searchText TEXT")

                db.execSQL("UPDATE transactions SET bankCode = 'mo' WHERE source = 'monobank'")

                // '_' is a LIKE wildcard, so it has to be escaped. Left unescaped, this
                // pattern would also match sources such as "import__".
                db.execSQL("DELETE FROM transactions WHERE source LIKE 'import!_%' ESCAPE '!'")

                // Filled in Kotlin rather than with SQL lower(), which folds ASCII only
                // and would leave Cyrillic titles in their original case. Every search
                // term is lowercased in Kotlin too, so a mismatch here would be silent.
                foldExistingSearchText(db)

                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_transactions_userId_bankCode " +
                        "ON transactions(userId, bankCode)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_transactions_userId_searchText " +
                        "ON transactions(userId, searchText)"
                )
            }

            private fun foldExistingSearchText(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                val folded = mutableListOf<Pair<Long, String>>()
                db.query("SELECT id, title, note, category, bankCode, cardLabel FROM transactions")
                    .use { cursor ->
                        while (cursor.moveToNext()) {
                            folded += cursor.getLong(0) to SearchText.of(
                                cursor.getString(1),
                                cursor.getString(2),
                                cursor.getString(3),
                                // At this point the only code that can exist is 'mo', set
                                // two statements above, and the banks table is not created
                                // until v5. So the name is the seeded one, which is
                                // deliberately the string this fold has always produced.
                                BankNames.ref(cursor.getString(4), SEEDED_BANK_NAMES),
                                cursor.getString(5)
                            )
                        }
                    }
                for ((id, text) in folded) {
                    db.execSQL("UPDATE transactions SET searchText = ? WHERE id = ?", arrayOf(text, id))
                }
            }
        }

        /**
         * Moves the bank list out of `BankCode` and into a table, and seeds the three banks
         * the app can attribute a statement to on its own.
         *
         * Not one transaction row is touched, and that is the whole design constraint here.
         * `searchText` is baked in at write time and embeds the bank name, so the seeded
         * display names have to be byte-identical to the strings `BankCode.label()`
         * returned. Given that, the upgrade is a `CREATE TABLE` and three inserts, and
         * search behaves exactly as it did before the upgrade. `Migration45Test` asserts
         * the rows are untouched, so editing a seed name later fails the build instead of
         * quietly making every stored row unfindable by its bank's name.
         */
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `banks` (" +
                        "`code` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
                        "`position` INTEGER NOT NULL, `archived` INTEGER NOT NULL, " +
                        "`builtIn` INTEGER NOT NULL, PRIMARY KEY(`code`))"
                )
                seedBuiltIns(db)
            }
        }

        /**
         * Adds the bond tables and the transfer direction.
         *
         * Three statements' worth of change and not one existing row touched. The direction
         * column is added nullable and deliberately left null on every stored row: the rows
         * that exist were all written before transfers existed, so they are not transfers and
         * there is nothing to infer. A row with no direction is counted as neither an inflow
         * nor an outflow, so this migration cannot move anybody's balance.
         *
         * Same constraint as v5: `searchText` is baked in at write time and this must not
         * rewrite it. `Migration56Test` asserts every column of every row is byte-identical
         * across the upgrade.
         */
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN transferDirection TEXT")
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
        }

        /**
         * Puts the three built-in banks into a table that has just been created.
         *
         * Shared by [MIGRATION_4_5] and [SEED_ON_CREATE], because a clean install runs no
         * migration at all and would otherwise open with an empty bank list: nothing to
         * import a statement into, and nothing to filter transactions by.
         *
         * Written as SQL rather than through [BankDao] because both callers run while Room is
         * still creating the schema, before any DAO exists.
         */
        internal fun seedBuiltIns(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            for (bank in BankEntity.BUILT_IN) {
                db.execSQL(
                    "INSERT INTO banks (code, displayName, position, archived, builtIn) " +
                        "VALUES (?, ?, ?, 0, 1)",
                    arrayOf(bank.code, bank.displayName, bank.position)
                )
            }
        }

        /**
         * Runs the built-in seed on a database Room creates from nothing.
         *
         * The counterpart to [MIGRATION_4_5]: migrations only run when upgrading an existing
         * file, so a fresh install needs this to end up in the same state.
         */
        internal val SEED_ON_CREATE = object : androidx.room.RoomDatabase.Callback() {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                seedBuiltIns(db)
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: build(context).also { instance = it }
            }
        }

        // Kept separate from getInstance so the builder's type argument is inferred
        // from an explicit Class, not from the expected return type.
        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6
                )
                .addCallback(SEED_ON_CREATE)
                .build()
    }
}
