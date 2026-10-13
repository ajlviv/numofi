package com.financetracker.data.backup

import com.financetracker.model.BondTradeEntity
import com.financetracker.model.BondTradeSide

/**
 * One bond trade as it travels in the backup file.
 *
 * A [BondTradeEntity] without the `userId`, for the same reason [TransactionRow] has none: the
 * account is stated once at the root, by an exporter that filtered on it, and a per-row copy is
 * the same fact written many times with room to disagree with itself. Keeping this a separate
 * type is what makes that removal safe rather than a convention — [toEntity] has to be handed
 * an account explicitly, so a snapshot that forgot to drop the column would not compile.
 *
 * The counterpart of this row is a transaction under [transactionId], which is the file's own
 * id for the cash row. The restorer resolves it to whatever the cash row is called here, since
 * ids from the writing device are never reused.
 */
data class BondTradeRow(
    val id: Long = 0,
    val isin: String,
    val side: BondTradeSide,
    val quantity: Int,
    val price: Double,
    val accruedInterest: Double = 0.0,
    val commission: Double = 0.0,
    val tradeDate: Long,
    val bankCode: String? = null,
    val transactionId: Long? = null
) {
    fun toEntity(uid: String, id: Long = this.id) = BondTradeEntity(
        id = id,
        userId = uid,
        isin = isin,
        side = side,
        quantity = quantity,
        price = price,
        accruedInterest = accruedInterest,
        commission = commission,
        tradeDate = tradeDate,
        bankCode = bankCode,
        transactionId = transactionId
    )

    companion object {
        fun from(entity: BondTradeEntity) = BondTradeRow(
            id = entity.id,
            isin = entity.isin,
            side = entity.side,
            quantity = entity.quantity,
            price = entity.price,
            accruedInterest = entity.accruedInterest,
            commission = entity.commission,
            tradeDate = entity.tradeDate,
            bankCode = entity.bankCode,
            transactionId = entity.transactionId
        )
    }
}
