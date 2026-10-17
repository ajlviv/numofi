package com.financetracker.ui.bond

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.financetracker.R
import com.financetracker.model.Bond
import com.financetracker.model.BondMath
import com.financetracker.model.BondTradeProblem
import com.financetracker.model.RECORDABLE_CURRENCIES
import com.financetracker.ui.component.MaturityField
import com.financetracker.ui.component.RequiredLabel
import com.financetracker.ui.component.SingleChoiceDropdown
import com.financetracker.ui.transaction.bondProblemMessage
import com.financetracker.util.AmountInput
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Corrects the descriptive terms of a bond the user already holds.
 *
 * Only the terms. The position is folded from trades at read time and is not a thing that can
 * be typed into, so there is no quantity, price or bank here. The ISIN is shown but not edited:
 * it is the primary key and what every trade refers to, so a corrected one is a different
 * instrument. And the nominal currency is fixed once any trade exists, because every stored
 * price and НКД is money in it — the field says so instead of silently re-reading the history.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BondEditScreen(
    bond: Bond,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BondEditViewModel = hiltViewModel()
) {
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val currencyLocked by viewModel.currencyLocked.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val snackbarText = message?.let { stringResource(it) }
    LaunchedEffect(message) {
        snackbarText?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(bond.isin) { viewModel.load(bond.isin) }

    // Saved is a one-way event, collected rather than read: this ViewModel outlives the form,
    // and a value merely still true would close the next opening on sight.
    LaunchedEffect(Unit) {
        viewModel.saved.collect { onSaved() }
    }

    // Every way out, including the system back gesture, goes through here, so a save still in
    // flight when the user leaves cannot close the next opening of the form.
    val dismiss = {
        viewModel.onFormDismissed()
        onCancel()
    }
    BackHandler(onBack = dismiss)

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bond_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = dismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            item {
                BondEditForm(
                    bond = bond,
                    currencyLocked = currencyLocked,
                    saving = saving,
                    onSave = viewModel::save
                )
            }
        }
    }
}

/** Epoch millis to a date, for filling the picker from a stored bond. */
private fun instantToLocalDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

private fun Double.toPlainString(): String =
    if (this % 1.0 == 0.0) toInt().toString() else toString()

@Composable
private fun BondEditForm(
    bond: Bond,
    currencyLocked: Boolean,
    saving: Boolean,
    onSave: (Bond) -> Unit
) {
    var name by remember(bond.isin) { mutableStateOf(bond.name) }
    var nominal by remember(bond.isin) { mutableStateOf(bond.nominal.toPlainString()) }
    var bondCurrency by remember(bond.isin) { mutableStateOf(bond.nominalCurrency) }
    var coupon by remember(bond.isin) { mutableStateOf(bond.couponPercent?.toPlainString() ?: "") }
    var couponPeriod by remember(bond.isin) { mutableStateOf(bond.couponPeriodMonths?.toString() ?: "") }
    var maturity by remember(bond.isin) { mutableStateOf(bond.maturityDate?.let(::instantToLocalDate)) }
    var error by remember { mutableStateOf<String?>(null) }

    // Resolved here, where the resources live, rather than in the model, which stays free of
    // them. The same table the trade form uses, so the two cannot drift apart.
    val problemText = BondTradeProblem.entries.associateWith { stringResource(bondProblemMessage(it)) }
    val errorEnterName = stringResource(R.string.add_error_enter_name)
    val errorCouponUnreadable = stringResource(R.string.add_error_coupon_unreadable)

    val nominalValue = AmountInput.parse(nominal) ?: 0.0
    val couponValue = AmountInput.parse(coupon)
    val couponReadable = coupon.isBlank() || AmountInput.parse(coupon) != null

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Shown, not edited: read-only because the ISIN is the primary key and what a trade
        // refers to. A corrected one is a different instrument, recorded as a new bond.
        OutlinedTextField(
            value = bond.isin,
            onValueChange = {},
            label = { Text(stringResource(R.string.add_isin)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = false,
            singleLine = true
        )

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
                label = { RequiredLabel(stringResource(R.string.add_nominal)) },
                placeholder = { Text(stringResource(R.string.add_nominal_placeholder)) },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true
            )
            if (currencyLocked) {
                // Static rather than a disabled dropdown: there is no choice to offer, and a
                // menu that opens onto one option is a lie about what the user can do.
                OutlinedTextField(
                    value = bondCurrency,
                    onValueChange = {},
                    label = { Text(stringResource(R.string.add_bond_currency)) },
                    modifier = Modifier.weight(1f),
                    enabled = false,
                    singleLine = true
                )
            } else {
                SingleChoiceDropdown(
                    label = stringResource(R.string.add_bond_currency),
                    options = RECORDABLE_CURRENCIES.map { it to it },
                    selected = bondCurrency,
                    onSelect = { bondCurrency = it },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (currencyLocked) {
            Text(
                text = stringResource(R.string.bond_edit_currency_locked, bondCurrency),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                label = { Text(stringResource(R.string.add_coupon_period)) },
                placeholder = { Text(stringResource(R.string.add_period_placeholder)) },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
        }

        MaturityField(maturity = maturity, onChange = { maturity = it })

        if (error != null) {
            Text(text = error!!, color = MaterialTheme.colorScheme.error)
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                val problem = validate(
                    name = name,
                    nominal = nominalValue,
                    coupon = couponValue,
                    couponReadable = couponReadable,
                    messages = problemText,
                    errorEnterName = errorEnterName,
                    errorCouponUnreadable = errorCouponUnreadable
                )
                if (problem != null) {
                    error = problem
                    return@Button
                }
                error = null
                onSave(
                    Bond(
                        // The stored ISIN, not the field: the field cannot be typed into, and
                        // this is the key the write is addressed to.
                        isin = bond.isin,
                        name = name.trim(),
                        nominal = nominalValue,
                        nominalCurrency = bondCurrency,
                        couponPercent = couponValue,
                        couponPeriodMonths = couponPeriod.toIntOrNull(),
                        maturityDate = maturity?.let {
                            it.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        }
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !saving
        ) {
            Text(if (saving) stringResource(R.string.add_saving) else stringResource(R.string.save))
        }
    }
}

/**
 * Checks the form before anything is written, so the user is told what is wrong while the fields
 * are still on screen. Null means usable; the instrument name comes first, because nothing else
 * can be checked against a nominal that is not yet there.
 */
private fun validate(
    name: String,
    nominal: Double,
    coupon: Double?,
    couponReadable: Boolean,
    messages: Map<BondTradeProblem, String>,
    errorEnterName: String,
    errorCouponUnreadable: String
): String? = when {
    name.isBlank() -> errorEnterName
    BondMath.validateNominal(nominal) != null -> messages.sentenceFor(BondMath.validateNominal(nominal))
    // Unreadable before negative: an unreadable coupon has already become null by the time it
    // gets here, and a null coupon is a legitimate "none recorded" rather than an error — so
    // coercing it to that would silently drop a figure the user did type.
    !couponReadable -> errorCouponUnreadable
    BondMath.validateCouponPercent(coupon) != null -> messages.sentenceFor(BondMath.validateCouponPercent(coupon))
    else -> null
}

/** The sentence for a problem, resolved once by the composable that owns the strings. */
private fun Map<BondTradeProblem, String>.sentenceFor(problem: BondTradeProblem?): String =
    problem?.let { this[it] } ?: ""
