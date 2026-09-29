package com.financetracker.model

/**
 * Every figure the bond screens show.
 *
 * Kept apart from any storage or UI type, and written as plain functions over plain doubles,
 * so the arithmetic can be checked against a broker's confirmation screen directly. Every
 * price here is a percentage of nominal because that is how these instruments are quoted;
 * the division by 100 happens in exactly one place each, which is the whole defence against
 * the same slip appearing twice with different results.
 */
object BondMath {

    /**
     * The clean price of a single bond, in UAH.
     *
     * [pricePercent] is a percentage of [nominalUAH]: 99.5 means 99.5% of face value.
     */
    fun cleanPriceUAH(nominalUAH: Double, pricePercent: Double): Double =
        nominalUAH * pricePercent / 100.0

    /**
     * What one trade costs, in UAH, whichever way it went.
     *
     * Quantity times the clean price, plus the accrued interest for every bond, plus the
     * commission once. The two additions are deliberately asymmetric:
     *
     * - [BondTrade.accruedInterestUAH] is a per-bond figure, so it scales with the quantity.
     *   This is how a broker quotes it and it is stored in that unit so a per-bond reading
     *   stays recoverable.
     * - [BondTrade.commissionUAH] is the whole trade's commission as charged, so it is added
     *   once. A broker quoting per bond and one quoting per trade are indistinguishable
     *   from a single number, and the field is defined as the total so that whatever the
     *   user types is what gets spent.
     *
     * A sale uses the same expression: the money is the same money, and keeping one function
     * means a sale cannot drift from a purchase's arithmetic.
     */
    fun tradeTotalUAH(trade: BondTrade, nominalUAH: Double): Double =
        trade.quantity * cleanPriceUAH(nominalUAH, trade.pricePercent) +
            trade.quantity * trade.accruedInterestUAH +
            trade.commissionUAH

    /**
     * What a trade costs, valued in the currency the account actually holds.
     *
     * [BondSettlement.amount] is what should be recorded on the cash row and
     * [BondSettlement.impliedRate] is the rate the user's own numbers imply, shown so a
     * mistyped amount is visible. The rate is never stored: there is no rate feed here, and
     * a stored rate would be a second source of truth about a conversion that happened
     * somewhere else entirely.
     */
    fun settlement(marketUAH: Double, settlementCurrency: String, actualAmount: Double?): BondSettlement {
        val isHomeCurrency = settlementCurrency.equals(HOME_CURRENCY, ignoreCase = true)
        if (isHomeCurrency) {
            // A rate against UAH would be inventing a 1.0 and implying a conversion that
            // never took place, so it is left off.
            return BondSettlement(amount = marketUAH, impliedRate = null)
        }
        if (actualAmount == null) {
            // The form has to be usable before the user goes and looks the figure up, so the
            // market value stands in. The balance is in the wrong currency until they fill
            // it in, which is visible rather than hidden.
            return BondSettlement(amount = marketUAH, impliedRate = null)
        }
        return BondSettlement(
            amount = actualAmount,
            impliedRate = if (actualAmount == 0.0) null else marketUAH / actualAmount
        )
    }

    /** The currency the bonds themselves are denominated in. */
    const val HOME_CURRENCY = "UAH"

    /**
     * A year's worth of coupon on a holding, or null when the coupon was never recorded.
     *
     * Null and not zero, because a missing rate is a different fact from a bond that pays
     * nothing. Multiplying by a bare [couponPercent] would be wrong by a factor of a
     * hundred: 9.5 is nine and a half percent, not nine and a half times the nominal.
     */
    fun annualCouponIncomeUAH(quantity: Int, nominalUAH: Double, couponPercent: Double?): Double? =
        couponPercent?.let { quantity * nominalUAH * it / 100.0 }

    /**
     * The coupon for one payment period, or null when the period is unknown.
     *
     * Divides the annual figure by the number of periods a year rather than by the number of
     * months, so a 3-month period is a third of a year and a 6-month one a sixth. Dividing
     * by the month count directly would give a quarterly payment as a thirty-second of the
     * annual coupon.
     */
    fun periodCouponIncomeUAH(
        quantity: Int,
        nominalUAH: Double,
        couponPercent: Double?,
        monthsBetween: Int?
    ): Double? {
        if (couponPercent == null || monthsBetween == null || monthsBetween <= 0) return null
        val periodsPerYear = 12.0 / monthsBetween
        return annualCouponIncomeUAH(quantity, nominalUAH, couponPercent)!! / periodsPerYear
    }

