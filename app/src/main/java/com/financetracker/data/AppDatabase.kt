package com.financetracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.financetracker.model.SearchText
import com.financetracker.model.TransactionEntity
import com.financetracker.model.UserEntity

@Database(entities = [TransactionEntity::class, UserEntity::class], version = 4, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao

    abstract fun userDao(): UserDao

    companion object {
        const val DATABASE_NAME = "finance_tracker.db"

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
                                cursor.getString(4),
                                cursor.getString(5)
                            )
                        }
                    }
                for ((id, text) in folded) {
                    db.execSQL("UPDATE transactions SET searchText = ? WHERE id = ?", arrayOf(text, id))
                }
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
