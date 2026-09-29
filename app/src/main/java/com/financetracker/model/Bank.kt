package com.financetracker.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.security.SecureRandom
import java.util.Random
import javax.inject.Inject

/**
 * A bank the user can file a transaction under.
 *
 * The set of banks is data rather than a compile-time constant, so the code and the name
 * have deliberately different lifecycles. The [code] is generated once and never changes,
 * because it is part of the import fingerprint: a bank whose code could change would give
 * every one of its rows a new external id on rename, and re-importing the same statement
 * would insert duplicates rather than match. The [displayName] is free to change at any
 * time and is the only thing the user ever sees or types.
 */
@Entity(tableName = "banks")
data class BankEntity(
    @PrimaryKey val code: String,
    val displayName: String,
    val position: Int,
    /**
     * Soft delete. An archived bank stops being offered for new imports but stays in the
     * table forever, because `transactions.bankCode` keeps pointing at it and the only
     * thing left to show the user would be the raw code.
     */
    val archived: Boolean = false,
    /**
     * Seeded by the app and carrying statement detection markers. Distinct from
     * [archived]: a built-in can be archived like anything else, it just cannot be deleted
     * from the schema's point of view because [BankDetector] keys off its code.
     */
    val builtIn: Boolean = false
) {
    fun toDomain(): Bank = Bank(
        code = code,
        displayName = displayName,
        position = position,
        archived = archived,
        builtIn = builtIn
    )

    companion object {
        /**
         * The banks the app can attribute a statement to on its own.
         *
         * These names are what a row becomes searchable by. `searchText` is a lowercased
         * concatenation baked into every row at write time and it embeds the bank name, and
         * renaming a bank deliberately does not reach back into rows already stored — so
         * editing one of the names here leaves the rows filed under it findable only by the
         * word they were written with. `FreshInstallSeedTest` pins these names for that
         * reason, and this list is what it checks.
         */
        val BUILT_IN: List<BankEntity> = listOf(
            BankEntity(BankCode.MONOBANK, "Monobank", 0, builtIn = true),
            BankEntity(BankCode.UKRSIBBANK, "Ukrsibbank", 1, builtIn = true),
            BankEntity(BankCode.PRIVATBANK, "PrivatBank", 2, builtIn = true)
        )
    }
}

/** A bank as the UI and the filters see it, see [BankEntity]. */
data class Bank(
    val code: String,
    val displayName: String,
    val position: Int,
    val archived: Boolean = false,
    val builtIn: Boolean = false
) {
    /**
     * How the bank is named in a dropdown.
     *
     * An archived bank is still offered by the filter while transactions reference it, and
     * without the marker the user has no way to tell why a bank they retired is still
     * sitting in the list.
     */
    fun label(): String = if (archived) "$displayName (archived)" else displayName
}

/**
 * A bank as it should be recorded in a row: the code that is stored, and the name to
 * search for.
 *
 * Modelled as one value rather than two loose strings because `mo` and `Monobank` are both
 * plain [String] and would sit side by side in a five-argument call, where passing the code
 * by mistake leaves every row of that bank unfindable by name with nothing to show for it.
 */
data class BankRef(val code: String?, val label: String)

/**
 * Turns a stored bank code into something worth showing, or into something worth searching.
 *
 * Separate from [Bank.label] on purpose: the "(archived)" marker belongs to the filter
 * list, where it explains why a retired bank is still offered. A transaction row reading
 * "Sense Bank (archived)" would just be wrong.
 */
object BankNames {

    /** What a row with no bank at all is called, see [TransactionEntity.bankCode]. */
    const val MANUAL = "Manual"

    /**
     * The bank to record in a row's haystack, or null when the row has no bank.
     *
     * Null stays null rather than becoming [MANUAL]: "Manual" is a display word, and no
     * one searches for it.
     */
    fun ref(code: String?, known: Map<String, String>): BankRef? =
        code?.let { BankRef(it, known[it] ?: it) }

    /**
     * The name to show for [code].
     *
     * A code that is not in the list falls back to itself rather than to [MANUAL] or to
     * nothing. A row naming a bank the list has lost track of must stay visibly different
     * from a row the user typed by hand — the two mean entirely different things, and
     * blanking the first would make them indistinguishable.
     */
    fun display(code: String?, known: Map<String, String>): String =
        ref(code, known)?.label ?: MANUAL
}

/**
 * Mints the opaque codes for user-added banks.
 *
 * The code is never shown and never editable, because it is part of the import
 * fingerprint: a code that could change would re-identify every one of the bank's rows and
 * make a re-imported statement insert duplicates instead of matching.
 */
class BankCodeGenerator internal constructor(
    private val random: Random
) {
    @Inject
    constructor() : this(Random(SecureRandom().nextLong()))

    /** Three bytes of entropy, which is ample for a list a person will ever keep. */
    fun next(): String {
        val bytes = ByteArray(CODE_BYTES)
        random.nextBytes(bytes)
        return PREFIX + bytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val PREFIX = "bank-"
        const val CODE_BYTES = 3
    }
}
