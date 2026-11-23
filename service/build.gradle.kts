plugins { id("selfwatch.android.library") }

android { namespace = "app.selfwatch.service" }

// The service runs in process ":watch" and must never depend on food, interop, ui or app (WP-07).
// ble and garmin reach it only through PortFactory and DriverFactory, which the app wires, so they
// are not dependencies yet; fit is not needed either.
dependencies {
    api(project(":core-model"))
    // Public constructors take SelfWatchDb.
    api(project(":core-db"))
    implementation(project(":net"))
    // The link (WP-13): ILinkService / ILinkCallback stubs, LinkCodec and LinkStatus.
    api(project(":link-client"))
    implementation(libs.coroutines.android)
    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.service)
    testImplementation(testFixtures(project(":core-model")))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
}
