plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.tgdrive.feature.browser"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        // Needed so the analytics prototype can gate on BuildConfig.DEBUG.
        // AGP 8.x makes BuildConfig opt-in for library modules.
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric needs resource resolution for BitmapFactory.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:telegram"))
    implementation(project(":core:db"))
    implementation(project(":core:datastore"))
    implementation(project(":core:download"))
    implementation(project(":core:zip"))
    implementation(project(":core:audio"))
    implementation(project(":core:security"))
    implementation(project(":designsystem"))
    implementation(project(":feature:upload"))
    implementation(project(":feature:player"))
    implementation(project(":feature:folders"))

    // zip4j — needed by di.BrowserModule for single-entry extraction.
    // :core:zip uses `implementation` (not `api`) so consumers must
    // re-declare it if they touch zip4j types directly.
    implementation(libs.zip4j)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.foundation)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)

    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.documentfile)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit5.jupiter.api)
    testImplementation(libs.junit5.jupiter.engine)
    // JUnit5 vintage engine — needed to run JUnit4-style tests (e.g.
    // Robolectric tests using @RunWith) on the JUnit5 platform that
    // the project uses globally (see top-level build.gradle.kts).
    testImplementation(libs.junit5.vintage.engine)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    // Robolectric — needed by ZipThumbnailGeneratorTest because
    // BitmapFactory.decodeStream is an Android API. The :feature:browser
    // module didn't previously need Robolectric (no Android-only types
    // were exercised in tests); this is the first test that does.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(libs.junit5.jupiter.api)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
