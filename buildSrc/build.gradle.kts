plugins {
    `kotlin-dsl`
    alias(libs.plugins.detekt)
}

repositories {
    google {
        mavenContent {
            includeGroupAndSubgroups("androidx")
            includeGroupAndSubgroups("com.android")
            includeGroupAndSubgroups("com.google")
        }
    }
    gradlePluginPortal()
    mavenCentral()
}

kotlin {
    jvmToolchain(
        libs.versions.jdk
            .get()
            .toInt(),
    )
    // As npn.toolchain sets for the app's modules; npn.werror=false turns it off here too.
    compilerOptions.allWarningsAsErrors.set(
        providers.gradleProperty("npn.werror").map { it.toBoolean() }.orElse(true),
    )
}

// The plugins the convention plugins apply or configure. On buildSrc's classpath they are on every
// build script's too, so the modules request them by id alone: Gradle refuses a version for a plugin
// that is already there.
dependencies {
    implementation(plugin(libs.plugins.kotlinMultiplatform))
    implementation(plugin(libs.plugins.androidMultiplatformLibrary))
    implementation(plugin(libs.plugins.detekt))
    testImplementation(kotlin("test"))
}

fun plugin(plugin: Provider<PluginDependency>): Provider<String> =
    plugin.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" }

// buildSrc tests do not run as part of the main build; run them with `./gradlew :buildSrc:test`.
tasks.test {
    useJUnit()
    // JdkPinsTest holds the JDK named outside the catalog to the catalog's.
    inputs.files("../.java-version", "../gradle/gradle-daemon-jvm.properties")
    systemProperty("npn.jdk", libs.versions.jdk.get())
}

// The root build's npn.detekt does not reach this build: `./gradlew :buildSrc:detekt` is its
// source-only run, on the same config, and like npn.detekt's it writes no report file.
detekt {
    buildUponDefaultConfig = true
    config.setFrom("../config/detekt/detekt.yml")
    source.setFrom("src/main/kotlin", "src/test/kotlin")
}
tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
    reports {
        checkstyle.required.set(false)
        html.required.set(false)
        markdown.required.set(false)
        sarif.required.set(false)
    }
}
