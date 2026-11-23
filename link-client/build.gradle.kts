// Apache-2.0. Other apps add this; it must stay tiny and dependency-free. minSdk 21 on purpose.
// Written in Java so consumers get no kotlin-stdlib through it (tests may use Kotlin).
plugins { id("selfwatch.android.library") }

android {
    namespace = "app.selfwatch.link.client"
    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }
    buildFeatures { aidl = true }
}

// AGP's built-in Kotlin adds kotlin-stdlib to every module. The library code is Java, so keep the
// stdlib out of everything a consumer sees (compile/runtime classpaths and published variants);
// unit tests keep it.
configurations.matching { it.name == "api" || it.name == "implementation" }.configureEach {
    withDependencies { removeIf { it.group == "org.jetbrains.kotlin" && it.name == "kotlin-stdlib" } }
}

dependencies {
    testImplementation(kotlin("stdlib"))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
