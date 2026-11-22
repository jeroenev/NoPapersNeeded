package dev.npn.app

import dev.npn.app.ui.palette.CommandPaletteViewModel
import dev.npn.app.ui.palette.PaletteCommand
import dev.npn.app.ui.palette.PaletteCommandId
import dev.npn.app.ui.palette.PaletteGroupKind
import dev.npn.app.ui.palette.PaletteNavigation
import dev.npn.app.ui.palette.PaletteRequest
import dev.npn.app.ui.palette.PaletteSettingsEntry
import dev.npn.app.ui.palette.PaletteTarget
import dev.npn.app.ui.settings.SettingsCategoryId
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySort
import dev.npn.core.app.SavedView
import dev.npn.core.app.SortField
import dev.npn.core.app.TagInfo
import dev.npn.core.app.ViewType
import dev.npn.core.testing.doc
import dev.npn.core.testing.listItem
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class CommandPaletteViewModelTest : MainDispatcherTest() {
    private class FakePaletteNavigation : PaletteNavigation {
        val documents = mutableListOf<String>()
        val filters = mutableListOf<LibraryFilter>()
        val views = mutableListOf<SavedView>()
        var reviewQueues = 0
        var tags = 0
        val settings = mutableListOf<Pair<SettingsCategoryId?, Boolean>>()
        var assistant = 0

        override fun openDocument(docId: String) {
            documents += docId
        }

        override fun openLibrary(filter: LibraryFilter) {
            filters += filter
        }

        override fun openSavedView(view: SavedView) {
            views += view
        }

        override fun openReviewQueue() {
            reviewQueues++
        }

        override fun openTags() {
            tags++
        }

        override fun openSettings(
            category: SettingsCategoryId?,
            revealAdvanced: Boolean,
        ) {
            settings += category to revealAdvanced
        }

        override fun openAssistant() {
            assistant++
        }
    }

    private fun TestScope.vm(
        session: FakeLibrarySession = FakeLibrarySession(),
        navigation: FakePaletteNavigation = FakePaletteNavigation(),
        settings: FakeSettingsRepository = FakeSettingsRepository(),
    ): CommandPaletteViewModel =
        CommandPaletteViewModel(session, settings, navigation).also { collecting(it.state) }

    private fun CommandPaletteViewModel.syncCommand() =
        setSources(listOf(PaletteCommand(PaletteCommandId.SYNC_NOW, "Sync now")), emptyList())

    @Test
    fun aSyncChoiceRunsTheSyncFacade() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session)
            model.syncCommand()
            model.open()
            advanceUntilIdle()

            model.run(model.state.value.items.single())
            advanceUntilIdle()

            session.sync.manualSyncs shouldBe 1
        }

    @Test
    fun aTagOpensTheLibraryFilteredToIt() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    tags.tags.value = listOf(TagInfo("invoice", null, null, 2))
                }
            val navigation = FakePaletteNavigation()
            val model = vm(session, navigation)
            model.open()
            advanceUntilIdle()

            model.run(model.state.value.items.single { it.key == "tag:invoice" })
            advanceUntilIdle()

            navigation.filters.single().tagsAll shouldContainExactly listOf("invoice")
        }

    @Test
    fun aSavedViewIsHandedToTheLibrary() =
        runTest(dispatcher) {
            val view =
                SavedView(
                    id = "v1",
                    name = "Invoices",
                    filter = LibraryFilter(tagsAll = listOf("invoice")),
                    sort = LibrarySort(SortField.Name),
                    viewType = ViewType.LIST,
                )
            val session = FakeLibrarySession().apply { savedViews.views.value = listOf(view) }
            val navigation = FakePaletteNavigation()
            val model = vm(session, navigation)
            model.open()
            advanceUntilIdle()

            model.run(model.state.value.items.single { it.key == "view:v1" })
            advanceUntilIdle()

            navigation.views.single() shouldBe view
        }

    @Test
    fun aDocumentResultOpensTheDocument() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            session.documents.documents.value = listOf(listItem(doc("doc-1", title = "Tax invoice")))
            val navigation = FakePaletteNavigation()
            val model = vm(session, navigation)
            model.open()
            model.onQueryChange("tax")
            advanceUntilIdle()

            model.run(model.state.value.items.single { it.key == "doc:doc-1" })
            advanceUntilIdle()

            navigation.documents shouldContainExactly listOf("doc-1")
        }

    @Test
    fun aSettingsMatchOpensThePaneWithItsAdvancedSection() =
        runTest(dispatcher) {
            val navigation = FakePaletteNavigation()
            val model = vm(navigation = navigation)
            model.setSources(
                commands = emptyList(),
                settings =
                    listOf(
                        PaletteSettingsEntry(
                            id = SettingsCategoryId.AI,
                            title = "Assistant",
                            keywords = listOf("API key"),
                            advancedKeywords = listOf("API key"),
                        ),
                    ),
            )
            model.open()
            model.onQueryChange("api")
            advanceUntilIdle()

            val item = model.state.value.items.single()
            item.target shouldBe PaletteTarget.Settings(SettingsCategoryId.AI, revealAdvanced = true)
            item.subtitle shouldBe "API key"
            model.run(item)
            advanceUntilIdle()

            navigation.settings shouldContainExactly listOf((SettingsCategoryId.AI to true))
        }

    @Test
    fun keyboardSelectionMovesAndRunsTheSelectedResult() =
        runTest(dispatcher) {
            val navigation = FakePaletteNavigation()
            val model = vm(navigation = navigation)
            model.setSources(
                commands =
                    listOf(
                        PaletteCommand(PaletteCommandId.TAGS, "Go to Tags"),
                        PaletteCommand(PaletteCommandId.SETTINGS, "Go to Settings"),
                    ),
                settings = emptyList(),
            )
            model.open()
            advanceUntilIdle()

            model.state.value.selected shouldBe 0
            model.moveSelection(1)
            advanceUntilIdle()
            model.state.value.selected shouldBe 1
            model.runSelected()
            advanceUntilIdle()

            navigation.settings shouldContainExactly listOf((null to false))

            model.open()
            advanceUntilIdle()
            model.moveSelection(9)
            advanceUntilIdle()
            model.state.value.selected shouldBe 1
            model.moveSelection(-9)
            advanceUntilIdle()
            model.state.value.selected shouldBe 0
        }

    @Test
    fun anEmptyQueryLeadsWithTheMostRecentChoice() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    tags.tags.value =
                        listOf(
                            TagInfo("invoice", null, null, 2),
                            TagInfo("travel", null, null, 1),
                        )
                }
            val model = vm(session)
            model.open()
            advanceUntilIdle()
            model.run(model.state.value.items.single { it.key == "tag:travel" })
            advanceUntilIdle()

            model.open()
            advanceUntilIdle()
            model.state.value.groups.first().kind shouldBe PaletteGroupKind.RECENT
            model.state.value.groups.first().items.first().key shouldBe "tag:travel"

            model.run(model.state.value.items.single { it.key == "tag:invoice" })
            advanceUntilIdle()
            model.open()
            advanceUntilIdle()
            model.state.value.groups.first().items.map { it.key } shouldContainExactly
                listOf("tag:invoice", "tag:travel")
        }

    @Test
    fun togglingOpensAndClosesTheOverlay() =
        runTest(dispatcher) {
            val model = vm()
            model.toggle()
            advanceUntilIdle()
            model.state.value.open shouldBe true
            model.toggle()
            advanceUntilIdle()
            model.state.value.open shouldBe false
        }

    @Test
    fun aRecentSettingsPaneLeadsTheEmptyQuery() =
        runTest(dispatcher) {
            val model = vm()
            model.setSources(
                commands = emptyList(),
                settings =
                    listOf(
                        PaletteSettingsEntry(SettingsCategoryId.AI, "Assistant"),
                        PaletteSettingsEntry(SettingsCategoryId.APPEARANCE, "Appearance"),
                    ),
            )
            model.open()
            advanceUntilIdle()
            model.run(model.state.value.items.single { it.key == "settings:AI" })
            advanceUntilIdle()

            model.open()
            advanceUntilIdle()
            model.state.value.groups.first().items.first().key shouldBe "settings:AI"
        }

    @Test
    fun aQueryWithNoMatchesShowsNothing() =
        runTest(dispatcher) {
            val model = vm()
            model.open()
            model.onQueryChange("zzzz")
            advanceUntilIdle()

            model.state.value.hasResults shouldBe false
            model.state.value.groups shouldContainExactly emptyList()
        }

    @Test
    fun aGroupIsBoundedEvenWithManyMatches() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    tags.tags.value = (1..12).map { TagInfo("tag$it", null, null, it) }
                }
            val model = vm(session)
            model.open()
            model.onQueryChange("tag")
            advanceUntilIdle()

            val tagGroup = model.state.value.groups.single { it.kind == PaletteGroupKind.TAGS }
            tagGroup.items.size shouldBe 8
        }

    @Test
    fun newTagWritesThroughTheTagFacadeAndShowsTags() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val navigation = FakePaletteNavigation()
            val model = vm(session, navigation)
            model.setSources(
                listOf(PaletteCommand(PaletteCommandId.NEW_TAG, "New tag…")),
                emptyList(),
            )
            model.open()
            advanceUntilIdle()

            model.run(model.state.value.items.single())
            advanceUntilIdle()
            model.state.value.newTagOpen shouldBe true
            model.confirmNewTag(" invoices ")
            advanceUntilIdle()

            session.tagAdmin.tagStyles shouldContainExactly
                listOf(Triple("invoices", null, null))
            navigation.tags shouldBe 1
        }

    @Test
    fun anExistingTagIsNotRestyled() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { tags.tagNames = listOf("invoices") }
            val navigation = FakePaletteNavigation()
            val model = vm(session, navigation)
            model.confirmNewTag("invoices")
            advanceUntilIdle()

            session.tagAdmin.tagStyles shouldContainExactly emptyList()
            navigation.tags shouldBe 1
        }

    @Test
    fun importAsksThePlatformForFiles() =
        runTest(dispatcher) {
            val model = vm()
            val requests = collecting(model.requests)
            model.setSources(
                listOf(PaletteCommand(PaletteCommandId.IMPORT, "Import PDFs…")),
                emptyList(),
            )
            model.open()
            advanceUntilIdle()

            model.run(model.state.value.items.single())
            advanceUntilIdle()

            requests shouldContainExactly listOf(PaletteRequest.PickFiles)
        }
}
