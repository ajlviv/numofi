package com.financetracker.data.backup

import com.financetracker.data.AppDatabase
import com.financetracker.model.BankEntity
import com.financetracker.model.BondEntity
import com.financetracker.model.BondTradeEntity
import com.financetracker.model.RecurringPaymentEntity
import com.financetracker.model.TransactionEntity
import kotlinx.coroutines.flow.first
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts the rows of a [BackupSnapshot] back into the database, merging with what is already
 * there.
 *
 * A merge and never a replace. The file is a copy the user chose to keep of a device that
 * may still be in use, and a restore that deleted the rows already on this one would throw
 * away whatever has happened since the file was written — which is exactly the window in
 * which somebody reaches for a backup. Nothing here deletes, so a restore can be repeated,
 * interrupted, or regretted without a second copy of the data.
 *
 * ## The account
 *
 * A file names the account it was taken for, and it is refused unless that is the account
 * signed in now. It is not a formality: banks, bonds and trades have no account column, so a
 * file taken while a second Google account was signed in carries that account's bond
 * positions inside it. There is no way to tell those apart from this account's, so the whole
 * file is refused rather than partly taken. See [RestoreResult.WrongAccount].
 *
 * ## The ids
 *
 * The ids in a file are the ones the writing device happened to use, and they cannot be
 * reused: a device with rows of its own would collide, and a collision on a primary key is a
 * silent overwrite of somebody's transaction. Every row is therefore inserted with
 * `id = 0` and the id the database hands back is remembered, so that a trade can be pointed
 * at the cash row that was just written for it. That link is the one relationship in the file
 * that cannot be recovered by matching rows against each other, which is why the trades go in
 * after the transactions rather than alongside them.
 *
 * ## What counts as already present
 *
 * A row imported from a bank carries an `externalId` the bank assigned, and that is the
 * identity to match on: it is stable across devices and re-imports, which a title or an
 * amount is not. A row the user typed in has no such id, so it is matched on its content
 * instead — without that, every repeat of a restore would duplicate the hand-entered rows,
 * and a restore the user can safely press twice is the property that makes one safe to offer.
 *
 * Banks and bonds are matched by their own natural keys, and an existing row is never
 * overwritten. See [mergeBank] for why a bank keeps the name it has here.
 */
