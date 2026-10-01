package com.financetracker.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a stop re-locks, or was one the app asked for.
 *
 * Plain JVM, and this is the whole class: there is no Android in it by construction, which is the
 * point. The bug it guards is not subtle to write but is invisible until a user picks a folder —
 * every picker stops the activity, so a lock that re-locked on every stop unmounted the settings
 * screen while the picker was in front and dropped the chosen file on the floor.
 *
 * What cannot be tested here is whether a real picker produces the stop being announced. That is
 * the part needing a device.
 */
class ExternalActivityLaunchTest {

    @Test
    fun `a stop nobody announced re-locks`() {
        val launch = ExternalActivityLaunch()

        assertFalse(launch.consumeExpected())
    }

    @Test
    fun `a stop the app announced does not re-lock`() {
        val launch = ExternalActivityLaunch()
        launch.expect()

        assertTrue(launch.consumeExpected())
    }

    /**
     * Announced once, spent once.
     *
     * The flag is consumed rather than read so that the *next* real backgrounding is examined
     * normally. A flag that stayed set would leave the app unlocked through every subsequent trip
     * to the recents screen, which is the exact thing the lock is for.
     */
    @Test
    fun `the announcement does not survive into the next stop`() {
        val launch = ExternalActivityLaunch()
        launch.expect()
        launch.consumeExpected()

        assertFalse(launch.consumeExpected())
    }

    @Test
    fun `two pickers in a row are each announced`() {
        val launch = ExternalActivityLaunch()

        launch.expect()
        assertTrue(launch.consumeExpected())
        launch.expect()
        assertTrue(launch.consumeExpected())
    }

    @Test
    fun `a picker the user backed out of still re-locks afterwards`() {
        // Backing out of a picker produces a result and then the user may leave the app. The
        // announcement was for that one stop only, so this one must be judged on its own.
        val launch = ExternalActivityLaunch()
        launch.expect()
        launch.consumeExpected()

        assertFalse(launch.consumeExpected())
    }
}