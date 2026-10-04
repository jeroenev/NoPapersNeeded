package dev.npn.core.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Create [file] as Room's exported schema [version] of [database] describes it, then let [seed]
 * fill it: the database an older build left behind, for testing what a migration makes of it.
 */
fun createFromSchema(
    file: File,
    version: Int,
    database: String = "dev.npn.core.db.NpnDatabase",
    seed: (SQLiteConnection) -> Unit,
) {
    val schema =
        Json
            .parseToJsonElement(File("schemas/$database/$version.json").readText())
            .jsonObject
            .getValue("database")
            .jsonObject
    file.parentFile?.mkdirs()
    val connection = BundledSQLiteDriver().open(file.absolutePath)
    try {
        for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
            val table = entity.getValue("tableName").jsonPrimitive.content

            fun create(sql: String) = connection.execSQL(sql.replace("\${TABLE_NAME}", table))
            create(entity.getValue("createSql").jsonPrimitive.content)
            entity["indices"]?.jsonArray?.forEach { index ->
                create(
                    index.jsonObject
                        .getValue("createSql")
                        .jsonPrimitive.content,
                )
            }
        }
        schema.getValue("setupQueries").jsonArray.forEach { connection.execSQL(it.jsonPrimitive.content) }
        connection.execSQL("PRAGMA user_version = $version")
        seed(connection)
    } finally {
        connection.close()
    }
}

/** Run [sql] with [args] bound in order: strings as text, numbers and booleans as integers. */
fun SQLiteConnection.exec(
    sql: String,
    vararg args: Any?,
) {
    prepare(sql).use { statement ->
        args.forEachIndexed { i, arg ->
            when (arg) {
                null -> statement.bindNull(i + 1)
                is String -> statement.bindText(i + 1, arg)
                is Boolean -> statement.bindLong(i + 1, if (arg) 1 else 0)
                is Number -> statement.bindLong(i + 1, arg.toLong())
                else -> error("Cannot bind $arg")
            }
        }
        statement.step()
    }
}
