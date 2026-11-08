plugins {
    id("reader.android.library")
}

android {
    namespace = "com.reader.core.common"
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
}
