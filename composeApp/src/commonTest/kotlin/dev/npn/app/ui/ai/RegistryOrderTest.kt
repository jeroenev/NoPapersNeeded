package dev.npn.app.ui.ai

import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldType
import io.kotest.matchers.collections.shouldContainExactly
import kotlin.test.Test

class RegistryOrderTest {
    private fun def(
        key: String,
        position: Int,
    ) = FieldDef(key, key, FieldType.TEXT, showByDefault = false, aiEnabled = true, position = position)

    @Test
    fun keysFollowTheRegistryAndUnlistedKeysComeLast() {
        val fields = listOf(def("title", 0), def("amount", 1))

        listOf("gone", "amount", "title").inRegistryOrder(fields) shouldContainExactly listOf("title", "amount", "gone")
    }
}
