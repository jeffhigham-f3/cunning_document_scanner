package biz.cunning.cunning_document_scanner.fallback.utils

// / The pixel arithmetic behind `PageFilter`, over plain ARGB int arrays.
// /
// / Kept free of Android framework types so the JVM unit tests can check every
// / filter on small synthetic pages. `PageFilter` is the thin `Bitmap` wrapper.
object PageFilterMath {
    // / Contrast and saturation applied by the colour filter. The same numbers the
    // / iOS renderer uses, so both platforms enhance a page alike.
    const val COLOR_CONTRAST = 1.15f
    const val COLOR_SATURATION = 1.2f
    const val COLOR_BRIGHTNESS = 0.03f

    // / Fraction of the page's longest edge used as the blur radius when estimating
    // / the paper background for black and white. Large enough to blur text away,
    // / small enough to follow a shadow or a fold across the page.
    const val BACKGROUND_RADIUS_FRACTION = 1f / 50f

    // / Luminance, relative to the estimated paper background, below which a pixel is
    // / ink. Faded thermal print sits around 0.7 of its background; a shadow across
    // / clean paper stays close to 1.0 because the background estimate follows it.
    const val INK_THRESHOLD = 0.8f

    // / The background estimate is computed on the page shrunk by this factor, which
    // / keeps its integral image inside `Int` range and its memory a sixty-fourth of
    // / the page's. The estimate is a blur anyway, so the lost detail is intended.
    const val BACKGROUND_SCALE = 8

    // / Pages with more pixels than this are decoded smaller before filtering, so a
    // / single page bitmap bounds the memory a filter needs. Above every common
    // / phone frame (a 12 MP camera is 4032x3024), so those pages are untouched.
    const val MAX_FILTER_PIXELS = 16_000_000L

    // / Rows a page is read and written in while filtering a file, so the Java heap
    // / holds one stripe rather than a page-sized array.
    const val STRIPE_ROWS = 256

    // / The paper background of a page, blurred, at `BACKGROUND_SCALE` resolution.
    class BackgroundPlane internal constructor(
        internal val blurred: IntArray,
        internal val smallWidth: Int,
    )

    // / The factor to decode a `width` x `height` page at so it holds at most
    // / `maxPixels`: 1 when it already does, otherwise a fraction just under the
    // / cap, never a power-of-two step down.
    fun scaleFor(
        width: Int,
        height: Int,
        maxPixels: Long,
    ): Float {
        val pixels = width.toLong() * height.toLong()
        if (pixels <= maxPixels) return 1f
        val exact = Math.sqrt(maxPixels.toDouble() / pixels.toDouble())
        // Rounding of each edge can push the product back over the cap; step down a
        // thousandth at a time until it is under.
        var scale = exact
        while ((width * scale).toLong() * (height * scale).toLong() > maxPixels) {
            scale -= 0.001
        }
        return scale.toFloat()
    }

    // / Builds the background estimate from a page read `stripeRows` rows at a time
    // / through `readStripe(y0, rows, buffer)`, so the page is never held whole.
    fun backgroundFromStripes(
        width: Int,
        height: Int,
        stripeRows: Int,
        readStripe: (y0: Int, rows: Int, buffer: IntArray) -> Unit,
    ): BackgroundPlane {
        val smallWidth = (width + BACKGROUND_SCALE - 1) / BACKGROUND_SCALE
        val smallHeight = (height + BACKGROUND_SCALE - 1) / BACKGROUND_SCALE
        val sums = LongArray(smallWidth * smallHeight)
        val counts = IntArray(smallWidth * smallHeight)
        val stripe = IntArray(width * stripeRows)
        var y0 = 0
        while (y0 < height) {
            val rows = minOf(stripeRows, height - y0)
            readStripe(y0, rows, stripe)
            for (r in 0 until rows) {
                val sy = (y0 + r) / BACKGROUND_SCALE
                for (x in 0 until width) {
                    val index = sy * smallWidth + x / BACKGROUND_SCALE
                    sums[index] += luminance(stripe[r * width + x])
                    counts[index]++
                }
            }
            y0 += rows
        }
        val small = IntArray(sums.size) { (sums[it] / counts[it]).toInt() }
        val radius = blurRadius(width, height)
        return BackgroundPlane(boxBlur(small, smallWidth, smallHeight, radius), smallWidth)
    }

    // / Thresholds `rows` rows of the page starting at `y0`, held in `stripe`, in
    // / place, against `background`. Pairs with `backgroundFromStripes`.
    fun binarizeStripe(
        stripe: IntArray,
        width: Int,
        y0: Int,
        rows: Int,
        background: BackgroundPlane,
    ) {
        for (r in 0 until rows) {
            val sy = (y0 + r) / BACKGROUND_SCALE
            for (x in 0 until width) {
                val i = r * width + x
                val paper = background.blurred[sy * background.smallWidth + x / BACKGROUND_SCALE].coerceAtLeast(1)
                val ink = luminance(stripe[i]).toFloat() / paper.toFloat() < INK_THRESHOLD
                val v = if (ink) 0 else 255
                stripe[i] = argb(alpha(stripe[i]), v, v, v)
            }
        }
    }

    private fun blurRadius(
        width: Int,
        height: Int,
    ): Int =
        (maxOf(width, height) * BACKGROUND_RADIUS_FRACTION / BACKGROUND_SCALE)
            .toInt()
            .coerceAtLeast(1)

