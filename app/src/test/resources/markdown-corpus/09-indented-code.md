## Indented Code Blocks

Before fenced code blocks became the dominant style, Markdown used indentation (4 spaces or 1 tab) to denote code. CommonMark still supports this syntax.

Here is a simple indented code block showing a shell command:

    echo "Hello, world!"
    ls -la /usr/local/bin
    export PATH="$PATH:/usr/local/bin"

Note that a blank line and a paragraph precede each indented block, and the code block ends when the indentation stops.

The following example shows an indented Python snippet that might appear in an older tutorial:

    def fibonacci(n):
        if n <= 1:
            return n
        a, b = 0, 1
        for _ in range(2, n + 1):
            a, b = b, a + b
        return b

    for i in range(10):
        print(fibonacci(i))

Indented code blocks cannot specify a language, so syntax highlighting is never applied to them. That is one reason fenced blocks with language tags are now preferred.

A paragraph can follow the indented block immediately (after a blank line):

    # This is inside the code block
    # Not a Markdown heading

The line above is code, not a heading, because it is indented by four spaces.

You can have multiple separate indented blocks in one document:

    first block
    line two

Some prose in between.

    second block
    line two of second

The two blocks above are separate code nodes even though they look similar.
