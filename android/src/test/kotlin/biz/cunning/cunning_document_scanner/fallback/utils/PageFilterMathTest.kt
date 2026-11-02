package biz.cunning.cunning_document_scanner.fallback.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageFilterMathTest {
    private val opaque = 0xFF shl 24

    private fun rgb(
        r: Int,
        g: Int,
        b: Int,
    ): Int = opaque or (r shl 16) or (g shl 8) or b

    @Test
    fun `grayscale replaces every channel with the luminance and keeps alpha`() {
        val pixels = intArrayOf(rgb(255, 0, 0), rgb(0, 0, 255), (0x80 shl 24) or 0xFFFFFF)

        val gray = PageFilterMath.grayscale(pixels)

        assertEquals(rgb(76, 76, 76), gray[0])
        assertEquals(rgb(29, 29, 29), gray[1])
        assertEquals((0x80 shl 24) or 0xFFFFFF, gray[2])
    }

    @Test
    fun `enhanceColor pushes a mid grey apart from pure white and keeps alpha`() {
        val pixels = intArrayOf(rgb(128, 128, 128), rgb(255, 255, 255), rgb(0, 0, 0))

        val enhanced = PageFilterMath.enhanceColor(pixels)

        assertEquals(rgb(135, 135, 135), enhanced[0])
        assertEquals(rgb(255, 255, 255), enhanced[1])
        assertEquals(rgb(0, 0, 0), enhanced[2])
        assertTrue(enhanced.all { (it ushr 24) == 0xFF })
    }

    @Test
    fun `binarize turns flat paper white`() {
        val width = 32
        val height = 32
        val pixels = IntArray(width * height) { rgb(200, 200, 200) }

        val page = PageFilterMath.binarize(pixels, width, height)

        assertTrue(page.all { it == rgb(255, 255, 255) })
    }

    @Test
    fun `binarize keeps dark text black on a page with a shadow`() {
        val width = 64
        val height = 64
        // Paper that darkens from 220 on the left to 90 on the right, as under a
        // shadow, with a 2-pixel ink stroke down the middle of each half.
        val pixels =
            IntArray(width * height) { i ->
                val x = i % width
                val paper = 220 - (130 * x) / (width - 1)
                val ink = x == 16 || x == 17 || x == 48 || x == 49
                val v = if (ink) paper / 4 else paper
                rgb(v, v, v)
            }

        val page = PageFilterMath.binarize(pixels, width, height)

        for (y in 0 until height) {
            assertEquals("ink at x=16,y=$y", rgb(0, 0, 0), page[y * width + 16])
            assertEquals("ink at x=48,y=$y", rgb(0, 0, 0), page[y * width + 48])
            assertEquals("paper at x=8,y=$y", rgb(255, 255, 255), page[y * width + 8])
            assertEquals("paper at x=60,y=$y", rgb(255, 255, 255), page[y * width + 60])
        }
    }

    @Test
    fun `boxBlur of a constant plane is the same plane`() {
        val plane = IntArray(5 * 4) { 77 }

        val blurred = PageFilterMath.boxBlur(plane, 5, 4, 2)

        assertArrayEquals(plane, blurred)
    }

    @Test
    fun `boxBlur averages over the clipped window at an edge`() {
        // 3x1 plane: the middle pixel averages all three; an end pixel only two.
        val plane = intArrayOf(0, 90, 180)

        val blurred = PageFilterMath.boxBlur(plane, 3, 1, 1)

        assertArrayEquals(intArrayOf(45, 90, 135), blurred)
    }

    @Test
    fun `luminance weights green most`() {
        assertEquals(76, PageFilterMath.luminance(rgb(255, 0, 0)))
        assertEquals(149, PageFilterMath.luminance(rgb(0, 255, 0)))
        assertEquals(29, PageFilterMath.luminance(rgb(0, 0, 255)))
        assertEquals(255, PageFilterMath.luminance(rgb(255, 255, 255)))
    }
}
