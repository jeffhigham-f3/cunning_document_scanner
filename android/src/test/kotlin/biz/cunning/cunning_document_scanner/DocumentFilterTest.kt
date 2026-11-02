package biz.cunning.cunning_document_scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentFilterTest {
    @Test
    fun `fromMethodChannel maps every documented value`() {
        assertEquals(DocumentFilter.ORIGINAL, DocumentFilter.fromMethodChannel("original"))
        assertEquals(DocumentFilter.COLOR, DocumentFilter.fromMethodChannel("color"))
        assertEquals(DocumentFilter.GRAYSCALE, DocumentFilter.fromMethodChannel("grayscale"))
        assertEquals(DocumentFilter.BLACK_AND_WHITE, DocumentFilter.fromMethodChannel("blackAndWhite"))
    }

    @Test
    fun `fromMethodChannel falls back to original for null and unknown values`() {
        assertEquals(DocumentFilter.ORIGINAL, DocumentFilter.fromMethodChannel(null))
        assertEquals(DocumentFilter.ORIGINAL, DocumentFilter.fromMethodChannel("sepia"))
    }
}
