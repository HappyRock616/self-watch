// Android library modules. AGP 9 has Kotlin built in.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { id("com.android.library") }

android {
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests { isIncludeAndroidResources = true } }
    lint { abortOnError = true; disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion") }
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17); allWarningsAsErrors.set(true) } }
