plugins { id("selfwatch.android.app") }

android { namespace = "app.selfwatch" }

// Unit tests also run against the release variant, but only the release checks (src/testRelease,
// package app.selfwatch.debug): the developer tools of debug builds must not reach release builds.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }
}
tasks.withType<Test>().configureEach {
    if (name == "testReleaseUnitTest") filter.includeTestsMatching("app.selfwatch.debug.*")
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-db"))
    implementation(project(":ui"))
    implementation(project(":service"))
    implementation(project(":food"))
    implementation(project(":interop"))
    implementation(project(":net"))
    implementation(project(":ble"))
    implementation(project(":garmin"))
    // Debug builds write small FIT workouts for the demo data (src/debug).
    debugImplementation(project(":fit"))
    implementation(libs.coroutines.android)
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.recyclerview)
    testImplementation(testFixtures(project(":core-model")))
    testImplementation(testFixtures(project(":core-db")))
    testImplementation(project(":fit"))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
}
