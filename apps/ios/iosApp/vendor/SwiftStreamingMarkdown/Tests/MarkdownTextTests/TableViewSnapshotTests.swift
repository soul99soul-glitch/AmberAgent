//
//  Copyright (c) Microsoft Corporation. All rights reserved.
//  Licensed under the MIT License. See LICENSE in the project root for license information.
//

import Foundation
@testable import SwiftStreamingMarkdown
import SwiftUI
import XCTest

@MainActor
final class TableViewSnapshotTests: SnapshotTestCase {

  // MARK: - Helper Methods

  /// Create a citation attachment with pre-decoded data
  private func createCitationAttachment(url: String, text: String) -> InlineCitationAttachment {
    // Create a proper citation URL with query parameters following the existing pattern
    let baseURL = "http://example.com"
    let citationURL = "\(baseURL)?citationMarker=9F742443&citationTitle=\(text)&citationA11yValue=\(text)"

    guard let citationData = CitationCoder.default.decode(linkDestination: citationURL),
          let attachment = InlineCitationAttachment(citationData: citationData, citationConfig: .default) else {
      XCTFail("Failed to create citation attachment for text: \(text)")
      // Return a minimal attachment as fallback (though test will fail)
      return InlineCitationAttachment(payload: Data(), citationConfig: .default)
    }
    return attachment
  }

  private func createTableView(_ attributedString: NSAttributedString) -> some View {
    CanvasView {
      VStack(alignment: .leading) {
        TableView(
          headings: [NSMutableAttributedString(string: "Header")],
          rows: [[NSMutableAttributedString(attributedString: attributedString)]]
        )
      }
    }
  }

  // MARK: - Snapshot Tests

  func testTableCellWithOnlyCitation() throws {
    // Create content with only a citation
    let mutableString = NSMutableAttributedString()
    let citationAttachment = createCitationAttachment(url: "source2", text: "Research Study")
    mutableString.append(NSAttributedString(attachment: citationAttachment))

    let view = createTableView(mutableString)
    assert(view)
  }

}
