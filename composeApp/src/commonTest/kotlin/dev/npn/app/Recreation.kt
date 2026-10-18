package dev.npn.app

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.npn.app.ui.ai.AiChatUiState
import dev.npn.app.ui.ai.AiEditUiState
import dev.npn.app.ui.ai.EditPhase
import dev.npn.app.ui.detail.AiMetadataState
import dev.npn.app.ui.detail.AiTagState
import dev.npn.app.ui.detail.DetailUiState
import dev.npn.app.ui.detail.HistoryUiState
import dev.npn.app.ui.detail.MetadataUiState
import dev.npn.app.ui.library.LibraryContentState
import dev.npn.app.ui.settings.AiModelListState
import dev.npn.app.ui.settings.AiSelfTestState
import dev.npn.app.ui.settings.SettingsPaneState
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.reflect.KClass

/**
 * A viewmodel under test as its screen holds it: in a store of its own, built by [build] from the
 * session's fakes and the [SavedStateHandle] its back-stack entry hands it. [recreate] does to it
 * what a restored process does, and checks the screen comes back as it was.
 *
 * Use: build it with [recreatable] instead of calling the constructor, drive [vm] into the middle of
 * something (a dialog open with text typed, a selection made, a pane chosen, a review under way),
 * then call [recreate] with the state the screen renders, and go on with the viewmodel it returns.
 */
class Recreatable<VM : ViewModel>(
    private val type: KClass<VM>,
    private val build: (SavedStateHandle) -> VM,
) {
    private var handle = SavedStateHandle()
    private var store = ViewModelStore()

    /** The viewmodel the screen holds now; another one after [recreate]. */
    var vm: VM = create()
        private set

    /**
     * Recreate [vm] as Android does across a process death, and assert the new one renders what the
     * old one did. In order: let the old one settle, take what [rendered] shows, keep only its saved
     * state ([afterProcessDeath]), clear it (cancelling everything it started), build a new one from
     * the same fakes and the restored saved state, let that settle, and compare. [transient] drops,
     * from both sides, what a restored process legitimately loses (see [Transient]); keep it to that.
     * Returns the new viewmodel, which is [vm] from then on.
     */
    fun <S> recreate(
        test: TestScope,
        rendered: (VM) -> Flow<S>,
        transient: (S) -> S = { it },
    ): VM {
        val before = test.settled(rendered(vm))
        val saved = handle.afterProcessDeath()
        store.clear()
        handle = saved
        store = ViewModelStore()
        vm = create()
        test.settled(rendered(vm)).let(transient) shouldBe before.let(transient)
        return vm
    }

    private fun <S> TestScope.settled(flow: Flow<S>): S {
        // Subscribed, as the screen is, so a state shared while subscribed is the live one.
        val seen = collecting(flow)
        advanceUntilIdle()
        return seen.last()
    }

    private fun create(): VM {
        val owner =
            object : ViewModelStoreOwner {
                override val viewModelStore = store
            }
        return ViewModelProvider.create(owner, viewModelFactory { addInitializer(type) { build(handle) } })[type]
    }
}

/** A [Recreatable] viewmodel built by [build]; see there. */
inline fun <reified VM : ViewModel> recreatable(noinline build: (SavedStateHandle) -> VM): Recreatable<VM> =
    Recreatable(VM::class, build)

/**
 * What a process death leaves of this handle: its values, and nothing they shared with the process.
 * Android writes them into a parcel and reads them back, so each must be a value a parcel carries;
 * a viewmodel saves strings ([dev.npn.app.ui.common.SavedUiState]) and plain values, nothing else.
 */
fun SavedStateHandle.afterProcessDeath(): SavedStateHandle =
    SavedStateHandle(
        keys().associateWith { key ->
            get<Any?>(key).also { value ->
                val type = value?.let { it::class }
                check(type == null || type in Parcelable) {
                    "'$key' holds a ${type?.simpleName}, which a restored process would not get back"
                }
            }
        },
    )

private val Parcelable =
    setOf(String::class, Boolean::class, Int::class, Long::class, Float::class, Double::class)

/**
 * What a restored process legitimately loses, screen by screen, and nothing else: work that was in
 * flight dies with the process, messages not yet shown go with it, and what only memory held before
 * (a result fetched on demand) is not there to show. Each entry clears it on both sides of
 * [Recreatable.recreate]. Keep it short: an open dialog, typed text, a selection, a chosen pane or a
 * review under way are never transient.
 */
internal object Transient {
    /** A rescan under way; and the dismissed scan report, which goes with the report ingest kept in memory. */
    fun library(state: LibraryContentState): LibraryContentState =
        state.copy(ui = state.ui.copy(refreshing = false, dismissedScanReport = null))

    /** The models listed and the self-test's verdicts: results fetched on demand, which the panes ask for again. */
    fun settings(state: SettingsPaneState): SettingsPaneState =
        state.copy(aiModels = AiModelListState(), aiSelfTest = AiSelfTestState())

    /**
     * AI edit's phase past the setup form: a batch being suggested or applied is work in flight, a
     * Done or Queued page reports work finished, and a Review's suggestions lived only in memory (the
     * one loss here that is not legitimate: the suggestions were paid for; see the report).
     */
    fun aiEdit(state: AiEditUiState): AiEditUiState = state.copy(phase = EditPhase.Idle)

    /** A bake, a re-index or a split under way. */
    fun document(state: DetailUiState): DetailUiState =
        state.copy(baking = false, reindexing = false, splitting = false)

    /**
     * The history, read on demand once its tab shows, which it does again in a restored process; and
     * the AI suggestions shown, which lived only in memory (not even what they cost survives them).
     */
    fun document(state: MetadataUiState): MetadataUiState =
        state.copy(
            history = HistoryUiState.NotLoaded,
            ai = state.ai.copy(tags = AiTagState(), metadata = AiMetadataState()),
        )

    /**
     * An answer streaming in; and a conversation nobody asked anything in but the open one, which the
     * store keeps only once asked in (so it is untitled until then).
     */
    fun chat(state: AiChatUiState): AiChatUiState =
        state.copy(
            busy = false,
            sessions = state.sessions.filter { it.title != null || it.id == state.currentSessionId },
        )
}
