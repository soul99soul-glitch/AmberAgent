Plain paragraphs are the most common building block of assistant responses. When you ask a question that doesn't need code or lists, you typically get a few paragraphs of explanation. This file is intentionally simple: no headings, no emphasis, no special syntax — just prose.

The quick brown fox jumps over the lazy dog. This sentence is famously used to test fonts because it contains every letter of the English alphabet. Typographers have relied on it for centuries, and it remains useful whenever you want to check that a rendering surface handles ordinary text correctly.

Paragraphs are separated by blank lines. The blank line tells the parser to start a new paragraph block. Without it, consecutive lines flow into the same paragraph. That behaviour matches CommonMark's spec and is consistent across most Markdown implementations.

Here is a third paragraph. It says nothing particularly interesting, but it gives the renderer something to do: create a third `<p>` element, apply the correct spacing above and below it, and make sure text reflow works at any viewport width. On mobile devices this is especially important because lines wrap far more frequently than on desktop.

And a fourth. Parsers must handle this gracefully without any trailing artefacts or extraneous blank nodes. The paragraph model is deceptively simple but underpins everything else: headings, blockquotes, list items, and table cells all resolve to paragraph content internally.
