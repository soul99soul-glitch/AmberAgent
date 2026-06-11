# Heading Level 1

This is the top-level heading, typically used as the document title. In rendered HTML it becomes an `<h1>`. Most chat UIs render it large and bold.

## Heading Level 2

Second-level headings divide a document into major sections. They correspond to `<h2>` and are the most common heading level in long assistant answers.

### Heading Level 3

Third-level headings carve sections into subsections. Useful when a topic has enough depth to warrant another layer of hierarchy.

#### Heading Level 4

Fourth-level headings are less common but appear in technical documentation. They render as `<h4>` elements.

##### Heading Level 5

Fifth-level headings are rarely needed in chat contexts, but parsers must handle them correctly regardless. This is `<h5>`.

###### Heading Level 6

The deepest heading level in CommonMark is `<h6>`. Renderers sometimes render this at near-body-text size because there is so little visual weight left to shed.

## Setext-Style Headings

CommonMark also allows setext-style headings using underlines. The following two headings use this syntax.

Level 1 Setext
==============

This paragraph follows the setext level-1 heading above. The equals-sign underline must be at least one character long.

Level 2 Setext
--------------

And this paragraph follows a setext level-2 heading (dash underline). Both setext forms are equivalent to their ATX counterparts.
