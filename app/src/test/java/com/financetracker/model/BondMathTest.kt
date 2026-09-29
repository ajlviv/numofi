package com.financetracker.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The money.
 *
 * These are the numbers a user checks against their broker's screen, so each test is written
 * as a worked figure rather than a round number. Since v7 the price is stored as money per
 * bond — 995.0 UAH, not 99.5% — so there is no percent-to-money division anywhere but the
 * one derived reading. Two mistakes are guarded here that money makes easy in another way:
 * treating 9.5 as a rate instead of 9.5 percent, and adding a per-bond commission once
 * rather than once per bond.
 */
class BondMathTest {

    // 1000 UAH nominal, 9.5% coupon: the everyday case.
    private val nominal = 1000.0
    private val coupon = 9.5

    /** 99.5% of the 1000 nominal, now entered directly as money. */
    private val parPrice = 995.0

    private fun trade(
        side: BondTradeSide = BondTradeSide.BUY,
        quantity: Int = 1,
        price: Double = parPrice,
        accrued: Double = 0.0,
        commission: Double = 0.0
    ) = BondTrade(
        id = 0,
        isin = "UA9000012345",
        side = side,
        quantity = quantity,
        price = price,
        accruedInterest = accrued,
        commission = commission,
        tradeDate = 0,
        bankCode = null,
        transactionId = null
    )

    // MARK: - One trade

    @Test
    fun `one bond costs what was entered, with no percentage conversion`() {
        // The whole point of storing money: 1020.00 typed is 1020.00 spent. The old code had
        // to divide 1020% by 100 and got a tenfold error when the unit was misread.
        assertEquals(1020.0, BondMath.tradeTotal(trade(price = 1020.0)), 0.0)
        assertEquals(995.0, BondMath.tradeTotal(trade()), 0.0)
    }

    @Test
    fun `the percentage-of-nominal reading is derived, not stored`() {
        // The market quotes 102%; the user typed 1020.00. Both are reachable, and the
        // division by 100 lives here and nowhere else.
        assertEquals(102.0, BondMath.percentOfNominal(1020.0, nominal), 0.0001)
        assertEquals(99.5, BondMath.percentOfNominal(parPrice, nominal), 0.0001)
    }

    @Test
    fun `a purchase totals the price, the accrual and the commission`() {
        // 2 bonds at 995.00 is 1990, plus 40 of accrued interest for each of the two bonds
        // (the accrual is a per-bond figure), plus 12 for the commission.
        val total = BondMath.tradeTotal(trade(quantity = 2, accrued = 40.0, commission = 12.0))

        assertEquals(2082.0, total, 0.0)
    }

    @Test
    fun `the commission is added once per trade, not once per bond`() {
        // 12 is the whole trade's commission as the broker charged it. Multiplying it by
        // the quantity would silently inflate every multi-bond purchase by the lot size.
        val perBond = BondMath.tradeTotal(trade(quantity = 1, commission = 12.0))
        val fiveBonds = BondMath.tradeTotal(trade(quantity = 5, commission = 12.0))

        assertEquals(1007.0, perBond, 0.0)
        assertEquals(4987.0, fiveBonds, 0.0)
    }

    @Test
    fun `the accrual is quoted per bond and scales with the quantity`() {
        // 20 per bond over 3 bonds: 3 x 995 of price plus 3 x 20 of accrual. This is the
        // opposite case to the commission, and the asymmetry is deliberate: brokers quote
        // the accrual per bond and the commission per trade, so each is stored in the unit
        // it is quoted in.
        val total = BondMath.tradeTotal(trade(quantity = 3, accrued = 20.0))

        assertEquals(3045.0, total, 0.0)
    }

    @Test
    fun `a sale pays what it brings in`() {
        // 1010 of price, plus 30 of accrued interest, plus 5 commission.
        val total = BondMath.tradeTotal(
            trade(side = BondTradeSide.SELL, quantity = 1, price = 1010.0, accrued = 30.0, commission = 5.0)
        )

        assertEquals(1045.0, total, 0.0)
    }

