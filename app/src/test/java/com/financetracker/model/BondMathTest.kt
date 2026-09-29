package com.financetracker.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The money.
 *
 * These are the numbers a user checks against their broker's screen, so each test is written
 * as a worked figure rather than a round number. Two mistakes are guarded here that a
 * percentage is easy to make and hard to notice: treating 9.5 as a rate instead of 9.5
 * percent, and adding a per-bond commission once rather than once per bond.
 */
class BondMathTest {

    // 1000 UAH nominal, 9.5% coupon: the everyday case.
    private val nominal = 1000.0
    private val coupon = 9.5

    private fun trade(
        side: BondTradeSide = BondTradeSide.BUY,
        quantity: Int = 1,
        pricePercent: Double = 99.5,
        accrued: Double = 0.0,
        commission: Double = 0.0
    ) = BondTrade(
        id = 0,
        isin = "UA9000012345",
        side = side,
        quantity = quantity,
        pricePercent = pricePercent,
        accruedInterestUAH = accrued,
        commissionUAH = commission,
        tradeDate = 0,
        bankCode = null,
        transactionId = null
    )

    // MARK: - One trade

    @Test
    fun `the clean price of one bond is a percentage of nominal`() {
        // 99.5% of 1000 is 995. If the division by 100 were dropped this would be 99500,
        // a hundredfold error that still looks like a plausible number on screen.
        assertEquals(995.0, BondMath.cleanPriceUAH(nominal, 99.5), 0.0)
    }

    @Test
    fun `a bond trading above nominal is not capped at nominal`() {
        // ОВДП do trade above par. Clamping here would make a 101% fill read as 100%.
        assertEquals(1012.0, BondMath.cleanPriceUAH(nominal, 101.2), 0.0)
    }

    @Test
    fun `a purchase totals the price, the accrual and the commission`() {
        // 2 bonds at 99.5% is 1990, plus 40 of accrued interest for each of the two bonds
        // (the accrual is a per-bond figure), plus 12 for the commission.
        val total = BondMath.tradeTotalUAH(
            trade(quantity = 2, accrued = 40.0, commission = 12.0),
            nominal
        )

        assertEquals(2082.0, total, 0.0)
    }

    @Test
    fun `the commission is added once per trade, not once per bond`() {
        // 12 is the whole trade's commission as the broker charged it. Multiplying it by
        // the quantity would silently inflate every multi-bond purchase by the lot size.
        val perBond = BondMath.tradeTotalUAH(trade(quantity = 1, commission = 12.0), nominal)
        val fiveBonds = BondMath.tradeTotalUAH(trade(quantity = 5, commission = 12.0), nominal)

        assertEquals(1007.0, perBond, 0.0)
        assertEquals(4987.0, fiveBonds, 0.0)
    }

    @Test
    fun `the accrual is quoted per bond and scales with the quantity`() {
        // 20 per bond over 3 bonds: 3 x 995 of price plus 3 x 20 of accrual. This is the
        // opposite case to the commission, and the asymmetry is deliberate: brokers quote
        // the accrual per bond and the commission per trade, so each is stored in the unit
        // it is quoted in.
        val total = BondMath.tradeTotalUAH(trade(quantity = 3, accrued = 20.0), nominal)

        assertEquals(3045.0, total, 0.0)
    }

    @Test
    fun `a sale pays what it brings in`() {
        // 1010 of price at 101%, plus 30 of accrued interest, plus 5 commission.
        val total = BondMath.tradeTotalUAH(
            trade(side = BondTradeSide.SELL, quantity = 1, pricePercent = 101.0, accrued = 30.0, commission = 5.0),
            nominal
        )

        assertEquals(1045.0, total, 0.0)
    }

    // MARK: - Coupon

