plugins {
    id("reader.jvm.library")
}

dependencies {
    // pure Kotlin, no Android deps
    testImplementation(libs.junit)
}
