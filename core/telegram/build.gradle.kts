plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.tgdrive.core.telegram"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":core:common"))
    api(project(":core:security"))

    // TDLib — prebuilt AAR from FaiBah/tdlib-android-prebuilt (v1.8.65, official
    // tdlib/td@ a17f87c, Java interface). Contains:
    //   - classes.jar with org.drinkless.tdlib.{TdApi,Client}
    //   - jni/<abi>/libtdjni.so for arm64-v8a, armeabi-v7a, x86_64, x86
    // The .so files are extracted into the APK at install time and loaded by
    // TDLib's Client.create() via System.loadLibrary("tdjni").
    api(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Coroutines (already api'd from :core:common)
    implementation(libs.coroutines.android)

    // DataStore for session preferences
    implementation(libs.datastore.preferences)

    testImplementation(libs.junit5.jupiter.api)
    testImplementation(libs.junit5.jupiter.engine)
    testImplementation(libs.junit5.jupiter.params)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
}
