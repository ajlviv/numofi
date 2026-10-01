package com.financetracker.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A payment the user expects to repeat — rent, a subscription, a loan instalment, a salary.
 *
 * A *schedule*, not a transaction and not a promise. Nothing here ever writes a row to
 * [TransactionEntity]: the ledger holds what happened, and this holds what is expected. The two
 * are shown side by side and never added together, because a forecast folded into a balance is a
 * guess wearing a fact's clothes.
 *
 * Scoped to [userId] like a transaction, because it describes one person's commitments — unlike
 * banks, bonds and trades, which are device-wide. That scoping is also what makes a schedule
 * survive a restore under the right account and only the right one.
 *
 * There is no "paid" flag. Whether a given occurrence actually happened is a question the
 * transactions list answers, and duplicating that here would be a second source of truth for the
 * same fact. The projection therefore reports what is *scheduled* in a window, never what remains.
 */
@Entity(
    tableName = "recurring_payments",
    indices = [Index("userId")]
)
data class RecurringPaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** See [TransactionEntity.userId]. */
    val userId: String,
    val title: String,
    /** Positive. Direction lives in [type]; a negative amount would double-sign it. */
    val amount: Double,
    /**
     * [TransactionType.EXPENSE] or [TransactionType.INCOME] only.
     *
     * A [TransactionType.TRANSFER] is refused by the form: a move between the user's own pockets
     * is not a commitment in the sense this feature forecasts, and admitting it would put a
     * figure in neither the income nor the expense line while still appearing in the list.
     */
    val type: TransactionType,
    val category: String,
    /** ISO 4217 code, from [RECORDABLE_CURRENCIES]. Never null: a schedule must be convertible. */
    val currencyCode: String,
    /** Optional, for grouping and for prefilling a hand-entered row later. */
    val bankCode: String? = null,
    val note: String? = null,
    val frequency: RepeatFrequency,
    /** Every N periods, e.g. 3 for a quarter. At least 1. */
    val intervalCount: Int = 1,
    /** Epoch millis of the first occurrence, inclusive. See [Recurrence]. */
    val startDate: Long,
    /** Epoch millis of the last occurrence, inclusive, or null for open-ended. */
    val endDate: Long? = null,
    /**
     * Archived definitions stop being projected but are kept.
     *
     * The same word the bank list uses, and for the same reason: "stop offering this" is not
     * "delete my history". A cancelled subscription that was projected for two years is worth
     * keeping to explain what the forecast used to say.
     */
    val archived: Boolean = false
) {
    fun toDomain(): RecurringPayment = RecurringPayment(
        id = id,
        title = title,
        amount = amount,
        type = type,
        category = category,
        currencyCode = currencyCode,
        bankCode = bankCode,
        note = note,
        frequency = frequency,
        intervalCount = intervalCount,
        startDate = startDate,
        endDate = endDate,
        archived = archived
    )

    companion object {
        fun fromDomain(payment: RecurringPayment, userId: String): RecurringPaymentEntity =
            RecurringPaymentEntity(
                id = payment.id,
                userId = userId,
                title = payment.title,
                amount = payment.amount,
                type = payment.type,
                category = payment.category,
                currencyCode = payment.currencyCode,
                bankCode = payment.bankCode,
                note = payment.note,
                frequency = payment.frequency,
                intervalCount = payment.intervalCount,
                startDate = payment.startDate,
                endDate = payment.endDate,
                archived = payment.archived
            )
    }
}

/** A schedule as the UI and the projection see it, see [RecurringPaymentEntity]. */
data class RecurringPayment(
    val id: Long = 0,
    val title: String,
    val amount: Double,
    val type: TransactionType,
    val category: String,
    val currencyCode: String,
    val bankCode: String? = null,
    val note: String? = null,
    val frequency: RepeatFrequency,
    val intervalCount: Int = 1,
    val startDate: Long,
    val endDate: Long? = null,
    val archived: Boolean = false
)
