package dev.npn.core.sync

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test

/**
 * The setup state machine over a recording fake [RcloneRpc]: which method and JSON each step sends,
 * how a conversation is remembered between `begin` and `answer`, and how transport failures become
 * [SetupStep.Failed] instead of exceptions. Field-level parsing lives in [RcloneSetupWireTest].
 */
class RcloneCloudSetupTest {
    @Test
    fun providersAsksForTheCatalogAndParsesIt() =
        runTest {
            val (setup, rpc) = cloudSetup { driveProviderResponse() }

            val providers = setup.providers()

            rpc.calls.single().method shouldBe "config/providers"
            providers.single().type shouldBe "drive"
            providers.single().name shouldBe "Google Drive"
            providers.single().browserSignIn shouldBe true
            providers.single().options.shouldBeEmpty()
        }

    @Test
    fun beginAsksTheFirstQuestionWithTheStartRequest() =
        runTest {
            val (setup, rpc) =
                cloudSetup { createResponse(state = "state-1", option = requiredOption("client_id")) }

            val question =
                setup.begin("gdrive", "drive", mapOf("client_id" to "abc")).shouldBeInstanceOf<SetupStep.Question>()

            question.remoteName shouldBe "gdrive"
            question.state shouldBe "state-1"
            question.option.name shouldBe "client_id"
            // A sign-in left behind by an earlier attempt is stopped first; rclone opens no browser.
            rpc.calls.map { it.method } shouldContainExactly listOf("config/oauthstop", "config/create")
            rpc.calls.last().input shouldBe
                buildJsonObject {
                    put("name", "gdrive")
                    put("type", "drive")
                    putJsonObject("parameters") {
                        put("client_id", "abc")
                        put("config_auth_no_browser", "true")
                    }
                    putJsonObject("opt") {
                        put("nonInteractive", true)
                        put("obscure", true)
                        put("all", false)
                        put("continue", false)
                    }
                }
        }

    @Test
    fun answerContinuesWithTheRememberedTypeAndParameters() =
        runTest {
            val (setup, rpc) =
                cloudSetup { call ->
                    if (call.input.continues()) {
                        createResponse(state = "")
                    } else {
                        createResponse(state = "state-1", option = requiredOption("client_id"))
                    }
                }
            setup.begin("gdrive", "drive", mapOf("client_id" to "abc"))

            setup.answer("gdrive", "state-1", "the-answer") shouldBe SetupStep.Done("gdrive")

            rpc.calls.last().method shouldBe "config/create"
            rpc.calls.last().input shouldBe
                buildJsonObject {
                    put("name", "gdrive")
                    put("type", "drive")
                    putJsonObject("parameters") {
                        put("client_id", "abc")
                        put("config_auth_no_browser", "true")
                    }
                    putJsonObject("opt") {
                        put("nonInteractive", true)
                        put("obscure", true)
                        put("all", false)
                        put("continue", true)
                        put("state", "state-1")
                        put("result", "the-answer")
                    }
                }
        }

    @Test
    fun answerWithoutABegunSessionFailsWithoutCallingRclone() =
        runTest {
            val (setup, rpc) = cloudSetup()

            setup.answer("gdrive", "state-1", "the-answer").shouldBeInstanceOf<SetupStep.Failed>()

            rpc.calls.shouldBeEmpty()
        }

    @Test
    fun anEmptyStateIsDone() =
        runTest {
            val (setup, _) = cloudSetup { createResponse(state = "") }

            setup.begin("gdrive", "drive", emptyMap()) shouldBe SetupStep.Done("gdrive")
        }

    @Test
    fun aTransportFailureBecomesAFailedStep() =
        runTest {
            val (setup, _) = cloudSetup { throw RcloneException("engine unreachable", status = null) }

            setup.begin("gdrive", "drive", emptyMap()) shouldBe
                SetupStep.Failed(SetupFailure.ENGINE, "engine unreachable")
        }

    @Test
    fun anAnswerWithoutABegunSessionSaysSo() =
        runTest {
            val (setup, _) = cloudSetup()

            setup.answer("gdrive", "state-1", "x") shouldBe SetupStep.Failed(SetupFailure.NOT_IN_PROGRESS)
        }

    @Test
    fun oauthStatusIsPolledAndMapped() =
        runTest {
            val (setup, rpc) =
                cloudSetup {
                    buildJsonObject {
                        put("status", "running")
                        put("authUrl", "http://127.0.0.1:53682/auth")
                    }
                }

            setup.oauthStatus() shouldBe OAuthStatus(running = true, authUrl = "http://127.0.0.1:53682/auth")
            rpc.calls.single().method shouldBe "config/oauthstatus"
        }

    @Test
    fun oauthStatusIsNullWhenTheEngineCannotAnswer() =
        runTest {
            val (setup, _) = cloudSetup { throw RcloneException("engine unreachable", status = null) }

            setup.oauthStatus().shouldBeNull()
        }

