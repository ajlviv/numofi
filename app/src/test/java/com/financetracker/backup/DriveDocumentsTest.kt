package com.financetracker.backup

import androidx.test.core.app.ApplicationProvider
import com.financetracker.data.backup.driveRootUri
import com.financetracker.data.backup.driveRootUriIfInstalled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The authority and root id are string literals with no compiler check on them, and a wrong
 * one fails silently: the picker still opens, just somewhere useless, and the only symptom is
 * a user reporting that they cannot find Drive. Asserted as a literal for that reason.
 */
@RunWith(RobolectricTestRunner::class)
class DriveDocumentsTest {

    @Test
    fun `the drive root is the my drive tree of the drive documents provider`() {
        // buildRootUri puts the root id under a "root" path segment, so the full URI has both
        // and dropping either one is a URI no provider will answer to.
        assertEquals(
            "content://com.google.android.apps.drive.documents/root/root",
            driveRootUri().toString()
        )
    }

    @Test
    fun `no initial location is offered when drive is not installed`() {
        // Robolectric has no Drive app, which is the state this guards: handing DocumentsUI
        // an initial URI whose provider cannot answer for it is what must be avoided.
        assertNull(driveRootUriIfInstalled(ApplicationProvider.getApplicationContext()))
    }
}
