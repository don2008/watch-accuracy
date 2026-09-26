package sk.watchaccuracy

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class DialRimDetectorTest {
    private val size = 200
    private fun image(cx: Double, cy: Double, rx: Double, ry: Double, masked: Boolean = false): IntArray =
        IntArray(size * size) { i ->
            val x = (i % size + .5) / size; val y = (i / size + .5) / size
            val inside = ((x - cx) / rx).pow(2) + ((y - cy) / ry).pow(2) < 1
            when {
                masked && hypot(x - .5, y - .5) > .495 -> -1
                !inside -> 35
                // A high-contrast logo and lower power-reserve arc must not move the centre.
                x in cx - .14..cx + .14 && y in cy - .21..cy - .16 -> 0
                x in cx - .24..cx - .10 && y in cy + .12..cy + .15 -> 0
                abs(x - cx) < .008 && y in cy..cy + .3 -> 60
                else -> 195
            }
        }
    private fun assertCenter(cx: Double, cy: Double, rx: Double, ry: Double, masked: Boolean = false) {
        val rim = DialRimDetector.detect(size, size, image(cx, cy, rx, ry, masked))
        assertNotNull(rim)
        assertEquals(cx, rim!!.x / size, .035)
        assertEquals(cy, rim.y / size, .035)
    }
    @Test fun findsLargeDialBeyondOldRadiusLimitDespiteLogoAndReserve() {
        assertCenter(.5, .5, .46, .46, true)
    }
    @Test fun findsTranslatedDialInsteadOfTemplateCentre() {
        assertCenter(.59, .43, .32, .32)
    }
    @Test fun toleratesModerateEllipticalForeshortening() {
        assertCenter(.51, .46, .40, .34)
    }
    @Test fun ignoresTransparentTemplateBoundaryAndBlankImages() {
        val flatMask = IntArray(size * size) { i ->
            if (hypot(i % size - 100.0, i / size - 100.0) < 95) 180 else -1
        }
        assertNull(DialRimDetector.detect(size, size, flatMask))
        assertNull(DialRimDetector.detect(size, size, IntArray(size * size) { 120 }))
    }
    @Test fun refusesCroppedDialWithOnlyTextHandsAndPartialArcs() {
        val partial = IntArray(size * size) { i ->
            val x = (i % size + .5) / size; val y = (i / size + .5) / size
            when {
                hypot(x - .5, y - .5) > .49 -> -1
                x in .35.. .65 && y in .25.. .30 -> 20
                abs(x - .5) < .01 && y in .12.. .8 -> 30
                abs(y - .55 - .5 * (x - .5)) < .01 && x in .15.. .85 -> 30
                x < .4 && abs(hypot(x - .37, y - .72) - .10) < .01 -> 20
                else -> 180
            }
        }
        assertNull(DialRimDetector.detect(size, size, partial))
    }

}
