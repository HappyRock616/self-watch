plugins { id("selfwatch.android.library") }

android { namespace = "app.selfwatch.ble" }

dependencies {
    api(project(":core-model"))
    implementation(libs.coroutines.android)
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity)
    testImplementation(testFixtures(project(":core-model")))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.runner)
}
