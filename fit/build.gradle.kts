plugins { id("selfwatch.kotlin.jvm") }

dependencies {
    api(project(":core-model"))
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

tasks.test {
    maxHeapSize = "512m"
    // -Dfit.perf=true: enforce the strict 1 s limit in FitPerformanceTest (default limit is generous for CI).
    // -Dfit.writeSamples=true: regenerate testdata/fit/synthetic/*.fit.
    systemProperty("fit.perf", providers.systemProperty("fit.perf").orElse("false").get())
    systemProperty("fit.writeSamples", providers.systemProperty("fit.writeSamples").orElse("false").get())
}
