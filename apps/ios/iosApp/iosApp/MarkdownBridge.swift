import Foundation

// C function declarations from AmberNative.xcframework
@_silgen_name("amber_markdown_parse")
func amber_markdown_parse(_ text: UnsafePointer<CChar>, _ outLen: UnsafeMutablePointer<UInt>) -> UnsafeMutablePointer<UInt8>?

@_silgen_name("amber_markdown_to_html")
func amber_markdown_to_html(_ text: UnsafePointer<CChar>) -> UnsafeMutablePointer<CChar>?

@_silgen_name("amber_free_string")
func amber_free_string(_ s: UnsafeMutablePointer<CChar>)

@_silgen_name("amber_free_bytes")
func amber_free_bytes(_ data: UnsafeMutablePointer<UInt8>, _ len: UInt)

enum MarkdownBridge {
    /// Parse markdown text to packed binary AST blob.
    /// Returns nil on error.
    static func parse(_ text: String) -> Data? {
        var outLen: UInt = 0
        guard let ptr = text.withCString({ textPtr in
            amber_markdown_parse(textPtr, &outLen)
        }) else { return nil }
        defer { amber_free_bytes(ptr, outLen) }
        return Data(bytes: ptr, count: Int(outLen))
    }

    /// Convert markdown to HTML string.
    /// Returns nil on error.
    static func toHtml(_ text: String) -> String? {
        guard let cStr = text.withCString({ ptr in amber_markdown_to_html(ptr) }) else { return nil }
        defer { amber_free_string(cStr) }
        return String(cString: cStr)
    }
}
