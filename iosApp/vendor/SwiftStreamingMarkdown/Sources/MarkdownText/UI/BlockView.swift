//
//  Copyright (c) Microsoft Corporation. All rights reserved.
//  Licensed under the MIT License. See LICENSE in the project root for license information.
//

import Foundation
import SwiftUI

struct BlockView: View {

  @Environment(\.markdownConfig) var config: MarkdownRenderConfig

  let renderables: [MarkdownRenderable]

  init(renderables: [MarkdownRenderable]) {
    self.renderables = renderables
  }

  var body: some View {
    VStack(alignment: .leading, spacing: config.blockSpacing) {
      ForEach(renderables) { renderable in
        SingleBlockView(renderable: renderable)
      }
    }
  }
}

struct SingleBlockView: View {

  @Environment(\.markdownConfig) var config: MarkdownRenderConfig
  @Environment(\.markdownUsesTextKit1ForAttachmentFreeText) var usesTextKit1ForAttachmentFreeText

  let renderable: MarkdownRenderable

  init(renderable: MarkdownRenderable) {
    self.renderable = renderable
  }

  var body: some View {
    Group {
      switch renderable {
      case .heading(_, _, let contents):
        let usesTextKit1 = shouldUseTextKit1(for: contents)
        HStack(spacing: 0) {
          ParagraphView(
            contents: contents,
            lineSpacing: config.headingLineSpacing,
            usesTextKit1: usesTextKit1
          )
            .id(usesTextKit1)
            .transition(.opacity)
            .accessibilityAddTraits(.isHeader)
          Spacer()
        }
      case .paragraph(_, let contents):
        let usesTextKit1 = shouldUseTextKit1(for: contents)
        HStack(spacing: 0) {
          ParagraphView(
            contents: contents,
            lineSpacing: config.paragraphLineSpacing,
            usesTextKit1: usesTextKit1
          )
            .id(usesTextKit1)
            .fixedSize(horizontal: false, vertical: true)
            .transition(.opacity)
          Spacer()
        }
      case .latex(_, let latexString):
        ScrollView(.horizontal) {
          HStack(spacing: 0) {
            BlockMathView(
              latex: latexString,
              color: Color(config.paragraphStyle.textColor),
              pointSize: Typography.base.uiFont.pointSize
            )
            Spacer()
          }
        }.scrollIndicators(.hidden)
      case .orderedList(_, let items):
        OrderedListView(items: items)
      case .unorderedList(_, let items, let nestedLevel):
        UnorderedListView(items: items, nestedLevel: nestedLevel)
      case .codeBlock(_, let language, let code):
        CodeBlockView(language: language ?? "",
                      code: code)
      case .thematicBreak:
        ThematicBreakView()
      case .table(_, let headers, let rows, let rawMarkdown):
        TableView(headings: headers,
                  rows: rows,
                  rawMarkdown: rawMarkdown)
      case .blockQuote(_, let item):
        BlockQuoteView(item: item)
      }
    }
  }

  private func shouldUseTextKit1(for contents: NSAttributedString) -> Bool {
    guard usesTextKit1ForAttachmentFreeText else { return false }
    var containsAttachment = false
    contents.enumerateAttribute(
      .attachment,
      in: NSRange(location: 0, length: contents.length)
    ) { value, _, stop in
      guard value != nil else { return }
      containsAttachment = true
      stop.pointee = true
    }
    return !containsAttachment
  }
}
