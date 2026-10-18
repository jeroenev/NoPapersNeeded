package dev.npn.core.ai

import dev.npn.core.db.TempIndexDb
import dev.npn.core.model.FieldKeys
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.string.shouldContain
import kotlin.test.AfterTest
import kotlin.test.Test

/** The assistant's system prompt names every tool the model is advertised, by its advertised name. */
class ChatPromptToolNamesTest {
    private val temp = TempIndexDb("npn-ai-prompt-tools-test")

    @AfterTest
    fun teardown() = temp.close()

    @Test
    fun chatPromptNamesEveryAdvertisedTool() {
        val fields = FieldKeys.seedDefinitions()
        val prompt = AiPrompts.chatSystem(AiPromptContext("Dutch (nl)", "2026-01-15"), fields)
        val names = LibraryAiTools.toolSetFor(temp.db, fields).tools.map { it.name }
        names.shouldNotBeEmpty()
        for (name in names) prompt shouldContain name
    }
}
