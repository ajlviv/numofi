package com.financetracker.ui.transaction

import androidx.annotation.StringRes
import com.financetracker.R
import com.financetracker.model.BondTradeProblem

/**
 * The sentence for each reason a bond trade can be refused.
 *
 * One place, because there are two callers — the form's own validation and the repository's
 * refusal after a save — and two tables of sentences would be two things to keep in step. It
 * lives beside the screens rather than in [com.financetracker.model.BondMath], which stays plain
 * arithmetic with no resource ids in it.
 *
 * Exhaustive on purpose: a new [BondTradeProblem] will not compile here until it has a message,
 * which is the point. `BondTradeProblemTest` then checks that the message is actually translated.
 */
@StringRes
fun bondProblemMessage(problem: BondTradeProblem): Int = when (problem) {
    BondTradeProblem.NAME_MISSING -> R.string.add_error_enter_name
    BondTradeProblem.QUANTITY_TOO_SMALL -> R.string.add_error_quantity
    BondTradeProblem.PRICE_NOT_POSITIVE -> R.string.add_error_price
    BondTradeProblem.PRICE_TOO_LARGE -> R.string.add_error_price_large
    BondTradeProblem.NOMINAL_NOT_POSITIVE -> R.string.add_error_nominal
    BondTradeProblem.COUPON_NEGATIVE -> R.string.add_error_coupon_negative
    BondTradeProblem.NOT_SAVED -> R.string.add_error_bond_not_saved
}