    /**
     * Folds trades into a holding, or null when there is nothing to hold.
     *
     * A running fold rather than a weighted average over the whole set, so that selling part
     * of a position removes part of its cost and leaves the average of what remains. Sold at
     * whatever the market paid: the average cost of the bonds kept is a fact about what the
     * user paid for them, and marking it to the sale price would rewrite history.
     *
     * [trades] must be in the order they happened, oldest first, which is what
     * [com.financetracker.data.BondDao] returns. Purchases may be in any order, since they
     * only add, but sales do depend on it: a sale removes part of a holding, so what it
     * removes is whatever was held at that moment. A sale of more than is held is clamped
     * rather than rejected here, because the repository is what refuses one and by the time a
     * trade is in the database it is a fact rather than a request.
     */
    fun position(bond: Bond, trades: List<BondTrade>): BondPosition? {
        if (trades.isEmpty()) return null

        var quantity = 0
        var cost = 0.0
        var lastPricePercent: Double? = null

        for (trade in trades) {
            val total = tradeTotalUAH(trade, bond.nominalUAH)
            when (trade.side) {
                BondTradeSide.BUY -> {
                    quantity += trade.quantity
                    cost += total
                }
                BondTradeSide.SELL -> {
                    val sold = minOf(trade.quantity, quantity)
                    val proportion = if (quantity == 0) 0.0 else sold.toDouble() / quantity
                    cost -= cost * proportion
                    quantity -= sold
                }
            }
            lastPricePercent = trade.pricePercent
        }

        // A fully closed position is left in place with a quantity of zero rather than
        // dropped, so the residual shows instead of a silent 0.0. Rounding on the
        // proportional subtraction leaves a few kopecks, and zeroing them would hide it.
        val average = if (quantity > 0) cost / quantity / bond.nominalUAH * 100.0 else null

        return BondPosition(
            bond = bond,
            quantity = quantity,
            costUAH = cost,
            averageCostPercent = average,
            lastPricePercent = lastPricePercent,
            annualCouponIncomeUAH = annualCouponIncomeUAH(quantity, bond.nominalUAH, bond.couponPercent)
        )
    }

    /**
     * Upper-cases and trims an ISIN.
     *
     * The primary key, and typed by hand on a phone keyboard where lower case is the likely
     * form. Left as typed, the same instrument would be two bonds with one position each.
     */
    fun normaliseIsin(raw: String): String = raw.trim().uppercase()

    /** Error text, or null when the quantity is usable. */
    fun validateQuantity(quantity: Int): String? = when {
        quantity <= 0 -> "Quantity must be at least 1"
        else -> null
    }

    /**
     * Error text, or null when the price is usable.
     *
     * Bounded loosely on purpose: a bond can trade far below par and a distressed one far
     * above it, and a range tight enough to be "sensible" would reject real trades. What is
     * caught is a zero and a number so large it can only be a misplaced decimal point.
     */
    fun validatePricePercent(pricePercent: Double): String? = when {
        pricePercent <= 0.0 -> "Price must be above 0%"
        pricePercent > 1000.0 -> "Price looks too large"
        else -> null
    }

    /**
     * Error text, or null when the nominal is usable.
     *
     * Zero is rejected rather than divided by, because the nominal scales every price in the
     * app and a zero one would make the average cost and the coupon income both meaningless.
     */
    fun validateNominal(nominalUAH: Double): String? = when {
        nominalUAH <= 0.0 -> "Nominal must be above 0"
        else -> null
    }

    /**
     * Error text, or null when the coupon is usable or absent.
     *
     * Zero is allowed: a bond that pays only its nominal at maturity is a real thing, and
     * treating it as an error would make it unrecordable.
     */
    fun validateCouponPercent(couponPercent: Double?): String? = when {
        couponPercent == null -> null
        couponPercent < 0.0 -> "Coupon cannot be negative"
        else -> null
    }
}
