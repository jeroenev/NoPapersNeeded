import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

/**
 * Builds a Linux .deb or .rpm by running jpackage on the app image the Compose plugin produces,
 * with [resourceDir] overriding the desktop entry template and its icon: jpackage picks them out by
 * file name, `<app name>.desktop` and `<app name>.png`. The plugin can't do that itself: it
 * appends its own `--resource-dir` (which it uses for the macOS Info.plist) after the task's
 * freeArgs, and jpackage keeps the last one (JetBrains/compose-multiplatform#773, CMP-9837).
 */
abstract class PackageLinuxInstallerTask
    @Inject
    constructor(
        private val execOps: ExecOperations,
    ) : DefaultTask() {
        /** jpackage `--type`: `deb` or `rpm`. */
        @get:Input
        abstract val packageType: Property<String>

        @get:InputDirectory
        abstract val appImageSrc: DirectoryProperty

        @get:InputDirectory
        abstract val resourceDir: DirectoryProperty

        @get:InputFile
        abstract val licenseFile: RegularFileProperty

        @get:Input
        abstract val jpackageTool: Property<String>

        @get:Input
        abstract val appName: Property<String>

        @get:Input
        abstract val appVersion: Property<String>

        @get:Input
        abstract val appDescription: Property<String>

        /** jpackage `--vendor`: the .deb Maintainer's name and the .rpm's Packager. */
        @get:Input
        abstract val appVendor: Property<String>

        /** jpackage `--linux-deb-maintainer`: the address beside [appVendor] in the .deb's Maintainer. */
        @get:Input
        abstract val maintainerEmail: Property<String>

        @get:OutputDirectory
        abstract val destinationDir: DirectoryProperty

        @TaskAction
        fun build() {
            val src = appImageSrc.get().asFile
            require(src.isDirectory) { "jpackage app-image not found at $src" }
            val dest = destinationDir.get().asFile
            dest.mkdirs()

            val jpackageArgs =
                mutableListOf(
                    "--type",
                    packageType.get(),
                    // Packages the already-built app image, which carries the bundled
                    // runtime, the launcher and the icon.
                    "--app-image",
                    src.absolutePath,
                    "--name",
                    appName.get(),
                    "--app-version",
                    appVersion.get(),
                    "--description",
                    appDescription.get(),
                    "--vendor",
                    appVendor.get(),
                    "--license-file",
                    licenseFile.get().asFile.absolutePath,
                    "--linux-shortcut",
                    "--resource-dir",
                    resourceDir.get().asFile.absolutePath,
                    "--dest",
                    dest.absolutePath,
                )
            // Only the .deb carries a Maintainer field; without an address jpackage writes "Unknown".
            if (packageType.get() == "deb") {
                jpackageArgs.addAll(listOf("--linux-deb-maintainer", maintainerEmail.get()))
            }

            execOps
                .exec {
                    executable = jpackageTool.get()
                    args = jpackageArgs
                }.assertNormalExitValue()
            logger.lifecycle("The distribution is written to ${dest.absolutePath}")
        }
    }
