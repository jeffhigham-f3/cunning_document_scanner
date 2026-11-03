/// One page a scan produced.
class ScannedDocumentPage {
  /// Creates a page record.
  const ScannedDocumentPage({required this.path, required this.filterApplied});

  /// The page file's path (an image, or the single PDF when `asPdf` was set).
  final String path;

  /// Whether the requested capture filter was applied to this page.
  ///
  /// `false` means the scanner could not apply it (the page would not decode,
  /// a write failed, or memory ran out) and returned the page as it was. With
  /// `DocumentFilter.original` there is nothing to apply, so it is `true`.
  /// For a PDF, `false` when any of its pages was returned unfiltered.
  final bool filterApplied;
}

/// What a scan returned: its pages, in order.
class ScanResult {
  /// Creates a result from its pages.
  const ScanResult({required this.pages});

  /// Decodes the method-channel map (`paths`, `unfilteredPaths`).
  factory ScanResult.fromChannel(Map<Object?, Object?> map) {
    final paths = (map['paths'] as List<Object?>? ?? const []).cast<String>();
    final unfiltered =
        (map['unfilteredPaths'] as List<Object?>? ?? const []).cast<String>();
    return ScanResult(
      pages: [
        for (final path in paths)
          ScannedDocumentPage(
            path: path,
            filterApplied: !unfiltered.contains(path),
          ),
      ],
    );
  }

  /// The pages, in scan order.
  final List<ScannedDocumentPage> pages;

  /// The page paths, in scan order (what [CunningDocumentScanner.getPictures]
  /// returns).
  List<String> get paths => [for (final page in pages) page.path];
}
