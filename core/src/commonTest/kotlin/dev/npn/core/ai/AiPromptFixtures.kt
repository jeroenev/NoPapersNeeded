package dev.npn.core.ai

import dev.npn.core.util.EpochClock
import kotlin.time.Instant

// A prompt's date must be assertable, so every context the prompt tests use is built on a fixed clock.

private val FIXED_CLOCK = EpochClock { Instant.parse("2026-09-07T10:15:00Z").toEpochMilliseconds() }

/** The prompt context of an app set to [languageTag], on the fixed clock. */
internal fun promptContext(languageTag: String?) = AiPromptContext.of(languageTag, FIXED_CLOCK)

/** The prompt context of an app set to Dutch. */
internal val dutch = promptContext("nl")