    @Test
    fun `annual coupon income treats the rate as a percentage`() {
        // One 1000 nominal at 9.5% is 95 a year, not 9500. This is the single most likely
        // arithmetic slip in the whole feature: a coupon of 9.5 is not a rate of 9.5.
        assertEquals(95.0, BondMath.annualCouponIncomeUAH(quantity = 1, nominalUAH = nominal, couponPercent = coupon)!!, 0.0)
        assertEquals(475.0, BondMath.annualCouponIncomeUAH(quantity = 5, nominalUAH = nominal, couponPercent = coupon)!!, 0.0)
    }

    @Test
    fun `a bond with no recorded coupon has none to report`() {
        // Null rather than zero: zero would be a claim that the bond pays nothing, which is
        // a different fact from not knowing.
        assertNull(BondMath.annualCouponIncomeUAH(quantity = 10, nominalUAH = nominal, couponPercent = null))
    }

    @Test
    fun `a zero coupon is reported as zero, not as unknown`() {
        // Distinct from null above, and it must survive the null check.
        assertEquals(0.0, BondMath.annualCouponIncomeUAH(quantity = 10, nominalUAH = nominal, couponPercent = 0.0)!!, 0.0)
    }

    @Test
    fun `the frequency of coupon payments does not change the annual figure`() {
        // Quarterly and annual payments differ in when the cash lands, not in how much a
        // year accrues. Dividing by the period here would understate the annual income by
        // the payment count.
        val quarterly = BondMath.annualCouponIncomeUAH(1, nominal, coupon)!!
        val annual = BondMath.annualCouponIncomeUAH(1, nominal, coupon)!!

        assertEquals(quarterly, annual, 0.0)
        assertEquals(95.0 / 4, BondMath.periodCouponIncomeUAH(1, nominal, coupon, monthsBetween = 3)!!, 0.0)
        // A zero or absent period is not a division by zero.
        assertNull(BondMath.periodCouponIncomeUAH(1, nominal, coupon, monthsBetween = 0))
        assertNull(BondMath.periodCouponIncomeUAH(1, nominal, coupon, monthsBetween = null))
        assertNull(BondMath.periodCouponIncomeUAH(1, nominal, null, monthsBetween = 3))
    }

    // MARK: - Positions

    private val bond = Bond(isin = "UA9000012345", name = "ОвДП 24/Б", nominalUAH = nominal, couponPercent = coupon)

    @Test
    fun `one purchase is a position costing what was paid`() {
        val position = BondMath.position(bond, listOf(trade(quantity = 2, accrued = 40.0, commission = 12.0)))!!

        assertEquals(2, position.quantity)
        assertEquals(2082.0, position.costUAH, 0.0)
        // Above nominal, because the accrual and commission are part of what it cost.
        assertEquals(104.1, position.averageCostPercent!!, 0.0001)
    }

    @Test
    fun `a sale reduces the quantity and the cost by the same proportion`() {
        // Sold at 101% but the cost basis is what the user paid, so the average cost of what
        // remains is unchanged by the price they got. Using the sale price here would quietly
        // rewrite the cost of the bonds they kept.
        val position = BondMath.position(
            bond,
            listOf(
                trade(quantity = 10, accrued = 0.0, commission = 0.0),
                trade(side = BondTradeSide.SELL, quantity = 4, pricePercent = 101.0, commission = 5.0)
            )
        )!!

        assertEquals(6, position.quantity)
        // Pro rata: 4 of 10 bonds, so 40% of 9950 leaves the cost basis. The 5 commission on
        // the sale is not added back, because a sale reduces the holding rather than
        // enlarging it — the cash it produced is on its own transaction row.
        assertEquals(5970.0, position.costUAH, 0.0)
        assertEquals(99.5, position.averageCostPercent!!, 0.0001)
    }

