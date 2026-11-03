import 'package:cunning_document_scanner/cunning_document_scanner.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const MethodChannel channel = MethodChannel('cunning_document_scanner');

  void stub(Object? Function(MethodCall call) handler) {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async => handler(call));
  }

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  group('scan', () {
    test('marks the pages the filter could not be applied to', () async {
      stub((_) => <String, Object?>{
            'paths': <String>['/a.jpg', '/b.jpg', '/c.jpg'],
            'unfilteredPaths': <String>['/b.jpg'],
          });

      final result = await CunningDocumentScanner.scan(
          captureFilter: DocumentFilter.blackAndWhite);

      expect(result, isNotNull);
      expect(result!.pages.map((p) => p.path), ['/a.jpg', '/b.jpg', '/c.jpg']);
      expect(result.pages.map((p) => p.filterApplied), [true, false, true]);
      expect(result.paths, ['/a.jpg', '/b.jpg', '/c.jpg']);
    });

    test('reports every page as filtered when the native side lists none',
        () async {
      stub((_) => <String, Object?>{
            'paths': <String>['/a.jpg'],
            'unfilteredPaths': <String>[],
          });

      final result = await CunningDocumentScanner.scan();

      expect(result!.pages.single.filterApplied, isTrue);
    });

    test('returns null when the scan is cancelled', () async {
      stub((_) => null);
      expect(await CunningDocumentScanner.scan(), isNull);

      stub((_) => <String, Object?>{
            'paths': <String>[],
            'unfilteredPaths': <String>[],
          });
      expect(await CunningDocumentScanner.scan(), isNull);
    });

    test('surfaces a native error as the documented exception', () async {
      stub((_) => throw PlatformException(code: 'ERROR', message: 'boom'));

      await expectLater(
        CunningDocumentScanner.scan(),
        throwsA(isA<CunningDocumentScannerException>()
            .having((e) => e.code, 'code', 'ERROR')),
      );
    });
  });

  group('getPictures over the map result', () {
    test('returns the paths, filtered or not', () async {
      stub((_) => <String, Object?>{
            'paths': <String>['/a.jpg', '/b.jpg'],
            'unfilteredPaths': <String>['/b.jpg'],
          });

      expect(await CunningDocumentScanner.getPictures(), ['/a.jpg', '/b.jpg']);
    });
  });
}
