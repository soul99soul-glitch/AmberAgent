## Special Characters and Backslash Escapes

CommonMark defines a set of ASCII punctuation characters that can be escaped with a backslash to prevent them from being interpreted as Markdown syntax.

### Escapable Characters

The full set of escapable characters: \! \# \$ \% \& \' \( \) \* \+ \, \- \. \/ \: \; \< \= \> \? \@ \[ \\ \] \^ \_ \` \{ \| \} \~

Each one above should render as the literal character, not as Markdown syntax.

### Practical Escape Examples

A literal asterisk: \*not italic\* — the backslashes prevent emphasis.

A literal underscore: \_not italic either\_.

A literal backtick: \`not inline code\`.

A literal hash at line start:
\# This is not a heading because the hash is escaped.

A literal bracket: \[not a link\](https://example.com).

A literal pipe: \| not a table cell separator \|.

### HTML Entities

HTML character entities can also represent special characters:

- `&amp;` → &amp;
- `&lt;` → &lt;
- `&gt;` → &gt;
- `&quot;` → &quot;
- `&apos;` → &apos;
- `&copy;` → &copy;
- `&mdash;` → &mdash;
- `&ndash;` → &ndash;
- `&nbsp;` → non-breaking&nbsp;space
- `&hellip;` → &hellip;

### Numeric Character References

Decimal: &#65; &#66; &#67; → A B C

Hexadecimal: &#x1F600; → 😀 (emoji via code point)

### Literal Backslash

A single backslash followed by a non-punctuation character is not an escape: \a \z \1 — the backslash is kept verbatim.

A literal backslash is produced by `\\`: here is one → \\

### Special Characters in Code Spans

Inside inline code, characters are literal and no escaping is needed: `\*not italic\*` renders showing the backslashes and asterisks exactly.

### URL-Safe Characters

In link destinations `(`, `)`, and `&` need attention:

- Parentheses in URLs: [example](https://example.com/path(1)) — balanced parens are fine.
- Ampersand in URLs: [search](https://example.com/?a=1&b=2) — unescaped in Markdown source.
- HTML entity in URL: [entity](&amp;url) — entity should be decoded before use as href.

### Combining Escapes and Emphasis

A sentence with an escaped asterisk \* followed by *actual emphasis* and then another escaped asterisk \* — the parser must handle the mix correctly.
