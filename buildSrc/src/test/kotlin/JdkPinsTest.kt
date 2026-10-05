import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Two files outside the version catalog name the JDK again, and the build cannot derive either: the
 * Gradle daemon's criteria (written by `./gradlew updateDaemonJvm`) and `.java-version`, which the
 * workflows' setup-java installs. Both must name the catalog's `jdk`.
 */
class JdkPinsTest {
    // The test task passes the catalog's `jdk` and runs in buildSrc/.
    private val jdk = System.getProperty("npn.jdk")
    private val root = File("..")

    @Test
    fun theWorkflowsInstallTheCatalogJdk() {
        assertEquals(jdk, File(root, ".java-version").readText().trim())
    }

    @Test
    fun theDaemonRunsOnTheCatalogJdk() {
        val criteria = Properties()
        File(root, "gradle/gradle-daemon-jvm.properties").reader().use(criteria::load)
        assertEquals(jdk, criteria.getProperty("toolchainVersion"))
    }
}
