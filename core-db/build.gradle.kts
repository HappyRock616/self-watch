plugins { id("selfwatch.android.library") }

android {
    namespace = "app.selfwatch.db"
    // TestDb for other modules' Robolectric tests: testImplementation(testFixtures(project(":core-db"))).
    testFixtures { enable = true }
}

dependencies {
    api(project(":core-model"))
    implementation(libs.coroutines.android)
    implementation(libs.androidx.core)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.coroutines.test)
}
