package dev.npn.app.ui.queue

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class StorageGrantTest {
    @Test
    fun onlyDirectLocationsNeedTheGrant() {
        needsStorageGrant(null, appPrivate = false, usable = false) shouldBe false
        needsStorageGrant("content://library/tree", appPrivate = false, usable = false) shouldBe false
        needsStorageGrant("/private/library", appPrivate = true, usable = false) shouldBe false
        needsStorageGrant("/shared/library", appPrivate = false, usable = true) shouldBe false
        needsStorageGrant("/shared/library", appPrivate = false, usable = false) shouldBe true
    }
}
