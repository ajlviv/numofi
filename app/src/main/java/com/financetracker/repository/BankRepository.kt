package com.financetracker.repository

import android.database.sqlite.SQLiteConstraintException
import com.financetracker.data.BankDao
import com.financetracker.data.backup.BackupReason
import com.financetracker.data.backup.BackupRequests
import com.financetracker.model.Bank
import com.financetracker.model.BankCodeGenerator
import com.financetracker.model.BankEntity
import com.financetracker.model.BankNames
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Why a bank could not be added, so the screen can say which. */
sealed interface AddBankResult {
    data class Added(val bank: Bank) : AddBankResult
    data object BlankName : AddBankResult
    data object NameTaken : AddBankResult

    /**
     * The name was fine but no unused code could be minted for it.
     *
     * Its own case rather than [NameTaken], because telling the user to pick a different
     * name would be sending them after a problem they do not have.
     */
    data object CodeUnavailable : AddBankResult
}

/**
 * The user's bank list, and the only thing that turns a stored bank code into a name.
 *
 * Every write here is a function of what the user asked for rather than of what the
 * database happens to allow, because the three rules that matter — one name per bank,
 * ignoring case and surrounding space; never overwrite a bank whose generated code
 * collided; never leave two banks sharing a position — are all things a bare DAO call
 * would get wrong quietly.
 */
@Singleton
class BankRepository @Inject constructor(
    private val dao: BankDao,
    private val codes: BankCodeGenerator,
    private val backupRequests: BackupRequests
) {

    /** Every bank, live or archived, in the order the user arranged. */
    val banks: Flow<List<Bank>> = dao.observeAll().map { it.map(BankEntity::toDomain) }

    /** What the import picker offers. Archived banks are for history, not for new files. */
    val activeBanks: Flow<List<Bank>> = dao.observeActive().map { it.map(BankEntity::toDomain) }

    /**
     * What the transaction filter offers: everything live, plus any archived bank a stored
     * row still names.
     *
     * Archiving means "stop offering this for new imports", not "pretend my history does
     * not exist". A bank that was retired while rows still reference it would otherwise
     * become impossible to filter down to.
     */
    val filterBanks: Flow<List<Bank>> =
        combine(dao.observeAll(), dao.referencedBankCodes()) { all, referenced ->
            all.filter { !it.archived || it.code in referenced }
        }.map { it.map(BankEntity::toDomain) }

    /** Code to plain display name, for resolving a bank somewhere a repository call is awkward. */
    val names: Flow<Map<String, String>> = banks.map { list ->
        list.associate { it.code to it.displayName }
    }

    /**
     * Adds a bank under [name], minting a code for it.
     *
     * The name is trimmed and refused if blank or already taken, because two banks
     * sharing a name are indistinguishable in every dropdown. The comparison is done here
     * in Kotlin rather than in SQL for the same reason `SearchText` is folded there:
     * SQLite's `lower()` folds ASCII only, and these names are going to be Ukrainian.
     */
    suspend fun add(name: String): AddBankResult {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return AddBankResult.BlankName
        if (dao.getAllOnce().hasName(trimmed, null)) return AddBankResult.NameTaken

        val position = dao.nextPosition()
        // A code collision is astronomically unlikely but not impossible, and the insert
        // aborts on one rather than replacing, so the safe move is simply to mint another.
        repeat(CODE_ATTEMPTS) {
            val code = codes.next()
            try {
                dao.insert(BankEntity(code, trimmed, position))
                backupRequests.requestUpload(BackupReason.BANK)
                return AddBankResult.Added(Bank(code, trimmed, position))
            } catch (_: SQLiteConstraintException) {
                // Minted code already taken; try again.
            }
        }
        return AddBankResult.CodeUnavailable
    }

    /** Returns false and changes nothing if [name] is blank or another bank already has it. */
    suspend fun rename(code: String, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        if (dao.getAllOnce().hasName(trimmed, code)) return false
        return (dao.rename(code, trimmed) > 0).also { renamed ->
            // A rename is a change to the stored data even though the rows filed under this
            // bank keep the old name in their search haystack, by design. The backup carries
            // both facts, and it is the one place both have to be readable together.
            if (renamed) backupRequests.requestUpload(BackupReason.BANK)
        }
    }

    /**
     * Archives or restores a bank, asking for a backup only when a row actually changed.
     *
     * A toggle in the UI can produce a request for a code that is not in the list, or for a
     * bank that is already in the state it is being put into, and neither is a change worth
     * an upload. The row count the update returns is not enough to tell the two apart — SQLite
     * counts rows matched, so re-archiving an archived bank still reports one — so the
     * current value is read first, as [rename] also does before it writes.
     */
    suspend fun setArchived(code: String, archived: Boolean) {
        val current = dao.getByCode(code) ?: return
        if (current.archived == archived) return
        dao.setArchived(code, archived)
        backupRequests.requestUpload(BackupReason.BANK)
    }

    suspend fun moveUp(code: String): Boolean = move(code, by = -1)

    suspend fun moveDown(code: String): Boolean = move(code, by = 1)

    /**
     * Moves [code] one slot in the visible order and renumbers the list around it.
     *
     * Returns false at either end of the list, or for a code that is not there, so the
     * screen can leave a disabled button disabled without checking the list itself.
     */
    private suspend fun move(code: String, by: Int): Boolean {
        val ordered = dao.getAllOnce()
        val from = ordered.indexOfFirst { it.code == code }
        val to = from + by
        if (from < 0 || to < 0 || to >= ordered.size) return false

        dao.replaceOrder(ordered.map { it.code }.toMutableList().apply { add(to, removeAt(from)) })
        return true.also { moved ->
            // The order is stored, so it is data like any other, but only a real move is worth
            // an upload: a button that did nothing is not a change.
            if (moved) backupRequests.requestUpload(BackupReason.BANK)
        }
    }

    /**
     * The name to show for [code], from the latest known list.
     *
     * Suspending because a dropdown may ask for a name outside a collected flow. Prefer
     * [names] where a flow is available; see [BankNames.resolve] for the fallback rules.
     */
    suspend fun nameOf(code: String?): String =
        BankNames.display(code, dao.getAllOnce().associate { it.code to it.displayName })

    /**
     * Whether [name] is already taken, ignoring case and surrounding space.
     *
     * [exceptCode] is the bank being renamed, which is allowed to keep a different casing
     * of its own name; treating that as a collision would make a name uncorrectable.
     */
    private fun List<BankEntity>.hasName(name: String, exceptCode: String?): Boolean {
        val wanted = name.trim().lowercase()
        return any { it.code != exceptCode && it.displayName.trim().lowercase() == wanted }
    }

    internal companion object {
        /**
         * How many codes to try before giving up on [add].
         *
         * Internal rather than private so a test can script exactly this many collisions
         * without restating the number, which would let the two drift apart.
         */
        const val CODE_ATTEMPTS = 5
    }
}
