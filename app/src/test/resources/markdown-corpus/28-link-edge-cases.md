## Link Edge Cases

This file tests link syntax that is likely to trip up parsers or renderers.

### Parentheses in URLs

Links whose URLs contain unbalanced parentheses need careful handling. CommonMark allows balanced parentheses without escaping:

[MDN Web Docs](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Array/map)

[Wikipedia: Kotlin (programming language)](https://en.wikipedia.org/wiki/Kotlin_(programming_language))

An URL with an escaped closing paren: [example](https://example.com/path\(1\))

### Spaces in URLs

Spaces in link destinations must be encoded as `%20` or the link must use angle brackets:

[Search with spaces](<https://example.com/search?q=hello world>)

### Empty Link Text

The spec allows an empty link text: [](https://example.com). Renderers vary on whether they produce a visible link.

### Empty URL

A link with an empty destination: [empty URL]() — this is technically valid and should produce a link with `href=""`.

### Link Immediately After Emphasis

*italic*[link](https://example.com) — no space between emphasis close and link open.

**bold**[another link](https://example.com/bold) — same with bold.

### Link Followed Immediately by Punctuation

Visit [example.com](https://example.com). The period is not part of the URL.

See [the docs](https://example.com/docs), then restart.

[Link at end of sentence](https://example.com)?

### Angle-Bracket Link Destinations

The angle-bracket form allows spaces and most special characters:

[weird URL](<https://example.com/path with spaces?q=a&b=c#fragment>)

### Nested Brackets in Link Text

Link text can contain `[` and `]` if they are balanced: [[nested brackets]](https://example.com) — this is uncommon but must not crash the parser.

### Reference Link — Undefined Reference

A reference link with no matching definition: [this reference][undefined-ref] — CommonMark requires rendering this as literal text `[this reference][undefined-ref]`.

### Link with Fragment Only

[Jump to heading](#link-edge-cases) — a fragment-only URL is valid.

### Protocol-Relative URL

[Protocol-relative](//example.com) — starts with `//` and inherits the page protocol.
