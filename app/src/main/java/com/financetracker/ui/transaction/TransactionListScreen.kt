package com.financetracker.ui.transaction

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.financetracker.model.BankCode
import com.financetracker.model.Transaction
import com.financetracker.model.TransactionType
import com.financetracker.util.CategoryLabel
import com.financetracker.util.MoneyFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TransactionType.label: String
    get() = if (this == TransactionType.INCOME) "Income" else "Expense"

@Composable
fun TransactionListScreen(
    listState: LazyListState = rememberLazyListState(),
    onTransactionClick: (Transaction) -> Unit = {},
    viewModel: TransactionListViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val cardLabels by viewModel.cardLabels.collectAsStateWithLifecycle()
    val dateFormat = DateTimeFormatter.ofPattern("MMM dd, yyyy")

    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = search,
            onValueChange = viewModel::onSearchChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true,
            label = { Text("Search") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (search.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onSearchChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear search")
                    }
                }
            }
        )

        FilterRow {
            BankCode.CHOICES.forEach { code ->
                FilterChip(
                    selected = filter.normalizedBank == code,
                    onClick = {
                        viewModel.onBankSelected(if (filter.normalizedBank == code) null else code)
                    },
                    label = { Text(BankCode.label(code)) }
                )
            }
            TransactionType.entries.forEach { type ->
                FilterChip(
                    selected = filter.type == type,
                    onClick = { viewModel.onTypeSelected(if (filter.type == type) null else type) },
                    label = { Text(type.label) }
                )
            }
        }

        // Offered whenever there is anything to offer: the card filter works on its own, and
        // the list is already narrowed to the selected bank when one is picked.
        if (cardLabels.isNotEmpty()) {
            FilterRow {
                cardLabels.forEach { label ->
                    FilterChip(
                        selected = filter.cardLabel == label,
                        onClick = {
                            viewModel.onCardSelected(if (filter.cardLabel == label) null else label)
                        },
                        label = { Text(label) }
                    )
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(16.dp)
        ) {
            if (transactions.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillParentMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        EmptyState(hasFilters = search.isNotBlank() || filter.bankCode != null ||
                            filter.cardLabel != null || filter.type != null)
                    }
                }
            }
            items(transactions) { transaction ->
                TransactionListItem(
                    transaction = transaction,
                    dateFormat = dateFormat,
                    onClick = { onTransactionClick(transaction) }
                )
            }
        }
    }
}

@Composable
private fun FilterRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
private fun EmptyState(hasFilters: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Default.AccountBalanceWallet,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = Color.Gray
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            // An empty result under a filter is a different situation from having no
            // transactions, and telling the user to "add your first transaction" while a
            // search is active would be nonsense.
            text = if (hasFilters) "Nothing matches" else "No transactions yet",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.Gray
        )
        Text(
            text = if (hasFilters) "Try clearing the search or filters" else "Tap + to add your first transaction",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
    }
}

@Composable
private fun TransactionListItem(
    transaction: Transaction,
    dateFormat: DateTimeFormatter,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            // IntrinsicSize.Min lets the trailing column stretch to the row height, and
            // the note below always occupies a line, so rows with a comment are not
            // taller than rows without one.
            modifier = Modifier.padding(16.dp).height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon representing transaction type
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(
                        if (transaction.isIncome()) Color(0xFFE8F5E9) else Color(0xFFFCE4EC)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (transaction.isIncome()) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                    contentDescription = null,
                    tint = if (transaction.isIncome()) Color(0xFF2E7D32) else Color(0xFFC62828),
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = transaction.title,
                    // Two lines at a smaller size. Imported descriptions run long, and a
                    // single ellipsised line hid exactly the part that identifies the
                    // payment, which is the part the user is scanning the list for.
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${CategoryLabel.label(transaction.category)} • ${Instant.ofEpochMilli(transaction.timestamp).atZone(ZoneId.systemDefault()).toLocalDateTime().format(dateFormat)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                transaction.provenance()?.let { provenance ->
                    Text(
                        text = provenance,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.fillMaxHeight()
            ) {
                Text(
                    text = "${if (transaction.isIncome()) "+" else "-"}${MoneyFormat.format(transaction.amount, transaction.currencyCode)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (transaction.isIncome()) Color(0xFF2E7D32) else Color(0xFFC62828)
                )
                // minLines keeps the slot reserved when there is no note, so every row
                // in the list has the same height.
                Text(
                    text = transaction.note.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    minLines = 1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}