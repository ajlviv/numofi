package com.financetracker.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Every figure the bond screens show.
 *
 * Kept apart from any storage or UI type, and written as plain functions over plain doubles,
 * so the arithmetic can be checked against a broker's confirmation screen directly. Every
 * amount here is money in the bond's own currency: the price the user types is the price
 * spent, with no division by 100 anywhere except the one derived reading that converts a
 * money price to the percentage-of-nominal quote convention.
 */
object BondMath {

    /** The most a bond can plausibly cost, as a multiple of its nominal. */
    const val MAX_PRICE_OF_NOMINAL = 2.0

    /**
     * The year a projected coupon is pro-rated over: actual/365, the everyday convention.
     *
     * Named rather than inlined because it is a convention and not a fact about the calendar.
     * A bank quoting the same offer on a 360-day year comes out a few hryvnias apart, and that
     * is only findable if the divisor is visible.
     */
    private const val DAYS_IN_YEAR = 365.0

    /** The currencies a bond may be denominated in. [Currency.kt] owns this list. */
    const val DEFAULT_NOMINAL_CURRENCY = "UAH"

    /**
     * What one trade costs, wherever it went, in the bond's currency.
     *
     * Quantity times the price per bond, plus the accrued interest for every bond, plus the
     * commission once. The two additions are deliberately asymmetric:
     *
     * - [BondTrade.accruedInterest] is a per-bond figure, so it scales with the quantity.
     *   This is how a broker quotes it and it is stored in that unit so a per-bond reading
     *   stays recoverable.
     * - [BondTrade.commission] is the whole trade's commission as charged, so it is added
     *   once. A broker quoting per bond and one quoting per trade are indistinguishable
     *   from a single number, and the field is defined as the total so that whatever the
     *   user types is what gets spent.
     *
     * A sale uses the same expression: the money is the same money, and keeping one function
     * means a sale cannot drift from a purchase's arithmetic.
     */
    fun tradeTotal(trade: BondTrade): Double =
        trade.quantity * trade.price +
            trade.quantity * trade.accruedInterest +
            trade.commission

    /**
     * The percentage-of-nominal reading of a money price, for display and nothing else.
     *
     * The one division by 100 worth doing. The stored figure is the money price, and this is
     * the exchange-quote convention it corresponds to, so the two never have to be reasoned
     * into agreement.
     */
    fun percentOfNominal(price: Double, nominal: Double): Double =
        price / nominal * 100.0

    /**
     * What a trade costs, valued in the currency the account actually holds.
     *
     * [BondSettlement.amount] is what should be recorded on the cash row and
     * [BondSettlement.impliedRate] is the rate the user's own numbers imply, shown so a
     * mistyped amount is visible. The rate is never stored: there is no rate feed here, and
     * a stored rate would be a second source of truth about a conversion that happened
     * somewhere else entirely.
     *
     * [bondCurrency] is the currency [marketValue] is in. Settlement in the bond's own
     * currency needs no rate even to mention; settlement in any other needs one, so the two
     * cases are told apart by comparing currencies rather than by assuming UAH.
     */
    fun settlement(
        marketValue: Double,
        bondCurrency: String,
        settlementCurrency: String,
        actualAmount: Double?
    ): BondSettlement {
        val sameCurrency = settlementCurrency.equals(bondCurrency, ignoreCase = true)
        if (sameCurrency) {
            // A rate against the bond's own currency would be inventing a 1.0 and implying a
            // conversion that never took place, so it is left off.
            return BondSettlement(amount = marketValue, impliedRate = null)
        }
        if (actualAmount == null) {
            // The form has to be usable before the user goes and looks the figure up, so the
            // market value stands in. The balance is in the wrong currency until they fill
            // it in, which is visible rather than hidden.
            return BondSettlement(amount = marketValue, impliedRate = null)
        }
        return BondSettlement(
            amount = actualAmount,
            impliedRate = if (actualAmount == 0.0) null else marketValue / actualAmount
        )
    }

    /**
     * A year's worth of coupon on a holding, or null when the coupon was never recorded.
     *
     * In the bond's currency. Null and not zero, because a missing rate is a different fact
     * from a bond that pays nothing. Multiplying by a bare [couponPercent] would be wrong by
     * a factor of a hundred: 9.5 is nine and a half percent, not nine and a half times the
     * nominal.
     */
    fun annualCouponIncome(quantity: Int, nominal: Double, couponPercent: Double?): Double? =
        couponPercent?.let { quantity * nominal * it / 100.0 }

    /**
     * The coupon for one payment period, or null when the period is unknown.
     *
     * Divides the annual figure by the number of periods a year rather than by the number of
     * months, so a 3-month period is a third of a year and a 6-month one a sixth. Dividing
     * by the month count directly would give a quarterly payment as a thirty-second of the
     * annual coupon.
     */
    fun periodCouponIncome(
        quantity: Int,
        nominal: Double,
        couponPercent: Double?,
        monthsBetween: Int?
    ): Double? {
        if (couponPercent == null || monthsBetween == null || monthsBetween <= 0) return null
        val periodsPerYear = 12.0 / monthsBetween
        return annualCouponIncome(quantity, nominal, couponPercent)!! / periodsPerYear
    }