    // MARK: - Coupon

    @Test
    fun `annual coupon income treats the rate as a percentage`() {
        // One 1000 nominal at 9.5% is 95 a year, not 9500. This is the single most likely
        // arithmetic slip in the whole feature: a coupon of 9.5 is not a rate of 9.5.
        assertEquals(95.0, BondMath.annualCouponIncome(quantity = 1, nominal = nominal, couponPercent = coupon)!!, 0.0)
        assertEquals(475.0, BondMath.annualCouponIncome(quantity = 5, nominal = nominal, couponPercent = coupon)!!, 0.0)
    }

    @Test
    fun `a bond with no recorded coupon has none to report`() {
        // Null rather than zero: zero would be a claim that the bond pays nothing, which is
        // a different fact from not knowing.
        assertNull(BondMath.annualCouponIncome(quantity = 10, nominal = nominal, couponPercent = null))
    }

    @Test
    fun `a zero coupon is reported as zero, not as unknown`() {
        // Distinct from null above, and it must survive the null check.
        assertEquals(0.0, BondMath.annualCouponIncome(quantity = 10, nominal = nominal, couponPercent = 0.0)!!, 0.0)
    }

    @Test
    fun `the frequency of coupon payments does not change the annual figure`() {
        // Quarterly and annual payments differ in when the cash lands, not in how much a
        // year accrues. Dividing by the period here would understate the annual income by
        // the payment count.
        assertEquals(95.0 / 4, BondMath.periodCouponIncome(1, nominal, coupon, monthsBetween = 3)!!, 0.0)
        // A zero or absent period is not a division by zero.
        assertNull(BondMath.periodCouponIncome(1, nominal, coupon, monthsBetween = 0))
        assertNull(BondMath.periodCouponIncome(1, nominal, coupon, monthsBetween = null))
        assertNull(BondMath.periodCouponIncome(1, nominal, null, monthsBetween = 3))
    }

    // MARK: - Positions

    private val bond = Bond(isin = "UA9000012345", name = "ОвДП 24/Б", nominal = nominal, couponPercent = coupon)

    @Test
    fun `one purchase is a position costing what was paid`() {
        val position = BondMath.position(bond, listOf(trade(quantity = 2, accrued = 40.0, commission = 12.0)))!!

        assertEquals(2, position.quantity)
        assertEquals(2082.0, position.cost, 0.0)
        // 1041.00 per bond — above par because the accrual and commission are part of the
        // cost. Money, not a percentage.
        assertEquals(1041.0, position.averageCost!!, 0.0001)
    }

    @Test
    fun `a sale reduces the quantity and the cost by the same proportion`() {
        // Sold at 1010 but the cost basis is what the user paid, so the average cost of what
        // remains is unchanged by the price they got. Using the sale price here would quietly
        // rewrite the cost of the bonds they kept.
        val position = BondMath.position(
            bond,
            listOf(
                trade(quantity = 10, commission = 0.0),
                trade(side = BondTradeSide.SELL, quantity = 4, price = 1010.0, commission = 5.0)
            )
        )!!

        assertEquals(6, position.quantity)
        // Pro rata: 4 of 10 bonds, so 40% of 9950 leaves the cost basis. The 5 commission on
        // the sale is not added back, because a sale reduces the holding rather than
        // enlarging it — the cash it produced is on its own transaction row.
        assertEquals(5970.0, position.cost, 0.0)
        assertEquals(995.0, position.averageCost!!, 0.0001)
    }

    @Test
    fun `two purchases average by what was paid, not by the last price`() {
        val position = BondMath.position(
            bond,
            listOf(
                trade(quantity = 1, price = 950.0, commission = 0.0),
                trade(quantity = 3, price = 1000.0, commission = 0.0)
            )
        )!!

        assertEquals(4, position.quantity)
        // 950 plus 3000. The accrual and commission are zero on both trades, so the cost
        // basis is exactly what the two fills came to.
        assertEquals(3950.0, position.cost, 0.0)
        // 987.50 per bond — not the 1000 the last fill was at.
        assertEquals(987.5, position.averageCost!!, 0.0001)
    }

