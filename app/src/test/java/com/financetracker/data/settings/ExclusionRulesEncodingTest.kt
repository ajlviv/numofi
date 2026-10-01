package com.financetracker.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning what is on disk back into rules, including the shapes this build did not write.
 *
 * A plain JVM test rather than a Robolectric one, and deliberately so: this is the half that can
 * meet a value nothing in the app would produce, and a value like that can only be planted in a
 * real DataStore file by a second instance on that file — which DataStore refuses. Reading the
 * decoding on its own is both possible and the thing actually worth testing.
 */
class ExclusionRulesEncodingTest {

    @Test
    fun `nothing stored means no rules`() {
        assertTrue(readExclusionRules(null).isEmpty)
    }

    @Test
    fun `an empty string means no rules`() {
        assertTrue(readExclusionRules("").isEmpty)
    }

    @Test
    fun `an empty array means no rules`() {
        assertTrue(readExclusionRules("[]").isEmpty)
    }

    @Test
    fun `an array of rules reads back in order`() {
        assertEquals(listOf("a", "b"), readExclusionRules("""["a","b"]""").patterns)
    }

    @Test
    fun `a value this build cannot read means no rules rather than an error`() {
        // A preference is not data the user cannot afford to lose, so unreadable reads as off.
        // What it must not do is throw: this runs inside a flow the settings screen observes,
        // and an exception here takes that screen down on the way past rather than showing a
        // rule that stopped working.
        assertTrue(readExclusionRules("not json at all").isEmpty)
        assertTrue(readExclusionRules("{").isEmpty)
        assertTrue(readExclusionRules("{\"a\":1}").isEmpty)
    }

    @Test
    fun `an array holding something that is not a string drops that entry`() {
        // Written by a build that stored richer entries. Skipping the odd one out keeps the
        // rest of the user's rules working, which is the same reason the whole read is
        // forgiving rather than all-or-nothing. The number is the case worth having: Gson calls
        // it a primitive too, and reading it as "1" would invent a rule the user never typed
        // that matches any title containing a digit.
        assertEquals(listOf("a", "b"), readExclusionRules("""["a",1,null,"b",true]""").patterns)
    }

    @Test
    fun `a rule stored as blank is dropped on read rather than matching everything`() {
        // The failure this prevents is the severe one: a blank needle is a substring of every
        // title, so honouring it would empty every total in the app.
        assertTrue(readExclusionRules("""["","   "]""").isEmpty)
    }

    @Test
    fun `the encoding is stable, so a re-save does not churn the stored string`() {
        val rules = listOf("переказ, на карту", "say \"hi\"")
        val once = encodeExclusionRules(rules)
        val twice = encodeExclusionRules(readExclusionRules(once).patterns)

        assertEquals(once, twice)
    }
}
