package com.financetracker.ui

import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves a composition can be driven on the JVM under Robolectric.
 *
 * If this passes, a screen can be exercised as part of the ordinary unit test run — a field typed
 * into and a button pressed — rather than only by hand on an attached device. That is the whole
 * reason for attempting it, so it is worth one test of its own: without it, every screen test
 * below would rest on an unproven assumption that Robolectric can compose at all.
 */
@RunWith(AndroidJUnit4::class)
class ComposeOnJvmSpikeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aFieldCanBeTypedIntoAndItsStateReadBack() {
        compose.setContent {
            var text by remember { mutableStateOf("") }
            OutlinedTextField(value = text, onValueChange = { text = it })
            Text("typed: $text")
        }

        // `hasSetTextAction` rather than a text matcher: a text field's editable content is not
        // its `text`, so matching on text finds nothing, and matching on "" matches everything.
        compose.onNode(hasSetTextAction()).performTextInput("1200,50")

        // The comma, because that is what a Ukrainian keypad sends and the reason AmountInput
        // exists. If this round-trips through a real composition the field path is proven.
        compose.onNodeWithText("typed: 1200,50").assertIsDisplayed()
    }

    @Test
    fun aButtonCanBePressed() {
        compose.setContent {
            var pressed by remember { mutableStateOf(false) }
            Text(if (pressed) "done" else "not yet")
            Button(onClick = { pressed = true }) { Text("press") }
        }

        compose.onNodeWithText("not yet").assertIsDisplayed()
        compose.onNodeWithText("press").performClick()
        compose.onNodeWithText("done").assertIsDisplayed()
    }
}