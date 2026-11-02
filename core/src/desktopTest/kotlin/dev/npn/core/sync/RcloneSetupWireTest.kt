package dev.npn.core.sync

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test

/**
 * The JSON surface of the setup protocol in isolation: requests the wire builds and rclone
 * responses it parses. [RcloneCloudSetupTest] covers the state machine on top; this suite keeps the
 * field-by-field parsing rules — catalog order, form filtering, `Default`/`DefaultStr`, `Path`
 * prefixes — out of that one.
 */
class RcloneSetupWireTest {
    @Test
    fun createRequestStartsAStayOpenConversation() {
        val request = RcloneSetupWire.createRequest("gdrive", "drive", mapOf("client_id" to "abc"))

        request shouldBe
            buildJsonObject {
                put("name", "gdrive")
                put("type", "drive")
                putJsonObject("parameters") { put("client_id", "abc") }
                putJsonObject("opt") {
                    put("nonInteractive", true)
                    put("obscure", true)
                    put("all", false)
                    put("continue", false)
                }
            }
    }

    @Test
    fun createRequestResumesFromTheLastState() {
        val request =
            RcloneSetupWire.createRequest(
                name = "gdrive",
                type = "drive",
                parameters = mapOf("client_id" to "abc"),
                state = "state-7",
                result = "answer",
            )

        request shouldBe
            buildJsonObject {
                put("name", "gdrive")
                put("type", "drive")
                putJsonObject("parameters") { put("client_id", "abc") }
                putJsonObject("opt") {
                    put("nonInteractive", true)
                    put("obscure", true)
                    put("all", false)
                    put("continue", true)
                    put("state", "state-7")
                    put("result", "answer")
                }
            }
    }

    @Test
    fun anEmptyStateMeansTheRemoteIsCreated() {
        val response = createResponse(state = "")

        RcloneSetupWire.parseCreateResult("gdrive", response) shouldBe SetupStep.Done("gdrive")
    }

    @Test
    fun aQuestionCarriesRclonesOptionAndIgnoresABlankError() {
        val response =
            buildJsonObject {
                put("State", "state-1")
                putJsonObject("Option") {
                    put("Name", "client_id")
                    put("Help", "OAuth Client Id")
                    put("Type", "string")
                    put("Required", true)
                }
                put("Error", "")
            }

        val question = RcloneSetupWire.parseCreateResult("gdrive", response).shouldBeInstanceOf<SetupStep.Question>()
        question.remoteName shouldBe "gdrive"
        question.state shouldBe "state-1"
        question.option.name shouldBe "client_id"
        question.option.required shouldBe true
        question.error.shouldBeNull()
    }

    @Test
    fun aQuestionCarriesTheErrorMessageForARejectedAnswer() {
        val response =
            buildJsonObject {
                put("State", "state-2")
                putJsonObject("Option") { put("Name", "root_folder_id") }
                put("Error", "that is not a folder id")
            }

        RcloneSetupWire
            .parseCreateResult("gdrive", response)
            .shouldBeInstanceOf<SetupStep.Question>()
            .error shouldBe "that is not a folder id"
    }

    @Test
    fun aQuestionWithoutAnOptionFails() {
        val response = buildJsonObject { put("State", "state-1") }

        RcloneSetupWire
            .parseCreateResult("gdrive", response)
            .shouldBeInstanceOf<SetupStep.Failed>()
            .reason shouldBe SetupFailure.UNEXPECTED_ANSWER
    }

    @Test
    fun providersKeepCatalogOrderAndDropUnknownBackends() {
        val response =
            providersResponse(
                provider("webdav", "WebDAV", option("url", required = true), option("vendor", advanced = true)),
                provider("madeup", "Made up backend"),
                provider("drive", "Google Drive", option("root_folder_id"), option("team_drive", advanced = true)),
                provider("s3", "Amazon S3"),
            )

        val providers = RcloneSetupWire.parseProviders(response)

        providers.map { it.type } shouldContainExactly listOf("drive", "webdav", "s3")
        providers.map { it.name } shouldContainExactly
            listOf("Google Drive", "WebDAV / Nextcloud", "Amazon S3 / compatible")
        // The consumer clouds lead the picker; the object-storage backends wait behind "more".
        providers.filter { it.advanced }.map { it.type } shouldContainExactly listOf("s3")
    }

    @Test
    fun theFormShowsRequiredAndDefaultlessOptionsButNeverOauthCredentials() {
        val response =
            providersResponse(
                provider(
                    "webdav",
                    "WebDAV",
                    option("url", required = true),
                    option("vendor"),
                    option("client_id", required = true),
                    option("client_secret"),
                    option("config_is_local", default = JsonPrimitive("true")),
                    option("chunk_size", default = JsonPrimitive("5Mi")),
                    option("bearer_token", advanced = true),
                ),
            )

        RcloneSetupWire
            .parseProviders(response)
            .single()
            .options
            .map { it.name } shouldContainExactly
            listOf("url", "vendor")
    }

