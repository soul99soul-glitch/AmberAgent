## Inline HTML

Unlike HTML blocks, inline HTML tags appear inside a paragraph or other inline context. They are passed through verbatim.

### Basic Inline Tags

You can use <strong>bold</strong> and <em>italic</em> via inline HTML, though the Markdown equivalents (`**` and `*`) are preferred. You can also use <code>monospace</code> inline.

A <span style="color: red;">red span</span> via inline HTML — note that in many sandboxed renderers, inline styles are stripped for security.

### Superscript and Subscript

The water molecule is H<sub>2</sub>O. The speed of light is approximately 3×10<sup>8</sup> m/s. These HTML tags have no Markdown equivalent in CommonMark, so inline HTML is the only way to achieve them (without KaTeX).

### Abbreviation via `<abbr>`

The <abbr title="Application Programming Interface">API</abbr> documentation is available on the developer portal.

### Line Break via `<br>`

This line ends with an inline HTML break.<br>
This line starts on a new line because of the `<br>` tag above.

### Kbd Tag

Press <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>P</kbd> to open the command palette in Android Studio.

### Mark Tag

The <mark>highlighted text</mark> stands out visually when the `<mark>` tag is rendered by the browser.

### Del and Ins

The price was <del>$99</del> <ins>$49</ins> after the sale.

### Mixed Markdown and Inline HTML

You can mix **bold Markdown** with <em>italic HTML</em> in the same paragraph. The parser must handle both simultaneously without confusing the two systems.

Here is a `code span` next to <code>inline HTML code</code> — both should render as monospace.

### Self-Closing Tags

An explicit break: line one<br />line two. The self-closing `/>`form is also valid inline HTML.

An inline image via HTML: <img src="https://example.com/icon.png" alt="icon" width="16" height="16" />
