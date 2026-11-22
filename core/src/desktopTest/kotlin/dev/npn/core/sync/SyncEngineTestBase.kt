package dev.npn.core.sync

import dev.npn.core.app.AppSessionRig
import dev.npn.core.app.ImportOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * Shared setup for the engine suites: two real sessions over one directory-backed remote, plus the
 * small PDF and import helpers. The tests are split across [SyncEngineTest] (convergence and the
 * document lifecycle) and [SyncEngineGuardTest] (the guards and corruption cases) so neither class
 * grows past the function-count rule.
 */
internal open class SyncEngineTestBase {
    protected fun pdf(seed: String): ByteArray = "%PDF-1.4\n$seed\n%%EOF\n".encodeToByteArray()

    protected fun withPair(
        reportsHashes: Boolean = false,
        block: suspend (SyncPair) -> Unit,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pair = SyncPair.create(scope, reportsHashes)
        try {
            block(pair)
        } finally {
            pair.close()
            scope.cancel()
        }
    }

    /** [withPair] for [members] libraries, for the scenarios that add or lose a replica. */
    protected fun withCluster(
        members: Int = 2,
        reportsHashes: Boolean = false,
        block: suspend (SyncCluster) -> Unit,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cluster = SyncCluster.open(scope, reportsHashes, members)
        try {
            block(cluster)
        } finally {
            cluster.close()
            scope.cancel()
        }
    }

    protected suspend fun SyncPair.importOnA(
        name: String,
        seed: String,
    ): String {
        val outcome = a.session.importPdfBytes(pdf(seed), name)
        return (outcome as ImportOutcome.Imported).docId
    }

    protected suspend fun AppSessionRig.importPdf(
        name: String,
        seed: String,
    ): String {
        val outcome = session.importPdfBytes(pdf(seed), name)
        return (outcome as ImportOutcome.Imported).docId
    }
}
