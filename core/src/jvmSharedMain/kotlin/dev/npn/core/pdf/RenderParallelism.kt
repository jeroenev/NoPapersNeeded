package dev.npn.core.pdf

/**
 * How many pages a device renders at once ([RenderGate]): one per CPU but one, which stays free for
 * the UI, and never more than [max], since every render holds a page bitmap. Each platform picks
 * its own [max].
 */
fun renderParallelism(max: Int): Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, max)
