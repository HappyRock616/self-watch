// Root project: repo-wide checks. Applied by the root build.gradle.kts.
import selfwatch.checks.ForbiddenNetworkApiTask

plugins { base }

val forbiddenNetworkApi = tasks.register<ForbiddenNetworkApiTask>("forbiddenNetworkApi") {
    group = "verification"
    description = "Fails if a module other than :net uses java.net / OkHttp / javax.net.ssl in production code."
    rootDirectory.set(layout.projectDirectory)
    sources.from(
        fileTree(layout.projectDirectory) {
            include("*/src/**/*.kt", "*/src/**/*.java")
            exclude("net/**", "build-logic/**", "tools/**")
            exclude("*/src/test/**", "*/src/testFixtures/**", "*/src/androidTest/**", "*/src/test*/**")
            exclude("*/build/**")
        },
    )
    report.set(layout.buildDirectory.file("reports/forbidden-network-api.txt"))
}

tasks.named("check") { dependsOn(forbiddenNetworkApi) }
