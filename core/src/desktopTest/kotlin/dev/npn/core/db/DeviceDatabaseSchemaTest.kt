package dev.npn.core.db

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * `device.db` is never dropped (see [DeviceDatabase]), so a schema a build shipped must never
 * change and every later version must migrate from it. This pins what shipped, and opens every
 * shipped schema through the production builder, so a changed schema without a version bump, or a
 * new version without a migration, fails here rather than on a user's device.
 */
class DeviceDatabaseSchemaTest {
    private val dir: File = Files.createTempDirectory("npn-device-schema").toFile()

    @AfterTest
    fun teardown() {
        dir.deleteRecursively()
    }

    @Test
    fun everyExportedSchemaIsTheOneThatShipped() {
        val exported = schemaFiles()
        exported.keys shouldBe SHIPPED.keys
        exported.forEach { (version, file) -> identityHash(file) shouldBe SHIPPED.getValue(version) }
    }

    @Test
    fun everyShippedVersionOpensThroughTheProductionBuilder() =
        runTest {
            for (version in SHIPPED.keys) {
                val file = File(dir, "device-$version.db")
                createFromSchema(file, version, database = DATABASE) {}
                val db = deviceDatabase(file)
                try {
                    db.ocrQueueDao().nextPending(now = 0) shouldBe null
                } finally {
                    db.close()
                }
            }
        }

    private fun schemaFiles(): Map<Int, File> =
        File("schemas/$DATABASE")
            .listFiles { file -> file.extension == "json" }
            .orEmpty()
            .associateBy { it.nameWithoutExtension.toInt() }

    private fun identityHash(file: File): String =
        Json
            .parseToJsonElement(file.readText())
            .jsonObject
            .getValue("database")
            .jsonObject
            .getValue("identityHash")
            .jsonPrimitive.content

    private companion object {
        const val DATABASE = "dev.npn.core.db.DeviceDatabase"

        /**
         * Every `device.db` version a build shipped, with the identity hash Room exported for it.
         * Append only: never edit or remove an entry. A schema change raises the version, adds a
         * real migration and adds its line here.
         */
        val SHIPPED: Map<Int, String> =
            mapOf(
                1 to "c682552d5e6662738f8589c47bbd5f32",
                2 to "2de9d041f774d090fff7fd96af3375f6",
                3 to "c77405c86e76ba0a623e2093be92eef8",
            )
    }
}