    // / Returns a copy of the pixels with contrast and saturation raised.
    fun enhanceColor(pixels: IntArray): IntArray =
        IntArray(pixels.size) { i ->
            val p = pixels[i]
            val r = red(p).toFloat()
            val g = green(p).toFloat()
            val b = blue(p).toFloat()
            val luma = 0.299f * r + 0.587f * g + 0.114f * b
            fun channel(c: Float): Int {
                val saturated = luma + (c - luma) * COLOR_SATURATION
                val contrasted = (saturated - 128f) * COLOR_CONTRAST + 128f
                return clamp(contrasted + COLOR_BRIGHTNESS * 255f)
            }
            argb(alpha(p), channel(r), channel(g), channel(b))
        }

    // / Returns a copy of the pixels converted to shades of grey.
    fun grayscale(pixels: IntArray): IntArray =
        IntArray(pixels.size) { i ->
            val p = pixels[i]
            val l = luminance(p)
            argb(alpha(p), l, l, l)
        }

    // / Returns a copy of the pixels thresholded to pure black and white.
    // /
    // / Each pixel's luminance is divided by the estimated paper background at that
    // / point (a wide blur of the luminance), which flattens shadows, folds and faded
    // / paper to a uniform background. What is darker than `INK_THRESHOLD` of its
    // / background is ink.
    fun binarize(
        pixels: IntArray,
        width: Int,
        height: Int,
    ): IntArray {
        require(pixels.size == width * height) { "pixel count does not match the dimensions" }
        if (width == 0 || height == 0) return IntArray(0)

        val luma = IntArray(pixels.size) { luminance(pixels[it]) }
        val background = estimateBackground(luma, width, height)

        return IntArray(pixels.size) { i ->
            val paper = background[i].coerceAtLeast(1)
            val ink = luma[i].toFloat() / paper.toFloat() < INK_THRESHOLD
            val v = if (ink) 0 else 255
            argb(alpha(pixels[i]), v, v, v)
        }
    }

    // / A wide blur of the luminance plane, at full size, computed on a shrunk copy.
    internal fun estimateBackground(
        luma: IntArray,
        width: Int,
        height: Int,
    ): IntArray {
        val smallWidth = (width + BACKGROUND_SCALE - 1) / BACKGROUND_SCALE
        val smallHeight = (height + BACKGROUND_SCALE - 1) / BACKGROUND_SCALE
        val small = downscale(luma, width, height, smallWidth, smallHeight)

        val blurred = boxBlur(small, smallWidth, smallHeight, blurRadius(width, height))

        return IntArray(width * height) { i ->
            val x = (i % width) / BACKGROUND_SCALE
            val y = (i / width) / BACKGROUND_SCALE
            blurred[y * smallWidth + x]
        }
    }

    // / Averages each `BACKGROUND_SCALE`-sized block of the plane into one value.
    private fun downscale(
        plane: IntArray,
        width: Int,
        height: Int,
        smallWidth: Int,
        smallHeight: Int,
    ): IntArray {
        val sums = LongArray(smallWidth * smallHeight)
        val counts = IntArray(smallWidth * smallHeight)
        for (y in 0 until height) {
            val sy = y / BACKGROUND_SCALE
            for (x in 0 until width) {
                val index = sy * smallWidth + x / BACKGROUND_SCALE
                sums[index] += plane[y * width + x]
                counts[index]++
            }
        }
        return IntArray(sums.size) { (sums[it] / counts[it]).toInt() }
    }

    // / A box blur of `radius` over the plane, through an integral image, with the
    // / window clipped at the edges so the border is averaged over what exists.
    internal fun boxBlur(
        plane: IntArray,
        width: Int,
        height: Int,
        radius: Int,
    ): IntArray {
        val stride = width + 1
        val integral = LongArray(stride * (height + 1))
        for (y in 1..height) {
            var rowSum = 0L
            for (x in 1..width) {
                rowSum += plane[(y - 1) * width + (x - 1)]
                integral[y * stride + x] = integral[(y - 1) * stride + x] + rowSum
            }
        }

        return IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            val x0 = (x - radius).coerceAtLeast(0)
            val y0 = (y - radius).coerceAtLeast(0)
            val x1 = (x + radius + 1).coerceAtMost(width)
            val y1 = (y + radius + 1).coerceAtMost(height)
            val sum =
                integral[y1 * stride + x1] - integral[y0 * stride + x1] -
                    integral[y1 * stride + x0] + integral[y0 * stride + x0]
            (sum / ((x1 - x0) * (y1 - y0))).toInt()
        }
    }

    // / Rec. 601 luminance of an ARGB pixel, 0 to 255.
    fun luminance(pixel: Int): Int = (299 * red(pixel) + 587 * green(pixel) + 114 * blue(pixel)) / 1000

    private fun clamp(value: Float): Int = value.toInt().coerceIn(0, 255)

    private fun alpha(pixel: Int): Int = (pixel ushr 24) and 0xFF

    private fun red(pixel: Int): Int = (pixel ushr 16) and 0xFF

    private fun green(pixel: Int): Int = (pixel ushr 8) and 0xFF

    private fun blue(pixel: Int): Int = pixel and 0xFF

    private fun argb(
        a: Int,
        r: Int,
        g: Int,
        b: Int,
    ): Int = (a shl 24) or (r shl 16) or (g shl 8) or b
}
