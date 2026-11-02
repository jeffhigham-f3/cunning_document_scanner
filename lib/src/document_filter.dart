/// Image filters the scanner can apply to a scanned page.
///
/// Passed as `captureFilter` to `getPictures`, the filter is applied to every
/// page the camera flows return, on both platforms, after the platform's own
/// crop and after any filter the user picked in the system camera. The iOS
/// gallery cropper uses it as `IosScannerOptions.defaultFilter` instead.
enum DocumentFilter {
  /// Leaves the page exactly as captured.
  original,

  /// Enhances contrast and saturation, keeping colours.
  color,

  /// Converts the page to shades of grey.
  grayscale,

  /// Flattens the lighting and thresholds the page to pure black and white,
  /// maximising text legibility.
  blackAndWhite,
}

/// Serialization of [DocumentFilter] for the method channel.
extension DocumentFilterValue on DocumentFilter {
  /// The identifier sent over the method channel.
  String get methodChannelValue {
    switch (this) {
      case DocumentFilter.original:
        return 'original';
      case DocumentFilter.color:
        return 'color';
      case DocumentFilter.grayscale:
        return 'grayscale';
      case DocumentFilter.blackAndWhite:
        return 'blackAndWhite';
    }
  }
}