    @Test
    fun `two purchases average by what was paid, not by the last price`() {
        val position = BondMath.position(
            bond,
            listOf(
                trade(quantity = 1, pricePercent = 95.0, commission = 0.0),
                trade(quantity = 3, pricePercent = 100.0, commission = 0.0)
            )
        )!!

        assertEquals(4, position.quantity)
        // 950 plus 3000. The accrual and commission are zero on both trades, so the cost
        // basis is exactly what the two fills came to.
        assertEquals(3950.0, position.costUAH, 0.0)
        // 987.50 per bond, which is 98.75% of nominal — not the 100 the last fill was at.
        assertEquals(98.75, position.averageCostPercent!!, 0.0001)
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
        assertNull(position.averageCostPercent)

        assertEquals(0, position.quantity)
        // Left at the residual rather than zeroed, so a rounding remainder does not vanish.
        assertTrue(position.costUAH < 1.0)
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
                trade(side = BondTradeSide.SELL, quantity = 2, pricePercent = 101.0),
                trade(quantity = 3, pricePercent = 100.0)
            )
        )!!

        assertEquals(3, position.quantity)
        assertTrue(position.costUAH >= 0.0)
    }

    @Test
    fun `buys in a different order fold to the same position`() {
        // The fold is a sum and a proportional subtraction, so it does not depend on the
        // order buys arrive in. The DAO still returns them oldest first, because relying on
        // this being true is cheaper than rediscovering it later.
        val a = BondMath.position(
            bond,
            listOf(trade(quantity = 1, pricePercent = 95.0), trade(quantity = 3, pricePercent = 100.0))
        )!!
        val b = BondMath.position(
            bond,
            listOf(trade(quantity = 3, pricePercent = 100.0), trade(quantity = 1, pricePercent = 95.0))
        )!!

        assertEquals(a.quantity, b.quantity)
        assertEquals(a.costUAH, b.costUAH, 0.0)
    }

    // MARK: - Settlement

    @Test
    fun `a hryvnia trade needs no rate`() {
        val settlement = BondMath.settlement(
            marketUAH = 9950.0,
            settlementCurrency = "UAH",
            actualAmount = null
        )

        assertEquals(9950.0, settlement.amount!!, 0.0)
        // Stating a rate against itself would be inventing a 1.0 and implying a conversion
        // that never happened.
        assertNull(settlement.impliedRate)
    }

    @Test
    fun `a foreign trade keeps the amount actually charged`() {
        // The broker took 325 USD off a USD account. Converting to 9950 at any rate the app
        // could have invented would be wrong, so the charged figure is what gets stored.
        val settlement = BondMath.settlement(
            marketUAH = 9950.0,
            settlementCurrency = "USD",
            actualAmount = 325.0
        )

        assertEquals(325.0, settlement.amount!!, 0.0)
        assertEquals(9950.0 / 325.0, settlement.impliedRate!!, 0.0000001)
    }

    @Test
    fun `a foreign trade with no amount given falls back to the market value`() {
        // So the form is usable before the user looks the figure up, at the cost of the
        // balance being in the wrong currency until they fill it in.
        val settlement = BondMath.settlement(marketUAH = 9950.0, settlementCurrency = "EUR", actualAmount = null)

        assertEquals(9950.0, settlement.amount!!, 0.0)
        assertNull(settlement.impliedRate)
    }

    @Test
    fun `a rate of zero has no implied rate to show`() {
        // Not a division by zero, and not an infinity: a zero charge is not a rate.
        val settlement = BondMath.settlement(marketUAH = 9950.0, settlementCurrency = "USD", actualAmount = 0.0)

        assertEquals(0.0, settlement.amount!!, 0.0)
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
    fun `a price outside zero to a sensible ceiling is rejected`() {
        // Not bounded tightly, because a distressed bond can trade far below par. Just not
        // zero, and not a number that would mean a misplaced decimal point.
        assertTrue(BondMath.validatePricePercent(0.0) != null)
        assertTrue(BondMath.validatePricePercent(-1.0) != null)
        // The ceiling is exclusive: exactly 1000 is the largest accepted figure.
        assertTrue(BondMath.validatePricePercent(1000.01) != null)
        assertNull(BondMath.validatePricePercent(1000.0))
        assertNull(BondMath.validatePricePercent(99.5))
    }

    @Test
    fun `a nominal of zero is rejected because every price depends on it`() {
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
