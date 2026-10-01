package com.financetracker.ui.transaction

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.financetracker.R
import com.financetracker.model.BankRef
import com.financetracker.model.Bond
import com.financetracker.model.BondMath
import com.financetracker.model.BondTradeSide
import com.financetracker.model.RECORDABLE_CURRENCIES
import com.financetracker.ui.MoneyAmount
import com.financetracker.ui.component.RequiredLabel
import com.financetracker.ui.component.SingleChoiceDropdown
import com.financetracker.util.MoneyFormat
import java.time.LocalDate
import java.util.Locale

/** What the bond form collected, validated. */
internal data class BondForm(
    val bond: Bond,
    val side: BondTradeSide,
    val quantity: Int,
    val price: Double,
    val accrued: Double,
    val commission: Double,
    val date: LocalDate,
    val bank: BankRef?,
    val settlementCurrency: String,
    val settlementAmount: Double?
)

/** Epoch millis to a date, for filling the picker from a stored bond. */
private fun instantToLocalDate(millis: Long): LocalDate =
    java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun BondEntryForm(
    banks: List<BankRef>,
    noBankLabel: String,
    saving: Boolean,
    onLookup: (String) -> Unit,
    knownBond: Bond?,
    onSave: (BondForm) -> Unit
) {
    var side by remember { mutableStateOf(BondTradeSide.BUY) }
    var isin by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var nominal by remember { mutableStateOf("1000") }
    var bondCurrency by remember { mutableStateOf("UAH") }
    var coupon by remember { mutableStateOf("") }
    var couponPeriod by remember { mutableStateOf("") }
    var maturity by remember { mutableStateOf<LocalDate?>(null) }
    var quantity by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var accrued by remember { mutableStateOf("") }
    var commission by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var bank by remember { mutableStateOf<BankRef?>(null) }
    var currency by remember { mutableStateOf("UAH") }
    var settlementAmount by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val errorEnterName = stringResource(R.string.add_error_enter_name)
    val errorAccrued = stringResource(R.string.add_error_accrued_negative)
    val errorCommission = stringResource(R.string.add_error_commission_negative)
    val errorCharged = stringResource(R.string.add_error_charged_negative)

    // A found instrument fills the terms in, but every field stays editable. A bond already
    // held has a maturity date and coupon the user would not want to retype, and one that is
    // not found has to be typeable at all.
    LaunchedEffect(knownBond) {
        knownBond?.let {
            isin = it.isin
            name = it.name
            nominal = it.nominal.toPlainString()
            bondCurrency = it.nominalCurrency
            it.couponPercent?.let { coupon = it.toPlainString() }
            it.couponPeriodMonths?.let { months -> couponPeriod = months.toString() }
            it.maturityDate?.let { millis -> maturity = instantToLocalDate(millis) }
        }
    }
    LaunchedEffect(isin) { onLookup(isin) }

    val quantityValue = quantity.toIntOrNull() ?: 0
    val priceValue = price.replace(',', '.').toDoubleOrNull() ?: 0.0
    val nominalValue = nominal.replace(',', '.').toDoubleOrNull() ?: 0.0
    val accruedValue = accrued.replace(',', '.').toDoubleOrNull() ?: 0.0
    val commissionValue = commission.replace(',', '.').toDoubleOrNull() ?: 0.0
    val couponValue = coupon.replace(',', '.').toDoubleOrNull()
    val isRedemption = side == BondTradeSide.REDEMPTION

    // A redemption is entered as the whole credit the bank put on the card, because that is the
    // figure a statement prints and dividing it by the quantity is work the app can do instead.
    // Money per bond is what gets stored, what the note reads back and what the position is
    // folded from, so the division happens once, here, and nothing downstream knows a total was
    // ever involved.
    val perBondPrice = if (isRedemption && quantityValue > 0) priceValue / quantityValue else priceValue
    // Neither is paid on a redemption — the issuer returns the nominal and nothing else — so
    // they are fixed at zero rather than read from fields a redemption does not show. The coupon
    // that may arrive in the same credit is a separate income row, not part of this one.
    val effectiveAccrued = if (isRedemption) 0.0 else accruedValue
    val effectiveCommission = if (isRedemption) 0.0 else commissionValue

    // A per-bond price cannot be worked out before the quantity is known, so the price check
    // waits for it instead of comparing a whole credit against one bond's nominal. The quantity
    // field is already saying what is missing.
    val priceProblem = if (isRedemption && quantityValue <= 0) {
        null
    } else {
        BondMath.validatePrice(perBondPrice, nominalValue.takeIf { it > 0.0 })
    }

    // A hint, never a value the form fills in — blank until the quantity is known, because
    // "0.00 ₴" would read as a figure rather than as a missing one.
    val pricePlaceholder = when {
        !isRedemption -> MoneyFormat.format(995.0, bondCurrency)
        quantityValue > 0 -> MoneyFormat.format(nominalValue * quantityValue, bondCurrency)
        else -> ""
    }

    // The live total, so the figure the user is about to commit is on screen while they
    // type it rather than only after. This is the number they will check against the broker.
    // Money in the bond's currency: price is typed as money since v7, so no nominal or
    // percentage is involved. Refused for a price the form itself rejects: totalling an
    // input it calls impossible would be the very mistake the error is warning about.
    val marketTotal = if (priceProblem == null && quantityValue > 0 && priceValue > 0.0) {
        quantityValue * perBondPrice + quantityValue * effectiveAccrued + effectiveCommission
    } else {
        null
    }
    val foreign = !currency.equals(bondCurrency, ignoreCase = true)
    val enteredAmount = settlementAmount.replace(',', '.').toDoubleOrNull()
    val settlement = marketTotal?.let { BondMath.settlement(it, bondCurrency, currency, enteredAmount) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SingleChoiceSegmentedButtonRow {
            BondTradeSide.entries.forEach { entry ->
                SegmentedButton(
                    selected = side == entry,
                    onClick = { side = entry },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(sideWord(entry), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        OutlinedTextField(
            value = isin,
            onValueChange = { isin = it.uppercase() },
            label = { Text(stringResource(R.string.add_isin)) },
            placeholder = { Text(stringResource(R.string.add_isin_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // Says so explicitly, because a silent hit and a silent miss look identical and the
        // user needs to know which one they got before typing the terms they may not have.
        if (isin.isNotBlank() && knownBond == null) {
            Text(
                text = stringResource(R.string.add_unknown_bond_hint),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        // Terms are what a purchase adds to an instrument the app has not seen. A redemption pays
        // back something already held, whose terms are already stored — and the repository re-reads
        // an existing bond rather than updating it, so editing them here would silently do
        // nothing. They stay when the lookup found nothing, because then there is no instrument at
        // all and the name and nominal are the only things that can be filled in.
        if (!isRedemption || knownBond == null) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { RequiredLabel(stringResource(R.string.add_name)) },
                placeholder = { Text(stringResource(R.string.add_name_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = nominal,
                    onValueChange = { nominal = it },
                    // Prefilled with 1000, the everyday nominal, and kept editable because a
                    // bond's face value determines the coupon and the price ceiling.
                    label = { RequiredLabel(stringResource(R.string.add_nominal)) },
                    placeholder = { Text(stringResource(R.string.add_nominal_placeholder)) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                SingleChoiceDropdown(
                    label = stringResource(R.string.add_bond_currency),
                    options = RECORDABLE_CURRENCIES.map { it to it },
                    selected = bondCurrency,
                    onSelect = {
                        // Settlement follows the bond unless the user had picked something of
                        // their own: a USD bond opens settled in USD, and a bond currency change
                        // does not silently re-file a settlement the user deliberately set.
                        if (currency == bondCurrency) currency = it
                        bondCurrency = it
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = coupon,
                    onValueChange = { coupon = it },
                    label = { Text(stringResource(R.string.add_coupon_percent)) },
                    placeholder = { Text(stringResource(R.string.add_coupon_placeholder)) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                OutlinedTextField(
                    value = couponPeriod,
                    onValueChange = { couponPeriod = it.filter(Char::isDigit) },
                    // Months rather than a frequency, because that is what a coupon calendar is
                    // written in and it is the only figure the period-income calculation needs.
                    label = { Text(stringResource(R.string.add_coupon_period)) },
                    placeholder = { Text(stringResource(R.string.add_period_placeholder)) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }

            MaturityField(maturity = maturity, onChange = { maturity = it })
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = quantity,
                onValueChange = { quantity = it.filter(Char::isDigit) },
                label = { RequiredLabel(stringResource(R.string.add_quantity)) },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
            OutlinedTextField(
                value = price,
                onValueChange = { price = it },
                // Money per bond in the bond's currency: what the broker's confirmation
                // prints. Not a percentage — the whole point of the v7 change is that the
                // number typed is the number spent, so 1020 means 1020.00, not 1020%.
                //
                // A redemption is the one exception, and holds the whole credit instead: that
                // is the figure on the bank statement, and the per-bond price it divides into
                // is shown back underneath rather than asked for.
                label = {
                    RequiredLabel(
                        stringResource(
                            if (isRedemption) R.string.add_redeemed_total else R.string.add_price_per_bond
                        )
                    )
                },
                placeholder = { Text(pricePlaceholder) },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true
            )
        }

        // The price's reading as the percentage-of-nominal the exchange quotes, kept live
        // under the field it belongs to. This is the one figure a misreading of a bank
        // screen mangles, and leaving it to the Save error would defer a correction the
        // form could already be showing. An impossible input is reported here instead, in
        // the same slot, so the field never silently totals it.
        when {
            priceProblem != null ->
                Text(
                    text = priceProblem,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            // The one figure a redemption's total hides, read back while it is typed. Money per
            // bond is what gets stored and what the position is folded from, so a mistyped total
            // is visible here instead of only in the holding afterwards.
            isRedemption && quantityValue > 0 && priceValue > 0.0 ->
                Text(
                    text = stringResource(
                        R.string.add_redeemed_per_bond,
                        MoneyFormat.text(perBondPrice, bondCurrency).plain
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            isRedemption -> Unit
            nominalValue > 0.0 && priceValue > 0.0 ->
                Text(
                    text = stringResource(
                        R.string.add_percent_of_nominal,
                        BondMath.percentOfNominal(priceValue, nominalValue),
                        MoneyFormat.text(nominalValue, bondCurrency).plain
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            nominalValue > 0.0 ->
                Text(
                    text = stringResource(
                        R.string.add_percent_of_named_nominal,
                        MoneyFormat.text(nominalValue, bondCurrency).plain
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            else ->
                Text(text = stringResource(R.string.add_percent_of_nominal_empty), style = MaterialTheme.typography.bodySmall)
        }

        // A matured ОВДП credits the nominal and the last coupon in one go — 20 000 + 1 585 on
        // the broker's schedule. The whole credit lands here as a transfer, because the
        // principal is the user's own money coming back, but the coupon inside it is earned
        // income and will not appear in the totals unless it is recorded as such. Said here
        // because a user who types the credit the bank printed has no other way to know that.
        if (isRedemption) {
            Text(
                text = stringResource(R.string.add_redeemed_coupon_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // A redemption pays neither: the issuer returns the nominal and nothing else. Hidden
        // rather than shown at zero, because a required-looking field reading 0 is a value the
        // user reads as one they forgot to fill in.
        if (!isRedemption) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = accrued,
                    onValueChange = { accrued = it },
                    // Per bond, not per trade: brokers quote it this way, and the field says so
                    // because the difference is a multiple of the lot size on the total.
                    label = { Text(stringResource(R.string.add_accrued_per_bond)) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                OutlinedTextField(
                    value = commission,
                    onValueChange = { commission = it },
                    label = { Text(stringResource(R.string.add_commission)) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SingleChoiceDropdown(
                label = noBankLabel,
                options = listOf(null to noBankLabel) +
                    banks.map { ref -> ref as BankRef? to ref.label },
                selected = bank,
                onSelect = { bank = it },
                modifier = Modifier.weight(1f)
            )
            DateField(date = date, onChange = { date = it })
        }

        SingleChoiceDropdown(
            label = stringResource(R.string.add_settlement),
                options = RECORDABLE_CURRENCIES.map { it to it },
            selected = currency,
            onSelect = { currency = it },
            modifier = Modifier.fillMaxWidth()
        )

        if (foreign) {
            // Asked for rather than derived. There is no rate feed in this app, so any
            // converted number would be a guess, and a wrong cash figure is worse than one
            // the user typed themselves.
            OutlinedTextField(
                value = settlementAmount,
                onValueChange = { settlementAmount = it },
                label = { Text(stringResource(R.string.add_amount_charged, currency)) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true
            )
        }

        if (settlement != null) {
            Column {
                MoneyAmount(
                    amount = settlement.amount,
                    currencyCode = currency,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                settlement.impliedRate?.let {
                    Text(
                        // Shown so a mistyped amount is visible before it is saved rather than
                        // after, at which point it is already in the balance. The bond is
                        // priced in its own currency, so the rate reads as bond currency per
                        // settlement currency.
                        text = stringResource(R.string.add_approx_rate, it, bondCurrency, currency),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (error != null) {
            Text(text = error!!, color = MaterialTheme.colorScheme.error)
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                val problem = validate(
                    isin = isin,
                    name = name,
                    nominal = nominalValue,
                    coupon = couponValue,
                    quantity = quantityValue,
                    price = perBondPrice,
                    accrued = effectiveAccrued,
                    commission = effectiveCommission,
                    settlementAmount = if (foreign) enteredAmount else null,
                    errorEnterName = errorEnterName,
                    errorAccrued = errorAccrued,
                    errorCommission = errorCommission,
                    errorCharged = errorCharged
                )
                if (problem != null) {
                    error = problem
                    return@Button
                }
                error = null
                val normalisedIsin = BondMath.normaliseIsin(isin).ifBlank {
                    BondMath.normaliseIsin(name)
                }
                onSave(
                    BondForm(
                        bond = Bond(
                            isin = normalisedIsin,
                            name = name.trim(),
                            nominal = nominalValue,
                            nominalCurrency = bondCurrency,
                            couponPercent = couponValue,
                            couponPeriodMonths = couponPeriod.toIntOrNull(),
                            maturityDate = maturity?.let {
                                it.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                            }
                        ),
                        side = side,
                        quantity = quantityValue,
                        // The effective values, not the raw fields: a redemption's total is
                        // divided by its quantity once, and the two figures a redemption does
                        // not pay stay zero whatever a hidden field happens to hold.
                        price = perBondPrice,
                        accrued = effectiveAccrued,
                        commission = effectiveCommission,
                        date = date,
                        bank = bank,
                        settlementCurrency = currency,
                        settlementAmount = if (foreign) enteredAmount else null
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
            // Disabled while the trade is being written, so a second tap cannot open a second
            // position the user did not mean to open.
            enabled = !saving
        ) {
            Text(
                when {
                    saving -> stringResource(R.string.add_saving)
                    else -> sideWord(side)
                }
            )
        }
    }
}

/**
 * Checks the form before anything is written, so the user is told what is wrong while the
 * fields are still on screen.
 *
 * Null means usable. The order is deliberate: the instrument name first, because nothing else
 * can be checked without a nominal to check it against.
 */
private fun validate(
    isin: String,
    name: String,
    nominal: Double,
    coupon: Double?,
    quantity: Int,
    price: Double,
    accrued: Double,
    commission: Double,
    settlementAmount: Double?,
    errorEnterName: String,
    errorAccrued: String,
    errorCommission: String,
    errorCharged: String
): String? = when {
    name.isBlank() -> errorEnterName
    BondMath.validateNominal(nominal) != null -> BondMath.validateNominal(nominal)!!
    BondMath.validateCouponPercent(coupon) != null -> BondMath.validateCouponPercent(coupon)!!
    BondMath.validateQuantity(quantity) != null -> BondMath.validateQuantity(quantity)!!
    BondMath.validatePrice(price, nominal.takeIf { it > 0.0 }) != null ->
        BondMath.validatePrice(price, nominal.takeIf { it > 0.0 })!!
    accrued < 0.0 -> errorAccrued
    commission < 0.0 -> errorCommission
    // Optional, so an empty box is allowed and a typed zero is allowed; only a negative
    // amount is refused, which can only be a slip.
    settlementAmount != null && settlementAmount < 0.0 -> errorCharged
    else -> null
}

/**
 * The maturity date, optional.
 *
 * A disabled empty box would read as a value the user had forgotten to fill in, and the date
 * is genuinely optional: someone recording a quick trade often knows the ISIN and the fill
 * price and nothing else. So the button opens the picker unset, and the trailing cross clears
 * it back to nothing.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun MaturityField(maturity: LocalDate?, onChange: (LocalDate?) -> Unit, modifier: Modifier = Modifier) {
    var showPicker by remember { mutableStateOf(false) }
    val pattern = remember { java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault()) }
    val maturityLabel = stringResource(R.string.add_maturity)

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.weight(1f)) {
            Text(
                text = maturity?.format(pattern) ?: maturityLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (maturity != null) {
            IconButton(onClick = { onChange(null) }) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.add_clear_maturity))
            }
        }
    }

    if (showPicker) {
        val state = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = (maturity ?: LocalDate.now().plusYears(1))
                .atStartOfDay(java.time.ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        )
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        onChange(
                            java.time.Instant.ofEpochMilli(millis)
                                .atZone(java.time.ZoneOffset.UTC)
                                .toLocalDate()
                        )
                    }
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } }
        ) {
            androidx.compose.material3.DatePicker(state = state)
        }
    }
}

private fun Double.toPlainString(): String =
    if (this % 1.0 == 0.0) toInt().toString() else toString()

/** What a side is called, on the switch at the top and on the button that saves it. */
@Composable
private fun sideWord(side: BondTradeSide): String = when (side) {
    BondTradeSide.BUY -> stringResource(R.string.add_buy)
    BondTradeSide.SELL -> stringResource(R.string.add_sell)
    BondTradeSide.REDEMPTION -> stringResource(R.string.add_redeem)
}
