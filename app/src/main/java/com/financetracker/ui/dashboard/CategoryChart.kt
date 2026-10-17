package com.financetracker.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.financetracker.R
import com.financetracker.model.CategorySlice
import com.financetracker.model.CategoryTotals
import com.financetracker.model.displaySlices
import com.financetracker.ui.CategoryIcon
import com.financetracker.ui.MoneyAmount
import com.financetracker.ui.TransactionAppearance
import com.financetracker.util.CategoryLabel
import com.financetracker.util.MoneyFormat

/**
 * A donut of income and spending broken down by category, scoped to the dashboard's period.
 *
 * It is the shape a pair of FlowFigures is asking for: the income and expense figures above it
 * say how much moved, and this says what by. So it is driven by the same window ([base] and
 * [CategoryTotals]) and converted on the same rate gate as those figures — a slice's share
 * always sums back to the number the card already showed.
 *
 * The breakdown has no figure when the base currency is unquotable: the card hides itself and
 * leaves the explanation to the net-worth card, which already names the currencies it could not
 * convert rather than guessing.
 */
@Composable
fun CategoryBreakdownCard(
    breakdown: CategoryTotals,
    /**
     * The window this breakdown covers, already resolved to the user's language — the same
     * period the net-worth card's selector controls. Shown on the card because the selector
     * lives on a different card, and without it a reader sees the breakdown change when they
     * window the flows above and has nothing to attribute it to. Blank hides the line, which
     * is what tests that only exercise the slices pass.
     */
    periodLabel: String = "",
    modifier: Modifier = Modifier
) {
    // A base the cache cannot quote is no breakdown at all, not a breakdown of zero — the net
    // worth card next to it already names the missing currency, and inventing one here would be
    // a second message for the same omission.
    if (!breakdown.isBaseQuotable) return

    var showIncome by rememberSaveable { mutableStateOf(false) }
    // Reset when the side changes, so a highlight does not carry across to a new slice set.
    var highlighted by remember(showIncome) { mutableStateOf<Int?>(null) }
    val side = if (showIncome) breakdown.income else breakdown.expense
    val total = if (showIncome) breakdown.totalIncome else breakdown.totalExpense
    val accent = if (showIncome) TransactionAppearance.Income else TransactionAppearance.Expense
    // Canonical label matching what CategoryLabel produces for real "Other" categories —
    // not the localized string, so the synthetic Other can merge with a real one that exists.
    val otherLabel = CategoryLabel.label("other")
    val displayed = remember(side, otherLabel) { displaySlices(side, otherLabel) }
    val colors = remember(displayed) { displayed.mapIndexed { index, _ -> sliceColor(index) } }
    // What the ring's hole shows when a slice is picked: the picked category, its amount and
    // its share, so a tap answers the question it was made to ask. Null puts the side total
    // back, which is the resting state.
    val selectedSlice = highlighted?.let { displayed.getOrNull(it) }

    // A single summary the ring speaks for itself on TalkBack; the centre amount is folded into
    // it, and the row-by-row legend below is the control surface a finger lands on.
    val sideLabelText = stringResource(
        if (showIncome) R.string.dash_net_worth_income else R.string.dash_net_worth_expense
    )
    val contentDescription = remember(displayed, sideLabelText, breakdown) {
        buildString {
            append(sideLabelText)
            append(". Total ")
            append(MoneyFormat.format(total, breakdown.base))
            if (displayed.isEmpty()) {
                append(". Nothing to break down.")
            } else {
                append(". Categories: ")
                displayed.forEach { append("${it.label}, ${it.percent.toInt()} percent; ") }
            }
            if (breakdown.unquoted.isNotEmpty()) {
                append("Some currencies could not be converted. ")
            }
        }
    }

    Card(modifier = modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.dash_categories_title),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (periodLabel.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.dash_categories_period, periodLabel),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !showIncome,
                    onClick = { showIncome = false },
                    label = { Text(stringResource(R.string.dash_net_worth_expense)) }
                )
                FilterChip(
                    selected = showIncome,
                    onClick = { showIncome = true },
                    label = { Text(stringResource(R.string.dash_net_worth_income)) }
                )
            }
            Spacer(Modifier.height(12.dp))

            if (displayed.isEmpty()) {
                Text(
                    text = stringResource(
                        if (showIncome) R.string.dash_categories_empty_income
                        else R.string.dash_categories_empty_expense
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Card
            }

            CategoryDonutChart(
                slices = displayed,
                colors = colors,
                total = total,
                baseCurrency = breakdown.base,
                accent = accent,
                selectedIndex = highlighted,
                selectedSlice = selectedSlice,
                contentDescription = contentDescription,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(176.dp)
            )
            Spacer(Modifier.height(12.dp))
            CategoryLegend(
                slices = displayed,
                colors = colors,
                baseCurrency = breakdown.base,
                selectedIndex = highlighted,
                onSelect = { highlighted = it },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** A palette of distinct hues; colour is only an aid, the legend carries the meaning. */
private val CATEGORY_COLORS = listOf(
    Color(0xFFE57373), Color(0xFFF06292), Color(0xFFBA68C8), Color(0xFF9575CD),
    Color(0xFF7986CB), Color(0xFF64B5F6), Color(0xFF4DD0E1), Color(0xFF4DB6AC),
    Color(0xFF81C784), Color(0xFFAED581), Color(0xFFFFD54F), Color(0xFFFF8A65)
)

private const val GAP_DEGREES = 1.5f

/**
 * The ring itself: one wedge per slice, a carved hole in the centre for the side total, and the
 * selected slice held at full colour while the rest dim so the eye can follow along.
 *
 * Wedges are drawn filled and the hole is carved with the card's own surface colour, so the ring
 * never depends on a guess about what lies beneath it — it is the surface the card already paints.
 *
 * The hole carries [total] at rest and [selectedSlice] — the tapped category, its amount and its
 * share — once one is picked, so the tap that dims the rest of the ring also names what was left
 * at full colour.
 */
@Composable
fun CategoryDonutChart(
    slices: List<CategorySlice>,
    colors: List<Color>,
    total: Double,
    baseCurrency: String,
    accent: Color,
    selectedIndex: Int?,
    selectedSlice: CategorySlice?,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        val surfaceColor = MaterialTheme.colorScheme.surface
        Canvas(modifier = Modifier.size(160.dp)) {
            val outer = size.minDimension / 2f
            val inner = outer * 0.55f
            val center = Offset(size.width / 2f, size.height / 2f)

            // A lone slice is the whole circle: the inter-slice gap exists to separate wedges,
            // and with nothing to separate it would carve a notch out of a ring that has no
            // seam to show. `sweep` is clamped so a slice rounded down to 0% cannot draw a
            // negative wedge back over its neighbour.
            val gap = if (slices.size > 1) GAP_DEGREES else 0f
            var startAngle = -90f // 12 o'clock, reading clockwise
            slices.forEachIndexed { index, slice ->
                val sweep = (slice.percent.toFloat() / 100f * 360f - gap).coerceAtLeast(0f)
                val color = if (selectedIndex == null || index == selectedIndex) {
                    colors[index]
                } else {
                    colors[index].copy(alpha = 0.25f)
                }
                drawArc(
                    color = color,
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = true
                )
                startAngle += sweep + gap
            }
            // A disc the colour of the card surface hides the inner part of every wedge and
            // leaves the ring, with the side total sitting in the hole.
            drawCircle(
                color = surfaceColor,
                radius = inner,
                center = center
            )
        }
        if (selectedSlice != null) {
            SelectedSliceLabel(
                slice = selectedSlice,
                baseCurrency = baseCurrency,
                accent = accent
            )
        } else {
            MoneyAmount(
                amount = total,
                currencyCode = baseCurrency,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = accent
            )
        }
    }
}

/**
 * What the ring's hole says once a slice is picked: the category, the amount, and the share.
 *
 * The category is width-limited and ellipsised so it sits inside the hole, and is resolved
 * through the same [resolveCategoryLabel] the legend uses so the two cannot disagree. The amount
 * is deliberately not width-limited: a constrained amount wraps at the space before the currency
 * symbol, and a sum split across two lines inside a ring reads as two numbers. Left to its own
 * width it stays one line — the ring it may cross is a price worth paying for a figure that is
 * unmistakably a single figure.
 */
@Composable
private fun SelectedSliceLabel(
    slice: CategorySlice,
    baseCurrency: String,
    accent: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = resolveCategoryLabel(slice.label),
            modifier = Modifier.widthIn(max = 104.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        MoneyAmount(
            amount = slice.amount,
            currencyCode = baseCurrency,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = accent
        )
        Text(
            text = "${slice.percent.toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * The control surface for the chart: one tappable row per slice, carrying the same colour, the
 * category icon and how the two figures are read.
 *
 * Tapping a row highlights the matching slice and dims the rest; tapping the highlighted row
 * clears the highlight again, so a reader who has found what they came for can put the chart back
 * to its full state with a second touch.
 */
@Composable
fun CategoryLegend(
    slices: List<CategorySlice>,
    colors: List<Color>,
    baseCurrency: String,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        slices.forEachIndexed { index, slice ->
            val selected = index == selectedIndex
            val label = resolveCategoryLabel(slice.label)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    // A soft container tint marks the picked row, so the legend and the ring
                    // agree about what is selected even when the ring is off-screen or small.
                    .clip(MaterialTheme.shapes.small)
                    .background(
                        if (selected) MaterialTheme.colorScheme.secondaryContainer
                        else Color.Transparent
                    )
                    .clickable { onSelect(if (selected) null else index) }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(RoundedCornerShape(50))
                        .background(colors[index])
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = CategoryIcon.forLabel(slice.label),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                MoneyAmount(
                    amount = slice.amount,
                    currencyCode = baseCurrency,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${slice.percent.toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

/** Resolves a canonical category label to the user's language, the way a row does. */
@Composable
private fun resolveCategoryLabel(label: String): String {
    val res = CategoryLabel.resource(label)
    return if (res != 0) stringResource(res) else label
}

private fun sliceColor(index: Int): Color = CATEGORY_COLORS[index % CATEGORY_COLORS.size]


