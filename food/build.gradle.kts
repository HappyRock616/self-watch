plugins { id("selfwatch.android.library") }

android { namespace = "app.selfwatch.food" }

dependencies {
    api(project(":core-model"))
    // The public API takes SelfWatchDb and SettingsStore, so callers need core-db types.
    api(project(":core-db"))
    implementation(project(":net"))
    implementation(libs.coroutines.android)
    implementation(libs.androidx.core)
    implementation(libs.zxing.core)
    testImplementation(testFixtures(project(":core-model")))
    testImplementation(testFixtures(project(":core-db")))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
}
