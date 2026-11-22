package dev.npn.core.update

import dev.npn.core.util.NpnJson
import dev.npn.core.util.runCatchingCancellable
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A published release: its version (no leading `v`) and the page to download it from. */
data class AppRelease(
    val version: String,
    val url: String,
)

/** Where the newest published release comes from. Null when it cannot be determined. */
fun interface LatestReleaseSource {
    suspend fun latest(): AppRelease?
}

object AppVersions {
    /**
     * True when [candidate] is a later `major.minor.patch` than [current]. A leading `v` and any
     * suffix after the numbers (`-debug`) are ignored; either side failing to parse counts as not
     * newer, so a malformed tag never prompts an update.
     */
    fun isNewer(
        candidate: String,
        current: String,
    ): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(current) ?: return false
        return compare(a, b) > 0
    }

    /**
     * True when [version] is on the beta channel: everything before 1.0.0 is published as a
     * pre-release (release.yml), and only a 0.x build is offered one, so a stable build never
     * prompts a user to move to a pre-1.0 release. A version that does not parse is not a beta.
     */
    fun isPreRelease(version: String): Boolean = parse(version)?.first() == 0

    /** The release with the highest version in [releases], skipping any whose version does not parse. */
    fun newest(releases: List<AppRelease>): AppRelease? =
        releases
            .mapNotNull { release -> parse(release.version)?.let { release to it } }
            .maxWithOrNull { (_, a), (_, b) -> compare(a, b) }
            ?.first

    private val VERSION = Regex("""^v?(\d+)\.(\d+)\.(\d+)""")

    private fun compare(
        a: List<Int>,
        b: List<Int>,
    ): Int = compareValuesBy(a, b, { it[0] }, { it[1] }, { it[2] })

    private fun parse(version: String): List<Int>? =
        VERSION
            .find(version.trim())
            ?.groupValues
            ?.drop(1)
            ?.map { it.toIntOrNull() ?: return null }
}

/**
 * Reads the releases of the app's GitHub repository. A build on the beta channel asks for the
 * newest release of any kind, because GitHub has no "latest pre-release" endpoint; a stable build
 * asks for the latest release, which GitHub never answers with a pre-release. Any failure (offline,
 * rate limit, unexpected body) yields null: an update check must never surface as an error.
 */
class GitHubLatestRelease(
    private val includePrereleases: Boolean = false,
    private val repository: String = "jeroenev/NoPapersNeeded",
    private val client: () -> HttpClient = { HttpClient() },
) : LatestReleaseSource {
    override suspend fun latest(): AppRelease? =
        runCatchingCancellable {
            client().use { http ->
                val response =
                    http.get(url()) {
                        header("Accept", "application/vnd.github+json")
                    }
                if (!response.status.isSuccess()) return@use null
                val body = response.bodyAsText()
                if (!includePrereleases) {
                    return@use json.decodeFromString<GitHubRelease>(body).toAppRelease()
                }
                AppVersions.newest(json.decodeFromString<List<GitHubRelease>>(body).map { it.toAppRelease() })
            }
        }.getOrNull()

    private fun url(): String {
        val releases = "https://api.github.com/repos/$repository/releases"
        return if (includePrereleases) "$releases?per_page=$PAGE" else "$releases/latest"
    }

    @Serializable
    private data class GitHubRelease(
        @SerialName("tag_name") val tagName: String,
        @SerialName("html_url") val htmlUrl: String,
    ) {
        fun toAppRelease() = AppRelease(version = tagName.removePrefix("v"), url = htmlUrl)
    }

    private companion object {
        // Enough releases to hold the newest stable one beside a run of betas; the app keeps a
        // single request per start.
        const val PAGE = 10
        val json = NpnJson.Lenient
    }
}
