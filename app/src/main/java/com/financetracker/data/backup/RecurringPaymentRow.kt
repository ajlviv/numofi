package com.financetracker.data.backup

import com.financetracker.model.RepeatFrequency
import com.financetracker.model.RecurringPaymentEntity
import com.financetracker.model.TransactionType

/**
 * One recurring payment schedule as it travels in the backup file.
 *
 * A [RecurringPaymentEntity] without the `userId`, for the same reason [TransactionRow] drops
 * its own: the account is stated once at the root, by an exporter that filtered on it, so a
 * `userId` on each row is one fact written many times with room to disagree with itself. The
 * converter is the only place an account can be put back, and [toEntity] has to be handed one
 * explicitly — a snapshot that had forgotten to drop the column would not compile.
 *
 * Every other field is carried verbatim. There is no `searchText` to preserve here, because a
 * schedule is not a searchable transaction: it has no bank name baked in and is not what the
 * transaction search ever looks at.
 */
data class RecurringPaymentRow(
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
) {
    fun toEntity(uid: String, id: Long = this.id) = RecurringPaymentEntity(
        id = id,
        userId = uid,
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
        fun from(entity: RecurringPaymentEntity) = RecurringPaymentRow(
            id = entity.id,
            title = entity.title,
            amount = entity.amount,
            type = entity.type,
            category = entity.category,
            currencyCode = entity.currencyCode,
            bankCode = entity.bankCode,
            note = entity.note,
            frequency = entity.frequency,
            intervalCount = entity.intervalCount,
            startDate = entity.startDate,
            endDate = entity.endDate,
            archived = entity.archived
        )
    }
}
