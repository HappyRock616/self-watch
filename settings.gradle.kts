pluginManagement {
    includeBuild("build-logic")
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "SelfWatch"

include(
    ":app", ":service", ":ui", ":food", ":interop", ":net", ":core-db", ":ble", ":link-client", // Android
    ":core-model", ":fit", ":garmin",                                                           // Kotlin only
)

// Samples stay out of the normal build (size, lint, CI time). Build with: ./gradlew -PwithSamples ...
if (providers.gradleProperty("withSamples").isPresent) {
    include(":samples:link-sample")
}
