import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.process.ExecOperations
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import javax.inject.Inject

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// The JDK that jlink/jpackage bundle as the app's runtime: the build JDK, which is newer than the
// bytecode target (see libs.versions.toml), so the desktop app gets current AWT/HiDPI fixes. It
// must be a toolchain JDK, not the system JVM: a system JDK without jlink/jpackage fails
// compose.desktop's checkRuntime.
val runtimeJdk =
    javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.jdk.get()))
    }

dependencies {
    implementation(project(":composeApp"))
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.components.resources)
    implementation(libs.kotlinx.coroutines.swing)

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnit()
}

kotlin {
    jvmToolchain(
        libs.versions.jdk
            .get()
            .toInt(),
    )
    compilerOptions {
        jvmTarget =
            JvmTarget.fromTarget(
                libs.versions.jvm.target
                    .get(),
            )
        freeCompilerArgs.add("-Xjdk-release=${libs.versions.jvm.target.get()}")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(
        libs.versions.jvm.target
            .get()
            .toInt(),
    )
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

// Per-OS files jpackage puts in the app image; only a Windows build has any (the bundled
// Tesseract, see prepareWindowsTesseract below).
val isWindowsHost = System.getProperty("os.name").startsWith("Windows")
val windowsAppResources = layout.buildDirectory.dir("tesseract-windows/app-resources")

compose.desktop {
    application {
        mainClass = npnMainClass

        // Use the toolchain runtime JDK (foojay-provisioned) for jlink/jpackage.
        javaHome =
            runtimeJdk
                .get()
                .metadata.installationPath.asFile.absolutePath

        // Skiko, the bundled SQLite driver and JNA load native libraries from the classpath. JDK 24+
        // warns about that unless native access is granted, and later releases will refuse it.
        jvmArgs("--enable-native-access=ALL-UNNAMED")

        // 8-byte instead of 12-byte object headers (JEP 519, a product option since JDK 25): a
        // smaller heap and better cache use for the many small objects Compose and the index
        // allocate.
        jvmArgs("-XX:+UseCompactObjectHeaders")

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
            if (isWindowsHost) appResourcesRootDir.set(windowsAppResources)
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
        runtimeJdk
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
//
// A tagged release, pinned by hash: the release job runs this binary, so it must be the bytes
// that were reviewed, never whatever the rolling "continuous" release holds that day. To bump it,
// take the SHA-256 GitHub lists for the asset on the release page.
val appImageArch = "x86_64"
val appImageToolVersion = "1.9.1"
val appImageToolUrl =
    "https://github.com/AppImage/appimagetool/releases/download/$appImageToolVersion/appimagetool-$appImageArch.AppImage"
val appImageToolSha256 = "ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0"

abstract class DownloadAppImageToolTask : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputFile
    abstract val outFile: RegularFileProperty

    @TaskAction
    fun download() {
        val tool = VerifiedDownload.fetch(url.get(), sha256.get(), outFile.get().asFile) { logger.lifecycle(it) }
        tool.setExecutable(true)
    }
}

val downloadAppImageTool =
    tasks.register<DownloadAppImageToolTask>("downloadAppImageTool") {
        url.set(appImageToolUrl)
        sha256.set(appImageToolSha256)
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

        /** AppImage update information; appimagetool embeds it and writes the matching .zsync. */
        @get:Input
        abstract val updateInfo: Property<String>

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
                    args =
                        listOf(
                            "--appimage-extract-and-run",
                            "--updateinformation",
                            updateInfo.get(),
                            dir.absolutePath,
                            out.absolutePath,
                        )
                    environment("ARCH", arch.get())
                    // appimagetool writes the .zsync into its working directory.
                    workingDir = out.parentFile
                }.assertNormalExitValue()
            logger.lifecycle("AppImage written: ${out.absolutePath}")
            require(File(out.path + ".zsync").exists()) { "appimagetool wrote no .zsync next to ${out.name}" }
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
        // AppImageUpdate (and launchers such as Gear Lever) fetch the .zsync of the latest GitHub
        // release, so the release has to upload it next to the AppImage.
        updateInfo.set("gh-releases-zsync|jeroenev|NoPapersNeeded|latest|$npnAppName-*-$appImageArch.AppImage.zsync")
        appImageSrc.set(layout.buildDirectory.dir("compose/binaries/main/app/$npnAppName"))
        appImageTool.set(layout.buildDirectory.file("appimage/appimagetool.AppImage"))
        iconFile.set(npnIconPng)
        appDir.set(layout.buildDirectory.dir("appimage/AppDir"))
        outFile.set(layout.buildDirectory.file("appimage/$npnAppName-$npnAppVersion-$appImageArch.AppImage"))
    }

// --- Bundled Tesseract (Windows) -------------------------------------------
// Desktop OCR shells out to the `tesseract` CLI (TesseractCliEngine). Linux users install it with
// their package manager and the Flatpak builds it in, but almost no Windows machine has one, so a
// Windows build ships its own: tesseract.exe and the DLLs it links, taken from the UB Mannheim
// installer (upstream publishes no Windows binaries; this is the build its docs point to), plus
// the fast models of every language the app UI is translated into. Compose copies the "windows"
// folder of the app resources into the app image, where AppEnvironment.desktop.kt finds it.
//
// Extracting the NSIS installer needs 7-Zip (on PATH, or its default install location; GitHub's
// Windows runners have it). The installer's DLLs carry ~100 MB of debug info, stripped when a
// binutils `strip` is around (the runners' MinGW has one); without it the build still works, the
// installer is just larger.
val tesseractWindowsInstallerUrl =
    "https://github.com/UB-Mannheim/tesseract/releases/download/v5.4.0.20240606/tesseract-ocr-w64-setup-5.4.0.20240606.exe"
val tesseractWindowsInstallerSha256 = "c885fff6998e0608ba4bb8ab51436e1c6775c2bafc2559a19b423e18678b60c9"

// tesseract.exe and the DLLs it loads, transitively: every import in the installer's own
// folder (the rest are Windows system DLLs). Recompute when bumping the installer, e.g. with
// `objdump -p` on the extracted files; the CI smoke test fails if one is missing.
val tesseractWindowsFiles =
    listOf(
        "tesseract.exe",
        "libarchive-13.dll",
        "libb2-1.dll",
        "libbz2-1.dll",
        "libcrypto-3-x64.dll",
        "libdeflate.dll",
        "libexpat-1.dll",
        "libgcc_s_seh-1.dll",
        "libgif-7.dll",
        "libiconv-2.dll",
        "libjbig-0.dll",
        "libjpeg-8.dll",
        "libleptonica-6.dll",
        "libLerc.dll",
        "liblz4.dll",
        "liblzma-5.dll",
        "libopenjp2-7.dll",
        "libpng16-16.dll",
        "libsharpyuv-0.dll",
        "libstdc++-6.dll",
        "libtesseract-5.dll",
        "libtiff-6.dll",
        "libwebp-7.dll",
        "libwebpmux-3.dll",
        "libwinpthread-1.dll",
        "libzstd.dll",
        "zlib1.dll",
    )

// tessdata_fast models, same release as the Flatpak manifest's: one per UI language.
val tessdataFastBaseUrl = "https://github.com/tesseract-ocr/tessdata_fast/raw/4.1.0"
val tessdataFastModels =
    mapOf(
        "eng" to "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2",
        "nld" to "ced0e5e046a84c908a6aa7accbef9a232c4a5d9a8276691b81c6ee64d02963f6",
        "deu" to "19d219bbb6672c869d20a9636c6816a81eb9a71796cb93ebe0cb1530e2cdb22d",
        "fra" to "ced037562e8c80c13122dece28dd477d399af80911a28791a66a63ac1e3445ca",
        "spa" to "6f2e04d02774a18f01bed44b1111f2cd7f3ba7ac9dc4373cd3f898a40ea6b464",
        "por" to "c4932b937207a9514b7514d518b931a99938c02a28a5a5a553f8599ed58b7deb",
    )

abstract class PrepareWindowsTesseractTask
    @Inject
    constructor(
        private val execOps: ExecOperations,
    ) : DefaultTask() {
        @get:Input
        abstract val installerUrl: Property<String>

        @get:Input
        abstract val installerSha256: Property<String>

        /** File names to take from the installer. */
        @get:Input
        abstract val files: ListProperty<String>

        @get:Input
        abstract val modelBaseUrl: Property<String>

        /** Tesseract language code to the SHA-256 of its model. */
        @get:Input
        abstract val models: MapProperty<String, String>

        /** Verified downloads, kept across runs so a rebuild does not fetch them again. */
        @get:Internal
        abstract val downloadDir: DirectoryProperty

        @get:OutputDirectory
        abstract val outDir: DirectoryProperty

        @TaskAction
        fun prepare() {
            val downloads = downloadDir.get().asFile.apply { mkdirs() }
            val installerName = installerUrl.get().substringAfterLast('/')
            val installer = fetch(installerUrl.get(), installerSha256.get(), File(downloads, installerName))

            val root = outDir.get().asFile
            root.deleteRecursively()
            val home = File(root, "windows/tesseract").apply { mkdirs() }
            // NSIS installers are 7-Zip archives; `e` takes just the named files, flattened.
            execOps
                .exec {
                    executable = sevenZip()
                    args = listOf("e", "-y", "-o${home.absolutePath}", installer.absolutePath) + files.get()
                }.assertNormalExitValue()
            files.get().forEach { require(File(home, it).isFile) { "$it is not in $installerName" } }
            stripDebugInfo(home)

            val tessdata = File(home, "tessdata").apply { mkdirs() }
            models.get().forEach { (lang, sha256) ->
                val model =
                    fetch("${modelBaseUrl.get()}/$lang.traineddata", sha256, File(downloads, "$lang.traineddata"))
                model.copyTo(File(tessdata, model.name), overwrite = true)
            }
        }

        private fun fetch(
            url: String,
            sha256: String,
            target: File,
        ): File = VerifiedDownload.fetch(url, sha256, target) { logger.lifecycle(it) }

        private fun sevenZip(): String =
            (onPath("7z.exe", "7z", "7zz") ?: File("C:/Program Files/7-Zip/7z.exe").takeIf { it.isFile })
                ?.absolutePath
                ?: throw GradleException("Bundling Tesseract needs 7-Zip (7z) on PATH to unpack its installer")

        private fun stripDebugInfo(dir: File) {
            val strip =
                onPath("strip.exe", "strip")
                    ?: listOf("C:/mingw64/bin/strip.exe", "C:/msys64/mingw64/bin/strip.exe")
                        .map(::File)
                        .firstOrNull { it.isFile }
            if (strip == null) {
                logger.warn("No `strip` found: bundling Tesseract with its debug info (~100 MB more)")
                return
            }
            execOps
                .exec {
                    executable = strip.absolutePath
                    args = listOf("--strip-debug") + files.get().map { File(dir, it).absolutePath }
                }.assertNormalExitValue()
        }

        private fun onPath(vararg names: String): File? =
            System
                .getenv("PATH")
                .orEmpty()
                .split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .flatMap { dir -> names.map { File(dir, it) } }
                .firstOrNull { it.isFile && it.canExecute() }
    }

val prepareWindowsTesseract =
    tasks.register<PrepareWindowsTesseractTask>("prepareWindowsTesseract") {
        group = "compose desktop"
        description = "Stages the Tesseract binary and language models that Windows builds bundle."
        installerUrl.set(tesseractWindowsInstallerUrl)
        installerSha256.set(tesseractWindowsInstallerSha256)
        files.set(tesseractWindowsFiles)
        modelBaseUrl.set(tessdataFastBaseUrl)
        models.set(tessdataFastModels)
        downloadDir.set(layout.buildDirectory.dir("tesseract-windows/downloads"))
        outDir.set(windowsAppResources)
    }

// Compose registers prepareAppResources after this script is evaluated, so match it by name
// lazily instead of looking it up now.
if (isWindowsHost) {
    tasks.named { it == "prepareAppResources" }.configureEach { dependsOn(prepareWindowsTesseract) }
}

// --- Linux tarball (Flatpak input) -------------------------------------------
// The jpackage app image plus the files a desktop install needs, in the layout the Flatpak
// manifest (desktopApp/flatpak/) installs from: app/ is the app image, share/ holds the
// desktop entry, metainfo and icon. `mise package-flatpak` builds from the staged directory;
// releases publish the tarball, which is what Flathub builds from.
val npnFlatpakId = "io.github.jeroenev.NoPapersNeeded"
val linuxBundleName = "$npnAppName-$npnAppVersion-linux-x86_64"

// Fills in the metainfo's release version and date. The date is the built commit's, which keeps
// the tarball reproducible.
abstract class RenderMetainfoTask : DefaultTask() {
    @get:InputFile
    abstract val template: RegularFileProperty

    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val releaseDate: Property<String>

    @get:OutputFile
    abstract val outFile: RegularFileProperty

    @TaskAction
    fun render() {
        val text =
            template
                .get()
                .asFile
                .readText()
                .replace("@VERSION@", version.get())
                .replace("@DATE@", releaseDate.get())
        outFile
            .get()
            .asFile
            .apply { parentFile.mkdirs() }
            .writeText(text)
    }
}

val renderMetainfo =
    tasks.register<RenderMetainfoTask>("renderMetainfo") {
        template.set(layout.projectDirectory.file("flatpak/$npnFlatpakId.metainfo.xml"))
        version.set(npnAppVersion)
        releaseDate.set(
            providers
                .exec { commandLine("git", "log", "-1", "--format=%cs") }
                .standardOutput.asText
                .map { it.trim() },
        )
        outFile.set(layout.buildDirectory.file("linux-bundle/metainfo/$npnFlatpakId.metainfo.xml"))
    }

val stageLinuxBundle =
    tasks.register<Sync>("stageLinuxBundle") {
        group = "compose desktop"
        description = "Stages the app image with its desktop entry, metainfo and icon."
        dependsOn("createDistributable")
        into(layout.buildDirectory.dir("linux-bundle/stage"))
        // jlink leaves the runtime's legal notices read-only, so the copy cannot overwrite them.
        doFirst { (this as Sync).destinationDir.deleteRecursively() }
        from(layout.buildDirectory.dir("compose/binaries/main/app/$npnAppName")) { into("app") }
        from("flatpak/$npnFlatpakId.desktop") { into("share") }
        from(renderMetainfo) { into("share") }
        val iconName = "$npnFlatpakId.png"
        from(npnIconPng) {
            rename { iconName }
            into("share")
        }
    }

tasks.register<Tar>("packageLinuxTarball") {
    group = "compose desktop"
    description = "Builds the Linux .tar.gz that Flathub builds the Flatpak from."
    from(stageLinuxBundle) { into(linuxBundleName) }
    compression = Compression.GZIP
    archiveFileName.set("$linuxBundleName.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("linux-bundle"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
