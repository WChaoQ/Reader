plugins {
    id("reader.android.library")
}

android {
    namespace = "com.reader.engine.layout"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":engine:format-api"))
    implementation(libs.kotlinx.coroutines.android)
}
