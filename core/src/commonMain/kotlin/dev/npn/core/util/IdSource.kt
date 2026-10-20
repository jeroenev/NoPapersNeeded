package dev.npn.core.util

import kotlin.uuid.Uuid

/**
 * Where the session's fresh names come from: journal actor ids, staging, import and recovery file
 * names, device ids. Production takes [IdSource.Random]; a test that replays a run passes a seeded
 * one, so the same steps make the same names, and the same bytes and the same order of a listing.
 */
fun interface IdSource {
    /** A name no other call returns, as a random UUID's text is. */
    fun next(): String

    companion object {
        val Random: IdSource = IdSource { Uuid.random().toString() }
    }
}
