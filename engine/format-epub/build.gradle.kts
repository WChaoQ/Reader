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
    testImplementation(libs.junit)
    // Android provides XmlPullParser at runtime, but local JVM unit tests need
    // a concrete implementation for XmlPullParserFactory.newInstance().
    testImplementation("net.sf.kxml:kxml2:2.3.0")

    // Keep Readium entirely inside this adapter module.
    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
}
