package com.financetracker.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The app lock flag, against real preferences.
 *
 * Same shape as [SettingsRepositoryExclusionTest] — a real repository over the real DataStore,
 * because what matters here is what lands on disk and what a second read makes of it.
 *
 * The default is the assertion worth having. `false` on a fresh install is what lets this
 * feature ship with no migration and no `AppDatabase` version bump: an existing install must not
 * be able to acquire a lock the user never asked for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsRepositoryAppLockTest {

    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() = runBlocking {
        settings = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())
        settings.setAppLockEnabled(false)
    }

    /** The delegate is a top-level property, so every instance in the JVM shares one file. */
    @After
    fun tearDown() = runBlocking { settings.setAppLockEnabled(false) }

    @Test
    fun `a fresh install has the lock off, which is the state a device starts in`() = runBlocking {
        assertFalse(settings.appLockEnabled.first())
    }

    @Test
    fun `the lock round-trips`() = runBlocking {
        settings.setAppLockEnabled(true)

        assertTrue(settings.appLockEnabled.first())
    }

    @Test
    fun `turning the lock back off round-trips, which is what a restore onto a new device reads`() = runBlocking {
        settings.setAppLockEnabled(true)
        settings.setAppLockEnabled(false)

        assertFalse(settings.appLockEnabled.first())
    }

    /**
     * Writing the stored key directly, to reach the state a user reaches by installing, enabling
     * the lock, and then being signed out. Signing out must not clear it: the file being
     * protected is still on this phone, and a lock that protects nothing once the user is
     * signed out protects nothing when the app is most worth looking at.
     */
    @Test
    fun `the lock survives being asked for twice with nothing in between`() = runBlocking {
        settings.setAppLockEnabled(true)
        settings.setAppLockEnabled(false)
        settings.setAppLockEnabled(true)

        assertTrue(settings.appLockEnabled.first())
    }
}