    @Test
    fun aBrowserSignInProviderHasNoFormAtAll() {
        val response =
            providersResponse(
                provider(
                    "drive",
                    "Google Drive",
                    option("client_id", required = true),
                    option("client_secret", required = true),
                    option("scope", default = JsonPrimitive("drive")),
                    option("root_folder_id"),
                    option("team_drive", advanced = true),
                ),
            )

        val drive = RcloneSetupWire.parseProviders(response).single()

        drive.browserSignIn shouldBe true
        drive.options shouldBe emptyList()
    }

    @Test
    fun theDefaultPrefersTheTypedValueAndFallsBackToDefaultStr() {
        RcloneSetupWire
            .parseOption(
                option("region", default = JsonNull, defaultStr = "us-east-1"),
            ).defaultValue shouldBe
            "us-east-1"
        RcloneSetupWire
            .parseOption(
                option("chunk_size", default = JsonPrimitive(5), defaultStr = "5Mi"),
            ).defaultValue shouldBe
            "5"
        RcloneSetupWire.parseOption(option("no_check_bucket", default = JsonPrimitive(false))).defaultValue shouldBe
            "false"
        RcloneSetupWire.parseOption(option("secret", default = null)).defaultValue.shouldBeNull()
    }

    @Test
    fun optionExamplesAreParsedInOrder() {
        val raw =
            buildJsonObject {
                put("Name", "scope")
                put("Help", "scope help")
                put("Type", "CommaSepList")
                putJsonArray("Examples") {
                    add(
                        buildJsonObject {
                            put("Value", "drive")
                            put("Help", "Full access")
                        },
                    )
                    add(
                        buildJsonObject {
                            put("Value", "drive.readonly")
                            put("Help", "Read only")
                        },
                    )
                }
            }

        val parsed = RcloneSetupWire.parseOption(raw)

        parsed.type shouldBe "CommaSepList"
        parsed.examples shouldContainExactly
            listOf(RcloneOptionExample("drive", "Full access"), RcloneOptionExample("drive.readonly", "Read only"))
    }

    @Test
    fun oauthStatusMapsRunningAndStopped() {
        val running =
            RcloneSetupWire.parseOAuthStatus(
                buildJsonObject {
                    put("status", "running")
                    put("authUrl", "http://127.0.0.1:53682/auth")
                },
            )
        running shouldBe OAuthStatus(running = true, authUrl = "http://127.0.0.1:53682/auth")

        val stopped = RcloneSetupWire.parseOAuthStatus(buildJsonObject { put("status", "stopped") })
        stopped shouldBe OAuthStatus(running = false, authUrl = null)
    }

    @Test
    fun oauthStatusWithoutAStatusIsNothing() {
        RcloneSetupWire.parseOAuthStatus(buildJsonObject { put("authUrl", "http://127.0.0.1/auth") }).shouldBeNull()
    }

    @Test
    fun folderNamesStripTheBrowsedPathAndSort() {
        val response =
            buildJsonObject {
                putJsonArray("list") {
                    add(entry("a/Photos", dir = true))
                    add(entry("a/Notes.txt", dir = false))
                    add(entry("a/Archive", dir = true))
                }
            }

        RcloneSetupWire.parseFolderNames(response, "a") shouldContainExactly listOf("Archive", "Photos")
    }

    @Test
    fun folderNamesToleratePathsRelativeToTheBrowsedDirectory() {
        val response =
            buildJsonObject {
                putJsonArray("list") {
                    add(entry("Photos", dir = true))
                    add(entry("readme.txt", dir = false))
                }
            }

        RcloneSetupWire.parseFolderNames(response, "") shouldContainExactly listOf("Photos")
    }

    private fun createResponse(
        state: String,
        error: String = "",
    ): JsonObject =
        buildJsonObject {
            put("State", state)
            put("Error", error)
        }

    private fun providersResponse(vararg providers: JsonObject): JsonObject =
        buildJsonObject { putJsonArray("providers") { providers.forEach { add(it) } } }

    private fun provider(
        type: String,
        description: String,
        vararg options: JsonObject,
    ): JsonObject =
        buildJsonObject {
            put("Name", type)
            put("Description", description)
            putJsonArray("Options") { options.forEach { add(it) } }
        }

    private fun option(
        name: String,
        required: Boolean = false,
        advanced: Boolean = false,
        default: JsonElement? = JsonNull,
        defaultStr: String? = null,
    ): JsonObject =
        buildJsonObject {
            put("Name", name)
            put("Help", "$name help")
            put("Type", "string")
            put("Required", required)
            put("Advanced", advanced)
            if (default != null) put("Default", default)
            if (defaultStr != null) put("DefaultStr", defaultStr)
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
}