    @Test
    fun stopOauthSwallowsAFinishedSignIn() =
        runTest {
            val (setup, rpc) = cloudSetup { throw RcloneException("no OAuth in progress", status = 500) }

            setup.stopOauth()

            rpc.calls.single().method shouldBe "config/oauthstop"
        }

    @Test
    fun foldersListsDirectoriesUnderTheBrowsedPath() =
        runTest {
            val (setup, rpc) = cloudSetup { folderListingResponse() }

            setup.folders("gdrive:lib", "root") shouldContainExactly listOf("Archive", "Photos")

            rpc.calls.single().method shouldBe "operations/list"
            rpc.calls.single().input shouldBe
                buildJsonObject {
                    put("fs", "gdrive:lib")
                    put("remote", "root")
                    putJsonObject("opt") {
                        put("filesOnly", false)
                        put("dirsOnly", true)
                        put("noModTime", true)
                        put("noMimeType", true)
                    }
                }
        }

    @Test
    fun folderContentsTellsALibraryFromOtherFilesAndAnEmptyFolder() =
        runTest {
            var listing: JsonObject = buildJsonObject { putJsonArray("list") {} }
            val (setup, rpc) = cloudSetup { listing }

            setup.folderContents("gdrive:", "lib") shouldBe FolderContents.EMPTY
            rpc.calls.single().method shouldBe "operations/list"

            listing = buildJsonObject { putJsonArray("list") { add(entry("lib/.npn", dir = true)) } }
            setup.folderContents("gdrive:", "lib") shouldBe FolderContents.LIBRARY

            listing = folderListingResponse()
            setup.folderContents("gdrive:", "lib") shouldBe FolderContents.OTHER_FILES
        }

    @Test
    fun deleteRemoteForgetsItsConfiguration() =
        runTest {
            val (setup, rpc) = cloudSetup()

            setup.deleteRemote("drive-3fa9c1")

            rpc.calls.single().method shouldBe "config/delete"
            rpc.calls.single().input shouldBe buildJsonObject { put("name", "drive-3fa9c1") }
        }

    @Test
    fun makeFolderCreatesTheGivenPath() =
        runTest {
            val (setup, rpc) = cloudSetup()

            setup.makeFolder("gdrive:lib", "no-papers-needed")

            rpc.calls.single().method shouldBe "operations/mkdir"
            rpc.calls.single().input shouldBe
                buildJsonObject {
                    put("fs", "gdrive:lib")
                    put("remote", "no-papers-needed")
                }
        }

    private fun cloudSetup(
        respond: (FakeRpc.Call) -> JsonObject = { JsonObject(emptyMap()) },
    ): Pair<RcloneCloudSetup, FakeRpc> {
        val rpc = FakeRpc()
        rpc.respond = respond
        return RcloneCloudSetup { rpc } to rpc
    }

    private fun createResponse(
        state: String,
        option: JsonObject? = null,
    ): JsonObject =
        buildJsonObject {
            put("State", state)
            if (option != null) put("Option", option)
            put("Error", "")
        }

    private fun requiredOption(name: String): JsonObject =
        buildJsonObject {
            put("Name", name)
            put("Help", "$name help")
            put("Type", "string")
            put("Required", true)
        }

    private fun driveProviderResponse(): JsonObject {
        val options = buildJsonArray { add(requiredOption("client_id")) }
        return buildJsonObject {
            putJsonArray("providers") {
                add(
                    buildJsonObject {
                        put("Name", "drive")
                        put("Description", "Google Drive")
                        put("Options", options)
                    },
                )
            }
        }
    }

    private fun folderListingResponse(): JsonObject =
        buildJsonObject {
            putJsonArray("list") {
                add(entry("root/Photos", dir = true))
                add(entry("root/readme.txt", dir = false))
                add(entry("root/Archive", dir = true))
            }
        }

    private fun entry(
        path: String,
        dir: Boolean,
    ): JsonObject =
        buildJsonObject {
            put("Path", path)
            put("Name", path.substringAfterLast('/'))
            put("IsDir", dir)
        }

    private fun JsonObject.continues(): Boolean =
        ((this["opt"] as? JsonObject)?.get("continue")?.jsonPrimitive?.boolean) ?: false

    /** A recording [RcloneRpc] whose [respond] is the test's answer for every call. */
    private class FakeRpc : RcloneRpc {
        val calls = mutableListOf<Call>()
        var respond: (Call) -> JsonObject = { JsonObject(emptyMap()) }

        override suspend fun call(
            method: String,
            input: JsonObject,
        ): JsonObject {
            val call = Call(method, input)
            calls += call
            return respond(call)
        }

        override fun close() = Unit

        data class Call(
            val method: String,
            val input: JsonObject,
        )
    }
}
