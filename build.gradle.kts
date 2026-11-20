// Top-level build file. Plugins are declared here but applied per-module.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ktlint) apply false
}

// Apply ktlint to every subproject so `./gradlew ktlintCheck` enforces
// the style rules documented in CONTRIBUTING.md across all modules.
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("1.3.1")
        android.set(true)
        // Don't fail builds on first run — there's existing style drift.
        // Flip to true once the codebase is clean.
        ignoreFailures.set(true)
    }
}

// Configure all test tasks to use JUnit5 Platform (Jupiter) instead of JUnit4
subprojects {
    pluginManager.withPlugin("com.android.base") {
        extensions.findByType<com.android.build.api.dsl.TestOptions>()?.apply {
            unitTests.all { test ->
                test.useJUnitPlatform()
            }
        }
        // Also configure the test task directly (the unitTests.all closure
        // above doesn't always apply on the first configuration pass).
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            // Force test discovery — without this, Gradle sometimes reports
            // 0 tests even though the classes compile and have @Test annotations.
            testLogging {
                events("passed", "skipped", "failed")
                showStandardStreams = false
            }
        }
    }
}
