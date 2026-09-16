package dev.npn.core.sync

/**
 * In-app configuration of a cloud remote through rclone's config API, so a user never has to know
 * rclone exists. The production implementation (`RcloneCloudSetup`, jvmSharedMain) drives the same
 * long-lived engine the transfers use; the wire it speaks is rclone's non-interactive config
 * protocol — `config/providers` for the catalog, `config/create`/`config/update` with
 * `opt.nonInteractive` and resumed with `opt.continue`/`opt.state`/`opt.result`, and
 * `config/oauthstatus`/`config/oauthstop` for the browser sign-in.
 *
 * The state machine is agnostic about provider: a question the implementation cannot answer itself
 * comes back as [SetupStep.Question] with rclone's own option metadata, and the UI renders whatever
 * it is handed. Nothing here is per-provider Kotlin — new backends work without a code change.
 */
interface CloudSetup {
    /**
     * The providers this build offers, in picker order, each with the options the setup form should
     * show. Already filtered to the curated catalog and to providers this rclone build ships.
     */
    suspend fun providers(): List<RcloneProvider>

    /** Start configuring [name] as provider [type], pre-answering the form's [params]. */
    suspend fun begin(
        name: String,
        type: String,
        params: Map<String, String>,
    ): SetupStep

    /**
     * Answer the question [state] asks with [result]. The OAuth answer blocks until the browser
     * sign-in finishes, so callers poll [oauthStatus] from another coroutine while it is in flight.
     */
    suspend fun answer(
        name: String,
        state: String,
        result: String,
    ): SetupStep

    /** The sign-in server's current state, or null while the engine cannot answer. */
    suspend fun oauthStatus(): OAuthStatus?

    /** Abandon an in-progress sign-in (the user backed out). */
    suspend fun stopOauth()

    /** Directory names directly under [path] on [remote] (`name:` or `name:path`), for the picker. */
    suspend fun folders(
        remote: String,
        path: String,
    ): List<String>

    /** Create the folder [path] on [remote], so a fresh library root exists before the first pass. */
    suspend fun makeFolder(
        remote: String,
        path: String,
    )
}

/** A remote this build's setup can create, paired with the metadata for its form. */
data class RcloneProvider(
    /** rclone's backend type, e.g. `drive`, `webdav`, `s3`. */
    val type: String,
    /** Friendly display name, e.g. "Google Drive". */
    val name: String,
    /** The backend's one-line description, from rclone. */
    val description: String,
    /**
     * The options worth showing on the form, in rclone's order. Empty for a provider that only
     * needs a website sign-in; rclone's OAuth plumbing (`client_id`, `client_secret`, `config_*`)
     * is never among them because the wizard answers it itself.
     */
    val options: List<RcloneOption>,
    /**
     * Whether setup is a "sign in with…" flow on the provider's own website rather than a form.
     * Curated in [RcloneCatalog] because rclone's metadata does not separate a browser sign-in from
     * a backend that merely accepts your own OAuth app credentials.
     */
    val browserSignIn: Boolean = false,
    /**
     * Whether the provider is kept out of the first-choice list and shown behind "more providers".
     * Curated in [RcloneCatalog]: the eight named consumer clouds lead, and everything a user would
     * have to know rclone to ask for waits behind a disclosure.
     */
    val advanced: Boolean = false,
)

/** One configurable option of a provider or one question of the setup state machine. */
data class RcloneOption(
    /** rclone's config key, e.g. `client_id`; this is what a form answer is stored under. */
    val name: String,
    val help: String,
    /** rclone's type string: `string`, `bool`, `int`, `Size`, `CommaSepList`, … */
    val type: String,
    val required: Boolean = false,
    val isPassword: Boolean = false,
    val advanced: Boolean = false,
    val exclusive: Boolean = false,
    val defaultValue: String? = null,
    val examples: List<RcloneOptionExample> = emptyList(),
)

data class RcloneOptionExample(
    val value: String,
    val help: String,
)

/** What the setup state machine asks next, or that it is finished. */
sealed interface SetupStep {
    data class Question(
        val remoteName: String,
        val state: String,
        val option: RcloneOption,
        /** rclone's own error for the previous answer, when there was one. */
        val error: String? = null,
    ) : SetupStep

    data class Done(
        val remoteName: String,
    ) : SetupStep

    data class Failed(
        val message: String,
    ) : SetupStep
}

/** The loopback OAuth server rclone runs while a browser sign-in is in progress. */
data class OAuthStatus(
    val running: Boolean,
    val authUrl: String? = null,
)

/**
 * The provider picker's curated catalog: the consumer storage backends worth offering by name,
 * every other rclone backend is left out of the in-app picker rather than dumped as an
 * unrecognizable list. A provider that this rclone build does not actually ship is dropped at
 * runtime by `providers()`, so an old engine cannot offer a backend it cannot run.
 *
 * The eight first-choice providers lead the picker; everything a user would have to know rclone to
 * ask for is [Choice.advanced] and waits behind "more providers", so the common case is not buried.
 * [Choice.browserSignIn] is likewise curated knowledge rclone's own metadata does not carry.
 */
object RcloneCatalog {
    data class Choice(
        val type: String,
        val name: String,
        /** True when setup is a single website sign-in; false when the form collects its fields. */
        val browserSignIn: Boolean = false,
        /** True when the provider is hidden behind "more providers" instead of listed first. */
        val advanced: Boolean = false,
    )

    val CHOICES: List<Choice> =
        listOf(
            Choice("drive", "Google Drive", browserSignIn = true),
            Choice("onedrive", "Microsoft OneDrive", browserSignIn = true),
            Choice("dropbox", "Dropbox", browserSignIn = true),
            Choice("box", "Box", browserSignIn = true),
            Choice("pcloud", "pCloud", browserSignIn = true),
            Choice("mega", "MEGA"),
            Choice("protondrive", "Proton Drive"),
            Choice("webdav", "WebDAV / Nextcloud"),
            Choice("koofr", "Koofr", advanced = true),
            Choice("yandex", "Yandex Disk", browserSignIn = true, advanced = true),
            Choice("jottacloud", "Jottacloud", advanced = true),
            Choice("hidrive", "HiDrive", browserSignIn = true, advanced = true),
            Choice("s3", "Amazon S3 / compatible", advanced = true),
            Choice("b2", "Backblaze B2", advanced = true),
            Choice("azureblob", "Azure Blob Storage", advanced = true),
            Choice("storj", "Storj", advanced = true),
            Choice("sftp", "SFTP", advanced = true),
            Choice("ftp", "FTP", advanced = true),
            Choice("smb", "SMB / CIFS", advanced = true),
            Choice("seafile", "Seafile", advanced = true),
        )
}
