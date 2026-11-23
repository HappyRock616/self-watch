// Apache-2.0. Sample companion app for the Self Watch link client. Not published to stores and not
// part of the normal build: ./gradlew -PwithSamples :samples:link-sample:assembleDebug
plugins { id("selfwatch.android.app") }

android {
    namespace = "app.selfwatch.sample.link"
    defaultConfig {
        applicationId = "app.selfwatch.sample.link"
        versionName = "1.0"
    }
}

dependencies {
    implementation(project(":link-client"))
}
