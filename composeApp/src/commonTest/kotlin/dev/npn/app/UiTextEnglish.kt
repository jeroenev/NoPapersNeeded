@file:OptIn(InternalResourceApi::class)

package dev.npn.app

import dev.npn.app.ui.common.TextSpec
import dev.npn.app.ui.common.UiMessage
import dev.npn.app.ui.common.UiText
import dev.npn.app.ui.common.spec
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_join_items
import org.jetbrains.compose.resources.DensityQualifier
import org.jetbrains.compose.resources.InternalResourceApi
import org.jetbrains.compose.resources.LanguageQualifier
import org.jetbrains.compose.resources.RegionQualifier
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.ThemeQualifier
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString

/**
 * Render a [UiText] the way the app does, but outside a composition.
 *
 * Reads the same `values/common.xml` the UI reads, through the same [spec] table, so a test can
 * assert on real user-facing wording without a second hand-maintained copy of it.
 *
 * English is requested explicitly (see [ENGLISH]), so these assertions hold whatever locale the
 * machine, the IDE or the Gradle test JVM happens to be in.
 */
suspend fun UiText.resolveEnglish(): String = spec().renderEnglish()

suspend fun UiMessage.resolveEnglish(): String = text.resolveEnglish()

private suspend fun TextSpec.renderEnglish(): String =
    when (this) {
        is TextSpec.Literal -> {
            value
        }

        is TextSpec.Str -> {
            getString(ENGLISH, res, *args.renderAllEnglish())
        }

        is TextSpec.Plural -> {
            getPluralString(ENGLISH, res, quantity, *args.renderAllEnglish())
        }

        is TextSpec.Join -> {
            parts.drop(1).fold(parts.first().renderEnglish()) { summary, part ->
                getString(ENGLISH, Res.string.common_join_items, summary, part.renderEnglish())
            }
        }
    }

private suspend fun List<Any?>.renderAllEnglish(): Array<Any> =
    map { if (it is TextSpec) it.renderEnglish() else it ?: "" }.toTypedArray()

/**
 * The `values/` resource environment, named rather than inferred.
 *
 * Compose's environment-less `getString` asks the platform which locale to resolve, and the two
 * test targets answer very differently: the `desktop` JVM reports its default locale, while the
 * Android host JVM calls `Resources.getSystem()`, which `android.jar` stubs out and throws for.
 * Stating the environment settles both, and drops the old requirement that the test JVM be pinned
 * to English.
 *
 * [ResourceEnvironment]'s constructor is Kotlin-`internal` with no public equivalent, so it is
 * reached reflectively — it is public in bytecode, so this needs no `setAccessible`. Contained to
 * this helper, and a Compose upgrade that changes the signature fails here immediately and loudly
 * rather than silently resolving the wrong strings.
 */
private val ENGLISH: ResourceEnvironment by lazy {
    val constructor =
        ResourceEnvironment::class.java.declaredConstructors.singleOrNull()
            ?: error("Compose's ResourceEnvironment no longer has a single constructor; see UiTextEnglish.kt")
    constructor.newInstance(
        LanguageQualifier("en"),
        RegionQualifier("US"),
        ThemeQualifier.LIGHT,
        DensityQualifier.MDPI,
    ) as ResourceEnvironment
}
