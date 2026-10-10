package dev.npn.app.platform

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test

/**
 * An activity imports the intent it was launched with once, unless a process death cut that import
 * short. Each [LaunchImports] stands for one process.
 */
class LaunchImportsTest {
    private val process = LaunchImports()

    @Test
    fun aFreshLaunchImports() {
        process.importsLaunchIntent(restored = false, fromHistory = false, unfinished = null) shouldBe true
    }

    @Test
    fun aReturnFromRecentsDoesNotImportAgain() {
        process.importsLaunchIntent(restored = false, fromHistory = true, unfinished = null) shouldBe false
    }

    @Test
    fun aRecreationInTheSameProcessDoesNotImportAgainWhileTheImportRuns() {
        val token = process.start()
        val saved = process.unfinished(token)

        saved shouldNotBe null
        process.importsLaunchIntent(restored = true, fromHistory = false, unfinished = saved) shouldBe false
    }

    @Test
    fun aFinishedImportIsNotSaved() {
        val token = process.start()
        process.finish(token)

        process.unfinished(token) shouldBe null
        process.unfinished(null) shouldBe null
        process.importsLaunchIntent(restored = true, fromHistory = false, unfinished = null) shouldBe false
    }

    @Test
    fun anImportAProcessDeathCutShortRunsAgainInTheNextProcess() {
        val saved = process.unfinished(process.start())
        val next = LaunchImports()

        next.importsLaunchIntent(restored = true, fromHistory = false, unfinished = saved) shouldBe true
        next.importsLaunchIntent(restored = true, fromHistory = true, unfinished = saved) shouldBe true
    }

    @Test
    fun anImportThatFinishedBeforeTheStateWasSavedDoesNotRunInTheNextProcess() {
        val token = process.start()
        process.finish(token)
        val saved = process.unfinished(token)

        LaunchImports().importsLaunchIntent(restored = true, fromHistory = true, unfinished = saved) shouldBe false
    }
}
