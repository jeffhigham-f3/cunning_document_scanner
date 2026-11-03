package biz.cunning.cunning_document_scanner.fallback.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import biz.cunning.cunning_document_scanner.DocumentFilter
import biz.cunning.cunning_document_scanner.fallback.extensions.saveToFile
import java.io.File
import java.io.IOException

// / Applies a `DocumentFilter` to page bitmaps and page files.
// /
// / The single home of the Android filter code: the ML Kit path and the fallback
// / cropper both go through it. The arithmetic itself lives in `PageFilterMath`.
class PageFilter {
    companion object {
        // / Quality of the JPEG written back over a filtered ML Kit page. The page was
        // / already compressed once by the scanner, so nothing is gained by losing more.
        const val REWRITE_QUALITY = 100

        // / Arbitrary density unit for fractional decode scaling: the decoder scales
        // / by `inTargetDensity / inDensity`.
        private const val DENSITY_UNIT = 1000
    }

    // / Returns `bitmap` with `filter` applied. `ORIGINAL` returns the input itself.
    // / The returned bitmap is a new one for every other filter; the caller recycles both.
    // /
    // / A page over `PageFilterMath.MAX_FILTER_PIXELS` is scaled to just under the cap
    // / first. The filter runs in place on the copy, a stripe of rows at a time, so
    // / beyond the two bitmaps the work needs one stripe and one small background plane.
    fun apply(
        bitmap: Bitmap,
        filter: DocumentFilter,
    ): Bitmap {
        if (filter == DocumentFilter.ORIGINAL) return bitmap

        val scale = PageFilterMath.scaleFor(bitmap.width, bitmap.height, PageFilterMath.MAX_FILTER_PIXELS)
        val output =
            if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                ).let { scaled -> if (scaled.isMutable) scaled else scaled.copy(Bitmap.Config.ARGB_8888, true).also { scaled.recycle() } }
            } else {
                bitmap.copy(Bitmap.Config.ARGB_8888, true)
            }
        filterInPlace(output, filter)
        return output
    }

    // / Applies `filter` to the JPEG at `file`, writing the result back over it, and
    // / returns whether the filter was applied.
    // /
    // / `ORIGINAL` touches nothing and counts as applied. A page over
    // / `PageFilterMath.MAX_FILTER_PIXELS` is decoded scaled to just under the cap.
    // / The page is filtered in place, a stripe of rows at a time, so the only
    // / page-sized allocation is the bitmap itself. The result is written to a
    // / sibling temp file and renamed over the original, so a failed write never
    // / truncates a page. Any failure, including running out of memory, leaves the
    // / file as the scanner wrote it and returns false: an unfiltered page is still
    // / the page, and a filter never fails a scan.
    fun applyToFile(
        file: File,
        filter: DocumentFilter,
    ): Boolean {
        if (filter == DocumentFilter.ORIGINAL) return true
        var decoded: Bitmap? = null
        return try {
            decoded = decodeForFilter(file) ?: return false
            val upright = bakeOrientation(decoded, file)
            if (upright != decoded) {
                decoded.recycle()
                decoded = upright
            }
            filterInPlace(decoded, filter)
            writeOver(decoded, file)
            true
        } catch (e: Exception) {
            false
        } catch (e: OutOfMemoryError) {
            false
        } finally {
            decoded?.recycle()
        }
    }

    // / Decodes the file mutable, scaled when it is over the pixel cap. Null when
    // / the file will not decode.
    private fun decodeForFilter(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val scale = PageFilterMath.scaleFor(bounds.outWidth, bounds.outHeight, PageFilterMath.MAX_FILTER_PIXELS)
        val options =
            BitmapFactory.Options().apply {
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
                if (scale < 1f) {
                    inScaled = true
                    inDensity = DENSITY_UNIT
                    inTargetDensity = (DENSITY_UNIT * scale).toInt()
                }
            }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    // / Filters the mutable bitmap stripe by stripe.
    private fun filterInPlace(
        bitmap: Bitmap,
        filter: DocumentFilter,
    ) {
        val width = bitmap.width
        val height = bitmap.height
        val stripeRows = PageFilterMath.STRIPE_ROWS
        val background =
            if (filter == DocumentFilter.BLACK_AND_WHITE) {
                PageFilterMath.backgroundFromStripes(width, height, stripeRows) { y0, rows, buffer ->
                    bitmap.getPixels(buffer, 0, width, 0, y0, width, rows)
                }
            } else {
                null
            }
        val stripe = IntArray(width * stripeRows)
        var y0 = 0
        while (y0 < height) {
            val rows = minOf(stripeRows, height - y0)
            bitmap.getPixels(stripe, 0, width, 0, y0, width, rows)
            val filtered =
                when (filter) {
                    DocumentFilter.ORIGINAL -> stripe
                    DocumentFilter.COLOR -> PageFilterMath.enhanceColor(stripe.copyOf(rows * width))
                    DocumentFilter.GRAYSCALE -> PageFilterMath.grayscale(stripe.copyOf(rows * width))
                    DocumentFilter.BLACK_AND_WHITE -> {
                        PageFilterMath.binarizeStripe(stripe, width, y0, rows, background!!)
                        stripe
                    }
                }
            bitmap.setPixels(filtered, 0, width, 0, y0, width, rows)
            y0 += rows
        }
    }

    // / Writes the bitmap as a JPEG to a sibling temp file, then renames it over `file`.
    @Throws(IOException::class)
    private fun writeOver(
        bitmap: Bitmap,
        file: File,
    ) {
        val temp = File(file.parentFile, "${file.name}.filtering")
        try {
            bitmap.saveToFile(temp, REWRITE_QUALITY)
            if (!temp.renameTo(file)) throw IOException("could not replace ${file.name}")
        } finally {
            temp.delete()
        }
    }

    private fun bakeOrientation(
        bitmap: Bitmap,
        file: File,
    ): Bitmap {
        val rotation =
            when (
                ExifInterface(file.absolutePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        if (rotation == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(rotation) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
