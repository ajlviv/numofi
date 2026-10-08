package com.financetracker.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.financetracker.data.settings.DEFAULT_CATEGORIES
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The user's category list, against real preferences.
 *
 * Same shape as `SettingsRepositoryExclusionTest` — a real repository over the real
 * DataStore, because what is worth checking is what actually lands on disk and what a
 * second read makes of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsRepositoryCategoriesTest {

    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() = runBlocking {
        settings = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())
        settings.saveCategories(DEFAULT_CATEGORIES)
    }

    @After
    fun tearDown() = runBlocking { settings.saveCategories(DEFAULT_CATEGORIES) }

    @Test
    fun `a fresh-shaped store starts at the defaults`() = runBlocking {
        assertEquals(DEFAULT_CATEGORIES, settings.categories.first())
    }

    @Test
    fun `categories round-trip in order`() = runBlocking {
        val names = listOf("Groceries", "Кава", "Other")
        settings.saveCategories(names)

        assertEquals(names, settings.categories.first())
    }

    @Test
    fun `stored text is kept as typed, not as folded`() = runBlocking {
        settings.saveCategories(listOf("Groceries", "  Кава  "))

        assertEquals(listOf("Groceries", "Кава"), settings.categories.first())
    }

    @Test
    fun `a duplicate is stored once`() = runBlocking {
        settings.saveCategories(listOf("Groceries", "groceries", "GROCERIES"))

        assertEquals(listOf("Groceries"), settings.categories.first())
    }

    @Test
    fun `removing a category leaves the others alone`() = runBlocking {
        settings.saveCategories(listOf("a", "b", "c"))
        settings.saveCategories(listOf("a", "c"))

        assertEquals(listOf("a", "c"), settings.categories.first())
    }

    @Test
    fun `reset restores the defaults`() = runBlocking {
        settings.saveCategories(listOf("Just coffee"))
        settings.resetCategories()

        assertEquals(DEFAULT_CATEGORIES, settings.categories.first())
    }
}
