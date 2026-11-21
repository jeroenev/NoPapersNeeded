package dev.npn.core.util

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test

/** What a newer build wrote that this one would drop, told apart from unused defaults and retired keys. */
class UnknownJsonDataTest {
    private fun json(text: String): JsonElement = NpnJson.Persisted.parseToJsonElement(text)

    private fun carries(
        written: String,
        read: String,
        retired: Set<String> = emptySet(),
    ) = carriesUnknownData(json(written), json(read), retired)

    @Test
    fun aKeyThisBuildDoesNotReadCountsOnceItHoldsSomething() {
        carries("""{"a":1,"b":"x"}""", """{"a":1}""").shouldBeTrue()
        carries("""{"a":1,"b":7}""", """{"a":1}""").shouldBeTrue()
        carries("""{"a":1,"b":true}""", """{"a":1}""").shouldBeTrue()
        carries("""{"a":1,"b":["x"]}""", """{"a":1}""").shouldBeTrue()
    }

    @Test
    fun anUnusedDefaultDoesNotCount() {
        for (empty in listOf("null", "false", "\"\"", "[]", "{}")) {
            carries("""{"a":1,"b":$empty}""", """{"a":1}""").shouldBeFalse()
        }
    }

    @Test
    fun aRetiredKeyNeverCounts() {
        carries("""{"a":1,"old":"kept once"}""", """{"a":1}""", retired = setOf("old")).shouldBeFalse()
    }

    @Test
    fun keysAreComparedAtEveryDepth() {
        carries("""{"f":{"x":1,"new":"y"}}""", """{"f":{"x":1}}""").shouldBeTrue()
        carries("""{"l":[{"x":1},{"x":2,"new":"y"}]}""", """{"l":[{"x":1},{"x":2}]}""").shouldBeTrue()
        // Values may differ (a legacy spelling read anew); only what is missing counts.
        carries("""{"f":{"x":"OLD"}}""", """{"f":{"x":"new"}}""").shouldBeFalse()
    }
}
