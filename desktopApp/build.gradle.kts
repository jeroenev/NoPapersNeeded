import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.process.ExecOperations
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.File
import java.net.URI
import javax.inject.Inject

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// jpackage/jlink must come from the toolchain JDK 17, not the system JVM. The system JVM here
// is a JDK 25 install without jlink/jpackage, which fails compose.desktop's checkRuntime.
val jdk17 =
    javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(17))
    }

dependencies {
    implementation(project(":composeApp"))
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.components.resources)
    implementation(libs.kotlinx.coroutines.swing)
}

kotlin {
    jvmToolchain(17)
}

val npnAppName = "no-papers-needed"
val npnAppVersion = NpnVersion.of(project).name
val npnDisplayName = "No papers needed"
val npnDescription = "Personal paperless document library"
val npnMainClass = "dev.npn.desktop.MainKt"

// Becomes Categories= in the Linux desktop entry, which jpackage otherwise sets to
// "Unknown".
val npnLinuxMenuGroup = "Office"

// GNOME (and other Wayland/X11 shells) identify a window by its WM_CLASS and look up the
// matching .desktop entry to decide which taskbar icon to show; the window's own icon is
// not used for that. AWT derives WM_CLASS from the main class with dots turned into
// dashes, so every desktop entry we ship has to advertise that exact string as
// StartupWMClass, or the taskbar falls back to a generic icon.
val npnWmClass = npnMainClass.replace('.', '-')

// Rendered from design/branding/icon.svg by `mise icons`. They live in the resource
// dir so the same PNG is both the jpackage icon and the runtime window icon.
val npnIconPng = layout.projectDirectory.file("src/main/resources/icon.png")
val npnIconIco = layout.projectDirectory.file("src/main/resources/icon.ico")

compose.desktop {
    application {
        mainClass = npnMainClass

        // Use the toolchain JDK 17 (foojay-provisioned) for jlink/jpackage.
        javaHome =
            jdk17
                .get()
                .metadata.installationPath.asFile.absolutePath

        nativeDistributions {
            // jlink strips the runtime to a minimal module set. FileKit's Linux file picker
            // uses dbus-java, which needs com.sun.security.auth.module.UnixSystem from
            // jdk.security.auth; without it the app crashes when opening a file dialog.
            // java.sql and jdk.unsupported are commonly needed by JVM libraries too.
            modules("jdk.security.auth", "java.sql", "jdk.unsupported")

            // Windows formats only. The Linux .deb is built by the packageDeb task below
            // instead of by the plugin: the plugin appends its own --resource-dir after
            // freeArgs, so a custom .desktop template can never win, and without one the
            // installed entry has no StartupWMClass. See the task for details.
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = npnAppName
            packageVersion = npnAppVersion
            description = npnDescription
            licenseFile.set(rootProject.file("LICENSE"))
            linux {
                shortcut = true
                iconFile.set(npnIconPng)
                menuGroup = npnLinuxMenuGroup
            }
            windows {
                menuGroup = "No Papers Needed"
                shortcut = true
                // Fixed per-app GUID so MSI upgrades replace prior installs instead of stacking.
                upgradeUuid = "8757e6c9-527e-412d-99a5-548042266c22"
                // Multi-resolution .ico (16-256 px). Building the MSI downloads WiX tooling at
                // build time (needs network), like appimagetool for AppImage.
                iconFile.set(npnIconIco)
            }
        }
    }
}

// --- Linux .deb and .rpm packaging ---------------------------------------
// jpackage builds the installed .desktop entry from a template, and its built-in one has
// no StartupWMClass, so the shell cannot match the running window to the installed entry
// and shows a generic taskbar icon. Overriding that template is a supported jpackage
// feature (--resource-dir), but not through the Compose plugin: the plugin appends its
// own --resource-dir (which it uses for the macOS Info.plist) after the task's freeArgs,
// and jpackage keeps the last one. See JetBrains/compose-multiplatform#773 and CMP-9837.
//
// So the .deb and .rpm are built here instead, by calling jpackage on the app image the
// plugin produces - the same approach the AppImage task below already uses. Nothing is
// post-processed: jpackage generates the entry from our template. The .rpm needs
// `rpmbuild` on the host (the `rpm` package on Debian/Ubuntu).

// jpackage picks overrides out of the resource dir by file name: "<app name>.desktop" for
// the menu entry template (caps tokens are substituted by jpackage) and "<app name>.png"
// for its icon. Packaging from an app image does not reuse the icon inside it, so the
// icon has to be here too or the entry falls back to the default Java coffee cup.
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

val jpackageResourceDir = layout.buildDirectory.dir("jpackage-resources")

val prepareJPackageResources =
    tasks.register<PrepareJPackageResourcesTask>("prepareJPackageResources") {
        group = "compose desktop"
        description = "Writes the jpackage overrides for the Linux desktop entry and its icon."
        appName.set(npnAppName)
        displayName.set(npnDisplayName)
        wmClass.set(npnWmClass)
        iconFile.set(npnIconPng)
        outDir.set(jpackageResourceDir)
    }

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

        @get:Input
        abstract val menuGroup: Property<String>

        @get:OutputDirectory
        abstract val destinationDir: DirectoryProperty

        @TaskAction
        fun build() {
            val src = appImageSrc.get().asFile
            require(src.isDirectory) { "jpackage app-image not found at $src" }
            val dest = destinationDir.get().asFile
            dest.mkdirs()

            execOps
                .exec {
                    executable = jpackageTool.get()
                    args =
                        listOf(
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
                            "--license-file",
                            licenseFile.get().asFile.absolutePath,
                            "--linux-shortcut",
                            "--linux-menu-group",
                            menuGroup.get(),
                            "--resource-dir",
                            resourceDir.get().asFile.absolutePath,
                            "--dest",
                            dest.absolutePath,
                        )
                }.assertNormalExitValue()
            logger.lifecycle("The distribution is written to ${dest.absolutePath}")
        }
    }

