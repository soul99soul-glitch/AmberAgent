## Links with Titles

Inline links can include an optional title in quotes after the URL. The title typically appears as a tooltip on hover in HTML renderers:

[Kotlin language site](https://kotlinlang.org "Official Kotlin documentation")

[Android Studio download](https://developer.android.com/studio "Download the latest Android Studio release")

Titles can also use single quotes:

[Jetpack Compose](https://developer.android.com/jetpack/compose 'Android modern UI toolkit')

Or parentheses:

[Material Design 3](https://m3.material.io (Material Design system specification))

### Reference Links

Reference-style links separate the link destination from the usage. This keeps long URLs out of the prose and makes text more readable:

I recommend reading [the Kotlin docs][kotlin] and checking out [the Compose samples][compose-samples].

[kotlin]: https://kotlinlang.org "Kotlin programming language"
[compose-samples]: https://github.com/android/compose-samples "Jetpack Compose samples"

Reference IDs are case-insensitive and can be reused:

Visit [Kotlin][kotlin] multiple times without repeating the URL.

### Collapsed and Shortcut Reference Links

When the link text matches the reference ID you can use a collapsed reference: [kotlin][] — just brackets with no ID.

Or a shortcut reference: [kotlin] — just the text in a single pair of brackets.

### Empty Titles

The title attribute may be an empty string: [example](https://example.com ""). This is technically valid though uncommon.

### Title Escaping

Titles may contain escaped quotes: [tricky](https://example.com "She said \"hello\""). The parser must handle the backslash-escape within the title string.
