package com.financetracker.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.financetracker.model.Transaction
import com.financetracker.ui.theme.isDark

/**
 * How a transaction is dressed on screen: accent colour, row tint and sign.
 *
 * The *icon* is deliberately not here: a row's shape is its category, and that mapping
 * lives in [CategoryIcon] — colour says which way the money went, the icon says what it
 * was for.
 *
 * One place, because this decision is made in four places — the list row, the dashboard row,
 * the detail header and the list summary. Left as a chain of `if (isIncome()) … else …` it
 * grew a third branch in every copy the moment transfers existed, and the copies drifted
 * apart: the dashboard and the detail screen had already chosen different tints from the list.
 *
 * Two sets of accents rather than one, because an amount is printed on two very different
 * backgrounds. On a card it sits on the surface (`#FFFFFF`/`#FAFAFA`, or `#1C1C1C`/`#121212`
 * in dark mode); on a chip or an icon box it sits on one of the tints below, which are the
 * same pale colours in both themes. The light values clear AA on white and on the tints, and
 * are the only ones that can: they are near 3:1 on `#1C1C1C`, which is unreadable at 14sp.
 * The dark values clear AAA on both dark surfaces. Splitting the two apart is not a style
 * choice — no single colour clears 4.5:1 on white *and* 3:1 on `#1C1C1C`.
 */
object TransactionAppearance {

    /** For a figure on a light surface, and for an icon or label on any of the tints. */
    val Income = Color(0xFF2E7D32)
    val Expense = Color(0xFFC62828)

    /**
     * Neither green nor red on purpose.
     *
     * A bond purchase is money leaving the account but it is not spending, and colouring it
     * like an expense would put the exact figure the app is trying not to inflate into its
     * most prominent position.
     */
    val Transfer = Color(0xFF546E7A)

    /**
     * The same three directions, lightened for a dark surface.
     *
     * [ExpenseOnDark] is the theme's own dark `error` colour: in the light theme the expense
     * accent and the error colour are already the same red, and keeping the pairing in dark
     * mode is what stops "you spent this" and "something is wrong" from being two different
     * reds on one screen.
     */
    val IncomeOnDark = Color(0xFF66BB6A)
    val ExpenseOnDark = Color(0xFFEF9A9A)
    val TransferOnDark = Color(0xFFB0BEC5)

    val IncomeTint = Color(0xFFE8F5E9)
    val ExpenseTint = Color(0xFFFCE4EC)
    val TransferTint = Color(0xFFE7ECEF)

    /**
     * The accent for something drawn **on a tint** — an icon in its box, the type badge's
     * label. Always the light value, in both themes, because the tints never darken: they are
     * the one background in the app whose colour does not follow the theme.
     */
    fun accent(transaction: Transaction): Color = when {
        transaction.isIncome() -> Income
        transaction.isExpense() -> Expense
        else -> Transfer
    }

    /**
     * The accent for an amount printed **on the card surface**, chosen from that surface.
     *
     * [surface] is passed rather than read here so the choice is testable without a
     * composition; the [accentOn] overloads below are the one-line convenience the screens
     * call. Anything sitting on a tint wants [accent] instead.
     */
    fun accentOn(surface: Color, transaction: Transaction): Color =
        if (surface.isDark()) accentOnDark(transaction) else accent(transaction)

    /** The same, for the screens that carry a direction rather than a transaction. */
    fun accentOn(surface: Color, outgoing: Boolean): Color =
        if (surface.isDark()) {
            if (outgoing) ExpenseOnDark else IncomeOnDark
        } else {
            if (outgoing) Expense else Income
        }

    /** The dark-surface half of [accentOn], exposed so the ratios can be asserted directly. */
    fun accentOnDark(transaction: Transaction): Color = when {
        transaction.isIncome() -> IncomeOnDark
        transaction.isExpense() -> ExpenseOnDark
        else -> TransferOnDark
    }

    /**
     * [accentOn] against the scheme's own surface.
     *
     * Every caller paints on a card, so the card's surface is the background — except the
     * transaction list, whose rows sit on `background`. That does not split the answer:
     * `background` and `surface` are one light pair and one dark pair, and [isDark] is a
     * threshold rather than a match, so both members of a pair choose the same variant. This
     * is the same reasoning [MoneyAmount] documents for its symbol colour.
     */
    @Composable
    fun accentOn(transaction: Transaction): Color =
        accentOn(MaterialTheme.colorScheme.surface, transaction)

    /** [accentOn] against the scheme's own surface, for a bare direction. */
    @Composable
    fun accentOn(outgoing: Boolean): Color =
        accentOn(MaterialTheme.colorScheme.surface, outgoing)

    fun tint(transaction: Transaction): Color = when {
        transaction.isIncome() -> IncomeTint
        transaction.isExpense() -> ExpenseTint
        else -> TransferTint
    }

    /**
     * The sign, taken from the cash direction rather than the type.
     *
     * A transfer out is a minus and a transfer in a plus, so this reads the same way for all
     * three types. A transfer with no recorded direction gets no sign rather than a guessed
     * one.
     */
    fun signPrefix(transaction: Transaction): String = when {
        transaction.isCashInflow() -> "+"
        transaction.isCashOutflow() -> "-"
        else -> ""
    }
}
