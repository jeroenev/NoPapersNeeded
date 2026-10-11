import org.gradle.api.Project

/**
 * The app version. `npn.version` in the root gradle.properties is the single source: the Android
 * versionName/versionCode, the desktop package version and the About screen all derive from it,
 * `mise release` bumps it in the same commit it tags, and the release workflow refuses a tag that
 * does not match it.
 *
 * Plain `major.minor.patch` only: jpackage and MSI reject anything else, and MSI caps the major
 * component at 255. Minor and patch stay below 100 so [code] is strictly increasing.
 */
data class NpnVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) {
    val name: String get() = "$major.$minor.$patch"

    /** Android versionCode: 1.2.3 becomes 10203, so every release supersedes the previous one. */
    val code: Int get() = major * 10_000 + minor * 100 + patch

    companion object {
        const val PROPERTY = "npn.version"

        // Keep the persisted/tagged spelling identical to package names, and bound components
        // before converting them so invalid input cannot overflow an integer.
        private val FORMAT = Regex("""(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)""")
        private const val MAX_MAJOR = 255
        private const val MAX_MINOR_PATCH = 99

        fun parse(value: String): NpnVersion {
            val match =
                requireNotNull(FORMAT.matchEntire(value)) {
                    "$PROPERTY must be major.minor.patch without leading zeros, got '$value'"
                }
            val (major, minor, patch) = match.destructured.toList().map(String::toInt)
            require(major in 0..MAX_MAJOR && minor in 0..MAX_MINOR_PATCH && patch in 0..MAX_MINOR_PATCH) {
                "$PROPERTY '$value' out of range: major 0-$MAX_MAJOR, minor and patch 0-$MAX_MINOR_PATCH"
            }
            return NpnVersion(major, minor, patch)
        }

        fun of(project: Project): NpnVersion =
            parse(
                requireNotNull(project.providers.gradleProperty(PROPERTY).orNull) {
                    "$PROPERTY is missing from gradle.properties"
                },
            )
    }
}
