package com.financetracker.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The user's exclusion rules, against real preferences.
 *
 * Same shape as `SettingsRepositoryRatesTest` — a real repository over the real DataStore,
 * because what is worth checking here is what actually lands on disk and what a second read
 * makes of it. The encoding in particular is only worth testing against real storage: a
 * delimiter that round-trips in a fake is a delimiter that round-trips.
 *
 * What happens to a value this build cannot read is tested in `ExclusionRulesEncodingTest`
 * instead, on the decoding half on its own, because reaching a hand-planted value into a real
 * DataStore file means a second instance on that file, and that is an error rather than a test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsRepositoryExclusionTest {

    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() = runBlocking {
        settings = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())
        settings.saveExclusionRules(emptyList())
    }

    /**
     * The preference delegate is a top-level property, so every instance in the JVM shares one
     * file and a rule left behind here would be the next test's starting point.
     */
    @After
    fun tearDown() = runBlocking { settings.saveExclusionRules(emptyList()) }

    @Test
    fun `a fresh install has no rules, which is the state a device starts in`() = runBlocking {
        assertTrue(settings.exclusionRules.first().isEmpty)
    }

    @Test
    fun `rules round-trip`() = runBlocking {
        val rules = listOf("transfer", "cashback", "ЗП")
        settings.saveExclusionRules(rules)

        assertEquals(rules, settings.exclusionRules.first().patterns)
    }

    @Test
    fun `a rule containing a comma survives, which is why the encoding is JSON`() = runBlocking {
        // The bug this guards is silent and total: a comma-joined encoding splits this into two
        // patterns, each of which then matches nothing, and the user's rule appears to be
        // working while excluding nothing.
        val rule = "переказ, на карту"
        settings.saveExclusionRules(listOf(rule))

        assertEquals(listOf(rule), settings.exclusionRules.first().patterns)
    }

    @Test
    fun `a rule containing a double quote survives`() = runBlocking {
        val rule = "say \"hello\""
        settings.saveExclusionRules(listOf(rule))

        assertEquals(listOf(rule), settings.exclusionRules.first().patterns)
    }

    @Test
    fun `a rule containing a quote and a comma together survive`() = runBlocking {
        val rule = "\"bank\", transfer"
        settings.saveExclusionRules(listOf(rule))

        assertEquals(listOf(rule), settings.exclusionRules.first().patterns)
    }

    @Test
    fun `an empty save leaves no rules rather than one empty rule`() = runBlocking {
        settings.saveExclusionRules(listOf("transfer"))
        settings.saveExclusionRules(emptyList())

        val read = settings.exclusionRules.first()
        assertTrue(read.isEmpty)
        assertEquals(emptyList<String>(), read.patterns)
    }

    @Test
    fun `removing a rule leaves the others alone`() = runBlocking {
        // Not a per-rule key: a rule may be added and removed freely, and none of them may
        // accumulate as a preference the user cannot see or clear. A per-rule key would need its
        // own sweeping to remove, and would leave orphans behind a rule the user deleted.
        settings.saveExclusionRules(listOf("a", "b", "c"))
        settings.saveExclusionRules(listOf("a", "c"))

        assertEquals(listOf("a", "c"), settings.exclusionRules.first().patterns)
    }

    @Test
    fun `stored text is kept as typed, not as folded`() = runBlocking {
        // The settings list has to show the user what they entered. Folding for matching must
        // not leak into what is stored, or "Transfer" would come back lowercased and the user
        // would wonder what else had been changed. The trim is the one exception: it is what
        // makes the rule mean the same thing however it was typed.
        settings.saveExclusionRules(listOf("Transfer", "  ЗП  "))

        assertEquals(listOf("Transfer", "ЗП"), settings.exclusionRules.first().patterns)
    }

    @Test
    fun `a duplicate rule is stored once`() = runBlocking {
        settings.saveExclusionRules(listOf("transfer", "transfer", "TRANSFER"))

        assertEquals(1, settings.exclusionRules.first().patterns.size)
    }

    @Test
    fun `many rules round-trip in order`() = runBlocking {
        val rules = (1..40).map { "rule $it" }
        settings.saveExclusionRules(rules)

        // Order is not cosmetic: the settings list is the user reading their own rules in the
        // order they added them, and a set would reorder them on a reload.
        assertEquals(rules, settings.exclusionRules.first().patterns)
    }
}
