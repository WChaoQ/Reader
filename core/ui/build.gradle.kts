plugins {
    id("reader.android.library")
    id("reader.android.compose")
}

android {
    namespace = "com.reader.core.ui"
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:model"))
}
