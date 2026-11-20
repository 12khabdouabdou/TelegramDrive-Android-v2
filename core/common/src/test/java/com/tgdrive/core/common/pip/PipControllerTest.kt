package com.tgdrive.core.common.pip

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [PipController] — pure logic, no Android dependencies
 * (Activity/Build.VERSION calls are guarded by SDK_INT checks and degrade
 * to no-ops in unit tests).
 */
class PipControllerTest {

    @Test
    @DisplayName("shouldEnterPip is false by default")
    fun defaultState() {
        val controller = PipController()
        assertThat(controller.shouldEnterPip()).isFalse()
    }

    @Test
    @DisplayName("setActive(true) enables shouldEnterPip")
    fun enablePip() {
        val controller = PipController()
        controller.setActive(true)
        assertThat(controller.shouldEnterPip()).isTrue()
    }

    @Test
    @DisplayName("setActive(false) disables shouldEnterPip")
    fun disablePip() {
        val controller = PipController()
        controller.setActive(true)
        controller.setActive(false)
        assertThat(controller.shouldEnterPip()).isFalse()
    }

    @Test
    @DisplayName("setActive with custom aspect ratio still enables PiP")
    fun customAspectRatio() {
        val controller = PipController()
        controller.setActive(true, aspectRatioWidth = 4, aspectRatioHeight = 3)
        assertThat(controller.shouldEnterPip()).isTrue()
    }
}
