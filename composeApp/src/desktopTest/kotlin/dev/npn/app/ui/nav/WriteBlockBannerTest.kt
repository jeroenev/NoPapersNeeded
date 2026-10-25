@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.nav

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import dev.npn.app.EnglishLocaleTest
import dev.npn.app.ui.click
import dev.npn.app.ui.closing
import dev.npn.app.ui.has
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.text
import dev.npn.app.ui.themedScene
import dev.npn.core.index.WriteBlock
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The read-only banner above every screen: the block's reason as a sentence, and Retry and Rebuild
 * index where they help.
 */
class WriteBlockBannerTest : EnglishLocaleTest() {
    /**
     * A journal still arriving reads as its reason, capitalized to start the banner, with a Retry
     * and a Rebuild index; a library a newer app changed offers neither; and the banner goes once
     * the block lifts.
     */
    @Test
    fun theBannerSaysWhyAndGoesOnceWritesAreTaken() =
        onUiThread {
            var block by mutableStateOf<WriteBlock?>(WriteBlock.JOURNAL_DAMAGED)
            var retries = 0
            var rebuilds = 0
            val actions =
                object : WriteBlockActions {
                    override fun retry() {
                        retries++
                    }

                    override fun rebuildIndex() {
                        rebuilds++
                    }
                }
            themedScene(WIDTH, HEIGHT) {
                WithWriteBlockBanner(block, actions) { Text("Library") }
            }.closing {
                render()
                has { it.text()?.startsWith("The library's change history is missing a file") == true } shouldBe true
                click { it.text() == "Retry" }
                retries shouldBe 1
                click { it.text() == "Rebuild index" }
                rebuilds shouldBe 1

                block = WriteBlock.NEWER_FORMAT
                Snapshot.sendApplyNotifications()
                render()
                has { it.text()?.startsWith("The library has changes from a newer version") == true } shouldBe true
                has { it.text() == "Retry" } shouldBe false
                has { it.text() == "Rebuild index" } shouldBe false

                block = null
                Snapshot.sendApplyNotifications()
                render()
                has { it.text()?.startsWith("The library") == true } shouldBe false
                has { it.text() == "Library" } shouldBe true
            }
        }

    private companion object {
        const val WIDTH = 600
        const val HEIGHT = 400
    }
}
