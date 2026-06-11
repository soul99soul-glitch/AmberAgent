## Hard and Soft Line Breaks

CommonMark distinguishes between soft breaks and hard breaks within a paragraph.

### Soft Breaks

A soft break is a single newline inside a paragraph. The renderer typically converts it to a space (or treats it as a join point for text reflow). Here is a paragraph where each sentence is on its own source line:

Line one of this paragraph.
Line two follows immediately below in the source.
Line three is also on its own line in the source.

In the rendered output, those three lines should appear as one continuous paragraph, with the line breaks converted to spaces.

### Hard Breaks

A hard break is forced by ending a line with two or more trailing spaces (or a backslash `\`) before the newline. It renders as a `<br>` element.

This line ends with two trailing spaces.  
This line should start on a new line in the rendered output.

And here using the backslash syntax:\
This line follows the backslash hard break.

### Mixing Soft and Hard Breaks

Soft break: one newline, no spaces.
Still part of the same paragraph.

Hard break with two trailing spaces at the end:  
New line within the same paragraph.  
And another hard-break new line.

### Breaks Inside Other Blocks

Line breaks inside blockquotes:

> First quoted line.
> Second quoted line (soft break — same paragraph in the quote).
> Third line.  
> Fourth line after a hard break within the blockquote.

Line breaks inside list items:

- First item, first line.  
  First item, second line (hard break, continued indented).
- Second item, single line.

### Trailing Spaces in Code Blocks

Note that trailing spaces inside fenced code blocks are preserved verbatim and do NOT trigger hard breaks, because code blocks are not paragraph content:

```
line with two trailing spaces  
next line — the spaces above are preserved as-is
```
