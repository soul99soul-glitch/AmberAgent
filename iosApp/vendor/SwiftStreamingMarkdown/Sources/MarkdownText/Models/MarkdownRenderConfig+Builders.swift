//
//  Copyright (c) Microsoft Corporation. All rights reserved.
//  Licensed under the MIT License. See LICENSE in the project root for license information.
//

import Foundation

extension MarkdownRenderConfig {
  /// Returns a copy with `shouldAnimateText` replaced.
  public func withShouldAnimateText(value: Bool) -> MarkdownRenderConfig {
    MarkdownRenderConfig(
      shouldAnimateText: value,
      blockQuoteStyle: blockQuoteStyle,
      headingStyle: headingStyle,
      orderedListStyle: orderedListStyle,
      paragraphStyle: paragraphStyle,
      tableStyle: tableStyle,
      inlineStyle: inlineStyle,
      textContextMenu: textContextMenu,
      citationConfig: citationConfig,
      blockSpacing: blockSpacing,
      paragraphLineSpacing: paragraphLineSpacing,
      tableCellHorizontalPadding: tableCellHorizontalPadding,
      tableCellVerticalPadding: tableCellVerticalPadding,
      listItemSpacing: listItemSpacing,
      headingLineSpacing: headingLineSpacing,
      tableMaxColumnWidth: tableMaxColumnWidth,
      unorderedListBulletWidth: unorderedListBulletWidth,
      collapsesSoftBreaks: collapsesSoftBreaks
    )
  }

  /// Returns a copy with `blockQuoteStyle` replaced.
  public func withBlockQuoteStyle(value: MarkdownTextStyle) -> MarkdownRenderConfig {
    MarkdownRenderConfig(
      shouldAnimateText: shouldAnimateText,
      blockQuoteStyle: value,
      headingStyle: headingStyle,
      orderedListStyle: orderedListStyle,
      paragraphStyle: paragraphStyle,
      tableStyle: tableStyle,
      inlineStyle: inlineStyle,
      textContextMenu: textContextMenu,
      citationConfig: citationConfig,
      blockSpacing: blockSpacing,
      paragraphLineSpacing: paragraphLineSpacing,
      tableCellHorizontalPadding: tableCellHorizontalPadding,
      tableCellVerticalPadding: tableCellVerticalPadding,
      listItemSpacing: listItemSpacing,
      headingLineSpacing: headingLineSpacing,
      tableMaxColumnWidth: tableMaxColumnWidth,
      unorderedListBulletWidth: unorderedListBulletWidth,
      collapsesSoftBreaks: collapsesSoftBreaks
    )
  }

  /// Returns a copy with `headingStyle` replaced.
  public func withHeadingStyle(value: MarkdownHeadingTextStyle) -> MarkdownRenderConfig {
    MarkdownRenderConfig(
      shouldAnimateText: shouldAnimateText,
      blockQuoteStyle: blockQuoteStyle,
      headingStyle: value,
      orderedListStyle: orderedListStyle,
      paragraphStyle: paragraphStyle,
      tableStyle: tableStyle,
      inlineStyle: inlineStyle,
      textContextMenu: textContextMenu,
      citationConfig: citationConfig,
      blockSpacing: blockSpacing,
      paragraphLineSpacing: paragraphLineSpacing,
      tableCellHorizontalPadding: tableCellHorizontalPadding,
      tableCellVerticalPadding: tableCellVerticalPadding,
      listItemSpacing: listItemSpacing,
      headingLineSpacing: headingLineSpacing,
      tableMaxColumnWidth: tableMaxColumnWidth,
      unorderedListBulletWidth: unorderedListBulletWidth,
      collapsesSoftBreaks: collapsesSoftBreaks
    )
  }

  /// Returns a copy with `orderedListStyle` replaced.
  public func withOrderedListStyle(value: MarkdownTextStyle) -> MarkdownRenderConfig {
    MarkdownRenderConfig(
      shouldAnimateText: shouldAnimateText,
      blockQuoteStyle: blockQuoteStyle,
      headingStyle: headingStyle,
      orderedListStyle: value,
      paragraphStyle: paragraphStyle,
      tableStyle: tableStyle,
      inlineStyle: inlineStyle,
      textContextMenu: textContextMenu,
      citationConfig: citationConfig,
      blockSpacing: blockSpacing,
      paragraphLineSpacing: paragraphLineSpacing,
      tableCellHorizontalPadding: tableCellHorizontalPadding,
      tableCellVerticalPadding: tableCellVerticalPadding,
      listItemSpacing: listItemSpacing,
      headingLineSpacing: headingLineSpacing,
      tableMaxColumnWidth: tableMaxColumnWidth,
      unorderedListBulletWidth: unorderedListBulletWidth,
      collapsesSoftBreaks: collapsesSoftBreaks
    )
  }

