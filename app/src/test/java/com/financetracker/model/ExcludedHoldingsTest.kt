package com.financetracker.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A holding the user's exclusion rules removed from the cash side only.
 *
 * A bond purchase is two rows: a cash row recording what left the account, and the position
 * itself. The exclusion rules are matched against transaction titles, so a rule can take the
 * cash row away and leave the bond where it is — and the total then reports the full nominal
 * of something the money for which has already been subtracted out. The figure grows by
 * everything paid and nothing on screen says so.
 *
 * The fix is that the rules are a statement about what a *total* counts, so they have to reach
 * the holdings as well as the cash. An excluded purchase must be excluded on both sides, or
 * not on either.
 */
class ExcludedHoldingsTest {

    private val rates = ExchangeRates(
        toUah = mapOf("USD" to 40.0, "EUR" to 50.0),
        date = "01.10.2026",
        fetchedAt = 0L
    )

    /** An ОВДП holding the way the dashboard receives one. */
    private fun position(
        name: String = "ОВДП 26/Б",
        nominalCurrency: String = "UAH",
        nominal: Double = 1_000.0,
        quantity: Int = 20
    ) = BondPosition(
        bond = Bond(isin = "UA0000000001", name = name, nominal = nominal, nominalCurrency = nominalCurrency),
        quantity = quantity,
        cost = 0.0,
        averageCost = null,
        lastPrice = null,
        annualCouponIncome = null,
        heldSince = 0L
    )

    /**
     * The whole path a real dashboard takes: rules applied to the rows, the survivors grouped,
     * then net worth built from those groups and the positions.
     */
    private fun netWorthWith(
        transactions: List<Transaction>,
        positions: List<BondPosition>,
        rules: ExclusionRules
    ): Pair<NetWorth, Int> {
        val counted = rules.select(transactions)
        return netWorth(
            totals = totalsByCurrency(counted.counted),
            positions = positions,
            rates = rates,
            base = "UAH",
            excluded = counted.excluded,
            rules = rules
        ) to counted.excluded
    }

    /** A cash row for buying a bond: the money that left. */
    private fun purchase(title: String, amount: Double = 21_357.40) = Transaction(
        id = 1L,
        title = title,
        amount = amount,
        type = TransactionType.TRANSFER,
        category = "investments",
        timestamp = 0L,
        currencyCode = "UAH",
        transferDirection = TransferDirection.OUT
    )

    private fun salary(amount: Double = 60_000.0) = Transaction(
        id = 2L,
        title = "Зарплата",
        amount = amount,
        type = TransactionType.INCOME,
        category = "salary",
        timestamp = 1L,
        currencyCode = "UAH"
    )

    @Test
    fun `a rule that takes the purchase cash out also takes the holding out`() {
        val rules = ExclusionRules(listOf("ОВДП"))
        val (result, _) = netWorthWith(
            transactions = listOf(salary(), purchase("ОВДП 26/Б")),
            positions = listOf(position()),
            rules = rules
        )

        // The salary is the whole of what may be counted. The bond's 20 000 of nominal is
        // added to nothing: the cash that paid for it is not in the total, so counting the
        // holding would report 20 000 of value whose purchase is not.
        assertEquals(60_000.0, result.total!!, 1e-6)
    }

    @Test
    fun `with no rule matching, a holding is still counted`() {
        val rules = ExclusionRules(listOf("щось інше"))
        val (result, _) = netWorthWith(
            transactions = listOf(salary(), purchase("ОВДП 26/Б")),
            positions = listOf(position()),
            rules = rules
        )

        // 60 000 salary, less the 21 357.40 that bought the bond, plus its 20 000 nominal.
        assertEquals(60_000.0 - 21_357.40 + 20_000.0, result.total!!, 1e-6)
    }

    @Test
    fun `an excluded holding is named beside the total, not dropped silently`() {
        val rules = ExclusionRules(listOf("ОВДП"))
        val (result, _) = netWorthWith(
            transactions = listOf(salary(), purchase("ОВДП 26/Б")),
            positions = listOf(position()),
            rules = rules
        )

        // A figure that quietly omits what it could not include is shown with the same
        // confidence as one that could, which is the reason `unquoted` exists. The same
        // reasoning applies to a holding the user's own rules removed.
        assertEquals(1, result.excludedHoldings)
    }

    @Test
    fun `an excluded holding is reported apart from an excluded row`() {
        val rules = ExclusionRules(listOf("ОВДП", "Зарплата"))
        val (result, excludedRows) = netWorthWith(
            transactions = listOf(salary(), purchase("ОВДП 26/Б")),
            positions = listOf(position()),
            rules = rules
        )

        // They are fixed in different places — one is a row, one is a holding — and a total
        // with both removed must say so without the user having to work out which was which.
        assertEquals(2, excludedRows)
        assertEquals(1, result.excludedHoldings)
    }

    @Test
    fun `a holding excluded by rule is still shown on its own card`() {
        val rules = ExclusionRules(listOf("ОВДП"))

        // The rule is about totals. The position itself is a real holding the user recorded
        // and may still want to read, exactly as an excluded row is still in the list below
        // the total on the dashboard.
        val visible = positionsVisibleDespite(rules, listOf(position()))

        assertEquals(1, visible.size)
    }

    /**
     * What the dashboard shows in the holdings cards, which is every position rather than the
     * ones a total counted — so this must stay independent of the rules.
     */
    private fun positionsVisibleDespite(
        rules: ExclusionRules,
        positions: List<BondPosition>
    ): List<BondPosition> = positions

    @Test
    fun `two holdings where one is excluded count only the other`() {
        val rules = ExclusionRules(listOf("ОВДП 26/Б"))
        val (result, _) = netWorthWith(
            transactions = listOf(salary()),
            positions = listOf(
                position(name = "ОВДП 26/Б", nominal = 1_000.0, quantity = 20),
                position(name = "ПДВ 25/А", nominal = 500.0, quantity = 10)
            ),
            rules = rules
        )

        // The excluded one would have added 20 000; the kept one adds 5 000.
        assertEquals(65_000.0, result.total!!, 1e-6)
        assertEquals(1, result.excludedHoldings)
    }

    @Test
    fun `no rules at all leaves the holding counted and nothing reported`() {
        val (result, _) = netWorthWith(
            transactions = listOf(salary()),
            positions = listOf(position()),
            rules = ExclusionRules()
        )

        assertEquals(60_000.0 + 20_000.0, result.total!!, 1e-6)
        assertEquals(0, result.excludedHoldings)
    }

    @Test
    fun `a rule matching a holding the user does not own changes nothing`() {
        val rules = ExclusionRules(listOf("облігації США"))
        val (result, _) = netWorthWith(
            transactions = listOf(salary()),
            positions = listOf(position()),
            rules = rules
        )

        assertEquals(60_000.0 + 20_000.0, result.total!!, 1e-6)
        assertTrue(result.unquoted.isEmpty())
    }

    @Test
    fun `the rule matches a holding by its name, as it matches a row by its title`() {
        val rules = ExclusionRules(listOf("26/б"))

        val matched = rules.selectHoldings(listOf(position(name = "ОВДП 26/Б")))
        val unmatched = rules.selectHoldings(listOf(position(name = "ОВДП 25/А")))

        assertEquals(0, matched.counted.size)
        assertEquals(1, matched.excluded)
        assertEquals(1, unmatched.counted.size)
        assertEquals(0, unmatched.excluded)
    }
}