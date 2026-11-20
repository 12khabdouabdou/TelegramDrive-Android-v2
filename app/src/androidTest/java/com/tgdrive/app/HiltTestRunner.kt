package com.tgdrive.app

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Custom AndroidJUnitRunner that swaps in [HiltTestApplication] so
 * instrumented tests can use @HiltAndroidTest.
 *
 * Wired in `app/build.gradle.kts`:
 * `testInstrumentationRunner = "com.tgdrive.app.HiltTestRunner"`
 */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, name: String?, context: Context?): Application {
        return super.newApplication(cl, HiltTestApplication::class.java.name, context)
    }
}
