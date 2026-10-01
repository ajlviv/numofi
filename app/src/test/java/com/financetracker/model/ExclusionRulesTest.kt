package com.financetracker.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ExclusionRulesTest {

    private fun tx(title: String) = Transaction(
        title = title,
        amount = 100.0,
        type = TransactionType.EXPENSE,
        category = "Other",
        timestamp = 0L
    )

    @Test
    fun `no rules count every row and report nothing excluded`() {
        val rows = listOf(tx("Salary"), tx("Groceries"))
        val result = ExclusionRules(emptyList()).select(rows)

        assertEquals(2, result.counted.size)
        assertEquals(0, result.excluded)
    }

    @Test
    fun `a rule drops the row it matches and says how many it dropped`() {
        val rows = listOf(tx("Salary"), tx("Transfer to savings"), tx("Groceries"))
        val result = ExclusionRules(listOf("transfer")).select(rows)

        assertEquals(listOf("Salary", "Groceries"), result.counted.map { it.title })
        assertEquals(1, result.excluded)
    }

    @Test
    fun `matching ignores the case of both sides`() {
        val result = ExclusionRules(listOf("TRANSFER")).select(listOf(tx("transfer to savings")))

        assertEquals(0, result.counted.size)
    }

    @Test
    fun `a rule matches anywhere in the title, not only at the start`() {
        val result = ExclusionRules(listOf("savings")).select(listOf(tx("Transfer to savings")))

        assertEquals(0, result.counted.size)
    }

    @Test
    fun `any one of several rules is enough to drop a row`() {
        val rows = listOf(tx("Internal transfer"), tx("Cashback"), tx("Rent"))
        val result = ExclusionRules(listOf("transfer", "cashback")).select(rows)

        assertEquals(listOf("Rent"), result.counted.map { it.title })
        assertEquals(2, result.excluded)
    }

    @Test
    fun `a rule matching nothing excludes nothing`() {
        val rows = listOf(tx("Salary"), tx("Groceries"))
        val result = ExclusionRules(listOf("paypal")).select(rows)

        assertEquals(2, result.counted.size)
        assertEquals(0, result.excluded)
    }

    @Test
    fun `a blank rule is dropped rather than matching every row`() {
        // The failure this prevents is specific and severe: an empty needle is a substring of
        // every title, so storing one would silently empty every total in the app.
        val rows = listOf(tx("Salary"), tx("Groceries"))
        val result = ExclusionRules(listOf("", "   ")).select(rows)

        assertEquals(2, result.counted.size)
        assertEquals(0, result.excluded)
    }

    @Test
    fun `a rule is trimmed before it is stored, so typing a space does not narrow it`() {
        val result = ExclusionRules(listOf("  transfer  ")).select(listOf(tx("Transfer to savings")))

        assertEquals(0, result.counted.size)
    }

    @Test
    fun `the same rule twice counts as one`() {
        // Storing a duplicate would make the settings list show a rule the user cannot
        // explain, and make the list longer than the set of things actually excluded.
        val rules = ExclusionRules(listOf("transfer", "transfer"))

        assertEquals(1, rules.patterns.size)
    }

    @Test
    fun `the same rule in two cases counts as one`() {
        val rules = ExclusionRules(listOf("transfer", "Transfer"))

        assertEquals(1, rules.patterns.size)
    }

    @Test
    fun `rules read back as the user typed them, not as they were folded`() {
        // The list on the settings screen has to show what was typed. Folding for matching
        // must not leak into the display form, or a rule would come back as "transfer" after
        // being entered as "Transfer".
        val rules = ExclusionRules(listOf("Transfer"))

        assertEquals(listOf("Transfer"), rules.patterns)
    }

    @Test
    fun `folding does not depend on the device locale`() {
        // Turkish is the locale this breaks in: its casing maps I to a dotless i, so a
        // Latin-script rule stops matching a Latin-script title. Without Locale.ROOT the
        // same stored rules exclude different rows on two devices, and neither is wrong.
        val default = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            val result = ExclusionRules(listOf("Transfer")).select(listOf(tx("Transfer to savings")))

            assertEquals(0, result.counted.size)
        } finally {
            Locale.setDefault(default)
        }
    }

    @Test
    fun `no rules is a state the app can be in rather than a null`() {
        // Not nullable: a missing preference is the ordinary fresh-install state, and making
        // it a null would put a branch at every call site for the case that is the default.
        assertTrue(ExclusionRules(emptyList()).isEmpty)
        assertFalse(ExclusionRules(listOf("transfer")).isEmpty)
    }

    @Test
    fun `a rule set with no stored rules reads as empty rather than as one blank rule`() {
        assertTrue(ExclusionRules(listOf("")).isEmpty)
    }

    @Test
    fun `excluding every row is allowed, and still reports the count`() {
        // A user may deliberately want one currency's totals to read as nothing. The report is
        // what keeps that from looking like a bug: zero rows counted is a different statement
        // from "no rules are on".
        val rows = listOf(tx("Transfer"), tx("Transfer two"))
        val result = ExclusionRules(listOf("transfer")).select(rows)

        assertTrue(result.counted.isEmpty())
        assertEquals(2, result.excluded)
    }

    @Test
    fun `a row matching several rules is excluded once`() {
        val rows = listOf(tx("Transfer between own accounts"))
        val result = ExclusionRules(listOf("transfer", "own accounts")).select(rows)

        assertEquals(1, result.excluded)
    }

    @Test
    fun `counting nothing with no rules does not allocate a list`() {
        val result = ExclusionRules(emptyList()).select(emptyList())

        assertTrue(result.counted.isEmpty())
        assertEquals(0, result.excluded)
    }

    @Test
    fun `two rule sets holding the same rules are equal`() {
        // What this buys: DataStore re-emits its whole value on any write, including writes to
        // other keys, so a flow combining this with the transaction list would otherwise
        // re-run every rule against every row each time an unrelated setting changed.
        assertEquals(ExclusionRules(listOf("transfer")), ExclusionRules(listOf("transfer")))
    }

    @Test
    fun `equality is over the folded form, so case and padding do not make two sets differ`() {
        assertEquals(ExclusionRules(listOf("Transfer")), ExclusionRules(listOf("  transfer  ")))
    }

    @Test
    fun `rule sets holding different rules are not equal`() {
        assertFalse(ExclusionRules(listOf("transfer")) == ExclusionRules(listOf("cashback")))
        assertFalse(ExclusionRules(listOf("transfer")) == ExclusionRules(emptyList()))
    }

    @Test
    fun `equal rule sets share a hash code, which is what makes them usable as a map key`() {
        assertEquals(
            ExclusionRules(listOf("a", "b")).hashCode(),
            ExclusionRules(listOf("A", "B ")).hashCode()
        )
    }
}
