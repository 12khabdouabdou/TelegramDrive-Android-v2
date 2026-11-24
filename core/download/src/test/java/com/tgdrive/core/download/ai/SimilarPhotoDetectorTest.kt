package com.tgdrive.core.download.ai

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SimilarPhotoDetectorTest {

    @Test
    fun `dHash identifies identical and near-identical gradients while separating opposite gradients`() {
        val leftBright = IntArray(72) { idx ->
            val x = idx % DHashCalculator.HASH_WIDTH
            255 - x * 25
        }
        val slightlyNoisy = leftBright.copyOf().also {
            it[0] = 240
            it[71] = 10
        }
        val rightBright = IntArray(72) { idx ->
            val x = idx % DHashCalculator.HASH_WIDTH
            x * 25
        }

        val h1 = DHashCalculator.computeDHashFromLuma(leftBright)
        val h2 = DHashCalculator.computeDHashFromLuma(slightlyNoisy)
        val h3 = DHashCalculator.computeDHashFromLuma(rightBright)

        assertThat(DHashCalculator.hammingDistance(h1, h2)).isAtMost(2)
        assertThat(DHashCalculator.hammingDistance(h1, h3)).isGreaterThan(32)
    }

    @Test
    fun `clusterByDHash groups near hashes and drops singletons`() {
        val clusters = DHashCalculator.clusterByDHash(
            listOf(
                "shot1" to 0b1111_0000_1111L,
                "shot2" to 0b1111_0000_1101L,
                "unique" to -1L,
                "shot3" to 0b1111_0000_1110L,
            ),
            maxDistance = 4,
        )
        assertThat(clusters).hasSize(1)
        assertThat(clusters.single()).containsExactly("shot1", "shot2", "shot3").inOrder()
    }
}