fun PackageLinuxInstallerTask.packageAs(type: String) {
    group = "compose desktop"
    description = "Builds a Linux .$type from the jpackage app-image output."
    dependsOn("createDistributable", prepareJPackageResources)

    packageType.set(type)
    appImageSrc.set(layout.buildDirectory.dir("compose/binaries/main/app/$npnAppName"))
    resourceDir.set(jpackageResourceDir)
    licenseFile.set(rootProject.layout.projectDirectory.file("LICENSE"))
    jpackageTool.set(
        jdk17
            .get()
            .metadata.installationPath
            .file("bin/jpackage")
            .asFile.absolutePath,
    )
    appName.set(npnAppName)
    appVersion.set(npnAppVersion)
    appDescription.set(npnDescription)
    menuGroup.set(npnLinuxMenuGroup)
    destinationDir.set(layout.buildDirectory.dir("compose/binaries/main/$type"))
}

tasks.register<PackageLinuxInstallerTask>("packageDeb") { packageAs("deb") }
tasks.register<PackageLinuxInstallerTask>("packageRpm") { packageAs("rpm") }

// The plugin has no Linux target format of its own now, so it does not register
// `packageDistributionForCurrentOS` there; on Linux, use `packageDeb` and `packageRpm`.
// On Windows the plugin still owns that task and produces the .msi/.exe.

// --- AppImage packaging ---------------------------------------------------
// jpackage (used by the Compose plugin) cannot emit AppImage, so this task wraps
// its app-image output with appimagetool. appimagetool is downloaded into build/
// (needs network) and run with --appimage-extract-and-run, so no host FUSE is
// needed to build. The resulting AppImage still needs FUSE (or that same flag) to
// run on the end-user machine.
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

val downloadAppImageTool =
    tasks.register<DownloadAppImageToolTask>("downloadAppImageTool") {
        url.set(appImageToolUrl)
        outFile.set(layout.buildDirectory.file("appimage/appimagetool.AppImage"))
    }

abstract class PackageAppImageTask
    @Inject
    constructor(
        private val execOps: ExecOperations,
    ) : DefaultTask() {
        @get:InputDirectory
        abstract val appImageSrc: DirectoryProperty

        @get:InputFile
        abstract val appImageTool: RegularFileProperty

        @get:InputFile
        abstract val iconFile: RegularFileProperty

        @get:Input
        abstract val appName: Property<String>

        @get:Input
        abstract val displayName: Property<String>

        @get:Input
        abstract val wmClass: Property<String>

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
                val isNative =
                    f.extension.equals("so", ignoreCase = true) ||
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

            // Minimal .desktop entry (required by appimagetool). StartupWMClass lets the
            // shell match the running window to this entry and show the app icon in the
            // taskbar; see npnWmClass above.
            File(dir, "$name.desktop").writeText(
                """
                [Desktop Entry]
                Type=Application
                Name=${displayName.get()}
                Exec=$name
                Icon=$name
                Categories=Office;Utility;
                Terminal=false
                StartupWMClass=${wmClass.get()}
                """.trimIndent() + "\n",
            )

            // Icon: the .desktop entry above points at "$name", which appimagetool resolves
            // to this PNG at the AppDir root. Same file jpackage uses for the .deb.
            iconFile.get().asFile.copyTo(File(dir, "$name.png"), overwrite = true)

            val out = outFile.get().asFile
            out.parentFile.mkdirs()

            logger.lifecycle("Running appimagetool -> ${out.absolutePath}")
            execOps
                .exec {
                    executable = appImageTool.get().asFile.absolutePath
                    // --appimage-extract-and-run avoids needing host FUSE at build time.
                    args = listOf("--appimage-extract-and-run", dir.absolutePath, out.absolutePath)
                    environment("ARCH", arch.get())
                }.assertNormalExitValue()
            logger.lifecycle("AppImage written: ${out.absolutePath}")
        }
    }

val packageAppImage =
    tasks.register<PackageAppImageTask>("packageAppImage") {
        group = "compose desktop"
        description = "Builds a Linux AppImage from the jpackage app-image output."
        dependsOn("createDistributable", downloadAppImageTool)

        appName.set(npnAppName)
        displayName.set(npnDisplayName)
        wmClass.set(npnWmClass)
        arch.set(appImageArch)
        appImageSrc.set(layout.buildDirectory.dir("compose/binaries/main/app/$npnAppName"))
        appImageTool.set(layout.buildDirectory.file("appimage/appimagetool.AppImage"))
        iconFile.set(npnIconPng)
        appDir.set(layout.buildDirectory.dir("appimage/AppDir"))
        outFile.set(layout.buildDirectory.file("appimage/$npnAppName-$npnAppVersion-$appImageArch.AppImage"))
    }
