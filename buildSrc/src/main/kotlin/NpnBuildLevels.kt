import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/**
 * The JDK, bytecode and Android SDK levels from the version catalog, for the convention plugins,
 * which get no type-safe `libs` accessor.
 */
class NpnBuildLevels private constructor(
    catalog: VersionCatalog,
) {
    /** The one JDK: runs Gradle, compiles every module and is the desktop app's runtime. */
    val jdk: Int = catalog.version("jdk").toInt()

    /** The bytecode level every module emits; Android shares it, so it stays below [jdk]. */
    val jvmTarget: String = catalog.version("jvm-target")

    val compileSdk: Int = catalog.version("android-compileSdk").toInt()

    val minSdk: Int = catalog.version("android-minSdk").toInt()

    companion object {
        fun of(project: Project): NpnBuildLevels =
            NpnBuildLevels(project.extensions.getByType<VersionCatalogsExtension>().named("libs"))

        private fun VersionCatalog.version(alias: String): String {
            val version = findVersion(alias).orElse(null) ?: error("No version '$alias' in the catalog")
            return version.requiredVersion
        }
    }
}
