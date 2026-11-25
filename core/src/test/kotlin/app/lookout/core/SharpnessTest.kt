package app.lookout.core

import app.lookout.awt.AwtImageOps
import app.lookout.awt.AwtImg
import java.awt.Color
import java.awt.image.BufferedImage
import java.util.Random
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharpnessTest {
    private fun noise(width: Int, height: Int, seed: Long = 42): AwtImg {
        val rng = Random(seed)
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = rng.nextInt(256)
                img.setRGB(x, y, (v shl 16) or (v shl 8) or v)
            }
        }
        return AwtImg(img)
    }

    @Test fun flatImageScoresNearZero() {
        val flat = AwtImageOps.solid(640, 480, Color(128, 128, 128).rgb)
        assertEquals(0.0, Sharpness.score(AwtImageOps, flat), 1e-6)
    }

    @Test fun sharpPatternScoresMuchHigherThanBlurred() {
        val sharp = Fixtures.checkerboard(640, 480)
        val mildBlur = Fixtures.boxBlur(sharp, 2)
        val heavyBlur = Fixtures.boxBlur(sharp, 6)
        val sSharp = Sharpness.score(AwtImageOps, sharp)
        val sMild = Sharpness.score(AwtImageOps, mildBlur)
        val sHeavy = Sharpness.score(AwtImageOps, heavyBlur)
        assertTrue(sSharp > sMild * 2.0, "sharp $sSharp should be much higher than mild blur $sMild")
        assertTrue(sMild > sHeavy * 2.0, "mild blur $sMild should be higher than heavy blur $sHeavy")
        assertTrue(sSharp > sHeavy * 10.0, "sharp $sSharp should be >10x heavy blur $sHeavy")

        val noisy = noise(640, 480)
        val blurredNoise = Fixtures.boxBlur(noisy, 3)
        val sNoise = Sharpness.score(AwtImageOps, noisy)
        val sBlurredNoise = Sharpness.score(AwtImageOps, blurredNoise)
        assertTrue(sNoise > sBlurredNoise * 5.0, "noise $sNoise vs blurred noise $sBlurredNoise")
    }

    @Test fun scoreIsComparableAt1280And4000Width() {
        val full4000 = Fixtures.checkerboard(4000, 3000)
        val full1280 = Fixtures.checkerboard(1280, 960)
        val roi = NRect(0.25, 0.25, 0.5, 0.5)

        val s4000Full = Sharpness.score(AwtImageOps, full4000)
        val s1280Full = Sharpness.score(AwtImageOps, full1280)
        val fullRatio = max(s4000Full, s1280Full) / min(s4000Full, s1280Full)
        assertTrue(fullRatio < 1.5, "full frame 4000 ($s4000Full) vs 1280 ($s1280Full), ratio=$fullRatio")

        val s4000Roi = Sharpness.score(AwtImageOps, full4000, roi)
        val s1280Roi = Sharpness.score(AwtImageOps, full1280, roi)
        val roiRatio = max(s4000Roi, s1280Roi) / min(s4000Roi, s1280Roi)
        assertTrue(roiRatio < 1.5, "roi 4000 ($s4000Roi) vs 1280 ($s1280Roi), ratio=$roiRatio")
    }
}
