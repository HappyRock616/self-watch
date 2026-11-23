plugins {
    id("selfwatch.kotlin.jvm")
    alias(libs.plugins.protobuf)
}

dependencies {
    api(project(":core-model"))
    implementation(project(":fit")) // real FIT decoders are wired in through files.FitDecoders later
    implementation(libs.protobuf.javalite)
    testImplementation(testFixtures(project(":core-model")))
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

// Our own .proto subset (garmin/src/main/proto) → Java lite classes. Generated Java is not Kotlin, so
// allWarningsAsErrors (Kotlin only) does not apply to it.
protobuf {
    protoc { artifact = libs.protoc.get().toString() }
    generateProtoTasks {
        all().configureEach {
            builtins {
                named("java") { option("lite") }
            }
        }
    }
}
