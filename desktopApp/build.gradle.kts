import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("npn.toolchain")
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
    implementation(projects.composeApp)
    implementation(projects.core)
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.components.resources)
    implementation(libs.kotlinx.coroutines.swing)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnit()
}

val npnAppName = "no-papers-needed"
val npnAppVersion = NpnVersion.of(project).name
val npnDisplayName = "No papers needed"
val npnFlatpakId = "io.github.jeroenev.NoPapersNeeded"
val npnDescription = "Personal paperless document library"
val npnMainClass = "dev.npn.desktop.MainKt"

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

// createDistributable's output: the jpackage app image (bundled runtime and launcher) that every
// Linux package below repackages.
val distributableAppImage = layout.buildDirectory.dir("compose/binaries/main/app/$npnAppName")

// Every Linux package ships the one desktop entry template (linux/), so they share its categories
// and StartupWMClass; each fills in how it starts the app and names its icon.
fun desktopEntry(
    name: String,
    exec: String,
    icon: String,
) = tasks.register<RenderTemplateTask>(name) {
    template.set(layout.projectDirectory.file("linux/$npnAppName.desktop"))
    tokens.putAll(
        mapOf(
            "NAME" to npnDisplayName,
            "COMMENT" to npnDescription,
            "EXEC" to exec,
            "ICON" to icon,
            "WM_CLASS" to npnWmClass,
        ),
    )
    outFile.set(layout.buildDirectory.file("desktop-entries/$name.desktop"))
}

// --- Linux .deb and .rpm packaging ---------------------------------------
// Built here by running jpackage on the app image, not by the Compose plugin, which cannot pass
// jpackage our desktop entry template (see PackageLinuxInstallerTask). jpackage's built-in one has
// no StartupWMClass, so the shell would not match the running window to the installed entry and
// would show a generic taskbar icon. The .rpm needs `rpmbuild` on the host (the `rpm` package on
// Debian/Ubuntu).

// jpackage fills in the installed launcher and icon paths itself.
val jpackageDesktopEntry = desktopEntry("jpackageDesktopEntry", "APPLICATION_LAUNCHER", "APPLICATION_ICON")

val jpackageResourceDir = layout.buildDirectory.dir("jpackage-resources")

// Packaging from an app image does not reuse the icon inside it, so the icon goes here too, or the
// entry falls back to the default Java coffee cup.
val prepareJPackageResources =
    tasks.register<Sync>("prepareJPackageResources") {
        group = "compose desktop"
        description = "Stages the jpackage overrides for the Linux desktop entry and its icon."
        from(jpackageDesktopEntry) { rename(".*", "$npnAppName.desktop") }
        from(npnIconPng) { rename(".*", "$npnAppName.png") }
        into(jpackageResourceDir)
    }

fun PackageLinuxInstallerTask.packageAs(type: String) {
    group = "compose desktop"
    description = "Builds a Linux .$type from the jpackage app-image output."
    dependsOn("createDistributable", prepareJPackageResources)

    packageType.set(type)
    appImageSrc.set(distributableAppImage)
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
    destinationDir.set(layout.buildDirectory.dir("compose/binaries/main/$type"))
}

tasks.register<PackageLinuxInstallerTask>("packageDeb") { packageAs("deb") }
tasks.register<PackageLinuxInstallerTask>("packageRpm") { packageAs("rpm") }

// The plugin has no Linux target format of its own now, so it does not register
// `packageDistributionForCurrentOS` there; on Linux, use `packageDeb` and `packageRpm`.
// On Windows the plugin still owns that task and produces the .msi/.exe.

// --- AppImage packaging ---------------------------------------------------
// appimagetool wraps the app image; it is downloaded into build/appimagetool/ (needs network), not
// next to the AppImage: the workflows publish every *.AppImage in build/appimage/. It is a tagged
// release, pinned by hash: the release job runs this binary, so it must be the bytes that were
// reviewed, never whatever the rolling "continuous" release holds that day. To bump it, take the
// SHA-256 GitHub lists for the asset on the release page. The runtime every AppImage starts with
// is pinned the same way: left to itself, appimagetool downloads the "continuous" one.
val appImageArch = "x86_64"
val appImageToolVersion = "1.9.1"
val appImageToolUrl =
    "https://github.com/AppImage/appimagetool/releases/download/$appImageToolVersion/appimagetool-$appImageArch.AppImage"
val appImageToolSha256 = "ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0"
val appImageRuntimeVersion = "20251108"
val appImageRuntimeUrl =
    "https://github.com/AppImage/type2-runtime/releases/download/$appImageRuntimeVersion/runtime-$appImageArch"
val appImageRuntimeSha256 = "2fca8b443c92510f1483a883f60061ad09b46b978b2631c807cd873a47ec260d"

val downloadAppImageTool =
    tasks.register<DownloadAppImageToolTask>("downloadAppImageTool") {
        url.set(appImageToolUrl)
        sha256.set(appImageToolSha256)
        outFile.set(layout.buildDirectory.file("appimagetool/appimagetool.AppImage"))
    }

val downloadAppImageRuntime =
    tasks.register<DownloadAppImageToolTask>("downloadAppImageRuntime") {
        url.set(appImageRuntimeUrl)
        sha256.set(appImageRuntimeSha256)
        outFile.set(layout.buildDirectory.file("appimagetool/runtime-$appImageArch"))
    }

