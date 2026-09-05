plugins {
    id("com.android.application")
}

android {
    namespace = "com.inkvox"
    compileSdk = 36

    defaultConfig {
        applicationId = "fun.ikkz.inkvox"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.3.0")
}
