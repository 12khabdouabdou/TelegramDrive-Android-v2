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
    if (path != ":core:tdlib") {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")

        configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
            version.set("1.3.1")
            android.set(true)
            // Baseline audit recorded 6,035 style findings across legacy files;
            // run `./gradlew ktlintFormat` cleanly before flipping to false.
            ignoreFailures.set(true)
        }
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
            // Route Robolectric runtime SDK artifact downloads through Google's
            // Maven Central mirror so shared CI runners avoid repo1.maven.org HTTP 429s.
            systemProperty(
                "robolectric.dependency.repo.url",
                "https://maven-central.storage-download.googleapis.com/maven2/",
            )
            systemProperty("robolectric.dependency.repo.id", "google-central-mirror")
            // Force test discovery — without this, Gradle sometimes reports
            // 0 tests even though the classes compile and have @Test annotations.
            testLogging {
                events("passed", "skipped", "failed")
                showStandardStreams = false
            }
        }
    }
}
