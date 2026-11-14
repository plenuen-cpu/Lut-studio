package com.plenuen.filmcam.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class Affine2Test {

    private fun assertPoint(expectedU: Float, expectedV: Float, actual: Affine2, x: Float, y: Float) {
        assertEquals(expectedU, actual.mapU(x, y), 0.0001f)
        assertEquals(expectedV, actual.mapV(x, y), 0.0001f)
    }

    @Test
    fun identityMapsToItself() {
        val identity = Affine2.identity()
        assertPoint(0.25f, 0.75f, identity, 0.25f, 0.75f)
    }

    @Test
    fun clockwiseQuarterTurnMovesTopLeftToTopRight() {
        // A source pixel at the top-left corner ends up at the top-right after a 90° CW turn.
        val rotation = Affine2.contentRotation(90, mirror = false)
        assertPoint(0f, 1f, rotation, 1f, 1f)
        assertPoint(1f, 0f, rotation, 0f, 1f)
        assertPoint(1f, 1f, rotation, 0f, 0f)
    }

    @Test
    fun halfTurnAndThreeQuarterTurn() {
        val half = Affine2.contentRotation(180, mirror = false)
        assertPoint(0f, 0f, half, 1f, 1f)

        val threeQuarters = Affine2.contentRotation(270, mirror = false)
        assertPoint(1f, 1f, threeQuarters, 0f, 1f)
        assertPoint(1f, 0f, threeQuarters, 1f, 1f)
    }

    @Test
    fun mirrorFlipsHorizontallyAfterRotating() {
        val mirrored = Affine2.contentRotation(0, mirror = true)
        assertPoint(0f, 0.3f, mirrored, 1f, 0.3f)
    }

    @Test
    fun cropScalesAroundTheCentre() {
        val crop = Affine2.scaleAboutCenter(0.5f, 1f)
        assertPoint(0.5f, 0.5f, crop, 0.5f, 0.5f)
        assertPoint(0.25f, 0.5f, crop, 0f, 0.5f)
        assertPoint(0.75f, 0.5f, crop, 1f, 0.5f)
    }

    @Test
    fun compositionAppliesTheRightHandSideFirst() {
        val rotation = Affine2.contentRotation(90, mirror = false)
        val crop = Affine2.scaleAboutCenter(0.5f, 0.5f)
        val combined = crop * rotation
        // (1,1) rotates to (0,1) and is then pulled back towards the centre by the crop
        assertPoint(0.25f, 0.75f, combined, 1f, 1f)
    }

    @Test
    fun previewCropsLandscapeBufferForPortraitView() {
        // 4:3 sensor buffer, 90° sensor, portrait 3:4 view => no extra crop
        val matrix = Affine2.forPreview(
            textureMatrix = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f),
            rotationDegrees = 90,
            mirror = false,
            sourceWidth = 4000,
            sourceHeight = 3000,
            viewWidth = 1080,
            viewHeight = 1440,
        )
        assertPoint(0.5f, 0.5f, matrix, 0.5f, 0.5f)
    }

    @Test
    fun previewFillsWidescreenViewByCroppingTopAndBottom() {
        val matrix = Affine2.forPreview(
            textureMatrix = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f),
            rotationDegrees = 0,
            mirror = false,
            sourceWidth = 4000,
            sourceHeight = 3000,
            viewWidth = 1800,
            viewHeight = 1000,
        )
        // 4:3 source in a 16:9 view: the vertical range is cropped, the horizontal range is kept
        assertEquals(0f, matrix.mapU(0f, 0f), 0.0001f)
        assertEquals(1f, matrix.mapU(1f, 0f), 0.0001f)
        // scaleY = (4/3) / 1.8 = 0.7407 -> v range 0.1296 .. 0.8704
        assertEquals(0.1296f, matrix.mapV(0f, 0f), 0.001f)
        assertEquals(0.8704f, matrix.mapV(0f, 1f), 0.001f)
    }
}
