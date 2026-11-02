import 'document_filter.dart';

/// The former name of [DocumentFilter], kept so existing callers compile.
///
/// The filters are no longer iOS-only: `captureFilter` applies them to camera
/// captures on both platforms.
@Deprecated('Use DocumentFilter instead')
typedef IosDocumentFilter = DocumentFilter;
