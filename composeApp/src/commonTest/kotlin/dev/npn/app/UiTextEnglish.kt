package dev.npn.app

import dev.npn.app.ui.common.TextSpec
import dev.npn.app.ui.common.UiMessage
import dev.npn.app.ui.common.UiText
import dev.npn.app.ui.common.spec
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_join_items
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString

/**
 * Render a [UiText] the way the app does, but outside a composition.
 *
 * Reads the same `values/common.xml` the UI reads, through the same [spec] table, so a test can
 * assert on real user-facing wording without a second hand-maintained copy of it.
 *
 * English is not requested explicitly — `ResourceEnvironment`'s constructor is library-internal, so
 * the locale comes from the JVM default. `composeApp/build.gradle.kts` pins the test JVM to English
 * for exactly this reason; without that, this resolves `values-nl` on a Dutch machine.
 *
 * That pin only applies to the Gradle test task. Running these tests straight from the IDE forks a
 * JVM Gradle does not configure, so on a Dutch machine they will fail on wording. Run them via
 * `./gradlew :composeApp:desktopTest`, or delegate IDE test runs to Gradle.
 */
suspend fun UiText.resolveEnglish(): String = spec().renderEnglish()

suspend fun UiMessage.resolveEnglish(): String = text.resolveEnglish()

private suspend fun TextSpec.renderEnglish(): String =
    when (this) {
        is TextSpec.Literal -> {
            value
        }

        is TextSpec.Str -> {
            getString(res, *args.renderAllEnglish())
        }

        is TextSpec.Plural -> {
            getPluralString(res, quantity, *args.renderAllEnglish())
        }

        is TextSpec.Join -> {
            parts.drop(1).fold(parts.first().renderEnglish()) { summary, part ->
                getString(Res.string.common_join_items, summary, part.renderEnglish())
            }
        }
    }

private suspend fun List<Any?>.renderAllEnglish(): Array<Any> =
    map { if (it is TextSpec) it.renderEnglish() else it ?: "" }.toTypedArray()
