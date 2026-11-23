# WP-01 Build, repo and CI

Lane G. Milestone M0. Depends on: nothing. Everything else depends on this.

## Goal

A repo where every module builds, tests run on every change, the release APK is small, reproducible and free of non-free code, and nobody can accidentally add a heavy library.

## Repo layout

```
SelfWatch/                      git repo root (Garmin Apps\SelfWatch)
├─ settings.gradle.kts          includes all modules below; includeBuild("build-logic")
├─ build.gradle.kts             root: nothing but plugin aliases
├─ gradle.properties            org.gradle.jvmargs, android.useAndroidX=true, kotlin.code.style=official,
│                               android.nonTransitiveRClass=true, org.gradle.configuration-cache=true
├─ gradle/libs.versions.toml    the only place versions live
├─ gradle/verification-metadata.xml   dependency checksums (supply-chain safety)
├─ build-logic/                 convention plugins (section below)
├─ app/  service/  ui/  food/  interop/  net/  core-db/  ble/   Android modules
├─ core-model/  fit/  garmin/                                  Kotlin-only (JVM) modules
├─ link-client/                 Android library, Apache-2.0, own LICENSE file
├─ tools/fooddb/                Tony's build scripts (not Gradle)
├─ tools/size/                  size budget script and budget file
├─ testdata/                    sample files (WP-14)
├─ docs/                        design docs (already there)
├─ branding/                    icon sources (already there)
├─ references/  calorie-app/    NOT in git (.gitignore)
├─ LICENSE (AGPL-3.0)  README.md  CONTRIBUTING.md  SECURITY.md
└─ .github/workflows/ci.yml, release.yml
```

## Toolchain and versions

| Item | Value | Note |
|---|---|---|
| JDK | 21 (installed) | Gradle toolchain = 21; bytecode target 17 |
| Gradle | latest 9.x wrapper | wrapper checksum pinned (`distributionSha256Sum`) |
| Android Gradle Plugin | 9.5.x | the reference uses 9.5.1 (Nov 2026) |
| Kotlin | 2.4.x | the reference uses 2.4.21 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 26 | COMPATIBILITY §1 |
| Build tools | 36.0.0 (installed) | |

