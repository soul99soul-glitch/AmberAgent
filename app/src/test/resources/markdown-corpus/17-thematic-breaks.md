## Thematic Breaks (Horizontal Rules)

A thematic break is a visual divider rendered as an `<hr>`. CommonMark accepts three forms: three or more hyphens, asterisks, or underscores, optionally with spaces between them.

---

The line above is a thematic break using three hyphens. Below is one using three asterisks:

***

And here is one using three underscores:

___

### Longer Forms

You can use more characters or add spaces:

- - -

* * * * *

_ _ _ _ _ _ _

All of the lines above should produce the same rendered output: a single horizontal rule.

### Common Use in Chat Responses

An assistant might use thematic breaks to separate distinct sections of a long answer without headings. For example:

Here is the first approach to solving this problem. You define a sealed class hierarchy for your states, and each state contains the data it needs.

---

The second approach uses a single data class with nullable fields. This is less type-safe but requires fewer classes.

---

The third approach uses a state machine library. This adds a dependency but gives you transition guards, history states, and side-effect hooks.

### Thematic Break vs Setext Heading Underline

A line of hyphens is a setext heading underline only if it immediately follows a paragraph with no blank line in between. With a blank line, or when the preceding content is not a paragraph, it is a thematic break.

This is a paragraph

---

The line above is a thematic break (blank line before it).

This is a heading
---

The line above turned the preceding line into an h2 heading (no blank line).
