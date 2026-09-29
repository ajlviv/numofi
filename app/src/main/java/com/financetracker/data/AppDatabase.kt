package com.financetracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.financetracker.model.BankEntity
import com.financetracker.model.BondEntity
import com.financetracker.model.BondTradeEntity
import com.financetracker.model.SearchText
import com.financetracker.model.TransactionEntity
import com.financetracker.model.UserEntity

/**
 * The app's database.
 *
 * It is built from the entities below and, once built, is only ever reopened: there is no
 * upgrade path. A file left behind by an older schema is not something this class can repair,
 * and `version` is deliberately still the number the last schema change gave it, so a v7 file
 * already on a device opens exactly as it did before.
 *
 * A schema change means bumping `version`, which makes Room refuse an existing file with
 * "A migration from N to M was required but not found" instead of quietly rewriting somebody's
 * rows. Clearing the app's data, or reinstalling, produces a fresh file, and that is the
 * intended way to take such a change. Failing loudly is the deliberate choice over a
 * destructive fallback, which would wipe a device's rows without saying so.
 *
 * Nothing here rewrites a stored row, which is what leaves `searchText` — folded by [SearchText]
 * and written once, at write time — byte-identical to how it was written.
 */
@Database(
    entities = [
        TransactionEntity::class,
        UserEntity::class,
        BankEntity::class,
        BondEntity::class,
        BondTradeEntity::class
    ],
    version = 7,
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
         * Puts the three built-in banks into a table that has just been created.
         *
         * Without this a fresh database would open with an empty bank list: nothing to import
         * a statement into, and nothing to filter transactions by.
         *
         * Written as SQL rather than through [BankDao] because it runs while Room is still
         * creating the schema, before any DAO exists.
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
         * Runs the built-in seed on the database Room creates from nothing.
         *
         * Creating the file is the only path this class has — there are no migrations to run
         * on an older one — so this callback is the only place the seed can happen.
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
                .addCallback(SEED_ON_CREATE)
                .build()
    }
}
