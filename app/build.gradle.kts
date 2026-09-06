plugins {
    id("com.android.application")
}

val releaseKeystorePath = providers.environmentVariable("RELEASE_KEYSTORE_PATH").orNull

android {
    namespace = "com.inkvox"
    compileSdk = 36

    androidResources.ignoreAssetsPattern += ":PublicSuffixDatabase.list"

    defaultConfig {
        applicationId = "fun.ikkz.inkvox"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.2"
    }

    if (releaseKeystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").get()
            }
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }

    buildTypes.getByName("release") {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.3.0")
}
