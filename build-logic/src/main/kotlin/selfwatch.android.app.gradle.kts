// The app module.
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { id("com.android.application") }

// G11 release signing. Secrets never live in git: they come from environment variables (CI) or an
// untracked keystore.properties at the repo root (local). Without them the release build stays unsigned.
val signingFile = rootProject.file("keystore.properties")
val signingProps = Properties().apply { if (signingFile.exists()) signingFile.inputStream().use { load(it) } }
fun signingValue(key: String, env: String): String? =
    providers.environmentVariable(env).orNull?.takeIf { it.isNotBlank() } ?: signingProps.getProperty(key)?.takeIf { it.isNotBlank() }
val releaseStore = signingValue("storeFile", "SELFWATCH_KEYSTORE")
val releaseStorePassword = signingValue("storePassword", "SELFWATCH_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "SELFWATCH_KEY_ALIAS") ?: "selfwatch"
val releaseKeyPassword = signingValue("keyPassword", "SELFWATCH_KEY_PASSWORD") ?: releaseStorePassword
val canSignRelease = releaseStore != null && releaseStorePassword != null && rootProject.file(releaseStore).exists()

android {
    compileSdk = 37
    defaultConfig {
        applicationId = "app.selfwatch"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.0.3"
        // The app's name. Debug builds put the version first ("0.0.3db Self Watch", db = debug) so the
        // launcher never cuts the part that tells builds apart.
        resValue("string", "app_name", "Self Watch")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    if (canSignRelease) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(releaseStore!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        release {
            if (canSignRelease) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            vcsInfo { include = false }
        }
        debug {
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "${defaultConfig.versionName}db Self Watch")
        }
    }
    buildFeatures { resValues = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources { localeFilters += listOf("en") }
    dependenciesInfo { includeInApk = false; includeInBundle = false }
    // The Robolectric screen tests build every card of every tab with demo data: 512 MB (the default) ran out.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.maxHeapSize = "1536m" }
        }
    }
    lint { abortOnError = true; disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion") }
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17); allWarningsAsErrors.set(true) } }

// G07: licences of everything shipped in the release APK must be on tools/licenses/allowlist.txt.
tasks.register<selfwatch.checks.LicenseCheckTask>("licenseCheck") {
    group = "verification"
    description = "Fails if a release runtime library has a licence outside tools/licenses/allowlist.txt."
    configurationName.set("releaseRuntimeClasspath")
    allowList.set(rootProject.layout.projectDirectory.file("tools/licenses/allowlist.txt"))
    report.set(layout.buildDirectory.file("reports/licenses.txt"))
}
