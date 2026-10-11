package dev.npn.app

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.npn.app.ui.nav.Route
import dev.npn.core.app.LibrarySession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A real activity's retained ViewModelStore and saved navigation, through the app's library host. */
class LibraryActivityLifecycleTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before
    fun resetLibrary() {
        instrumentation.runOnMainSync { LifecycleTestState.session = FakeLibrarySession() }
    }

    @Test
    fun activityRecreationKeepsTheDocumentScreenAndItsViewModel() {
        launch().use { scenario ->
            await { LifecycleTestState.shown != null }
            scenario.onActivity { checkNotNull(LifecycleTestState.shown).nav.navigate(Route.DocDetail("document")) }
            await { LifecycleTestState.probe != null }
            val before = checkNotNull(LifecycleTestState.probe)

            instrumentation.runOnMainSync {
                LifecycleTestState.shown = null
                LifecycleTestState.probe = null
            }
            scenario.recreate()
            await { LifecycleTestState.probe != null }

            assertSame(before, LifecycleTestState.probe)
            assertFalse(before.cleared)
            scenario.onActivity {
                assertTrue(
                    checkNotNull(LifecycleTestState.shown).nav.currentDestination?.hasRoute<Route.DocDetail>() == true,
                )
            }
        }
        assertTrue(checkNotNull(LifecycleTestState.probe).cleared)
    }

    @Test
    fun switchingLibrariesClearsTheOldScreensEvenAfterActivityRecreation() {
        launch().use { scenario ->
            await { LifecycleTestState.shown != null }
            scenario.onActivity { checkNotNull(LifecycleTestState.shown).nav.navigate(Route.DocDetail("document")) }
            await { LifecycleTestState.probe != null }
            val before = checkNotNull(LifecycleTestState.probe)
            scenario.recreate()
            scenario.onActivity {
                LifecycleTestState.probe = null
                LifecycleTestState.session = FakeLibrarySession()
            }
            await { before.cleared }
            scenario.onActivity {
                assertTrue(
                    checkNotNull(LifecycleTestState.shown).nav.currentDestination?.hasRoute<Route.Library>() == true,
                )
            }
            assertTrue(before.cleared)
        }
    }

    private fun launch(): ActivityScenario<LibraryLifecycleTestActivity> {
        instrumentation.runOnMainSync {
            LifecycleTestState.shown = null
            LifecycleTestState.probe = null
        }
        return ActivityScenario.launch(Intent(instrumentation.context, LibraryLifecycleTestActivity::class.java))
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS
        while (!condition() && SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            SystemClock.sleep(20)
        }
        check(condition()) { "Activity did not settle before the deadline" }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
    }
}

/** Fixture activity; its state stands in for the process's already-open library. */
class LibraryLifecycleTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val scope = rememberCoroutineScope()
            LocalizedApp(LifecycleTestState.settings, LifecycleTestState.session, scope) { library ->
                if (library != null) {
                    SideEffect { LifecycleTestState.shown = library }
                    key(library) {
                        LibraryViewModelHost(library) {
                            NavHost(library.nav, startDestination = Route.Library) {
                                composable<Route.Library> { Text("Library") }
                                composable<Route.DocDetail> {
                                    val probe = viewModel { LifecycleScreenProbe() }
                                    SideEffect { LifecycleTestState.probe = probe }
                                    Text("Document")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal object LifecycleTestState {
    val settings = FakeSettingsRepository()
    var session by mutableStateOf<LibrarySession?>(null)

    @Volatile var shown: LibraryUi? = null

    @Volatile var probe: LifecycleScreenProbe? = null
}

internal class LifecycleScreenProbe : ViewModel() {
    @Volatile var cleared = false

    override fun onCleared() {
        cleared = true
    }
}
