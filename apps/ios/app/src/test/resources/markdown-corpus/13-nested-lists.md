## Nested Lists

Lists can be nested by indenting child items with at least two spaces (or four, depending on parser). Here is a realistic assistant answer that uses nested lists to explain an Android project structure.

### Project Module Structure

- **app** — Main application module
  - `ui/` — All Compose screens and components
    - `pages/` — Full-screen destinations
    - `components/` — Reusable composables
  - `data/` — Data layer
    - `repository/` — Repository classes
    - `model/` — Domain models
    - `api/` — Network API interfaces
  - `di/` — Koin dependency injection modules
- **core** — Shared pure-Kotlin modules
  - `agent-runtime` — Agent Kernel contracts
  - `agent-store-room` — Room persistence
  - `agent-utils` — JSON utilities
- **feature** — Feature-specific modules
  - `deepread/api` — DeepRead agent types
  - `chat/api` — Chat agent types
- **ai** — AI provider abstraction
- **highlight** — Syntax highlighting
- **search** — Search SDK (Exa, Tavily, Zhipu)

### Mixed Ordered and Unordered Nesting

1. Set up your Android Studio project
   - Install Android Studio Ladybug or later
   - Enable Kotlin DSL for Gradle
   - Choose **Empty Activity** template
2. Add dependencies to `libs.versions.toml`
   - Declare version catalogs for all libraries
   - Reference them in module `build.gradle.kts` files
   - Sync the project
3. Configure the app module
   - Set `compileSdk`, `minSdk`, and `targetSdk`
   - Add `buildFeatures { compose = true }`
   - Enable `kotlinOptions { jvmTarget = "17" }`
4. Write your first Composable
   - Create a Kotlin file in `ui/pages/`
   - Annotate the function with `@Composable`
   - Preview it with `@Preview`

### Three Levels Deep

- Level 1
  - Level 2
    - Level 3 item A
    - Level 3 item B
  - Another level 2
    - Level 3 under the second item
- Back to level 1
