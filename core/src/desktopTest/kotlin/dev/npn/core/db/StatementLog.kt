package dev.npn.core.db

import androidx.room3.Room
import androidx.room3.useReaderConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File

/**
 * A driver that records the SQL of every statement it runs, once per execution, so a test can
 * check what a call actually executed rather than what the DAO source suggests. Room caches
 * prepared statements, so recording happens on the first step after a prepare or reset.
 */
class StatementLog(
    private val delegate: SQLiteDriver = BundledSQLiteDriver(),
) : SQLiteDriver by delegate {
    private val executed = ArrayList<String>()

    /** Every statement executed since the last [clear], in order. */
    val statements: List<String> get() = synchronized(executed) { executed.toList() }

    fun clear() = synchronized(executed) { executed.clear() }

    override fun open(fileName: String): SQLiteConnection = Connection(delegate.open(fileName))

    private inner class Connection(
        private val connection: SQLiteConnection,
    ) : SQLiteConnection by connection {
        override fun prepare(sql: String): SQLiteStatement = Statement(sql, connection.prepare(sql))
    }

    private inner class Statement(
        private val sql: String,
        private val statement: SQLiteStatement,
    ) : SQLiteStatement by statement {
        private var started = false

        override fun step(): Boolean {
            if (!started) {
                started = true
                synchronized(executed) { executed += sql }
            }
            return statement.step()
        }

        override fun reset() {
            started = false
            statement.reset()
        }
    }
}

/** An index database at [file] whose every executed statement lands in [log]. */
fun recordingDatabase(
    file: File,
    log: StatementLog,
): NpnDatabase {
    file.parentFile?.mkdirs()
    return Room
        .databaseBuilder<NpnDatabase>(name = file.absolutePath)
        .setDriver(log)
        .build()
}

/** The `EXPLAIN QUERY PLAN` detail lines for [sql]; its parameters may stay unbound. */
suspend fun NpnDatabase.queryPlan(sql: String): List<String> =
    useReaderConnection { connection ->
        connection.usePrepared("EXPLAIN QUERY PLAN $sql") { statement ->
            buildList { while (statement.step()) add(statement.getText(PLAN_DETAIL_COLUMN)) }
        }
    }

/**
 * Whether a plan line reads every row of the FTS5 table [table]. A virtual table always shows as
 * SCAN; FTS5 then names the constraint it uses (`=` for a rowid, `M…` for MATCH), and an empty one
 * means a pass over the whole index.
 */
fun isFullFtsScan(
    line: String,
    table: String = "fts_documents",
): Boolean = Regex("""SCAN $table VIRTUAL TABLE INDEX \d+:$""").containsMatchIn(line)

/** Whether a plan line walks every row of the ordinary table [table], directly or through an index. */
fun isFullTableScan(
    line: String,
    table: String,
): Boolean = Regex("""^SCAN $table\b""").containsMatchIn(line)

private const val PLAN_DETAIL_COLUMN = 3
