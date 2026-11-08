plugins {
    id("reader.android.library")
}

android {
    namespace = "com.reader.engine.format.api"
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.android)
}