  /// Returns a copy with `paragraphStyle` replaced.
  public func withParagraphStyle(value: MarkdownTextStyle) -> MarkdownRenderConfig {
    MarkdownRenderConfig(
      shouldAnimateText: shouldAnimateText,
      blockQuoteStyle: blockQuoteStyle,
      headingStyle: headingStyle,
      orderedListStyle: orderedListStyle,
      paragraphStyle: value,
      tableStyle: tableStyle,
      inlineStyle: inlineStyle,
      textContextMenu: textContextMenu,
      citationConfig: citationConfig,
      blockSpacing: blockSpacing,
      paragraphLineSpacing: paragraphLineSpacing,
      tableCellHorizontalPadding: tableCellHorizontalPadding,
      tableCellVerticalPadding: tableCellVerticalPadding,
      listItemSpacing: listItemSpacing,
      headingLineSpacing: headingLineSpacing,
      tableMaxColumnWidth: tableMaxColumnWidth,
      unorderedListBulletWidth: unorderedListBulletWidth,
      collapsesSoftBreaks: collapsesSoftBreaks
    )
  }

  /// Returns a copy with `tableStyle` replaced.
  public func withTableStyle(value: MarkdownTableTextStyle) -> MarkdownRenderConfig {
    MarkdownRenderConfig(
      shouldAnimateText: shouldAnimateText,
      blockQuoteStyle: blockQuoteStyle,
      headingStyle: headingStyle,
      orderedListStyle: orderedListStyle,
      paragraphStyle: paragraphStyle,
      tableStyle: value,
      inlineStyle: inlineStyle,
      textContextMenu: textContextMenu,
      citationConfig: citationConfig,
      blockSpacing: blockSpacing,
      paragraphLineSpacing: paragraphLineSpacing,
      tableCellHorizontalPadding: tableCellHorizontalPadding,
      tableCellVerticalPadding: tableCellVerticalPadding,
      listItemSpacing: listItemSpacing,
      headingLineSpacing: headingLineSpacing,
      tableMaxColumnWidth: tableMaxColumnWidth,
      unorderedListBulletWidth: unorderedListBulletWidth,
      collapsesSoftBreaks: collapsesSoftBreaks
    )
  }

  /// Returns a copy with `inlineStyle` replaced.
  public func withInlineStyle(value: MarkdownInlineTextStyle) -> MarkdownRenderConfig {
    MarkdownRenderConfig(
      shouldAnimateText: shouldAnimateText,
      blockQuoteStyle: blockQuoteStyle,
      headingStyle: headingStyle,
      orderedListStyle: orderedListStyle,
      paragraphStyle: paragraphStyle,
      tableStyle: tableStyle,
      inlineStyle: value,
      textContextMenu: textContextMenu,
      citationConfig: citationConfig,
      blockSpacing: blockSpacing,
      paragraphLineSpacing: paragraphLineSpacing,
      tableCellHorizontalPadding: tableCellHorizontalPadding,
      tableCellVerticalPadding: tableCellVerticalPadding,
      listItemSpacing: listItemSpacing,
      headingLineSpacing: headingLineSpacing,
      tableMaxColumnWidth: tableMaxColumnWidth,
      unorderedListBulletWidth: unorderedListBulletWidth,
      collapsesSoftBreaks: collapsesSoftBreaks
    )
  }

  /// Returns a copy with `textContextMenu` replaced. Pass `nil` to remove the
  /// custom context menu and fall back to the system menu.
  public func withTextContextMenu(value: TextContextMenu?) -> MarkdownRenderConfig {
    MarkdownRenderConfig(
      shouldAnimateText: shouldAnimateText,
      blockQuoteStyle: blockQuoteStyle,
      headingStyle: headingStyle,
      orderedListStyle: orderedListStyle,
      paragraphStyle: paragraphStyle,
      tableStyle: tableStyle,
      inlineStyle: inlineStyle,
      textContextMenu: value,
      citationConfig: citationConfig,
      blockSpacing: blockSpacing,
      paragraphLineSpacing: paragraphLineSpacing,
      tableCellHorizontalPadding: tableCellHorizontalPadding,
      tableCellVerticalPadding: tableCellVerticalPadding,
      listItemSpacing: listItemSpacing,
      headingLineSpacing: headingLineSpacing,
      tableMaxColumnWidth: tableMaxColumnWidth,
      unorderedListBulletWidth: unorderedListBulletWidth,
      collapsesSoftBreaks: collapsesSoftBreaks
    )
  }

