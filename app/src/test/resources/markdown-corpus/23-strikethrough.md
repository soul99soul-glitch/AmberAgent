## Strikethrough (GFM Extension)

Strikethrough text is wrapped in double tildes: `~~text~~`. It is a GitHub Flavored Markdown extension and renders as an `<s>` or `<del>` HTML element.

### Basic Usage

The old recommendation was to use ~~AsyncTask~~ for background work, but it has been deprecated since Android API 30. You should use Kotlin Coroutines or WorkManager instead.

~~SharedPreferences~~ has been superseded by DataStore for most new use cases, though SharedPreferences remains available for compatibility.

### Strikethrough in Lists

Here is a to-do list where completed items are crossed out:

- ~~Research coroutines API~~
- ~~Set up Koin dependency injection~~
- ~~Create Room database schema~~
- Write Repository layer
- Implement ViewModel
- Build Compose UI

### Strikethrough with Other Emphasis

You can combine strikethrough with other inline decorations:

- ~~*struck and italic*~~
- ~~**struck and bold**~~
- **~~bold and struck~~**
- *~~italic and struck~~*
- ~~`struck code`~~ (note: some parsers may not render this as strikethrough)

### Strikethrough in a Table

| Library | Status | Replacement |
| --- | --- | --- |
| ~~AsyncTask~~ | Deprecated | Coroutines + Dispatchers.IO |
| ~~RxJava~~ | Legacy | Kotlin Flow |
| ~~Picasso~~ | Maintained but older | Coil |
| ~~GSON~~ | Works but slower | Kotlinx Serialization |
| Room | Active | — |

### Double Tilde Ambiguity

A single tilde is not strikethrough: ~this is not struck~. Only double tildes trigger the extension.

Two separate single tildes: text ~ with ~ single ~ tildes — all plain text.

An unclosed double tilde: ~~this never closes because there is no matching delimiter on this line.