@Singleton
class BackupRestorer @Inject constructor(
    private val db: AppDatabase,
    private val backupRequests: BackupRequests
) {

    /**
     * Merges [snapshot] into the database as [uid].
     *
     * Not the whole call in one transaction: the dedupe has to read what is already stored
     * before it can decide what to write, and a rollback that undid the writes would also undo
     * the read's meaning for a second attempt. Room's own transaction still guards each
     * insert, and a failure partway through leaves a prefix of the file in place rather than
     * a half-written trade with no cash row.
     */
    suspend fun restore(snapshot: BackupSnapshot, uid: String): RestoreResult {
        if (snapshot.uid != uid) {
            return RestoreResult.WrongAccount(snapshot.uid)
        }

        // What each row already has as an id here, keyed by what makes it the same row. Seeded
        // from the rows already stored, because a row the file and this device both hold has
        // no new id to give — and that is precisely the case where its trade is already here.
        val localId = db.transactionDao().getAllForUser(uid).first()
            .associate { identity(it) to it.id }
            .toMutableMap()

        // A trade names its cash row by the id the writing device happened to use, which is
        // not an id anything on this device can look up. Resolving it to a row and then to
        // whatever that row is called here is what makes the link survive the renumbering.
        val localByFileId = snapshot.transactions.associate { it.id to identity(it.toEntity(uid)) }

        var restored = 0
        for (row in snapshot.transactions) {
            val key = identity(row.toEntity(uid))
            if (localId.containsKey(key)) continue

            val newId = db.transactionDao().insert(row.toEntity(uid, id = 0))
            localId[key] = newId
            restored++
        }

        val banks = mergeBanks(snapshot.banks)
        val bonds = mergeBonds(snapshot.bonds)
        val trades = mergeTrades(snapshot.bondTrades, localByFileId, localId)
        // orEmpty: a v1 or v2 file has no schedules key at all, and Gson leaves the field null.
        val recurring = mergeRecurring(snapshot.recurringPayments.orEmpty(), uid)

        if (restored > 0 || banks > 0 || bonds > 0 || trades > 0 || recurring > 0) {
            // Asked for only if something landed. A restore of a file whose rows are all
            // already here has changed nothing, and uploading would rewrite Drive with the
            // state it already holds.
            backupRequests.requestUpload(BackupReason.MANUAL)
        }

        return RestoreResult.Done(
            restored = restored,
            skipped = snapshot.transactions.size - restored,
            banks = banks,
            bonds = bonds,
            trades = trades,
            recurring = recurring
        )
    }

    /**
     * Banks already here keep their own name, and only their own name.
     *
     * A restore is a merge, and the name on this device is the newer of the two. It is also
     * load-bearing rather than cosmetic: every stored row's `searchText` bakes in the bank
     * name as it was when that row was written, so overwriting a seeded name with the copy
     * from a file would leave the two disagreeing by construction. The position and the
     * archived flag are left alone for the same reason — both are this device's ordering, not
     * the file's.
     */
    private suspend fun mergeBanks(incoming: List<BankEntity>): Int {
        var added = 0
        for (bank in incoming) {
            if (db.bankDao().getByCode(bank.code) != null) continue
            db.bankDao().insert(
                bank.copy(
                    // Appended rather than restored at the file's position: positions are a
                    // list this device orders for itself, and reusing a number from the file
                    // would put the new bank in the middle of an order the user has arranged.
                    position = db.bankDao().nextPosition(),
                    builtIn = false
                )
            )
            added++
        }
        return added
    }

    /** Bonds already here keep their terms, see [mergeBank]; a trade does not overwrite them either. */
    private suspend fun mergeBonds(incoming: List<BondEntity>): Int {
        var added = 0
        for (bond in incoming) {
            if (db.bondDao().getByIsin(bond.isin) != null) continue
            db.bondDao().insertIfAbsent(bond)
            added++
        }
        return added
    }

    /**
     * Writes the trades that are not here yet.
     *
     * A trade has no external id of its own, so it is recognised by the cash row it belongs
     * to, and that row is reached through [localByFileId] and then [localId]. Either
     * outcome is one lookup: a row just written resolves to its new id, and a row that was
     * already here resolves to the id it has always had, where
     * [com.financetracker.data.BondDao.getTradeForTransaction] then finds the trade that is
     * already stored against it. The bond has to exist first, because the trade's foreign key
     * is on the isin.
     *
     * A trade whose cash row cannot be resolved at all — a null
     * [BondTradeEntity.transactionId], or one naming a row the file does not contain — is
     * written anyway, with the link left empty. The file's own writer always writes both
     * halves, so this is a hand-edited or partial file; dropping the trade would silently
     * lose a position, which is the half of the pair that cannot be reconstructed from
     * anything else. The balance is the half that goes missing, and a position that
     * disagrees with the balance is visible rather than hidden.
     *
     * A trade refused for a missing isin is dropped, not written dangling: the foreign key
     * would take it anyway, and a position whose instrument is missing is one nothing can
     * price.
     */
    private suspend fun mergeTrades(
        incoming: List<BondTradeEntity>,
        localByFileId: Map<Long, String>,
        localId: Map<String, Long>
    ): Int {
        var added = 0
        for (trade in incoming) {
            val cashId = trade.transactionId?.let { localByFileId[it] }?.let { localId[it] }
            if (cashId != null && db.bondDao().getTradeForTransaction(cashId) != null) continue
            if (db.bondDao().getByIsin(trade.isin) == null) continue

            db.bondDao().insertTrade(trade.copy(id = 0, transactionId = cashId))
            added++
        }
        return added
    }

    /**
     * Writes the schedules that are not here yet.
     *
     * A schedule has no external id, so it is recognised by its content — the same fallback
     * [identity] uses for a hand-entered transaction, and for the same reason: it is what makes
     * a restore repeatable. The trade-off is the same one too. Two genuinely different schedules
     * that share every field would read as one, which is a smaller failure than duplicating
     * every schedule on a second restore.
     *
     * An existing row is never overwritten and its id is never reused: a row from the file is
     * inserted with `id = 0`, so a file id cannot collide with a row this device already has.
     */
    private suspend fun mergeRecurring(incoming: List<RecurringPaymentRow>, uid: String): Int {
        // Seeded from what is stored, so a row the file and this device both hold is skipped and
        // a file with two identical rows writes one.
        val seen = db.recurringPaymentDao().getAllForUserOnce(uid)
            .map(::scheduleIdentity)
            .toMutableSet()

        var added = 0
        for (row in incoming) {
            if (!seen.add(scheduleIdentity(row.toEntity(uid)))) continue
            db.recurringPaymentDao().insert(row.toEntity(uid, id = 0))
            added++
        }
        return added
    }

    /**
     * What makes a schedule "already here".
     *
     * Every stored field except [RecurringPaymentEntity.archived], so the match is about the
     * commitment rather than the device's ordering. Archiving is this device's state, exactly
     * as a bank's archived flag is, so a restore that treated it as part of the identity would
     * write a second copy whenever the file was taken before the archive.
     *
     * The amount is quantised to kopecks exactly as [identity] quantises a transaction's, so a
     * figure that travelled through the file as 12000.0 and came back as 12000.00 is recognised
     * as the same schedule.
     */
    private fun scheduleIdentity(row: RecurringPaymentEntity): String {
        val amount = BigDecimal(row.amount).setScale(KOpecks, RoundingMode.HALF_UP).toPlainString()
        return buildString {
            append(row.title.lowercase()).append('|').append(amount).append('|')
            append(row.type.name).append('|').append(row.category).append('|')
            append(row.currencyCode).append('|').append(row.bankCode).append('|')
            append(row.note).append('|').append(row.frequency.name).append('|')
            append(row.intervalCount).append('|').append(row.startDate).append('|')
            append(row.endDate)
        }
    }

    /**
     * What makes a transaction "already here".
     *
     * The bank's own id where there is one, because it is the same string on every device
     * that imported the same statement — and matching on anything weaker would treat two
     * genuinely different purchases of the same coffee on the same day as one row.
     *
     * A hand-entered row has no such id, so its content stands in for it. The amount is
     * quantised to kopecks so that a row that travelled through a decimal as 99.9 and one that
     * came back as 99.90 are the same row, and the text is folded the way the search haystack
     * is, so that a capital letter does not read as a difference.
     *
     * A hand-entered row is therefore matched loosely, and the one thing that cannot be told
     * apart is the user having entered the identical row twice. That is the price of making a
     * restore repeatable, and it is far smaller than the alternative of doubling every typed
     * row on every attempt.
     */
    private fun identity(row: TransactionEntity): String {
        row.externalId?.let { return "id:$it" }
        val amount = BigDecimal(row.amount).setScale(KOpecks, RoundingMode.HALF_UP).toPlainString()
        val text = "${row.title}|${row.amount}|$amount".lowercase()
        return "row:${row.timestamp}|$text|${row.type}|${row.currencyCode}|${row.bankCode}|${row.cardLabel}"
    }

    private companion object {
        const val KOpecks = 2
    }
}

/** What a restore did, or why it did not happen. */
sealed interface RestoreResult {
    /**
     * The file belongs to a different account, and nothing was written.
     *
     * Naming the uid it was for is what makes this actionable — the user can sign in as that
     * account, or pick a different file — instead of a failure with no next step.
     */
    data class WrongAccount(val snapshotUid: String) : RestoreResult

    data class Done(
        /** Transaction rows written. */
        val restored: Int,
        /** Transaction rows that were already here. */
        val skipped: Int,
        val banks: Int,
        val bonds: Int,
        val trades: Int,
        /** Recurring payment schedules written. */
        val recurring: Int
    ) : RestoreResult {
        /** Nothing in the file was missing. */
        val changed: Boolean
            get() = restored > 0 || banks > 0 || bonds > 0 || trades > 0 || recurring > 0
    }
}
