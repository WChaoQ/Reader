plugins {
    id("reader.android.library")
    id("reader.android.compose")
    id("reader.android.hilt")
}

android {
    namespace = "com.reader.feature.reader"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:ui"))
    implementation(project(":engine:format-api"))
    implementation(project(":engine:layout"))
    implementation(project(":engine:render"))
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.navigation.compose)
    implementation("androidx.compose.material:material-icons-extended")
}
