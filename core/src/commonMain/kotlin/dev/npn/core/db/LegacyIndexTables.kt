package dev.npn.core.db

import androidx.room3.PooledConnection
import androidx.room3.executeSQL
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection

/**
 * Tables older index schemas held that the current one does not: data the index used to keep but
 * cannot rebuild. A migration leaves them in place (see [NpnDatabase.MIGRATIONS]), since it sees
 * only this database; the session moves their rows to where that data lives now, then drops them.
 * Read as plain SQL, because they are no longer part of the schema Room knows.
 */
internal object LegacyIndexTables {
    const val SAVED_VIEWS = "saved_views"

    /** The serialized views the index still holds, or null once their table is gone. */
    suspend fun savedViews(db: NpnDatabase): List<String>? =
        db.useReaderConnection { connection ->
            if (!connection.hasTable(SAVED_VIEWS)) return@useReaderConnection null
            connection.usePrepared("SELECT json FROM $SAVED_VIEWS") { statement ->
                buildList { while (statement.step()) add(statement.getText(0)) }
            }
        }

    /** Drop [table], whose rows now live elsewhere. */
    suspend fun drop(
        db: NpnDatabase,
        table: String,
    ) = db.useWriterConnection { it.executeSQL("DROP TABLE IF EXISTS $table") }

    private suspend fun PooledConnection.hasTable(name: String): Boolean =
        usePrepared("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?") { statement ->
            statement.bindText(1, name)
            statement.step()
        }
}
