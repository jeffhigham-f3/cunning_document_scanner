import CoreImage
import UIKit

/// Available image filter modes for scanned document pages.
enum DocumentFilter: Int, CaseIterable {
    case original = 0
    case color = 1
    case grayscale = 2
    case blackAndWhite = 3

    var titleKey: String {
        switch self {
        case .original: return "cunning_document_scanner_filter_original"
        case .color: return "cunning_document_scanner_filter_color"
        case .grayscale: return "cunning_document_scanner_filter_grayscale"
        case .blackAndWhite: return "cunning_document_scanner_filter_bw"
        }
    }

    var defaultTitle: String {
        switch self {
        case .original: return "Original"
        case .color: return "Color"
        case .grayscale: return "Grayscale"
        case .blackAndWhite: return "B&W"
        }
    }

    /// The identifier used on the Dart method channel.
    var methodChannelValue: String {
        switch self {
        case .original: return "original"
        case .color: return "color"
        case .grayscale: return "grayscale"
        case .blackAndWhite: return "blackAndWhite"
        }
    }

    /// Builds a filter from its Dart method channel identifier, falling back to `.original`.
    init(methodChannelValue: String) {
        self = DocumentFilter.allCases.first { $0.methodChannelValue == methodChannelValue } ?? .original
    }
}

/// Renders a `DocumentFilter` onto page images with Core Image.
///
/// The single home of the filter code: the gallery cropper previews and bakes its
/// per-page selection through it, and the camera flow runs every captured page
/// through it when a `captureFilter` was requested. One shared `CIContext` is kept,
/// because creating one is expensive and the context is safe to use from any thread.
final class DocumentFilterRenderer {
    static let shared = DocumentFilterRenderer()

    /// Fraction of the page's longest edge used as the blur radius when estimating the
    /// paper background for black and white. Large enough to blur text away, small
    /// enough to follow a shadow or a fold across the page.
    static let backgroundRadiusFraction: CGFloat = 1.0 / 50.0

    /// Luminance, relative to the estimated paper background, below which a pixel is
    /// ink. Faded thermal print sits around 0.7 of its background; a shadow across
    /// clean paper stays close to 1.0 because the background estimate follows it.
    static let inkThreshold: Float = 0.8

    private let context = CIContext(options: nil)

    private init() {}

    /// Applies `filter` to `image`, returning the input itself when there is nothing to
    /// do or the render fails. Never throws: a page that will not filter is still a
    /// valid page, and the caller decides nothing about it.
    func apply(_ filter: DocumentFilter, to image: UIImage) -> UIImage {
        return render(filter, to: image) ?? image
    }

    /// Applies `filter` to `image`, or returns `nil` when the render fails.
    ///
    /// The output is an upright (`.up`) image at the input's scale. `.original` returns
    /// the input unchanged, so the caller can tell that no bytes were touched.
    func render(_ filter: DocumentFilter, to image: UIImage) -> UIImage? {
        guard filter != .original else { return image }
        guard let source = ciImage(from: image) else { return nil }

        let output: CIImage
        switch filter {
        case .original:
            return image
        case .color:
            output = colorEnhanced(source)
        case .grayscale:
            output = grayscale(source)
        case .blackAndWhite:
            output = blackAndWhite(source)
        }

        guard let cgImage = context.createCGImage(output, from: source.extent) else {
            return nil
        }
        return UIImage(cgImage: cgImage, scale: image.scale, orientation: .up)
    }

    /// Applies `filter` to every image, one at a time on a background queue, and hands
    /// the results to `completion` on the main queue in the same order, with the indices
    /// of the pages that failed to render and were passed through unfiltered. `.original`
    /// completes at once with the same array, untouched, and no failures.
    func render(
        _ filter: DocumentFilter,
        to images: [UIImage],
        completion: @escaping ([UIImage], Set<Int>) -> Void
    ) {
        guard filter != .original, !images.isEmpty else {
            completion(images, [])
            return
        }

        DispatchQueue.global(qos: .userInitiated).async {
            var rendered: [UIImage] = []
            var unfiltered = Set<Int>()
            rendered.reserveCapacity(images.count)
            for (index, image) in images.enumerated() {
                autoreleasepool {
                    if let output = self.render(filter, to: image) {
                        rendered.append(output)
                    } else {
                        rendered.append(image)
                        unfiltered.insert(index)
                    }
                }
            }
            DispatchQueue.main.async {
                completion(rendered, unfiltered)
            }
        }
    }

