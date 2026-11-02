package biz.cunning.cunning_document_scanner

// / Image filters the scanner can apply to a scanned page.
// / Mirrors the Dart `DocumentFilter` enum; the method channel carries its name.
enum class DocumentFilter(
    val methodChannelValue: String,
) {
    ORIGINAL("original"),
    COLOR("color"),
    GRAYSCALE("grayscale"),
    BLACK_AND_WHITE("blackAndWhite"),
    ;

    companion object {
        // / Resolves the `captureFilter` method channel value.
        // / A missing or unknown value leaves the page untouched, so an older Dart side
        // / and a newer native side keep today's behaviour.
        fun fromMethodChannel(value: String?): DocumentFilter =
            entries.firstOrNull { it.methodChannelValue == value } ?: ORIGINAL
    }
}