  /// Returns a copy with `blockSpacing` replaced.
  public func withBlockSpacing(value: CGFloat) -> MarkdownRenderConfig {
    MarkdownRenderConfig(
      shouldAnimateText: shouldAnimateText,
      blockQuoteStyle: blockQuoteStyle,
      headingStyle: headingStyle,
      orderedListStyle: orderedListStyle,
      paragraphStyle: paragraphStyle,
      tableStyle: tableStyle,
      inlineStyle: inlineStyle,
      textContextMenu: textContextMenu,
      citationConfig: citationConfig,
      blockSpacing: value,
      paragraphLineSpacing: paragraphLineSpacing,
      tableCellHorizontalPadding: tableCellHorizontalPadding,
      tableCellVerticalPadding: tableCellVerticalPadding,
      listItemSpacing: listItemSpacing,
      headingLineSpacing: headingLineSpacing,
      tableMaxColumnWidth: tableMaxColumnWidth,
      unorderedListBulletWidth: unorderedListBulletWidth,
      collapsesSoftBreaks: collapsesSoftBreaks
    )
  }

  /// Returns a copy with `paragraphLineSpacing` replaced.
  public func withParagraphLineSpacing(value: CGFloat) -> MarkdownRenderConfig {
    MarkdownRenderConfig(
      shouldAnimateText: shouldAnimateText,
      blockQuoteStyle: blockQuoteStyle,
      headingStyle: headingStyle,
      orderedListStyle: orderedListStyle,
      paragraphStyle: paragraphStyle,
      tableStyle: tableStyle,
      inlineStyle: inlineStyle,
      textContextMenu: textContextMenu,
      citationConfig: citationConfig,
      blockSpacing: blockSpacing,
      paragraphLineSpacing: value,
      tableCellHorizontalPadding: tableCellHorizontalPadding,
      tableCellVerticalPadding: tableCellVerticalPadding,
      listItemSpacing: listItemSpacing,
      headingLineSpacing: headingLineSpacing,
      tableMaxColumnWidth: tableMaxColumnWidth,
      unorderedListBulletWidth: unorderedListBulletWidth,
      collapsesSoftBreaks: collapsesSoftBreaks
    )
  }

  /// Returns a copy with `tableCellHorizontalPadding` replaced.
  public func withTableCellHorizontalPadding(value: CGFloat) -> MarkdownRenderConfig {
    MarkdownRenderConfig(
      shouldAnimateText: shouldAnimateText,
      blockQuoteStyle: blockQuoteStyle,
      headingStyle: headingStyle,
      orderedListStyle: orderedListStyle,
      paragraphStyle: paragraphStyle,
      tableStyle: tableStyle,
      inlineStyle: inlineStyle,
      textContextMenu: textContextMenu,
      citationConfig: citationConfig,
      blockSpacing: blockSpacing,
      paragraphLineSpacing: paragraphLineSpacing,
      tableCellHorizontalPadding: value,
      tableCellVerticalPadding: tableCellVerticalPadding,
      listItemSpacing: listItemSpacing,
      headingLineSpacing: headingLineSpacing,
      tableMaxColumnWidth: tableMaxColumnWidth,
      unorderedListBulletWidth: unorderedListBulletWidth,
      collapsesSoftBreaks: collapsesSoftBreaks
    )
  }

  /// Returns a copy with `tableCellVerticalPadding` replaced.
  public func withTableCellVerticalPadding(value: CGFloat) -> MarkdownRenderConfig {
    MarkdownRenderConfig(
      shouldAnimateText: shouldAnimateText,
      blockQuoteStyle: blockQuoteStyle,
      headingStyle: headingStyle,
      orderedListStyle: orderedListStyle,
      paragraphStyle: paragraphStyle,
      tableStyle: tableStyle,
      inlineStyle: inlineStyle,
      textContextMenu: textContextMenu,
      citationConfig: citationConfig,
      blockSpacing: blockSpacing,
      paragraphLineSpacing: paragraphLineSpacing,
      tableCellHorizontalPadding: tableCellHorizontalPadding,
      tableCellVerticalPadding: value,
      listItemSpacing: listItemSpacing,
      headingLineSpacing: headingLineSpacing,
      tableMaxColumnWidth: tableMaxColumnWidth,
      unorderedListBulletWidth: unorderedListBulletWidth,
      collapsesSoftBreaks: collapsesSoftBreaks
    )
  }

  /// Returns a copy with `listItemSpacing` replaced.
  public func withListItemSpacing(value: CGFloat) -> MarkdownRenderConfig {
    MarkdownRenderConfig(
      shouldAnimateText: shouldAnimateText,
      blockQuoteStyle: blockQuoteStyle,
      headingStyle: headingStyle,
      orderedListStyle: orderedListStyle,
      paragraphStyle: paragraphStyle,
      tableStyle: tableStyle,
      inlineStyle: inlineStyle,
      textContextMenu: textContextMenu,
      citationConfig: citationConfig,
      blockSpacing: blockSpacing,
      paragraphLineSpacing: paragraphLineSpacing,
      tableCellHorizontalPadding: tableCellHorizontalPadding,
      tableCellVerticalPadding: tableCellVerticalPadding,
      listItemSpacing: value,
      headingLineSpacing: headingLineSpacing,
      tableMaxColumnWidth: tableMaxColumnWidth,
      unorderedListBulletWidth: unorderedListBulletWidth,
      collapsesSoftBreaks: collapsesSoftBreaks
    )
  }

