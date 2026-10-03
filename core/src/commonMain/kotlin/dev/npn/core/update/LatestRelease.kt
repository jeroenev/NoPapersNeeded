package dev.npn.core.update

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
        return compareValuesBy(a, b, { it[0] }, { it[1] }, { it[2] }) > 0
    }

    private val VERSION = Regex("""^v?(\d+)\.(\d+)\.(\d+)""")

    private fun parse(version: String): List<Int>? =
        VERSION
            .find(version.trim())
            ?.groupValues
            ?.drop(1)
            ?.map { it.toIntOrNull() ?: return null }
}

/**
 * Reads the latest release of the app's GitHub repository. Drafts and pre-releases are not
 * "latest" to GitHub, so a release only shows up here once it is published. Any failure (offline,
 * rate limit, unexpected body) yields null: an update check must never surface as an error.
 */
class GitHubLatestRelease(
    private val repository: String = "jeroenev/NoPapersNeeded",
    private val client: () -> HttpClient = { HttpClient() },
) : LatestReleaseSource {
    @Suppress("TooGenericExceptionCaught") // Any failure means "unknown", see the class doc.
    override suspend fun latest(): AppRelease? =
        try {
            client().use { http ->
                val response =
                    http.get("https://api.github.com/repos/$repository/releases/latest") {
                        header("Accept", "application/vnd.github+json")
                    }
                if (!response.status.isSuccess()) return@use null
                val release = json.decodeFromString<GitHubRelease>(response.bodyAsText())
                AppRelease(version = release.tagName.removePrefix("v"), url = release.htmlUrl)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    @Serializable
    private data class GitHubRelease(
        @SerialName("tag_name") val tagName: String,
        @SerialName("html_url") val htmlUrl: String,
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
