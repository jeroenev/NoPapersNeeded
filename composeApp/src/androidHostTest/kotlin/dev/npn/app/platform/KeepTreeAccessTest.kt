package dev.npn.app.platform

import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** A folder picked through SAF is a location to save only once the system keeps this app's access to it. */
class KeepTreeAccessTest {
    private val tree = "content://com.example.documents/tree/primary%3AInbox"

    @Test
    fun aKeptGrantGivesTheLocation() {
        var taken = 0
        keepTreeAccess(tree) { taken++ } shouldBe FolderPick.Location(tree)
        taken shouldBe 1
    }

    @Test
    fun aRefusedGrantGivesNoLocation() {
        keepTreeAccess(tree) {
            throw SecurityException("No persistable permission grants found for UID 10123 and Uri $tree")
        } shouldBe FolderPick.AccessNotKept
    }

    @Test
    fun anythingElseIsABug() {
        assertFailsWith<IllegalArgumentException> { keepTreeAccess(tree) { throw IllegalArgumentException("flags") } }
    }
}
