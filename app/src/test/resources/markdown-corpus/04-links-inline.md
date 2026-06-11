## Inline Links

The most common link form in Markdown is the inline link: [link text](https://example.com). The text in brackets is what the user sees; the URL in parentheses is the destination.

Here are a few realistic examples from an assistant answer:

You can read the official Kotlin documentation at [kotlinlang.org](https://kotlinlang.org) for detailed language reference. The [Android developer guides](https://developer.android.com/guide) are the canonical source for platform APIs.

For dependency management, [Maven Central](https://search.maven.org) hosts most open-source JVM artifacts, while [Google Maven](https://maven.google.com) hosts Android-specific ones.

### Autolinks

Raw URLs written between angle brackets are autolinks: <https://example.com>. They produce a clickable link whose text is the URL itself.

Email autolinks also work: <user@example.com>. The renderer should produce a `mailto:` link.

### Link Destinations

Links can point to relative paths: [README](../README.md). They can also contain query strings: [search](https://example.com/search?q=kotlin&lang=en).

A link destination with a fragment: [section anchor](#heading-level-2). These are especially common in long document navigation.

### Bare URL Behaviour

In standard CommonMark a bare URL like https://example.com without angle brackets is *not* automatically linked. However, many renderers (including GFM) do auto-link bare URLs. This sample includes one to test which behaviour the renderer applies: https://github.com/jetbrains/kotlin.

### Nested Inline Content in Link Text

Links can contain *emphasis* in their text: [*Italic link text*](https://example.com). They can also contain `inline code`: [`kotlin.collections`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/).

They cannot, however, contain other links — that would be invalid nesting.
