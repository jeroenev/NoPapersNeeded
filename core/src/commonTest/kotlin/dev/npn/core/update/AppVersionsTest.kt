package dev.npn.core.update

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class AppVersionsTest {
    @Test
    fun comparesNumericallyPerComponent() {
        AppVersions.isNewer("0.10.0", "0.9.9") shouldBe true
        AppVersions.isNewer("1.0.0", "0.99.99") shouldBe true
        AppVersions.isNewer("0.1.1", "0.1.0") shouldBe true
        AppVersions.isNewer("0.1.0", "0.1.0") shouldBe false
        AppVersions.isNewer("0.1.0", "0.2.0") shouldBe false
    }

    @Test
    fun ignoresTagPrefixAndBuildSuffix() {
        AppVersions.isNewer("v1.2.0", "1.1.0") shouldBe true
        AppVersions.isNewer("1.2.0", "1.2.0-debug") shouldBe false
    }

    @Test
    fun malformedVersionIsNeverNewer() {
        AppVersions.isNewer("nightly", "1.0.0") shouldBe false
        AppVersions.isNewer("2.0", "1.0.0") shouldBe false
        AppVersions.isNewer("2.0.0", "unknown") shouldBe false
        AppVersions.isNewer("99999999999.0.0", "1.0.0") shouldBe false
    }
}
