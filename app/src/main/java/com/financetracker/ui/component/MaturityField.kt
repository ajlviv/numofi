package com.financetracker.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.financetracker.R
import com.financetracker.util.DateFormats
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The maturity date, optional.
 *
 * A disabled empty box would read as a value the user had forgotten to fill in, and the date
 * is genuinely optional: someone recording a quick trade often knows the ISIN and the fill
 * price and nothing else. So the button opens the picker unset, and the trailing cross clears
 * it back to nothing.
 *
 * Shared by the trade form and the terms-edit form, which are the two places a bond's maturity
 * is entered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MaturityField(maturity: LocalDate?, onChange: (LocalDate?) -> Unit, modifier: Modifier = Modifier) {
    var showPicker by remember { mutableStateOf(false) }
    val pattern = remember { DateFormats.date() }
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
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (maturity ?: LocalDate.now().plusYears(1))
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        onChange(
                            java.time.Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                        )
                    }
                    showPicker = false
                }) { Text(stringResource(R.string.add_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        ) {
            DatePicker(state = state)
        }
    }
}