    @Test
    fun `no trades means no position at all`() {
        // Null rather than an empty one: a zero-quantity position would show up in the list
        // as a bond the user owns, which they do not.
        assertNull(BondMath.position(bond, emptyList()))
    }

    @Test
    fun `selling everything closes the position`() {
        val position = BondMath.position(
            bond,
            listOf(
                trade(quantity = 5),
                trade(side = BondTradeSide.SELL, quantity = 5)
            )
        )!!
        assertNull(position.averageCost)

        assertEquals(0, position.quantity)
        // Left at the residual rather than zeroed, so a rounding remainder does not vanish.
        assertTrue(position.cost < 1.0)
    }

    @Test
    fun `a sale is clamped to what is actually held`() {
        // Selling 2 with nothing bought first cannot happen through the UI, which blocks the
        // oversell, but it can reach here as stored data — an import, or a hand-edited date
        // that reorders a sell before its buy. The fold clamps rather than going negative,
        // because a negative position would render a negative market value and a negative
        // unrealised figure, both of which read as real numbers to the user.
        val position = BondMath.position(
            bond,
            listOf(
                trade(side = BondTradeSide.SELL, quantity = 2, price = 1010.0),
                trade(quantity = 3, price = 1000.0)
            )
        )!!

        assertEquals(3, position.quantity)
        assertTrue(position.cost >= 0.0)
    }

    @Test
    fun `buys in a different order fold to the same position`() {
        // The fold is a sum and a proportional subtraction, so it does not depend on the
        // order buys arrive in. The DAO still returns them oldest first, because relying on
        // this being true is cheaper than rediscovering it later.
        val a = BondMath.position(
            bond,
            listOf(trade(quantity = 1, price = 950.0), trade(quantity = 3, price = 1000.0))
        )!!
        val b = BondMath.position(
            bond,
            listOf(trade(quantity = 3, price = 1000.0), trade(quantity = 1, price = 950.0))
        )!!

        assertEquals(a.quantity, b.quantity)
        assertEquals(a.cost, b.cost, 0.0)
    }

    @Test
    fun `a fully held position values it at the last price in money`() {
        val position = BondMath.position(
            bond,
            listOf(trade(quantity = 3, price = 995.0), trade(quantity = 2, price = 1000.0))
        )!!

        // 5 bonds at the most recent price of 1000.00, market value in the bond's currency.
        assertEquals(1000.0, position.lastPrice!!, 0.0)
        assertEquals(5000.0, position.marketValue, 0.0)
        // 5 x 995 + 2 x 1000... the pair above is 3 x 995 + 2 x 1000 = 4985, so unrealised
        // is +15 at the mark.
        assertEquals(4985.0, position.cost, 0.0)
        assertEquals(15.0, position.unrealised, 0.0)
    }

    // MARK: - Settlement

    @Test
    fun `settlement in the bond's own currency needs no rate`() {
        val settlement = BondMath.settlement(
            marketValue = 9950.0,
            bondCurrency = "UAH",
            settlementCurrency = "UAH",
            actualAmount = null
        )

        assertEquals(9950.0, settlement.amount, 0.0)
        // Stating a rate against itself would be inventing a 1.0 and implying a conversion
        // that never happened.
        assertNull(settlement.impliedRate)
    }

    @Test
    fun `a dollar bond settled in dollars needs no rate either`() {
        // The previous hard-coded UAH comparison would have demanded a baked 1.0 rate on a
        // USD-nominal bond settled in USD. The comparison is against the bond's currency.
        val settlement = BondMath.settlement(
            marketValue = 995.0,
            bondCurrency = "USD",
            settlementCurrency = "USD",
            actualAmount = null
        )

        assertEquals(995.0, settlement.amount, 0.0)
        assertNull(settlement.impliedRate)
    }

