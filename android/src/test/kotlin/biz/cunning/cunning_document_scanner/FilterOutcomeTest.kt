package biz.cunning.cunning_document_scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class FilterOutcomeTest {
    @Test
    fun `lists every page and only the ones the filter missed`() {
        val outcome =
            FilterOutcome.of(
                listOf("/a.jpg" to true, "/b.jpg" to false, "/c.jpg" to true),
            )

        assertEquals(listOf("/a.jpg", "/b.jpg", "/c.jpg"), outcome.paths)
        assertEquals(listOf("/b.jpg"), outcome.unfilteredPaths)
    }

    @Test
    fun `a pdf is the only path and is unfiltered when any page was`() {
        val clean = FilterOutcome.of(listOf("/a.jpg" to true), pdfPath = "/scan.pdf")
        val missed = FilterOutcome.of(listOf("/a.jpg" to true, "/b.jpg" to false), pdfPath = "/scan.pdf")

        assertEquals(listOf("/scan.pdf"), clean.paths)
        assertEquals(emptyList<String>(), clean.unfilteredPaths)
        assertEquals(listOf("/scan.pdf"), missed.paths)
        assertEquals(listOf("/scan.pdf"), missed.unfilteredPaths)
    }

    @Test
    fun `the channel map carries both lists under fixed keys`() {
        val map = FilterOutcome.of(listOf("/a.jpg" to false)).toChannelMap()

        assertEquals(listOf("/a.jpg"), map["paths"])
        assertEquals(listOf("/a.jpg"), map["unfilteredPaths"])
    }
}