  /// Returns a copy with `headingLineSpacing` replaced.
  public func withHeadingLineSpacing(value: CGFloat?) -> MarkdownRenderConfig {
    MarkdownRenderConfig(
      shouldAnimateText: shouldAnimateText,
      blockQuoteStyle: blockQuoteStyle,
      headingStyle: headingStyle,
      orderedListStyle: orderedListStyle,
      paragraphStyle: paragraphStyle,
      tableStyle: tableStyle,
      inlineStyle: inlineStyle,
      textContextMenu: textContextMenu,
      citationConfig: citationConfig,
      blockSpacing: blockSpacing,
      paragraphLineSpacing: paragraphLineSpacing,
      tableCellHorizontalPadding: tableCellHorizontalPadding,
      tableCellVerticalPadding: tableCellVerticalPadding,
      listItemSpacing: listItemSpacing,
      headingLineSpacing: value,
      tableMaxColumnWidth: tableMaxColumnWidth,
      unorderedListBulletWidth: unorderedListBulletWidth,
      collapsesSoftBreaks: collapsesSoftBreaks
    )
  }

  /// Returns a copy with `collapsesSoftBreaks` replaced.
  public func withCollapsesSoftBreaks(value: Bool) -> MarkdownRenderConfig {
    MarkdownRenderConfig(
      shouldAnimateText: shouldAnimateText,
      blockQuoteStyle: blockQuoteStyle,
      headingStyle: headingStyle,
      orderedListStyle: orderedListStyle,
      paragraphStyle: paragraphStyle,
      tableStyle: tableStyle,
      inlineStyle: inlineStyle,
      textContextMenu: textContextMenu,
      citationConfig: citationConfig,
      blockSpacing: blockSpacing,
      paragraphLineSpacing: paragraphLineSpacing,
      tableCellHorizontalPadding: tableCellHorizontalPadding,
      tableCellVerticalPadding: tableCellVerticalPadding,
      listItemSpacing: listItemSpacing,
      headingLineSpacing: headingLineSpacing,
      tableMaxColumnWidth: tableMaxColumnWidth,
      unorderedListBulletWidth: unorderedListBulletWidth,
      collapsesSoftBreaks: value
    )
  }

  /// Returns a copy with `tableMaxColumnWidth` replaced.
  public func withTableMaxColumnWidth(value: CGFloat) -> MarkdownRenderConfig {
    MarkdownRenderConfig(
      shouldAnimateText: shouldAnimateText,
      blockQuoteStyle: blockQuoteStyle,
      headingStyle: headingStyle,
      orderedListStyle: orderedListStyle,
      paragraphStyle: paragraphStyle,
      tableStyle: tableStyle,
      inlineStyle: inlineStyle,
      textContextMenu: textContextMenu,
      citationConfig: citationConfig,
      blockSpacing: blockSpacing,
      paragraphLineSpacing: paragraphLineSpacing,
      tableCellHorizontalPadding: tableCellHorizontalPadding,
      tableCellVerticalPadding: tableCellVerticalPadding,
      listItemSpacing: listItemSpacing,
      headingLineSpacing: headingLineSpacing,
      tableMaxColumnWidth: value,
      unorderedListBulletWidth: unorderedListBulletWidth,
      collapsesSoftBreaks: collapsesSoftBreaks
    )
  }

  /// Returns a copy with `unorderedListBulletWidth` replaced.
  public func withUnorderedListBulletWidth(value: CGFloat) -> MarkdownRenderConfig {
    MarkdownRenderConfig(
      shouldAnimateText: shouldAnimateText,
      blockQuoteStyle: blockQuoteStyle,
      headingStyle: headingStyle,
      orderedListStyle: orderedListStyle,
      paragraphStyle: paragraphStyle,
      tableStyle: tableStyle,
      inlineStyle: inlineStyle,
      textContextMenu: textContextMenu,
      citationConfig: citationConfig,
      blockSpacing: blockSpacing,
      paragraphLineSpacing: paragraphLineSpacing,
      tableCellHorizontalPadding: tableCellHorizontalPadding,
      tableCellVerticalPadding: tableCellVerticalPadding,
      listItemSpacing: listItemSpacing,
      headingLineSpacing: headingLineSpacing,
      tableMaxColumnWidth: tableMaxColumnWidth,
      unorderedListBulletWidth: value,
      collapsesSoftBreaks: collapsesSoftBreaks
    )
  }
}
