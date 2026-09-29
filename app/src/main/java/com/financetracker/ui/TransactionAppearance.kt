package com.financetracker.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.financetracker.model.Transaction

/**
 * How a transaction is dressed on screen: accent colour, row tint, icon and sign.
 *
 * One place, because this decision is made in four places — the list row, the dashboard row,
 * the detail header and the list summary. Left as a chain of `if (isIncome()) … else …` it
 * grew a third branch in every copy the moment transfers existed, and the copies drifted
 * apart: the dashboard and the detail screen had already chosen different tints from the list.
 */
object TransactionAppearance {

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

    val IncomeTint = Color(0xFFE8F5E9)
    val ExpenseTint = Color(0xFFFCE4EC)
    val TransferTint = Color(0xFFE7ECEF)

    fun accent(transaction: Transaction): Color = when {
        transaction.isIncome() -> Income
        transaction.isExpense() -> Expense
        else -> Transfer
    }

    fun tint(transaction: Transaction): Color = when {
        transaction.isIncome() -> IncomeTint
        transaction.isExpense() -> ExpenseTint
        else -> TransferTint
    }

    /**
     * A transfer gets its own icon rather than an arrow.
     *
     * The arrows say "earned" and "spent", which is precisely the claim a transfer declines
     * to make; the direction is carried by the sign instead.
     */
    fun icon(transaction: Transaction): ImageVector = when {
        transaction.isIncome() -> Icons.Default.ArrowUpward
        transaction.isExpense() -> Icons.Default.ArrowDownward
        else -> Icons.Default.SwapVert
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
