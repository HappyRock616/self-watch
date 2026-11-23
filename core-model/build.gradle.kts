plugins {
    id("selfwatch.kotlin.jvm")
    `java-test-fixtures`
}

dependencies {
    api(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
