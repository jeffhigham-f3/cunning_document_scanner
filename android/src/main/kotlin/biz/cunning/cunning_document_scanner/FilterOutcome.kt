package biz.cunning.cunning_document_scanner

// / What a scan hands back to Flutter: every result path, and the subset the
// / requested filter could not be applied to (those pages are the scanner's
// / output as it was). A PDF built from the pages is one path, and it counts as
// / unfiltered when any of its pages was.
data class FilterOutcome(
    val paths: List<String>,
    val unfilteredPaths: List<String>,
) {
    fun toChannelMap(): Map<String, Any> =
        mapOf(
            KEY_PATHS to paths,
            KEY_UNFILTERED_PATHS to unfilteredPaths,
        )

    companion object {
        const val KEY_PATHS = "paths"
        const val KEY_UNFILTERED_PATHS = "unfilteredPaths"

        // / `pages` pairs each page path with whether the filter was applied to it.
        fun of(
            pages: List<Pair<String, Boolean>>,
            pdfPath: String? = null,
        ): FilterOutcome {
            val missed = pages.filterNot { it.second }.map { it.first }
            if (pdfPath != null) {
                return FilterOutcome(listOf(pdfPath), if (missed.isEmpty()) emptyList() else listOf(pdfPath))
            }
            return FilterOutcome(pages.map { it.first }, missed)
        }

        // / Every path filtered, or nothing to filter.
        fun allApplied(paths: List<String>): FilterOutcome = FilterOutcome(paths, emptyList())
    }
}
