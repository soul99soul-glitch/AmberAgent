## Images

Images use the same syntax as links but with an exclamation mark prefix:

![Android robot logo](https://developer.android.com/images/brand/Android_Robot.png)

The text in brackets is the alt text, which screen readers use to describe the image. The URL in parentheses is the image source.

### Images with Titles

Like links, images can have optional titles that appear as tooltips:

![Jetpack Compose logo](https://developer.android.com/images/jetpack/compose-tutorial/compose-logo.png "Jetpack Compose logo")

### Relative Image Paths

In a real project, images are often local assets:

![Architecture diagram](../assets/architecture-overview.png)

The renderer is responsible for resolving relative paths, though in most chat contexts only absolute URLs are meaningful.

### Images Inside Links

An image can be used as the clickable content of a link by nesting the image syntax inside link brackets:

[![Kotlin logo](https://kotlinlang.org/assets/images/open-graph/kotlin.png)](https://kotlinlang.org)

Clicking the image above would navigate to kotlinlang.org.

### Alt Text Matters

The alt attribute is important for accessibility. When the image fails to load, the alt text is displayed in its place. A good alt text describes the image's *content*, not its appearance — for example, "screenshot of the Settings screen showing dark mode toggle" rather than just "screenshot".

### Inline Images in Paragraphs

Images can appear inline within a sentence, like this satellite view of the Eiffel Tower ![Eiffel Tower thumbnail](https://upload.wikimedia.org/wikipedia/commons/thumb/8/85/Smiley.svg/24px-Smiley.svg.png) though in practice inline images inside paragraphs can be tricky to style consistently across different devices.
