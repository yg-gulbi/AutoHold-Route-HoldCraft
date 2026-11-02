package com.autohold.holdcraft.inference

import com.autohold.holdcraft.model.HoldCandidate
import com.autohold.holdcraft.model.Point2D
import org.junit.Assert.*
import org.junit.Test

class YoloSegDecoderTest {

    @Test
    fun decode_extractsHoldAndPolygonInOriginalImageCoordinates() {
        val candidateCount = 10
        val maskSize = 16
        val maskChannels = 4
        val labels = listOf("hold", "volume")
        val decoder = YoloSegDecoder(
            labels = labels,
            candidateCount = candidateCount,
            maskSize = maskSize,
            maskChannels = maskChannels,
            confThreshold = 0.4f
        )

        val output0 = FloatArray((4 + labels.size + maskChannels) * candidateCount)
        val output1 = FloatArray(maskChannels * maskSize * maskSize)

        // Candidate 0: hold at center (320, 320), size (160, 160), score 0.92
        output0[0 * candidateCount + 0] = 320f // cx
        output0[1 * candidateCount + 0] = 320f // cy
        output0[2 * candidateCount + 0] = 160f // w
        output0[3 * candidateCount + 0] = 160f // h
        output0[4 * candidateCount + 0] = 0.92f // class 0 ("hold")
        output0[5 * candidateCount + 0] = 0.05f // class 1 ("volume")
        output0[6 * candidateCount + 0] = 1.0f  // mask coeff 0

        // Activate prototype 0 inside center region [6..10, 6..10]
        for (y in 6..10) {
            for (x in 6..10) {
                output1[y * maskSize + x] = 2.0f
            }
        }

        val holds = decoder.decode(output0, output1, origWidth = 640, origHeight = 640)
        assertEquals(1, holds.size)
        val hold = holds.first()
        assertEquals("hold", hold.className)
        assertEquals(0.92f, hold.confidence, 1e-4f)
        assertEquals(listOf(240, 240, 400, 400), hold.bbox)
        assertTrue(hold.polygon.size >= 3)
        assertTrue(hold.contains(320f, 320f))
        assertFalse(hold.contains(50f, 50f))
    }

    @Test
    fun pointInPolygon_accuratelyDetectsTouch() {
        val candidate = HoldCandidate(
            id = 1,
            className = "hold",
            confidence = 0.9f,
            bbox = listOf(100, 100, 200, 200),
            polygon = listOf(
                Point2D(100, 100),
                Point2D(200, 100),
                Point2D(200, 200),
                Point2D(100, 200)
            )
        )
        assertTrue(candidate.contains(150f, 150f))
        assertFalse(candidate.contains(90f, 150f))
    }
}

