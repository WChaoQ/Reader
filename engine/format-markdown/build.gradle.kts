plugins {
    id("reader.android.library")
    id("reader.android.hilt")
}

android {
    namespace = "com.reader.engine.format.markdown"
}

dependencies {
    implementation(project(":engine:format-api"))
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
