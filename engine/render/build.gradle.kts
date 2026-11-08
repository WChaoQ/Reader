plugins {
    id("reader.android.library")
    id("reader.android.compose")
}

android {
    namespace = "com.reader.engine.render"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":engine:layout"))
}
