package com.tgdrive.core.audio

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AudioPlayerControllerTest {

    @Test
    fun `deduplicateQueue keeps distinct paths and remaps startIndex`() {
        val tracks = listOf(
            AudioTrack(title = "Track A", path = "/tmp/a.mp3"),
            AudioTrack(title = "Empty", path = "  "),
            AudioTrack(title = "Dup A", path = "/tmp/a.mp3"),
            AudioTrack(title = "Track B", path = "/tmp/b.mp3"),
            AudioTrack(title = "Track C", path = "/tmp/c.mp3"),
        )
        val (deduped, mappedStart) = AudioQueueLogic.deduplicateQueue(tracks, startIndex = 3)
        assertThat(deduped.map { it.title }).containsExactly("Track A", "Track B", "Track C").inOrder()
        assertThat(mappedStart).isEqualTo(2)
    }

    @Test
    fun `nextIndex and previousIndex respect repeatAll bounds`() {
        assertThat(AudioQueueLogic.nextIndex(currentIndex = 0, size = 3, repeatAll = false)).isEqualTo(1)
        assertThat(AudioQueueLogic.nextIndex(currentIndex = 2, size = 3, repeatAll = false)).isNull()
        assertThat(AudioQueueLogic.nextIndex(currentIndex = 2, size = 3, repeatAll = true)).isEqualTo(0)

        assertThat(AudioQueueLogic.previousIndex(currentIndex = 0, size = 3, repeatAll = false)).isNull()
        assertThat(AudioQueueLogic.previousIndex(currentIndex = 0, size = 3, repeatAll = true)).isEqualTo(2)
        assertThat(AudioQueueLogic.previousIndex(currentIndex = 1, size = 3, repeatAll = false)).isEqualTo(0)
    }

    @Test
    fun `speed cycling wraps cleanly and clamps extremes`() {
        assertThat(AudioQueueLogic.nextSpeed(1.0f)).isEqualTo(1.25f)
        assertThat(AudioQueueLogic.nextSpeed(2.0f)).isEqualTo(0.75f)
        assertThat(AudioQueueLogic.normalizeSpeed(0.1f)).isEqualTo(0.5f)
        assertThat(AudioQueueLogic.normalizeSpeed(5.0f)).isEqualTo(2.5f)
        assertThat(AudioQueueLogic.formatSpeedLabel(1.0f)).isEqualTo("1x")
        assertThat(AudioQueueLogic.formatSpeedLabel(1.25f)).isEqualTo("1.25x")
    }
}
