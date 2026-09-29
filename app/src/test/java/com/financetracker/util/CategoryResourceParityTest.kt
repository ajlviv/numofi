package com.financetracker.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [CategoryLabel.resource] and `res/values/strings.xml` are two tables of the same words:
 * the Kotlin one decides grouping, the resource one decides what a Ukrainian screen says.
 * Nothing but this test notices if they drift — a renamed English value would leave the
 * list showing one wording and the dashboard another — so the English side of every
 * resource is pinned here to the byte exactly what [CategoryLabel.label] produces.
 */
@RunWith(RobolectricTestRunner::class)
class CategoryResourceParityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Every code the exact and range tables can reach, plus the unmatched fallback. */
    @Test
    fun `every mcc label equals the resource it resolves to`() {
        for (code in 0..9999) {
            val key = "mcc_$code"
            val resource = CategoryLabel.resource(key)
            assertTrue("no resource for $key", resource != 0)
            assertEquals(CategoryLabel.label(key), context.getString(resource))
        }
    }

    @Test
    fun `the special categories resolve to their own words`() {
        listOf(null, "", "   ", "imported", "other", "investments").forEach { key ->
            val resource = CategoryLabel.resource(key)
            assertTrue("no resource for '$key'", resource != 0)
            assertEquals(CategoryLabel.label(key), context.getString(resource))
        }
    }

    @Test
    fun `a free-typed category is left in the user's own wording`() {
        // resource() = 0 is the signal for "render label() unchanged"; it must not silently
        // map to some generic bucket, or the user's own words would disappear from the list.
        assertEquals(0, CategoryLabel.resource("food_delivery"))
        assertEquals("Food Delivery", CategoryLabel.label("food_delivery"))
    }
}