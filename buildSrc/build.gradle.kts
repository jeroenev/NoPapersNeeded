plugins {
    `kotlin-dsl`
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
}

// The plugins the convention plugins apply or configure. On buildSrc's classpath they are on every
// build script's too, so the modules request them by id alone: Gradle refuses a version for a plugin
// that is already there.
dependencies {
    implementation(plugin(libs.plugins.kotlinMultiplatform))
    implementation(plugin(libs.plugins.androidMultiplatformLibrary))
    testImplementation(kotlin("test"))
}

fun plugin(plugin: Provider<PluginDependency>): Provider<String> =
    plugin.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" }

// buildSrc tests do not run as part of the main build; run them with `./gradlew :buildSrc:test`.
tasks.test {
    useJUnit()
}
