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
    }

    // / Returns `bitmap` with `filter` applied. `ORIGINAL` returns the input itself.
    // / The returned bitmap is a new one for every other filter; the caller recycles both.
    fun apply(
        bitmap: Bitmap,
        filter: DocumentFilter,
    ): Bitmap {
        if (filter == DocumentFilter.ORIGINAL) return bitmap

        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val filtered =
            when (filter) {
                DocumentFilter.ORIGINAL -> pixels
                DocumentFilter.COLOR -> PageFilterMath.enhanceColor(pixels)
                DocumentFilter.GRAYSCALE -> PageFilterMath.grayscale(pixels)
                DocumentFilter.BLACK_AND_WHITE -> PageFilterMath.binarize(pixels, width, height)
            }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(filtered, 0, width, 0, 0, width, height)
        return output
    }

    // / Applies `filter` to the JPEG at `file`, writing the result back over it.
    // /
    // / `ORIGINAL` touches nothing, so the bytes stay exactly what the scanner wrote.
    // / The EXIF orientation is baked into the pixels first, because the rewritten
    // / file carries no EXIF and the page would otherwise come back rotated.
    // / A file that will not decode is left as it is: the scan itself succeeded, and
    // / an unfiltered page is still the page.
    @Throws(IOException::class)
    fun applyToFile(
        file: File,
        filter: DocumentFilter,
    ) {
        if (filter == DocumentFilter.ORIGINAL) return

        val decoded = BitmapFactory.decodeFile(file.absolutePath) ?: return
        val upright = bakeOrientation(decoded, file)
        val filtered = apply(upright, filter)
        try {
            filtered.saveToFile(file, REWRITE_QUALITY)
        } finally {
            filtered.recycle()
            if (upright != decoded) upright.recycle()
            decoded.recycle()
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
