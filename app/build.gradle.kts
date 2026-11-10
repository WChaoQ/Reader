plugins {
    id("reader.android.application")
    id("reader.android.compose")
    id("reader.android.hilt")
}

android {
    namespace = "com.reader.app"

    defaultConfig {
        applicationId = "com.reader.app"
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:ui"))
    implementation(project(":feature:library"))
    implementation(project(":feature:reader"))
    implementation(project(":feature:settings"))
    implementation(project(":engine:format-api"))
    implementation(project(":engine:format-txt"))
    implementation(project(":engine:format-markdown"))
    implementation(project(":engine:format-epub"))
    implementation(project(":engine:format-pdf"))
    implementation(project(":engine:layout"))
    implementation(project(":engine:render"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.profileinstaller)

    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
