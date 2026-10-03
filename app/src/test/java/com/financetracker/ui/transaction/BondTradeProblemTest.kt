package com.financetracker.ui.transaction

import com.financetracker.R
import com.financetracker.model.BondTradeProblem
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every reason a bond trade can be refused, and whether a reader can actually be told.
 *
 * The validators in `BondMath` used to return the sentence itself — "Price must be above 0" —
 * which cannot be translated, because a Kotlin string literal is the same in every language.
 * A Ukrainian reader was shown English errors on the one screen in the app that validates a
 * trade, and nothing in the resource tables could fix it, because the text never went near them.
 *
 * So the model reports *which* problem it found and the screen turns that into a resource. This
 * is what keeps that honest: a problem added to the enum without a message for it fails here
 * rather than reaching a screen as a blank or a missing-resource crash.
 */
class BondTradeProblemTest {

    @Test
    fun `every problem has a message`() {
        val missing = BondTradeProblem.entries.filter { messageName(it).isEmpty() }

        assertTrue("no string resource for: $missing", missing.isEmpty())
    }

    @Test
    fun `no two problems share a message`() {
        // Two problems sharing one resource is usually a copy-paste, and it means one of them
        // tells the user the wrong thing.
        val shared = BondTradeProblem.entries
            .groupBy { messageName(it) }
            .filterValues { it.size > 1 }

        assertTrue("problems sharing one message: $shared", shared.isEmpty())
    }

    @Test
    fun `every message is translated`() {
        // The reason any of this exists. A resource id that resolves in English and falls back
        // to English in Ukrainian is the defect itself, so this asks the resource tables rather
        // than trusting that adding a key was enough.
        val untranslated = BondTradeProblem.entries.filter { problem ->
            val name = messageName(problem)
            !ukrainianNames.contains(name)
        }

        assertTrue(
            "no Ukrainian message for: ${untranslated.map { messageName(it) }}",
            untranslated.isEmpty()
        )
    }

    private fun messageName(problem: BondTradeProblem): String = when (problem) {
        BondTradeProblem.NAME_MISSING -> "add_error_enter_name"
        BondTradeProblem.QUANTITY_TOO_SMALL -> "add_error_quantity"
        BondTradeProblem.PRICE_NOT_POSITIVE -> "add_error_price"
        BondTradeProblem.PRICE_TOO_LARGE -> "add_error_price_large"
        BondTradeProblem.NOMINAL_NOT_POSITIVE -> "add_error_nominal"
        BondTradeProblem.COUPON_NEGATIVE -> "add_error_coupon_negative"
        BondTradeProblem.NOT_SAVED -> "add_error_bond_not_saved"
    }

    private fun messageFor(problem: BondTradeProblem): Int = when (problem) {
        BondTradeProblem.NAME_MISSING -> R.string.add_error_enter_name
        BondTradeProblem.QUANTITY_TOO_SMALL -> R.string.add_error_quantity
        BondTradeProblem.PRICE_NOT_POSITIVE -> R.string.add_error_price
        BondTradeProblem.PRICE_TOO_LARGE -> R.string.add_error_price_large
        BondTradeProblem.NOMINAL_NOT_POSITIVE -> R.string.add_error_nominal
        BondTradeProblem.COUPON_NEGATIVE -> R.string.add_error_coupon_negative
        BondTradeProblem.NOT_SAVED -> R.string.add_error_bond_not_saved
    }

    private val ukrainianNames: Set<String> by lazy {
        val file = sequenceOf("src/main/res/values-uk/strings.xml", "app/src/main/res/values-uk/strings.xml")
            .map { java.io.File(it) }
            .firstOrNull { it.isFile }
            ?: error("cannot find values-uk/strings.xml from ${java.io.File("").absolutePath}")
        Regex("""<string\s+name="([^"]+)"""")
            .findAll(file.readText(Charsets.UTF_8))
            .map { it.groupValues[1] }
            .toSet()
    }
}