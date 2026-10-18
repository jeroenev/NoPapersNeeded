package dev.npn.app.ui.common

import kotlinx.coroutines.flow.SharingStarted

/**
 * How long a viewModelScope-hosted StateFlow keeps its upstream subscription alive after the last
 * collector disappears. Five seconds survives configuration changes / brief navigation without
 * re-running the upstream, while still tearing down when the screen is truly gone.
 */
const val STATE_FLOW_STOP_TIMEOUT_MILLIS = 5_000L

/** Shared [SharingStarted] policy for viewmodel StateFlows. */
@Suppress("ForbiddenMethodCall") // The one definition the rule points every other flow to.
val WhileSubscribedTimeout: SharingStarted = SharingStarted.WhileSubscribed(STATE_FLOW_STOP_TIMEOUT_MILLIS)