**Library catalog** (the only libraries allowed; versions are the reference's current ones, re-checked in T-G03 to be at least 2 weeks old):

| Alias | Coordinates | Version | Used by |
|---|---|---|---|
| androidx-core | `androidx.core:core-ktx` | 1.19.1 | Android modules |
| androidx-appcompat | `androidx.appcompat:appcompat` | 1.8.0 | app, ui |
| androidx-activity | `androidx.activity:activity-ktx` | 1.13.0 | app |
| androidx-fragment | `androidx.fragment:fragment-ktx` | 1.9.1 | app, ui |
| androidx-recyclerview | `androidx.recyclerview:recyclerview` | 1.4.0 | app, ui |
| androidx-lifecycle | `androidx.lifecycle:lifecycle-viewmodel-ktx`, `-runtime-ktx`, `-service` | 2.11.0 | app, service |
| material | `com.google.android.material:material` | 1.14.0 | ui, app |
| coroutines | `org.jetbrains.kotlinx:kotlinx-coroutines-core` (+ `-android` in Android modules, `-test` for tests) | 1.11.0 | all |
| zxing | `com.google.zxing:core` | 3.5.4 | food |
| protobuf | `com.google.protobuf:protobuf-javalite` + plugin `com.google.protobuf` | 4.36.2 / 0.10.1 | garmin |
| junit | `junit:junit` | 4.13.2 | tests |
| robolectric | `org.robolectric:robolectric` | 4.18 | tests |
| androidx-test | `androidx.test:runner`, `androidx.test.ext:junit` | current | instrumented tests |

**Not allowed:**
- Hilt or Dagger, Room, greenDAO, Compose, OkHttp, Retrofit, Gson, Moshi.
- Navigation component, WorkManager, MPAndroidChart.
- Google Play Services, Firebase.
- Any analytics or crash reporter.
- Any library with native code. **CameraX is not used** (decided 2026-11-23): it ships native libraries and pulls in Dagger and Guava. Barcode scanning uses Android's built-in Camera2 directly (WP-11).

JSON is handled with Android's built-in `org.json` in Android modules, and a small hand-written reader and writer in Kotlin-only modules (`core-model/json`).

## Convention plugins (`build-logic/`)

| Plugin id | Applies | Sets |
|---|---|---|
| `selfwatch.kotlin.jvm` | `org.jetbrains.kotlin.jvm` | JVM toolchain 21, target 17, `allWarningsAsErrors=true`, JUnit tests |
| `selfwatch.android.library` | `com.android.library` + Kotlin | compileSdk 37, minSdk 26, Java 17, lint options, unit-test options (`isIncludeAndroidResources=true` for Robolectric), `allWarningsAsErrors` |
| `selfwatch.android.app` | `com.android.application` + Kotlin | the above plus `applicationId = "app.selfwatch"`, targetSdk 36, release build rules (below) |

## Release build rules (`app`)

```kotlin
buildTypes {
  release {
    isMinifyEnabled = true            // R8, full mode is the default
    isShrinkResources = true
    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    vcsInfo { include = false }       // reproducible: no git info in the APK
  }
  debug { applicationIdSuffix = ".debug"; isMinifyEnabled = false }   // debug installs beside release
}
androidResources { localeFilters += listOf("en") }   // grows as translations arrive
dependenciesInfo { includeInApk = false; includeInBundle = false }   // F-Droid
packaging { jniLibs { useLegacyPackaging = false }; resources { excludes += setOf("META-INF/*.version", "kotlin/**", "DebugProbesKt.bin") } }
```

- `proguard-rules.pro` keeps only what reflection needs: the protobuf-lite generated classes, and the AIDL stubs for the link service.
- **Signing:** the release keystore lives outside the repo (HappyRock holds it). `release.yml` signs using secrets that HappyRock adds. Debug builds use the default debug key.

## CI (`.github/workflows/ci.yml`, on every pull request and push to main)

All actions pinned by commit SHA.

| Job | Commands | Fails when |
|---|---|---|
| build | `./gradlew assembleDebug assembleRelease` (release unsigned) | Any compile error or warning |
| test | `./gradlew test` (all JVM and Robolectric tests) | Any failing test |
| lint | `./gradlew lint` | New lint errors (baseline file kept empty) |
| size | `tools/size/check.sh app/build/outputs/apk/release/*.apk` | APK above `tools/size/budget.txt`, or any `lib/` folder (native code) inside the APK |
| licenses | `./gradlew :app:dependencies` → `tools/size/license-check.kts` against an allow-list (Apache-2.0, MIT, BSD-2/3, EPL-free) | A dependency outside the list |
| reproducible | build the release twice in clean checkouts, compare SHA-256 of the unsigned APKs | Hashes differ |
| deps | Gradle dependency verification is on | A checksum mismatch |

**Size budget:** M0 records the empty-app size. The budget starts at baseline + 30% and is raised only by a commit that says why (WP-14 tracks it). Planning estimate for v1: under 8 MB, to be confirmed.

## Files to write

- `LICENSE` (AGPL-3.0 full text); `link-client/LICENSE` (Apache-2.0).
- `README.md`: what it is, build steps, license.
- `CONTRIBUTING.md`: the no-copying rule, the reference-comment format, tests required, maintainer-agent rules (pull requests only).
- `SECURITY.md`: how to report privately.
- `.gitignore`: `references/`, `calorie-app/`, `local.properties`, `*.jks`, `*.keystore`, `build/`, `.gradle/`, `.idea/` (except shared run configurations).
- `.editorconfig`: Kotlin official style, 4 spaces, LF.

## Definition of done

- [ ] `./gradlew build` passes from a clean clone on Windows (this PC) and on Linux (CI).
- [ ] Debug installs on the Pixel 5 next to a future release build (`.debug` suffix).
- [ ] All CI jobs green; size baseline recorded in `tools/size/budget.txt`.
- [ ] Adding a library not in the catalog fails review (documented in CONTRIBUTING.md); adding native code fails CI.
- [ ] Reproducible job passes twice in a row.