    // MARK: - Filters

    /// The image as Core Image sees it, with the UIImage orientation applied so the
    /// output no longer depends on orientation metadata.
    private func ciImage(from image: UIImage) -> CIImage? {
        let raw: CIImage?
        if let ci = CIImage(image: image) {
            raw = ci
        } else if let cg = image.cgImage {
            raw = CIImage(cgImage: cg)
        } else {
            raw = image.ciImage
        }
        return raw?.oriented(CGImagePropertyOrientation(image.imageOrientation))
    }

    private func colorEnhanced(_ input: CIImage) -> CIImage {
        guard let filter = CIFilter(name: "CIColorControls") else { return input }
        filter.setValue(input, forKey: kCIInputImageKey)
        filter.setValue(1.15, forKey: kCIInputContrastKey)
        filter.setValue(1.2, forKey: kCIInputSaturationKey)
        filter.setValue(0.03, forKey: kCIInputBrightnessKey)
        return filter.outputImage ?? input
    }

    private func grayscale(_ input: CIImage) -> CIImage {
        if let mono = CIFilter(name: "CIPhotoEffectMono") {
            mono.setValue(input, forKey: kCIInputImageKey)
            if let output = mono.outputImage { return output }
        }
        return desaturated(input)
    }

    private func desaturated(_ input: CIImage) -> CIImage {
        guard let filter = CIFilter(name: "CIColorControls") else { return input }
        filter.setValue(input, forKey: kCIInputImageKey)
        filter.setValue(0.0, forKey: kCIInputSaturationKey)
        return filter.outputImage ?? input
    }

    /// A binarised page: the luminance is divided by a blurred copy of itself, which
    /// flattens shadows, folds and faded paper to a uniform background, and the result
    /// is thresholded to pure black and white. Below iOS 14 the threshold filter does
    /// not exist, and the page gets the high-contrast grayscale this filter used to be.
    private func blackAndWhite(_ input: CIImage) -> CIImage {
        let gray = desaturated(input)
        guard #available(iOS 14.0, *) else {
            return highContrast(gray)
        }

        let radius = max(input.extent.width, input.extent.height) * Self.backgroundRadiusFraction
        guard let blur = CIFilter(name: "CIGaussianBlur"),
              let divide = CIFilter(name: "CIDivideBlendMode"),
              let threshold = CIFilter(name: "CIColorThreshold") else {
            return highContrast(gray)
        }

        // Clamping before the blur keeps the edges from fading to transparent black,
        // which would read as ink along the whole border.
        blur.setValue(gray.clampedToExtent(), forKey: kCIInputImageKey)
        blur.setValue(radius, forKey: kCIInputRadiusKey)
        guard let background = blur.outputImage?.cropped(to: input.extent) else {
            return highContrast(gray)
        }

        // CIDivideBlendMode divides the background image by the input image, so the
        // paper estimate is the input: the result is luminance over its background.
        divide.setValue(background, forKey: kCIInputImageKey)
        divide.setValue(gray, forKey: kCIInputBackgroundImageKey)
        guard let flattened = divide.outputImage else {
            return highContrast(gray)
        }

        threshold.setValue(flattened, forKey: kCIInputImageKey)
        threshold.setValue(Self.inkThreshold, forKey: "inputThreshold")
        return threshold.outputImage?.cropped(to: input.extent) ?? highContrast(gray)
    }

    private func highContrast(_ input: CIImage) -> CIImage {
        guard let filter = CIFilter(name: "CIColorControls") else { return input }
        filter.setValue(input, forKey: kCIInputImageKey)
        filter.setValue(1.8, forKey: kCIInputContrastKey)
        filter.setValue(0.05, forKey: kCIInputBrightnessKey)
        return filter.outputImage ?? input
    }
}
