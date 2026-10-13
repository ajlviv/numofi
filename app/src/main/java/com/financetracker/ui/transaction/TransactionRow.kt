package com.financetracker.ui.transaction

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.financetracker.model.BankRef
import com.financetracker.model.Transaction
import com.financetracker.ui.CategoryIcon
import com.financetracker.ui.MoneyAmount
import com.financetracker.ui.TransactionAppearance
import com.financetracker.util.CategoryLabel
import com.financetracker.util.MerchantName
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The one transaction row, used by the list and by the dashboard's recent rows.
 *
 * It exists as a single composable because the two lists had drifted — same data, two
 * shapes — and because the reading order is a decision worth making once: **what** the
 * payment was (the cleaned merchant, 16sp Medium, one line), **where the detail is**
 * (`note - account`, one 12sp line ellipsised at the end), and **how much, when**
 * (the amount in its direction colour with the date under it on the right).
 *
 * The title never shows the bank's raw payment purpose: [MerchantName] reduces it to a
 * merchant, and a title with no merchant in it falls back to the category — which is why
 * the icon is the category rather than another copy of the words. The subtitle's merchant
 * segment ([composeRowSubtitle]) is skipped whenever the title already is the merchant,
 * which under these rules is whenever a merchant exists at all.
 *
 * [modifier] is applied *inside* the row's own click, so padding a caller adds is still
 * inside the tap target — the promise a tappable-looking row makes should cover the whole
 * card, padding included.
 *
 * @param iconSize one size for both call sites: the list reads at 48dp, the dashboard's
 * denser recent list at 40dp, and the icon is half the box either way.
 */
@Composable
fun TransactionRow(
    transaction: Transaction,
    bank: BankRef?,
    dateFormat: DateTimeFormatter,
    modifier: Modifier = Modifier,
    iconSize: Dp = 48.dp,
    onClick: () -> Unit
) {
    // A mapped category translates through its cat_* resource; anything else is the
    // user's own typed wording and stays exactly as written.
    val categoryRes = CategoryLabel.resource(transaction.category)
    val categoryText =
        if (categoryRes != 0) stringResource(categoryRes) else CategoryLabel.label(transaction.category)
    val merchant = MerchantName.clean(transaction.title)?.takeIf { MerchantName.isMeaningful(it) }
    val title = merchant ?: categoryText
    val subtitle = composeRowSubtitle(
        title = title,
        note = transaction.note,
        account = transaction.provenance(bank),
        merchant = merchant
    )
    val date = Instant.ofEpochMilli(transaction.timestamp)
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime()
        .format(dateFormat)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(modifier)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(iconSize)
                .clip(MaterialTheme.shapes.small)
                .background(TransactionAppearance.tint(transaction)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = CategoryIcon.icon(transaction),
                contentDescription = null,
                tint = TransactionAppearance.accent(transaction),
                modifier = Modifier.size(iconSize / 2)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.fillMaxHeight()
        ) {
            MoneyAmount(
                amount = transaction.amount,
                currencyCode = transaction.currencyCode,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TransactionAppearance.accent(transaction),
                prefix = TransactionAppearance.signPrefix(transaction)
            )
            // minLines keeps the slot reserved when the date would otherwise be the only
            // thing deciding the height, so a column of rows stays a column of rows.
            Text(
                text = date,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * The row's one secondary line: `[note] - [account] - [merchant]`.
 *
 * Blanks are skipped rather than printed as gaps — a hand-entered row has no account, and
 * a row with no note should not lead with a separator. The merchant is dropped when the
 * title already carries it: the same name twice on one row reads like two different
 * places, and the title has already claimed it.
 */
fun composeRowSubtitle(
    title: String,
    note: String?,
    account: String?,
    merchant: String?
): String = listOfNotNull(
    note?.trim()?.takeIf { it.isNotEmpty() },
    account?.trim()?.takeIf { it.isNotEmpty() },
    merchant?.trim()?.takeIf { it.isNotEmpty() && it != title.trim() }
).joinToString(" - ")
