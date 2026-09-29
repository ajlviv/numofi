package com.financetracker.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * A dropdown of checkable options, of which any number may be picked.
 *
 * The menu stays open while picking, because the whole point of a multi-select is that the
 * next choice is usually in the same list, and each row carries a tick rather than a
 * checkbox so that the row is the only thing that toggles. A checkbox inside a menu item
 * would receive the click itself and either swallow it or toggle twice.
 */
@Composable
internal fun <T> MultiSelectDropdown(
    label: String,
    options: List<Pair<T, String>>,
    isSelected: (T) -> Boolean,
    onToggle: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = options.filter { isSelected(it.first) }

    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(
                // One pick reads better than a count of one; the count only earns its place
                // once there is more than one thing to count.
                text = when (selected.size) {
                    0 -> label
                    1 -> selected.first().second
                    else -> "$label · ${selected.size}"
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = { onToggle(value) },
                    trailingIcon = if (isSelected(value)) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else {
                        null
                    }
                )
            }
            HorizontalDivider()
            TextButton(
                onClick = { expanded = false },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Done") }
        }
    }
}

/**
 * A dropdown of which exactly one option is picked, closing after the choice.
 *
 * [T] is the value rather than a flag per row because a single-select has no "currently
 * picked" to tick; the chosen row is the button's own label, which is also what tells the
 * user what they picked without opening the menu. T is nullable in the one place this is
 * used, where "no bank" is a real choice rather than an absent one.
 */
@Composable
internal fun <T> SingleChoiceDropdown(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = options.firstOrNull { it.first == selected }?.second ?: label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * Renders a field label with a red asterisk indicating a required field.
 */
@Composable
internal fun RequiredLabel(text: String) {
    Text(
        buildAnnotatedString {
            append(text)
            append(" ")
            withStyle(SpanStyle(color = Color.Red)) {
                append("*")
            }
        }
    )
}
