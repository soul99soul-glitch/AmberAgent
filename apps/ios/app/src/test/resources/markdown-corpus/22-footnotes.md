## Footnotes

Footnotes are a popular Markdown extension (supported by Pandoc, GitHub, and many others). They let you add reference notes without interrupting the prose flow.

### Basic Footnotes

Kotlin was designed by JetBrains and first released in 2011.[^kotlin-history] It became Google's preferred language for Android development in 2019.[^google-kotlin]

The `suspend` keyword marks a function that can be paused and resumed without blocking a thread.[^coroutines] This is the foundation of Kotlin Coroutines, which are significantly more lightweight than threads.[^thread-cost]

[^kotlin-history]: JetBrains publicly announced Kotlin on July 22, 2011 at the JVM Language Summit.
[^google-kotlin]: At Google I/O 2019, Google announced Kotlin-first for Android, meaning new APIs and samples would prefer Kotlin.
[^coroutines]: See the [Kotlin Coroutines documentation](https://kotlinlang.org/docs/coroutines-overview.html) for a full introduction.
[^thread-cost]: A typical JVM thread uses ~1 MB of stack memory; a coroutine uses only a few hundred bytes.

### Footnote with Multi-Paragraph Content

Some footnotes contain multiple paragraphs.[^long-note]

[^long-note]: This is the first paragraph of the long footnote.

    This is the second paragraph. It is indented by four spaces to keep it inside the footnote.

    And a third paragraph. Code blocks inside footnotes must also be indented:

        val x = footnoteExample()

### Inline Footnotes

Some processors support inline footnotes using the `^[...]` syntax: here is an inline footnote^[Inline footnotes are defined at the point of reference, not at the bottom of the document.] embedded in the sentence.

### Multiple References to the Same Footnote

You can reference the same footnote multiple times.[^reuse] For example, after explaining a concept[^reuse] and then again when summarising it.[^reuse]

[^reuse]: This footnote is referenced three times in the document above.

### Numeric vs Named

Footnote references can be numeric[^1] or named.[^named]

[^1]: This is footnote number one.
[^named]: This is a named footnote — the name is for authoring clarity; the rendered number depends on order of appearance.
