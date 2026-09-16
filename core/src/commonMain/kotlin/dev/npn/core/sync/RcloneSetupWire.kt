package dev.npn.core.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Pure translation between rclone's non-interactive config JSON and the [CloudSetup] model. Nothing
 * here performs I/O or holds state: [RcloneCloudSetup] owns the conversation, this file owns the
 * shapes. rclone's `Option` objects are PascalCase and its RC errors are reported in-band
 * (`Error`, `State`), so the field names below are rclone's, not the model's.
 *
 * Kept in `commonMain` so the parsing can be exercised on every test target without an engine.
 */
object RcloneSetupWire {
    /**
     * The `config/create` request that starts a remote ([state] null) or resumes the question
     * [state] asks with the user's [result]. rclone is driven non-interactively and asked to obscure
     * password answers itself, so the wizard never handles the obscured form.
     */
    fun createRequest(
        name: String,
        type: String,
        parameters: Map<String, String>,
        state: String? = null,
        result: String? = null,
    ): JsonObject =
        buildJsonObject {
            put("name", name)
            put("type", type)
            putJsonObject("parameters") { parameters.forEach { (key, value) -> put(key, value) } }
            putJsonObject("opt") {
                put("nonInteractive", true)
                put("obscure", true)
                put("all", false)
                put("continue", state != null)
                if (state != null) {
                    put("state", state)
                    put("result", result.orEmpty())
                }
            }
        }

    /**
     * The step a `config/create` response asks for. An empty `State` means the remote was created;
     * anything else is a question carrying rclone's option metadata and, when the last answer was
     * rejected, rclone's own `Error` text.
     */
    fun parseCreateResult(
        remoteName: String,
        response: JsonObject,
    ): SetupStep {
        val state = response.text("State")
        if (state.isNullOrEmpty()) return SetupStep.Done(remoteName)
        val option =
            response["Option"] as? JsonObject
                ?: return SetupStep.Failed("rclone asked a question without an option")
        return SetupStep.Question(
            remoteName = remoteName,
            state = state,
            option = parseOption(option),
            error = response.text("Error")?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * The providers this rclone build ships, narrowed to the curated [RcloneCatalog.CHOICES] in
     * catalog order. Each keeps its friendly catalog name and the options its setup form should
     * show; a browser sign-in provider's form is empty, and rclone's OAuth plumbing is never shown.
     */
    fun parseProviders(response: JsonObject): List<RcloneProvider> {
        val shipped =
            (response["providers"] as? JsonArray)
                .orEmpty()
                .mapNotNull { it as? JsonObject }
                .associateBy { it.text("Name").orEmpty() }
        return RcloneCatalog.CHOICES.mapNotNull { choice ->
            val provider = shipped[choice.type] ?: return@mapNotNull null
            RcloneProvider(
                type = choice.type,
                name = choice.name,
                description = provider.text("Description").orEmpty(),
                options = formOptions(parseOptions(provider["Options"] as? JsonArray), choice.browserSignIn),
                browserSignIn = choice.browserSignIn,
                advanced = choice.advanced,
            )
        }
    }

    /** One rclone `Option` object in the model's camelCase form. */
    fun parseOption(raw: JsonObject): RcloneOption =
        RcloneOption(
            name = raw.text("Name").orEmpty(),
            help = raw.text("Help").orEmpty(),
            type = raw.text("Type").orEmpty(),
            required = raw.flag("Required"),
            isPassword = raw.flag("IsPassword"),
            advanced = raw.flag("Advanced"),
            exclusive = raw.flag("Exclusive"),
            defaultValue = raw.defaultText(),
            examples = parseExamples(raw["Examples"] as? JsonArray),
        )

    /**
     * The loopback sign-in server's state, or null when the response does not carry one (rclone
     * without a `status` is not answering this question, not reporting it stopped).
     */
    fun parseOAuthStatus(response: JsonObject): OAuthStatus? {
        val status = response.text("status") ?: return null
        return OAuthStatus(running = status == "running", authUrl = response.text("authUrl"))
    }

    /**
     * The immediate sub-directories in an `operations/list` response as names under [path]. rclone
     * reports each entry's `Path` relative to the `fs` root, which may include the [path] being
     * browsed, so the prefix is stripped and the final segment kept.
     */
    fun parseFolderNames(
        response: JsonObject,
        path: String,
    ): List<String> =
        (response["list"] as? JsonArray)
            .orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { it.flag("IsDir") }
            .mapNotNull { it.text("Path") }
            .map { nameUnder(it, path) }
            .filter { it.isNotEmpty() }
            .sorted()

    private fun parseOptions(raw: JsonArray?): List<RcloneOption> =
        raw
            .orEmpty()
            .mapNotNull { it as? JsonObject }
            .map { parseOption(it) }

    /**
     * The options the setup form shows. rclone's OAuth plumbing is never a user field — the wizard
     * supplies `client_id`/`client_secret` (blank means rclone's own app) and the `config_*`
     * switches itself — and a [RcloneProvider.browserSignIn] provider needs no form at all, so only
     * its required fields survive.
     */
    private fun formOptions(
        options: List<RcloneOption>,
        browserSignIn: Boolean,
    ): List<RcloneOption> =
        options
            .filter { it.advanced.not() && it.name !in OAUTH_APP_OPTIONS && it.name.startsWith("config_").not() }
            .filter { if (browserSignIn) it.required else it.required || it.defaultValue.isNullOrBlank() }

    /** rclone's bring-your-own-OAuth-app keys; left blank, rclone signs in with its own app. */
    private val OAUTH_APP_OPTIONS = setOf("client_id", "client_secret")

    private fun parseExamples(raw: JsonArray?): List<RcloneOptionExample> =
        raw.orEmpty().mapNotNull { element ->
            val example = element as? JsonObject ?: return@mapNotNull null
            RcloneOptionExample(value = example.text("Value").orEmpty(), help = example.text("Help").orEmpty())
        }

    /**
     * The directory name [entryPath] stands for beneath [path], tolerating rclone reporting the
     * path either relative to the `fs` root (`a/sub`) or relative to the browsed directory (`sub`).
     */
    private fun nameUnder(
        entryPath: String,
        path: String,
    ): String {
        val base = path.trim('/')
        val relative = if (base.isEmpty()) entryPath else entryPath.removePrefix("$base/")
        return relative.trim('/').substringAfterLast('/')
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

    /**
     * The option's default as a display string: rclone's typed `Default` when it has one, otherwise
     * its string rendering `DefaultStr` (which is all a null or absent `Default` leaves).
     */
    private fun JsonObject.defaultText(): String? =
        (this["Default"] as? JsonPrimitive)?.contentOrNull ?: (this["DefaultStr"] as? JsonPrimitive)?.contentOrNull
}
