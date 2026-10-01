package com.financetracker.security

import com.financetracker.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the settings section says, given what the device reports.
 *
 * Plain JVM, and deliberately: `BiometricManager` is the untestable half of this feature — it
 * needs a device with an enrolled credential, and this project has none attached — so the
 * decision built on top of it is the part worth pinning. It is the decision that decides whether
 * the switch may be pressed at all.
 *
 * Comparing the `R.string` ids rather than rendered text is enough here. What has to be right is
 * that the four combinations land on four different sentences; the wording is copy, and copy has
 * no behaviour to break.
 */
class LockCapabilityTest {

    @Test
    fun `a device with nothing enrolled cannot be locked`() {
        val capability = LockCapability.of(biometricAvailable = false, deviceCredentialAvailable = false)

        assertFalse(capability.canLock)
    }

    @Test
    fun `a device with nothing enrolled says so, rather than offering the switch`() {
        val capability = LockCapability.of(biometricAvailable = false, deviceCredentialAvailable = false)

        assertEquals(R.string.settings_app_lock_none, capability.descriptionRes)
    }

    @Test
    fun `a biometric on its own can lock`() {
        val capability = LockCapability.of(biometricAvailable = true, deviceCredentialAvailable = false)

        assertTrue(capability.canLock)
    }

    @Test
    fun `a device credential on its own can lock`() {
        val capability = LockCapability.of(biometricAvailable = false, deviceCredentialAvailable = true)

        assertTrue(capability.canLock)
    }

    @Test
    fun `both enrolled can lock`() {
        val capability = LockCapability.of(biometricAvailable = true, deviceCredentialAvailable = true)

        assertTrue(capability.canLock)
    }

    /**
     * The four answers are four sentences, not two.
     *
     * This is the reason `canAuthenticate` is asked twice rather than once with a combined mask:
     * a device whose only credential is a PIN is not a device whose fingerprint stopped working,
     * and the user cannot tell which of those two they are looking at if both report the same
     * line.
     */
    @Test
    fun `the four combinations name four different things`() {
        val sentences = listOf(true, false).flatMap { biometric ->
            listOf(true, false).map { credential ->
                LockCapability.of(biometric, credential).descriptionRes
            }
        }

        assertEquals(sentences.size, sentences.distinct().size)
    }

    @Test
    fun `having both is not described as either one alone`() {
        val both = LockCapability.of(biometricAvailable = true, deviceCredentialAvailable = true)
        val biometricOnly = LockCapability.of(biometricAvailable = true, deviceCredentialAvailable = false)
        val credentialOnly = LockCapability.of(biometricAvailable = false, deviceCredentialAvailable = true)

        // A device with both shows the biometric first and falls back only after a failure, so
        // describing it as two alternatives would lead the user to expect a prompt the system
        // does not offer.
        assertFalse(both.descriptionRes == biometricOnly.descriptionRes)
        assertFalse(both.descriptionRes == credentialOnly.descriptionRes)
    }

    @Test
    fun `only nothing-enrolled refuses to lock`() {
        val refusals = listOf(true, false).flatMap { biometric ->
            listOf(true, false).map { credential ->
                LockCapability.of(biometric, credential).canLock
            }
        }.count { !it }

        assertEquals(1, refusals)
    }
}