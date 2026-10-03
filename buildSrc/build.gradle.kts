plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    testImplementation(kotlin("test"))
}

// buildSrc tests do not run as part of the main build; run them with `./gradlew :buildSrc:test`.
tasks.test {
    useJUnit()
}
