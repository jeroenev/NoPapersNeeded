package dev.npn.app.ui.settings

import dev.npn.app.english
import dev.npn.core.pathrules.StoragePathError
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class StoragePathCopyTest {
    @Test
    fun everyStoragePathErrorHasCopy() =
        runTest {
            val errors =
                listOf(
                    StoragePathError.UnclosedPlaceholder(3),
                    StoragePathError.UnexpectedClosingBrace(3),
                    StoragePathError.UnknownVariable("author"),
                    StoragePathError.MissingFieldKey,
                    StoragePathError.NotADateField("amount"),
                    StoragePathError.FormatNotAllowed("tags"),
                    StoragePathError.Blank,
                    StoragePathError.Absolute,
                    StoragePathError.Backslash,
                    StoragePathError.Traversal,
                    StoragePathError.ReservedDirectory(".npn"),
                    StoragePathError.EdgeWhitespace,
                    StoragePathError.InvalidSegment("a:b"),
                )
            errors.map { it.text().english() } shouldContainExactly
                listOf(
                    "Unclosed placeholder starting at position 3",
                    "Unexpected closing brace at position 3",
                    "Unknown template variable author",
                    "{field} needs a field key, for example {field:amount}",
                    "amount is not a date field, so it takes no date format",
                    "{tags} does not take a format",
                    "Path must not be blank",
                    "Absolute paths are not allowed",
                    "Backslashes are not allowed",
                    "Path traversal is not allowed",
                    "The .npn directory is reserved",
                    "Path segments may not start or end with whitespace",
                    "Invalid filename segment a:b",
                )
        }
}
