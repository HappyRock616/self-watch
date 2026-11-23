plugins { id("selfwatch.android.library") }

android { namespace = "app.selfwatch.net" }

dependencies {
    api(project(":core-model"))
    // The public API takes SettingsStore, NetLogDao and SelfWatchDb, so callers need core-db types.
    api(project(":core-db"))
    implementation(libs.coroutines.android)
    implementation(libs.androidx.core)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
}
