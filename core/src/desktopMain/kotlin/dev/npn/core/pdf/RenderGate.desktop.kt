package dev.npn.core.pdf

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual fun renderDispatcher(parallelism: Int): CoroutineDispatcher =
    Dispatchers.Default.limitedParallelism(parallelism.coerceAtLeast(1))

actual fun defaultRenderParallelism(): Int =
    (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
