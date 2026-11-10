plugins {
    id("reader.android.library")
    id("reader.android.hilt")
}

android {
    namespace = "com.reader.engine.format.epub"
}

dependencies {
    implementation(project(":engine:format-api"))
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.android)

    // Keep Readium entirely inside this adapter module.
    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
}
