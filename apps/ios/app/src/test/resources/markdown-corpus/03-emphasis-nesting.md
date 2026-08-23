## Emphasis and Strong Emphasis

The two basic inline decorations are *italic* and **bold**. You can also write them with underscores: _italic_ and __bold__. Both forms are equivalent in CommonMark.

Nesting is where things get interesting. You can have **bold containing *italic* text** or *italic containing **bold** text*. Parsers must handle the open/close delimiters correctly even when they interleave.

Triple delimiters give you ***bold italic*** all at once — equivalent to `***text***`. That's the same as `**_text_**` or `*__text__*`.

### Edge Cases

An asterisk at the start of a word: *emphasis* right next to a comma, like *this*, should close cleanly.

Emphasis that spans a **long phrase with multiple words** should still apply across all of them without breaking at spaces.

You can have _nested **strong inside em**_ or **nested _em inside strong_**. The renderer must keep both decorations active simultaneously.

~~Strikethrough~~ is a GFM extension covered in a later sample, but it is worth noting that it does not nest naturally with emphasis in some parsers.

An underscore in the _middle\_of\_a\_word_ requires escaping if you don't want emphasis. Without escaping, `middle_of_a_word` is treated as plain text because intraword underscores are not emphasis delimiters in CommonMark.

**Bold text followed immediately by *italic* with no space** — the renderer must not merge these into one run.

A single asterisk at end of sentence like this* is not emphasis because the left-flanking delimiter rule is not satisfied.

Here is a sentence where emphasis is used inside a longer explanation: the *Quick Sort* algorithm has an *average* time complexity of $O(n \log n)$, though its *worst-case* is $O(n^2)$ when the pivot selection is poor.
