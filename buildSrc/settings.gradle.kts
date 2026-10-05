rootProject.name = "build-logic"

// The app's version catalog, so the build logic reads the same JDK and plugin versions as the
// modules it configures.
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
