package com.financetracker.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.financetracker.R
import com.financetracker.util.CategoryLabel

/**
 * A category field that offers the user's settings list but still accepts a one-off value.
 *
 * A plain text field is how every stored row ended up with its own spelling of the same
 * bucket ("food", "Food", "food_delivery"), and a strict dropdown is how a legitimate new
 * bucket becomes un-enterable. So this is both: typing filters the list, tapping picks, and
 * a value that matches nothing can still be saved — it just stays on the row and is never
 * added to the settings list behind the user's back.
 *
 * Labels resolve through [CategoryLabel.resource] where one exists, so the menu reads in
 * the app's language while the stored value stays the English label the settings list
 * holds.
 */
@Composable
fun CategoryDropdown(
    value: String,
    categories: List<String>,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    // Separate from [value] on purpose: filtering by the value already chosen would reopen
    // the menu showing only that one entry, turning a re-pick into a search for the thing
    // you just picked. The filter starts empty on every open and only narrows while typing.
    var filter by remember { mutableStateOf("") }
    val filtered = remember(filter, categories) {
        if (filter.isEmpty()) categories
        else categories.filter { it.contains(filter, ignoreCase = true) }
    }
    // A one-off is a value the list does not hold (case-insensitively): still savable, just
    // said out loud so it does not look like it joined the list.
    val isOneOff = value.isNotBlank() && categories.none { it.equals(value, ignoreCase = true) }

    Column(modifier = modifier) {
        Box {
            OutlinedTextField(
                value = value,
                onValueChange = {
                    onValueChange(it)
                    filter = it.trim()
                    expanded = true
                },
                label = label,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = {
                        filter = ""
                        expanded = !expanded
                    }) {
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                }
            )
            DropdownMenu(
                expanded = expanded && (filtered.isNotEmpty() || isOneOff),
                onDismissRequest = { expanded = false }
            ) {
                filtered.forEach { option ->
                    val res = CategoryLabel.resource(option)
                    DropdownMenuItem(
                        text = {
                            Text(if (res != 0) stringResource(res) else option)
                        },
                        onClick = {
                            onValueChange(option)
                            filter = ""
                            expanded = false
                        }
                    )
                }
                if (isOneOff) {
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(R.string.settings_category_use_typed, value.trim()))
                        },
                        onClick = {
                            filter = ""
                            expanded = false
                        }
                    )
                }
            }
        }
        if (isOneOff) {
            Text(
                text = stringResource(R.string.settings_category_one_off_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
