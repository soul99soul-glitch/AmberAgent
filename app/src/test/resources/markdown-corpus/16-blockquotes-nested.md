## Blockquotes — Nested

Blockquotes use `>` prefixes. They can be nested by stacking multiple `>` characters.

### Simple Blockquote

> The best way to get a project done faster is to start sooner.
> — Jim Highsmith

### Multi-Paragraph Blockquote

> Coroutines are a way of doing cooperative multitasking. Unlike threads, they don't block the underlying OS thread; they suspend execution and yield control back to the caller.
>
> This means you can have thousands of coroutines running on just a handful of threads, making them much more memory-efficient than traditional thread-per-request models.

### Nested Blockquote

> **Outer quote:** Here is the original statement that was made in a previous message.
>
> > **Inner quote:** And this is a reply or elaboration nested inside the first quote.
> >
> > The inner quote can also span multiple paragraphs.
>
> We return to the outer quote level here, after the nested section.

### Three Levels Deep

> Level 1 — original message
>
> > Level 2 — first reply
> >
> > > Level 3 — reply to the reply
> > >
> > > This is the deepest level in this example.
> >
> > Back to level 2.
>
> Back to level 1.

### Blockquote Containing Other Blocks

> ### Heading Inside a Blockquote
>
> A blockquote can contain headings, lists, and code:
>
> - Item A inside a blockquote
> - Item B inside a blockquote
>
> ```kotlin
> val msg = "code inside a blockquote"
> println(msg)
> ```
>
> The content above is fully rendered inside the quote boundary.

### Lazy Continuation

> This is the start of a blockquote.
The continuation line does not need a `>` prefix in lazy mode.
But the next blank line ends it.

Back to regular prose here.
