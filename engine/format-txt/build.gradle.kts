plugins {
    id("reader.android.library")
    id("reader.android.hilt")
}

android {
    namespace = "com.reader.engine.format.txt"
}

dependencies {
    implementation(project(":engine:format-api"))
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.android)
}