val appImageDesktopEntry = desktopEntry("appImageDesktopEntry", npnAppName, npnAppName)

val packageAppImage =
    tasks.register<PackageAppImageTask>("packageAppImage") {
        group = "compose desktop"
        description = "Builds a Linux AppImage from the jpackage app-image output."
        dependsOn("createDistributable")

        appName.set(npnAppName)
        desktopEntry.set(appImageDesktopEntry.flatMap { it.outFile })
        arch.set(appImageArch)
        // AppImageUpdate (and launchers such as Gear Lever) fetch the .zsync of the latest GitHub
        // release, so the release has to upload it next to the AppImage.
        updateInfo.set("gh-releases-zsync|jeroenev|NoPapersNeeded|latest|$npnAppName-*-$appImageArch.AppImage.zsync")
        appImageSrc.set(distributableAppImage)
        appImageTool.set(downloadAppImageTool.flatMap { it.outFile })
        runtimeFile.set(downloadAppImageRuntime.flatMap { it.outFile })
        iconFile.set(npnIconPng)
        appDir.set(layout.buildDirectory.dir("appimage/AppDir"))
        outFile.set(layout.buildDirectory.file("appimage/$npnAppName-$npnAppVersion-$appImageArch.AppImage"))
        // appimagetool names the .zsync after the AppImage it writes.
        zsyncFile.set(layout.file(outFile.map { it.asFile.resolveSibling("${it.asFile.name}.zsync") }))
    }

// --- Bundled Tesseract (Windows) -------------------------------------------
// Desktop OCR shells out to the `tesseract` CLI (TesseractCliEngine). Linux users install it with
// their package manager and the Flatpak builds it in, but almost no Windows machine has one, so a
// Windows build ships its own: tesseract.exe and the DLLs it links, taken from the UB Mannheim
// installer (upstream publishes no Windows binaries; this is the build its docs point to), plus
// the fast models of every language the app UI is translated into. Compose copies the "windows"
// folder of the app resources into the app image, where AppEnvironment.desktop.kt finds it.
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

// The tessdata_fast models a Windows build bundles, one per UI language: tessdata-fast.txt.
val tessdataFastBaseUrl = "https://github.com/tesseract-ocr/tessdata_fast/raw/4.1.0"
val tessdataFastModelList = layout.projectDirectory.file("tessdata-fast.txt")
val tessdataFastModels = providers.fileContents(tessdataFastModelList).asText.map(TessdataModels::parse)

// BundledTessdataTest holds the Flatpak manifest's models and OcrLanguages.BUNDLED to that same
// list. It reads the files from the project directory, its working directory.
tasks.test {
    inputs.files(tessdataFastModelList, "flatpak/$npnFlatpakId.yml")
    systemProperty("npn.tessdataFastBaseUrl", tessdataFastBaseUrl)
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
val linuxBundleName = "$npnAppName-$npnAppVersion-linux-x86_64"

// The release date is the built commit's, which keeps the tarball reproducible.
val renderMetainfo =
    tasks.register<RenderTemplateTask>("renderMetainfo") {
        template.set(layout.projectDirectory.file("flatpak/$npnFlatpakId.metainfo.xml"))
        tokens.put("VERSION", npnAppVersion)
        tokens.put(
            "DATE",
            providers
                .exec { commandLine("git", "log", "-1", "--format=%cs") }
                .standardOutput.asText
                .map { it.trim() },
        )
        outFile.set(layout.buildDirectory.file("linux-bundle/metainfo/$npnFlatpakId.metainfo.xml"))
    }

val flatpakDesktopEntry = desktopEntry("flatpakDesktopEntry", npnAppName, npnFlatpakId)

val linuxBundleStage = layout.buildDirectory.dir("linux-bundle/stage")

val stageLinuxBundle =
    tasks.register<Sync>("stageLinuxBundle") {
        group = "compose desktop"
        description = "Stages the app image with its desktop entry, metainfo and icon."
        dependsOn("createDistributable")
        into(linuxBundleStage)
        // jlink leaves the runtime's legal notices read-only, so the copy cannot overwrite them.
        doFirst { (this as Sync).destinationDir.deleteRecursively() }
        from(distributableAppImage) { into("app") }
        from(flatpakDesktopEntry) {
            rename(".*", "$npnFlatpakId.desktop")
            into("share")
        }
        from(renderMetainfo) { into("share") }
        from(npnIconPng) {
            rename(".*", "$npnFlatpakId.png")
            into("share")
        }
    }

// Reads the archive back for the check below.
interface InjectedArchives {
    @get:Inject
    val archives: ArchiveOperations
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
    // Flathub copies app/ with the modes this archive stores, so a launcher or JDK helper stored
    // without its execute bit is an app that cannot start (or start Tesseract). Gradle's archives
    // store fixed modes (0644 for every file) unless told to keep the staged ones; the check below
    // fails the task if an executable lost its bit anyway.
    useFileSystemPermissions()

    val archives = objects.newInstance<InjectedArchives>().archives
    val archive = destinationDirectory.file(archiveFileName)
    val stage = linuxBundleStage
    val into = linuxBundleName
    val launcher = "app/bin/$npnAppName"
    doLast { ArchiveModes.requireExecutablesKept(archives.tarTree(archive), stage.get().asFile, into, launcher) }
}
