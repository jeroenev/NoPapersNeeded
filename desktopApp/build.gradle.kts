import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.File
import java.net.URI
import java.util.Base64
import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// jpackage/jlink must come from the toolchain JDK 17, not the system JVM. The system JVM here
// is a JDK 25 install without jlink/jpackage, which fails compose.desktop's checkRuntime.
val jdk17 = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(17))
}

dependencies {
    implementation(project(":composeApp"))
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
}

kotlin {
    jvmToolchain(17)
}

val npnAppName = "no-papers-needed"
val npnAppVersion = "1.0.0"

compose.desktop {
    application {
        mainClass = "dev.npn.desktop.MainKt"

        // Use the toolchain JDK 17 (foojay-provisioned) for jlink/jpackage.
        javaHome = jdk17.get().metadata.installationPath.asFile.absolutePath

        nativeDistributions {
            // jlink strips the runtime to a minimal module set. FileKit's Linux file picker
            // uses dbus-java, which needs com.sun.security.auth.module.UnixSystem from
            // jdk.security.auth; without it the app crashes when opening a file dialog.
            // java.sql and jdk.unsupported are commonly needed by JVM libraries too.
            modules("jdk.security.auth", "java.sql", "jdk.unsupported")

            // Deb only: building Rpm requires rpmbuild, which is not present on Debian/Ubuntu
            // build hosts. Add TargetFormat.Rpm on a host with the rpm-build tooling installed.
            targetFormats(TargetFormat.Deb)
            packageName = npnAppName
            packageVersion = npnAppVersion
            description = "Personal paperless document library"
            licenseFile.set(rootProject.file("LICENSE"))
            linux {
                shortcut = true
            }
        }
    }
}

// --- AppImage packaging ---------------------------------------------------
// jpackage (used by the Compose plugin) cannot emit AppImage: on Linux it only
// produces app-image, deb, and rpm. This task wraps the app-image output with
// appimagetool. appimagetool is downloaded automatically into build/ (requires
// network at build time) and invoked with --appimage-extract-and-run so no host
// FUSE is needed to build. The resulting AppImage still needs FUSE (or the same
// extract-and-run flag) to run on the end-user machine.
val appImageArch = "x86_64"
val appImageToolUrl =
    "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-$appImageArch.AppImage"

abstract class DownloadAppImageToolTask : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:OutputFile
    abstract val outFile: RegularFileProperty

    @TaskAction
    fun download() {
        val target = outFile.get().asFile
        if (target.exists() && target.length() > 0L) return
        target.parentFile.mkdirs()
        logger.lifecycle("Downloading appimagetool from ${url.get()}")
        URI(url.get()).toURL().openStream().use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        target.setExecutable(true)
    }
}

val downloadAppImageTool by tasks.registering(DownloadAppImageToolTask::class) {
    url.set(appImageToolUrl)
    outFile.set(layout.buildDirectory.file("appimage/appimagetool.AppImage"))
}

abstract class PackageAppImageTask @Inject constructor(
    private val execOps: ExecOperations,
) : DefaultTask() {

    @get:InputDirectory
    abstract val appImageSrc: DirectoryProperty

    @get:InputFile
    abstract val appImageTool: RegularFileProperty

    @get:Input
    abstract val appName: Property<String>

    @get:Input
    abstract val arch: Property<String>

    @get:Internal
    abstract val appDir: DirectoryProperty

    @get:OutputFile
    abstract val outFile: RegularFileProperty

    @TaskAction
    fun build() {
        val name = appName.get()
        val src = appImageSrc.get().asFile
        require(src.isDirectory) { "jpackage app-image not found at $src" }

        val dir = appDir.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()

        // Copy the self-contained app-image (bundled JRE + launcher) into AppDir/usr.
        // Kotlin's copyRecursively does not preserve the executable bit, so restore it
        // afterwards on the launcher and every ELF/.so under the runtime.
        val usrDir = File(dir, "usr")
        src.copyRecursively(usrDir, overwrite = true)

        val launcherFile = File(dir, "usr/bin/$name")
        require(launcherFile.exists()) { "launcher not found at usr/bin/$name inside app-image" }
        val launcher = "usr/bin/$name"

        // Restore executable permission for the launcher and bundled native binaries.
        usrDir.walkTopDown().forEach { f ->
            if (!f.isFile) return@forEach
            val underBin = f.parentFile?.name == "bin"
            val isNative = f.extension.equals("so", ignoreCase = true) ||
                f.name.contains(".so.")
            if (underBin || isNative) {
                f.setExecutable(true, false)
            }
        }

        // AppRun -> the bundled launcher.
        val appRun = File(dir, "AppRun")
        appRun.writeText(
            """
            #!/bin/sh
            HERE="${'$'}(dirname "${'$'}(readlink -f "${'$'}0")")"
            exec "${'$'}HERE/$launcher" "${'$'}@"
            """.trimIndent() + "\n",
        )
        appRun.setExecutable(true)

        // Minimal .desktop entry (required by appimagetool).
        File(dir, "$name.desktop").writeText(
            """
            [Desktop Entry]
            Type=Application
            Name=no papers needed
            Exec=$name
            Icon=$name
            Categories=Office;Utility;
            Terminal=false
            """.trimIndent() + "\n",
        )

        // Icon: reuse a packaged PNG if present, else write a 1x1 placeholder so
        // appimagetool is satisfied. Ship real branding via
        // nativeDistributions.linux.iconFile.
        val iconTarget = File(dir, "$name.png")
        val shippedIcon = usrDir.walkTopDown().firstOrNull {
            it.isFile && it.extension.equals("png", ignoreCase = true)
        }
        if (shippedIcon != null) {
            shippedIcon.copyTo(iconTarget, overwrite = true)
        } else {
            iconTarget.writeBytes(
                Base64.getDecoder().decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
                ),
            )
        }

        val out = outFile.get().asFile
        out.parentFile.mkdirs()

        logger.lifecycle("Running appimagetool -> ${out.absolutePath}")
        execOps.exec {
            executable = appImageTool.get().asFile.absolutePath
            // --appimage-extract-and-run avoids needing host FUSE at build time.
            args = listOf("--appimage-extract-and-run", dir.absolutePath, out.absolutePath)
            environment("ARCH", arch.get())
        }.assertNormalExitValue()
        logger.lifecycle("AppImage written: ${out.absolutePath}")
    }
}

val packageAppImage by tasks.registering(PackageAppImageTask::class) {
    group = "compose desktop"
    description = "Builds a Linux AppImage from the jpackage app-image output."
    dependsOn("createDistributable", downloadAppImageTool)

    appName.set(npnAppName)
    arch.set(appImageArch)
    appImageSrc.set(layout.buildDirectory.dir("compose/binaries/main/app/$npnAppName"))
    appImageTool.set(layout.buildDirectory.file("appimage/appimagetool.AppImage"))
    appDir.set(layout.buildDirectory.dir("appimage/AppDir"))
    outFile.set(layout.buildDirectory.file("appimage/$npnAppName-$npnAppVersion-$appImageArch.AppImage"))
}
