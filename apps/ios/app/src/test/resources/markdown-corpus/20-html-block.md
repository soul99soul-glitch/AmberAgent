## HTML Blocks

CommonMark allows raw HTML blocks. A block-level HTML tag that appears at the start of a line begins an HTML block, which is passed through to the output verbatim.

### Simple DIV

<div class="note">
  <strong>Note:</strong> This is a raw HTML block. CommonMark passes it through to the HTML output unchanged. The Markdown renderer must recognise it as a block, not try to parse it as Markdown.
</div>

The paragraph above and below are regular Markdown paragraphs.

### HTML Table (not GFM)

<table>
  <thead>
    <tr><th>Name</th><th>Value</th></tr>
  </thead>
  <tbody>
    <tr><td>Alpha</td><td>1</td></tr>
    <tr><td>Beta</td><td>2</td></tr>
    <tr><td>Gamma</td><td>3</td></tr>
  </tbody>
</table>

### Details / Summary

<details>
<summary>Click to expand the explanation</summary>

The content inside the details block is hidden until the user clicks the summary. This is useful for collapsible sections in documentation.

```kotlin
// This code block is inside a details element
val answer = 42
```

</details>

### Script Tag (Type 6 Block)

HTML blocks can be any block-level element including `<script>`:

<script type="text/javascript">
// This content should NOT be executed — it is inside a Markdown document.
// The renderer must decide how to handle script blocks.
console.log("html block script");
</script>

### Comment Block

<!-- This is an HTML comment. It should not appear in the rendered output. -->

The comment above should be invisible in rendered output. This paragraph should appear immediately after the heading.

### Pre Block

<pre>
Preformatted   text   here
  with   extra   spaces
    and controlled indentation
</pre>
