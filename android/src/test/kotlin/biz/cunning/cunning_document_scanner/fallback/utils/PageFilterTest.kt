package biz.cunning.cunning_document_scanner.fallback.utils

import biz.cunning.cunning_document_scanner.DocumentFilter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// / On the JVM the Android bitmap decoder is a stub that decodes nothing, which is
// / exactly the "page will not decode" case.
class PageFilterTest {
    private fun pageFile(): File =
        File.createTempFile("page", ".jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3))
            deleteOnExit()
        }

    @Test
    fun `original is applied without touching the file`() {
        val file = pageFile()

        assertTrue(PageFilter().applyToFile(file, DocumentFilter.ORIGINAL))
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
    }

    @Test
    fun `a page that will not decode is reported unfiltered and left as it was`() {
        val file = pageFile()

        assertFalse(PageFilter().applyToFile(file, DocumentFilter.BLACK_AND_WHITE))
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
        assertFalse(File(file.parentFile, "${file.name}.filtering").exists())
    }
}