    @Test
    fun `a foreign settlement keeps the amount actually charged`() {
        // The broker took 325 USD off a USD account for a UAH bond. Converting to 9950 at
        // any rate the app could have invented would be wrong, so the charged figure is what
        // gets stored.
        val settlement = BondMath.settlement(
            marketValue = 9950.0,
            bondCurrency = "UAH",
            settlementCurrency = "USD",
            actualAmount = 325.0
        )

        assertEquals(325.0, settlement.amount, 0.0)
        assertEquals(9950.0 / 325.0, settlement.impliedRate!!, 0.0000001)
    }

    @Test
    fun `a foreign settlement with no amount given falls back to the market value`() {
        // So the form is usable before the user looks the figure up, at the cost of the
        // balance being in the wrong currency until they fill it in.
        val settlement = BondMath.settlement(
            marketValue = 995.0,
            bondCurrency = "USD",
            settlementCurrency = "EUR",
            actualAmount = null
        )

        assertEquals(995.0, settlement.amount, 0.0)
        assertNull(settlement.impliedRate)
    }

    @Test
    fun `a rate of zero has no implied rate to show`() {
        // Not a division by zero, and not an infinity: a zero charge is not a rate.
        val settlement = BondMath.settlement(
            marketValue = 9950.0,
            bondCurrency = "UAH",
            settlementCurrency = "USD",
            actualAmount = 0.0
        )

        assertEquals(0.0, settlement.amount, 0.0)
        assertNull(settlement.impliedRate)
    }

    // MARK: - Validation

    @Test
    fun `an isin is accepted in lower case and stored upper`() {
        // Typed on a phone keyboard, lower case is the likely form; a lower-cased primary
        // key would create a second bond for the same instrument.
        assertEquals("UA9000012345", BondMath.normaliseIsin(" ua9000012345 "))
        assertEquals("UA9000012345", BondMath.normaliseIsin("UA9000012345"))
    }

    @Test
    fun `a quantity must be a whole number above zero`() {
        assertEquals("Quantity must be at least 1", BondMath.validateQuantity(0))
        assertEquals("Quantity must be at least 1", BondMath.validateQuantity(-3))
        assertNull(BondMath.validateQuantity(1))
    }

    @Test
    fun `a money price is valid at and below twice the nominal`() {
        // The price field is money since v7, so 1020.00 on a 1000 bond is simply valid —
        // the old build wanted 102 put in instead and the tenfold total came from that.
        assertNull(BondMath.validatePrice(1020.0, nominal))
        assertNull(BondMath.validatePrice(2000.0, nominal))
        assertTrue(BondMath.validatePrice(0.0, nominal) != null)
        assertTrue(BondMath.validatePrice(-1.0, nominal) != null)
        // Above twice the nominal, whatever its currency, is an impossible bond price.
        assertTrue(BondMath.validatePrice(2000.01, nominal) != null)
    }

    @Test
    fun `the price ceiling does not depend on knowing the nominal`() {
        // A price typed before the nominal is filled in is checked against what is known and
        // passed through. The ceiling is applied as soon as the nominal exists.
        assertNull(BondMath.validatePrice(50_000.0, nominal = null))
    }

    @Test
    fun `a nominal of zero is rejected because the coupon and price ceiling depend on it`() {
        assertTrue(BondMath.validateNominal(0.0) != null)
        assertTrue(BondMath.validateNominal(-1000.0) != null)
        assertNull(BondMath.validateNominal(1000.0))
    }

    @Test
    fun `a negative coupon is rejected but zero is allowed`() {
        // Zero is a real thing: a bond that pays only its nominal at maturity.
        assertTrue(BondMath.validateCouponPercent(-0.5) != null)
        assertNull(BondMath.validateCouponPercent(0.0))
        assertNull(BondMath.validateCouponPercent(9.5))
        assertNull(BondMath.validateCouponPercent(null))
    }
}