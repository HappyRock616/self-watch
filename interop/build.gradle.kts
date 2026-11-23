plugins { id("selfwatch.android.library") }

android { namespace = "app.selfwatch.interop" }

dependencies {
    api(project(":core-model"))
    implementation(project(":core-db"))
    implementation(project(":fit"))
    implementation(libs.coroutines.android)
    implementation(libs.androidx.core)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
}
