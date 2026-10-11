package dev.npn.app.platform

import android.content.Intent
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/** Exercise the system's real grant refusal, rather than a host fake that throws SecurityException. */
class TreeAccessDeviceTest {
    @Test
    fun aTreeWithNoPersistableGrantIsNeverReturnedAsALocationToSave() {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ANpnTestWithoutGrant")
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        val picked = keepTreeAccess(tree.toString()) { resolver.takePersistableUriPermission(tree, flags) }

        assertEquals(FolderPick.AccessNotKept, picked)
    }
}
