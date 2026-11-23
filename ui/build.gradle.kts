plugins { id("selfwatch.android.library") }

android { namespace = "app.selfwatch.ui" }

dependencies {
    api(project(":core-model"))
    api(libs.androidx.appcompat)
    api(libs.material)
    api(libs.androidx.recyclerview)
    implementation(libs.androidx.core)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.runner)
}
