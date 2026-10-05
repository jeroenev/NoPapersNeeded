import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * The jpackage overrides for the Linux desktop entry and its icon. jpackage picks them out of its
 * resource dir by file name: `<app name>.desktop` for the menu entry template (jpackage substitutes
 * the caps tokens) and `<app name>.png` for its icon. Packaging from an app image does not reuse
 * the icon inside it, so the icon has to be here too or the entry falls back to the default Java
 * coffee cup.
 */
abstract class PrepareJPackageResourcesTask : DefaultTask() {
    @get:Input
    abstract val appName: Property<String>

    @get:Input
    abstract val displayName: Property<String>

    @get:Input
    abstract val wmClass: Property<String>

    @get:InputFile
    abstract val iconFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outDir: DirectoryProperty

    @TaskAction
    fun prepare() {
        val dir = outDir.get().asFile
        dir.mkdirs()
        File(dir, "${appName.get()}.desktop").writeText(
            """
            [Desktop Entry]
            Name=${displayName.get()}
            Comment=APPLICATION_DESCRIPTION
            Exec=APPLICATION_LAUNCHER
            Icon=APPLICATION_ICON
            Terminal=false
            Type=Application
            Categories=DEPLOY_BUNDLE_CATEGORY
            DESKTOP_MIMES
            StartupWMClass=${wmClass.get()}
            """.trimIndent() + "\n",
        )
        iconFile.get().asFile.copyTo(File(dir, "${appName.get()}.png"), overwrite = true)
    }
}
