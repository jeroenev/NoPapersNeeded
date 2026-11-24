package dev.npn.app.platform

import android.content.Context
import android.content.Intent
import android.print.PrintManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.npn.app.LibraryLifecycleTestActivity
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintActivityTest {
    @Test
    fun activityIsUsedOnlyWhileRegistered() {
        val context = InstrumentationRegistry.getInstrumentation().context
        ActivityScenario
            .launch<LibraryLifecycleTestActivity>(
                Intent(context, LibraryLifecycleTestActivity::class.java),
            ).use { scenario ->
                scenario.onActivity { activity ->
                    PrintActivity.resumed(activity)
                    assertSame(activity, PrintActivity.current())
                    assertTrue(PrintActivity.current()?.getSystemService(Context.PRINT_SERVICE) is PrintManager)
                    PrintActivity.paused(activity)
                    assertNull(PrintActivity.current())
                }
            }
    }
}
