package com.financetracker.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A bond the user holds or has traded.
 *
 * The only instrument this app models is ОВДП — domestic government bonds issued by
 * Мінфін. They are denominated in UAH, which is why [nominalUAH] carries the currency in its
 * name while no other field here does: it is the one field whose unit is fixed by the issuer
 * rather than chosen by the trade.
 *
 * Terms are nullable because the user types them in by hand from a broker's screen, and
 * someone recording a quick intraday trade may know the ISIN and nothing else. A missing
 * coupon is not treated as a zero coupon, and [maturityDate] is only needed to show a
 * position; the trade itself is still recordable without them.
 */
@Entity(tableName = "bonds")
data class BondEntity(
    /** The ISIN, normalised to upper case, and the primary key. */
    @PrimaryKey val isin: String,
    /** What to call it, e.g. "ОвДП 24/Б". Free text: the app has no name feed. */
    val name: String,
    /**
     * Face value of one bond in UAH. The unit price and the accrual are quoted against this,
     * so it is needed to turn any percentage into money.
     */
    val nominalUAH: Double,
    /** Annual coupon as a percentage, e.g. 9.5 for 9.5% a year. Not a fraction. */
    val couponPercent: Double? = null,
    /**
     * Months between coupon payments, e.g. 3 for quarterly. Null when unknown or when the
     * bond pays only at maturity.
     */
    val couponPeriodMonths: Int? = null,
    /** Epoch millis of redemption, or null if the user has not recorded it. */
    val maturityDate: Long? = null
) {
    fun toDomain(): Bond = Bond(isin, name, nominalUAH, couponPercent, couponPeriodMonths, maturityDate)
}

/**
 * One buy or sale.
 *
 * Trades are the only record of what happened; a position is folded from them at read time
 * rather than stored. A separate position table would be a second source of truth for the
 * same fact, and the two would disagree the moment a trade was edited or deleted.
 */
@Entity(
    tableName = "bond_trades",
    foreignKeys = [
        ForeignKey(
            entity = BondEntity::class,
            parentColumns = ["isin"],
            childColumns = ["isin"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
            // Cascade, and not a refusal to delete. A trade and its cash row are one fact
            // written in two places, so deleting either half has to take the other with it.
            // A refusal would put a dead delete button in front of the user with nothing to
            // say about why, and would leave the alternative — deleting the cash row and
            // keeping the trade — as a position the balance no longer agrees with.
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("isin"), Index("transactionId")]
)
data class BondTradeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The instrument, see [BondEntity.isin]. */
    val isin: String,
    val side: BondTradeSide,
    /** How many bonds. Always positive; direction lives in [side]. */
    val quantity: Int,
    /**
     * Percentage of nominal the trade happened at, e.g. 99.5 for 99.5% of face value.
     *
     * A percentage and not an absolute price because that is how these are quoted, and
     * because it stays meaningful if the nominal is corrected afterwards.
     */
    val pricePercent: Double,
    /**
     * Accrued interest in UAH for one bond, as the broker quoted it, or 0 when the trade
     * settled on a coupon date.
     *
     * Per bond, not per 100 and not already summed over the trade: what brokers print is a
     * number per bond, and storing the summed figure would make the per-bond reading
     * unrecoverable. [BondMath] multiplies by [quantity] to get the trade total.
     */
    val accruedInterestUAH: Double = 0.0,
    /**
     * Broker commission for the whole trade in UAH, as charged, or 0 when there was none.
     *
     * A per-trade total and not a per-bond rate, because brokers quote it both ways and
     * there is no way to tell from a single number which the user meant. The field is defined
     * as the total so that the number entered is the number spent.
     */
    val commissionUAH: Double = 0.0,
    /** Epoch millis of the trade, which need not be the day it was entered. */
    val tradeDate: Long,
    /**
     * The account it settled through, see [BankEntity.code]. No foreign key: a bank is
     * archived rather than deleted, and this must never be able to block a write.
     */
    val bankCode: String? = null,
    /**
     * The cash row for the same trade, see [TransactionEntity.id].
     *
     * Nullable, and null in practice only for a trade written by something other than
     * [com.financetracker.repository.BondRepository]. The foreign key cannot be NOT NULL
     * because the two rows are inserted in one statement batch, not one after the other.
     */
    val transactionId: Long? = null
) {
    fun toDomain(): BondTrade = BondTrade(
        id = id,
        isin = isin,
        side = side,
        quantity = quantity,
        pricePercent = pricePercent,
        accruedInterestUAH = accruedInterestUAH,
        commissionUAH = commissionUAH,
        tradeDate = tradeDate,
        bankCode = bankCode,
        transactionId = transactionId
    )
}

/** Which way a [BondTradeEntity] went. Only these two; there is no transfer between brokers. */
enum class BondTradeSide {
    BUY,
    SELL
}

/** A bond as the UI sees it, see [BondEntity]. */
data class Bond(
    val isin: String,
    val name: String,
    val nominalUAH: Double,
    val couponPercent: Double? = null,
    val couponPeriodMonths: Int? = null,
    val maturityDate: Long? = null
)

/** A trade as the UI sees it, see [BondTradeEntity]. */
data class BondTrade(
    val id: Long,
    val isin: String,
    val side: BondTradeSide,
    val quantity: Int,
    val pricePercent: Double,
    val accruedInterestUAH: Double,
    val commissionUAH: Double,
    val tradeDate: Long,
    val bankCode: String?,
    val transactionId: Long?
)

/**
 * A trade's value in the currency the account holds, see [BondMath.settlement].
 *
 * @param amount what should be written to the cash row.
 * @param impliedRate the rate the user's own numbers imply, for showing back to them. Null
 * when the trade is in UAH, when the amount has not been entered, or when the amount is zero
 * — in none of those cases is there a conversion to show.
 */
data class BondSettlement(val amount: Double, val impliedRate: Double?)

/** A holding folded from trades, see [BondMath.position]. */
data class BondPosition(
    val bond: Bond,
    /** Positive. Zero means fully sold, which is kept rather than hidden. */
    val quantity: Int,
    /** What the bonds still held cost, accrued interest and commission included. */
    val costUAH: Double,
    /**
     * The average cost of one bond as a percentage of nominal, or null when nothing is held
     * and there is therefore nothing to average.
     */
    val averageCostPercent: Double?,
    /** The price of the most recent trade, which is the only price this app knows. */
    val lastPricePercent: Double?,
    /** Null when the bond has no recorded coupon, see [BondMath.annualCouponIncomeUAH]. */
    val annualCouponIncomeUAH: Double?
) {
    /** What the holding is worth at the last price entered, in UAH. */
    val marketValueUAH: Double
        get() = quantity * (lastPricePercent ?: 0.0) / 100.0 * bond.nominalUAH

    /**
     * Profit or loss against what was paid, at the last price entered.
     *
     * Not the real gain, which would need the current market price and the coupon received
     * since. Both are absent by design, so this is labelled in the UI as being calculated at
     * the last entered price rather than shown as if it were a live valuation.
     */
    val unrealisedUAH: Double get() = marketValueUAH - costUAH
}
