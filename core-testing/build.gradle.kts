// Test doubles of core's facets, shared by the commonTest of core and composeApp so the
// two suites fake the same interfaces the same way. Only test source sets depend on it, so it never
// reaches a release artifact.
plugins {
    id("npn.kmp-module")
}

kotlin {
    android {
        namespace = "dev.npn.core.testing"
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.core)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
