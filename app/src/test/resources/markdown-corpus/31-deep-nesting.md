## Deep Nesting

This file tests deeply nested structures: blockquotes inside lists inside blockquotes, code inside nested lists, and other pathological but valid combinations.

### Blockquote Inside a List Item

- First list item at the top level.

  > This is a blockquote nested inside the first list item. It starts after a blank line and an indent.
  >
  > The blockquote has a second paragraph.

- Second list item at the top level.

  > Another blockquote inside the second item.

### List Inside a Blockquote Inside a List

1. Outer ordered item one.

   > **Blockquote inside item one:**
   >
   > - Unordered item A inside the blockquote
   > - Unordered item B inside the blockquote
   >   - Sub-item of B, still inside the blockquote
   >
   > Back to blockquote prose.

2. Outer ordered item two.

### Code Block Inside Nested List

- Level 1 item
  - Level 2 item with a code block following it:

    ```kotlin
    fun nestedCode() = "I am inside a level-2 list item"
    ```

  - Level 2 item after the code block
- Back to level 1

### Blockquote Containing a Table

> | Column A | Column B |
> | --- | --- |
> | Cell 1 | Cell 2 |
> | Cell 3 | Cell 4 |
>
> The table above is inside a blockquote.

### Deeply Nested Blockquotes with Lists

> Level 1 blockquote
>
> - List item inside level 1 blockquote
> - Another item
>
> > Level 2 blockquote
> >
> > - List inside level 2 blockquote
> >
> > > Level 3 blockquote
> > >
> > > This is the deepest nest in this file. The renderer must track indentation correctly across all three levels of quoting.

### Paragraph + Code + Paragraph in a List Item (Loose)

- First loose item

  This is a paragraph inside the first list item.

  ```kotlin
  val x = "code inside loose list item"
  ```

  Another paragraph in the same item.

- Second loose item

  Just a paragraph here.
