plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.tgdrive.core.download"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:telegram"))
    implementation(project(":core:db"))
    implementation(project(":core:datastore"))
    implementation(project(":core:zip"))

    implementation(libs.coroutines.android)
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.androidx.compiler)

    testImplementation(libs.junit5.jupiter.api)
    testImplementation(libs.junit5.jupiter.engine)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.truth)
}