    /**
     * What a holding is expected to pay by its maturity: the nominal back, plus every coupon
     * falling due while it is held.
     *
     * That is the shape of an ОВДП payout — a broker's schedule is a run of coupon rows and a
     * final row of nominal plus coupon — and it is deliberately *not* "the money you paid plus
     * interest on it". A bond accrues its coupon on the [nominal] and repays the [nominal], so
     * whatever a buyer paid above the face value is money spent and never money stored.
     * Projecting `invested + invested × rate` hands that premium back and then pays the coupon
     * rate on top of it, which is a figure no issuer pays and no broker's schedule shows. On a
     * bond bought near par the two agree, which is why it went unnoticed for as long as it did.
     *
     * The number of payments comes from the schedule run backwards off [maturityMillis] in
     * [couponPeriodMonths] steps, counting the dates strictly after [heldSinceMillis]. Maturity
     * is one of them, because brokers pay the last coupon in the same row as the nominal.
     * Strictly after, not on or after: a bond bought on a coupon date trades ex-coupon and does
     * not collect that date's coupon.
     *
     * The maturity date anchors the schedule because it is the one coupon date that is
     * certain — the others are not recorded. A bond bought between two of them is therefore
     * counted by the schedule it sits in rather than by the days it was held, which is what a
     * broker's schedule does too. With no [couponPeriodMonths] there is no schedule to run, so
     * the coupon falls back to being pro-rated over actual/365 — an estimate, and reported with
     * a null [BondPayout.payments] so the caller can say so.
     *
     * Null when there is nothing to project rather than a zero standing in for an unknown: no
     * recorded coupon, no maturity, a maturity already passed — a payout that already happened
     * is a recorded redemption, and projecting it again would be a second answer to a question
     * the ledger has settled — or nothing left held, which the card already says in other words.
     */
    fun expectedAtMaturity(
        invested: Double,
        quantity: Int,
        nominal: Double,
        couponPercent: Double?,
        couponPeriodMonths: Int?,
        heldSinceMillis: Long,
        maturityMillis: Long?,
        zone: ZoneId
    ): BondPayout? {
        if (couponPercent == null || maturityMillis == null || quantity <= 0) return null
        val heldFrom = localDate(heldSinceMillis, zone)
        val maturity = localDate(maturityMillis, zone)
        if (!maturity.isAfter(heldFrom)) return null

        val faceValue = quantity * nominal
        val payments = couponPayments(heldFrom, maturity, couponPeriodMonths)
        val perPeriod = periodCouponIncome(quantity, nominal, couponPercent, couponPeriodMonths)
        val coupon = if (payments != null && perPeriod != null) {
            perPeriod * payments
        } else {
            faceValue * couponPercent / 100.0 * ChronoUnit.DAYS.between(heldFrom, maturity) / DAYS_IN_YEAR
        }
        return BondPayout(
            invested = invested,
            nominal = faceValue,
            coupon = coupon,
            payments = payments
        )
    }

    /**
     * How many coupon payments fall between [heldFrom] and [maturity], counting maturity, or
     * null when no period is recorded and no schedule can be run.
     *
     * Every step strictly decreases the date, so the walk terminates however far back it goes.
     */
    private fun couponPayments(heldFrom: LocalDate, maturity: LocalDate, couponPeriodMonths: Int?): Int? {
        if (couponPeriodMonths == null || couponPeriodMonths <= 0) return null
        var payments = 0
        var date = maturity
        while (date.isAfter(heldFrom)) {
            payments++
            date = date.minusMonths(couponPeriodMonths.toLong())
        }
        return payments
    }

    /** The calendar date an instant falls on, as this zone reads it. */
    private fun localDate(millis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

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
        var lastPrice: Double? = null

        for (trade in trades) {
            val total = tradeTotal(trade)
            when (trade.side) {
                BondTradeSide.BUY -> {
                    quantity += trade.quantity
                    cost += total
                }
                // A redemption takes bonds off the holding exactly as a sale does, and the
                // cost that bought them goes with them. Proportional rather than all of it,
                // because ОВДП can be redeemed in part.
                BondTradeSide.SELL, BondTradeSide.REDEMPTION -> {
                    val gone = minOf(trade.quantity, quantity)
                    val proportion = if (quantity == 0) 0.0 else gone.toDouble() / quantity
                    cost -= cost * proportion
                    quantity -= gone
                }
            }
            lastPrice = trade.price
        }

        // A fully closed position is left in place with a quantity of zero rather than
        // dropped, so the residual shows instead of a silent 0.0. Rounding on the
        // proportional subtraction leaves a few kopecks, and zeroing them would hide it.
        val average = if (quantity > 0) cost / quantity else null

        return BondPosition(
            bond = bond,
            quantity = quantity,
            cost = cost,
            averageCost = average,
            lastPrice = lastPrice,
            annualCouponIncome = annualCouponIncome(quantity, bond.nominal, bond.couponPercent),
            // The first trade, not the most recent one: a projected payout is earned over the
            // whole time the bonds were held, so it starts where the holding did.
            heldSince = trades.minOf { it.tradeDate }
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
     * Error text, or null when the price of one bond is usable.
     *
     * Money in, money out: the field is a UAH/USD/EUR price per bond, so the bottom is zero
     * and the top is [MAX_PRICE_OF_NOMINAL] times the nominal. A distressed bond can trade
     * far below par, but none trades at multiples of its face value, so a figure above twice
     * the nominal can only be a slip — most often a quantity or a comma typed in the wrong
     * box. No percentage conversion is needed or offered here.
     */
    fun validatePrice(price: Double, nominal: Double?): String? = when {
        price <= 0.0 -> "Price must be above 0"
        nominal != null && nominal > 0.0 && price > MAX_PRICE_OF_NOMINAL * nominal ->
            "Price looks too large — a bond does not trade at more than twice its nominal"
        else -> null
    }

    /**
     * Error text, or null when the nominal is usable.
     *
     * Zero is rejected rather than divided by, because the nominal scales the coupon and the
     * price ceiling and a zero one would make both meaningless.
     */
    fun validateNominal(nominal: Double): String? = when {
        nominal <= 0.0 -> "Nominal must be above 0"
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