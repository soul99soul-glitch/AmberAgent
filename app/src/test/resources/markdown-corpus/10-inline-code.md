## Inline Code

Inline code uses backtick delimiters and renders as a monospace span. It is extremely common in technical writing.

Use `val` for immutable bindings and `var` for mutable ones in Kotlin. The `fun` keyword declares a function. Classes are declared with `class`, interfaces with `interface`, and objects with `object`.

### Single Backtick

The simplest form: `println("hello")`. Any ASCII character can appear inside single backticks, including `*`, `_`, `[`, and `]`, which would otherwise be interpreted as Markdown syntax.

### Double Backtick for Literal Backtick

If the code itself contains a backtick, wrap it in double backticks: `` val s = `this is a string` ``. The space before and after is stripped by the parser, so you get a single backtick in the rendered output.

### Longer Delimiter Strings

Triple backtick inline code is also valid: ```code with `` inside``` — useful when the code contains double backticks.

### Common Usage Patterns in Assistant Answers

- Call `Activity.setContentView()` to inflate the layout.
- The `@Composable` annotation marks a function as a Compose UI component.
- Set `minSdk = 24` in your `build.gradle.kts` to target Android 7.0+.
- Import `androidx.compose.material3.*` for Material 3 components.
- Use `LaunchedEffect(key)` to launch a coroutine tied to the composition lifecycle.
- The `remember { mutableStateOf(...) }` pattern creates a state holder that survives recomposition.

### Inline Code with Emphasis

You cannot apply emphasis *inside* inline code — the backticks consume everything literally. But you can have inline code inside emphasis: *use `val`*, or **the `fun` keyword**.

### Path and Command Examples

Run `./gradlew :app:connectedAndroidTest` to execute instrumented tests on a connected device. The output is written to `app/build/outputs/androidTest-results/`.

The environment variable `ANDROID_HOME` must point to your SDK installation, e.g. `/Users/you/Library/Android/sdk`.
