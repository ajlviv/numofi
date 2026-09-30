package com.financetracker.data.backup

import com.financetracker.model.TransactionEntity
import com.financetracker.model.TransactionType
import com.financetracker.model.TransferDirection

/**
 * One transaction as it travels in the backup file.
 *
 * A [TransactionEntity] without the `userId`, and deliberately not a subclass or an alias of
 * it. The entity's account column exists to scope queries on a device that may hold more than
 * one account's data; in a file it is a fact already stated once, at the root, by an exporter
 * that filtered on it. Repeating it on every row is the same fact written 147 times with room
 * to disagree with itself, and the tables beside it — banks, bonds, trades — carry no account
 * at all, so the column was inconsistent about what it even meant.
 *
 * Keeping this a separate type is what makes the removal safe rather than a convention: the
 * converter is the only place that can put an account back, and [toEntity] has to be given one
 * explicitly. A snapshot that had forgotten to drop the column would not compile.
 *
 * Every other field is carried across untouched, [searchText] above all. It is baked in at
 * write time and embeds the bank display name, so rebuilding it on the way out would rewrite
 * a field the app never rewrites.
 */
data class TransactionRow(
    val id: Long = 0,
    val title: String,
    val amount: Double,
    val type: TransactionType,
    val category: String,
    val timestamp: Long,
    val note: String? = null,
    val externalId: String? = null,
    val source: String? = null,
    val currencyCode: String? = null,
    val bankCode: String? = null,
    val cardLabel: String? = null,
    val searchText: String? = null,
    val transferDirection: TransferDirection? = null
) {
    fun toEntity(uid: String, id: Long = this.id) = TransactionEntity(
        id = id,
        userId = uid,
        title = title,
        amount = amount,
        type = type,
        category = category,
        timestamp = timestamp,
        note = note,
        externalId = externalId,
        source = source,
        currencyCode = currencyCode,
        bankCode = bankCode,
        cardLabel = cardLabel,
        searchText = searchText,
        transferDirection = transferDirection
    )

    companion object {
        fun from(entity: TransactionEntity) = TransactionRow(
            id = entity.id,
            title = entity.title,
            amount = entity.amount,
            type = entity.type,
            category = entity.category,
            timestamp = entity.timestamp,
            note = entity.note,
            externalId = entity.externalId,
            source = entity.source,
            currencyCode = entity.currencyCode,
            bankCode = entity.bankCode,
            cardLabel = entity.cardLabel,
            searchText = entity.searchText,
            transferDirection = entity.transferDirection
        )
    }
}
