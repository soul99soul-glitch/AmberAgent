# AmberAgent iOS Liquid Glass Design System

> Status: draft v1
> Target: iOS 26+ native SwiftUI surface
> Purpose: give Open Design and SwiftUI implementation a concrete visual and interaction anchor.
> Official sources reviewed: Apple Developer Design overview, Design Pathway, Apple Design Resources, WWDC25 "Meet Liquid Glass", WWDC25 "Get to know the new design system", and Apple design principle sessions.

## 0. Apple Official Design Takeaways

Apple's design guidance is not a style pack. It is a working method:

- Start with the experience and interaction model, not visual decoration.
- Use the Human Interface Guidelines as the reference from first sketch to final pixel.
- Use official Apple Design Resources, UI kits, color guides, fonts, product bezels, and SF Symbols as production anchors.
- Prototype to test ideas, not to make speculative art.
- Let the system carry familiarity: platform components, system typography, system symbols, system bars, system sheets, and accessibility behaviors should do as much work as possible.

For AmberAgent, this means:

- The iOS app must be designed around native workflows: chat, search, model selection, tool permission, settings, and memory review.
- A prototype is not accepted merely because it looks like an iPhone. It must map cleanly to SwiftUI structures and Apple system components.
- Each custom component needs a reason to exist. If `List`, `Form`, `ToolbarItem`, `Menu`, `DisclosureGroup`, `sheet`, `safeAreaInset`, or `Label` can express the behavior, prefer the system component.
- Official iOS 26/27 UI kit references should be used before inventing shapes, bars, icon weights, or control densities.

## 1. Design Thesis

AmberAgent iOS should feel like a native iOS AI workstation, not an Android screen translated into SwiftUI and not a web dashboard inside an iPhone frame.

The product personality is:

- Calm: long reading and repeated expert use should feel quiet.
- Precise: dense agent state is visible, but not noisy.
- Native: system navigation, sheets, lists, forms, menus, and typography come first.
- Glass where it belongs: Liquid Glass is system chrome and transient control material, not a decorative card style.

The key rule:

> Reading surfaces are solid. Control surfaces may be glass.

## 2. Native Structure

Use system structures as the first design decision.

| Product area | iOS structure | Notes |
|---|---|---|
| Chat workspace | `NavigationStack` + transcript + bottom safe-area composer | Chat is the app's primary screen. |
| Conversation history | `List` with `.searchable` | Pinned, recent, archived sections. |
| Settings | `Form` with grouped sections | Provider, Assistant, Tools, Memory, Sync. |
| Model selection | `sheet` with detents + segmented control | Group by provider, show capabilities inline. |
| Tool permissions | `Form`, `DisclosureGroup`, `Toggle` | Avoid custom dashboards. |
| iPad | `NavigationSplitView` | Sidebar conversations, detail chat, optional inspector. |

Avoid:

- Fake device chrome as the visual focus.
- Hero layouts.
- Floating web cards for every section.
- Emoji icons in product controls.
- Android Material top bars, drawers, FABs, or chips copied directly.

System structure details:

- Toolbars should express hierarchy through grouping and placement, not extra borders, custom backgrounds, or heavy decoration.
- Group toolbar items by function and frequency. Primary actions stay distinct; secondary actions move into menus.
- Do not mix a text button and an unrelated symbol inside one visual group if people might read them as one action.
- If content is not visible up front, search is a first-class navigation affordance. For AmberAgent, conversation search and command/search should be explicit, reachable, and native.
- Screen-specific actions belong with the content they affect; persistent app-level navigation belongs in tab/sidebar areas.

## 3. Liquid Glass Rules

Liquid Glass usage must map to SwiftUI APIs:

- Group glass controls in `GlassEffectContainer`.
- Use `.glassEffect(...)` only after layout and visual modifiers.
- Use `.interactive()` only for tappable or focusable glass.
- Use `.buttonStyle(.glass)` and `.buttonStyle(.glassProminent)` for action buttons where appropriate.
- Use `glassEffectID` only for real morphing transitions, such as composer expanding into a tool palette or model chip opening the sheet header.
- Gate iOS 26 APIs with `#available(iOS 26, *)`; use `.ultraThinMaterial` fallback only for older OS support if needed.

Apple's material model:

- Liquid Glass is a functional layer floating above content. It provides structure and clarity without stealing focus.
- It should feel physically connected to interaction: controls can glow, bend light, and respond to touch, but these effects must serve input feedback and spatial relationship.
- Avoid applying Liquid Glass to both a parent layer and inner content. Put the material on the control/surface; use fills, transparency, and vibrancy for content sitting on top.
- Use one Liquid Glass variant consistently inside a feature. Do not mix Regular and Clear variants in the same control family.
- Tint must preserve legibility. Use semantic tinting sparingly; do not tint every control.
- Pair modal Liquid Glass with a dimming layer when a task interrupts the main flow.
- For parallel tasks, let glass separate layers without breaking the flow.

Glass zones:

| Zone | Glass level | Shape | Interaction |
|---|---:|---|---|
| Bottom composer accessory | High | continuous rounded rect / capsule group | interactive |
| Model chip in toolbar | Medium | capsule | interactive |
| Floating tool strip above composer | Medium | capsule group | interactive |
| Sheet grab/header controls | Low-medium | capsule / circle | interactive |
| Tab bar / toolbar background | System default | Apple-provided chrome | automatic |
| Message bubbles | None | solid | no glass |
| Code blocks | None | solid dark or solid grouped | no glass |
| Settings rows | None | solid grouped form | no glass |
| Conversation rows | None | list rows | no glass |

Bad glass:

- Glass cards behind long text.
- Glass inside settings forms.
- Glass backgrounds behind code.
- Multiple stacked translucent panels.
- Heavy CSS blur that looks like web glassmorphism.

Scroll edge effects:

- When floating controls overlap scrollable content, use a scroll edge effect or equivalent soft boundary.
- Scroll edge effects are not decoration. Use them only where pinned/floating UI overlaps scroll content.
- Use one scroll edge style per view; do not stack blur gradients, shadows, and dividers at the same edge.
- Prefer soft edge effects on iPhone/iPad. Harder opaque boundaries are for cases needing stronger text/control clarity.

## 4. Visual Tokens

Use Apple system colors in implementation where possible. These tokens describe intent for prototypes.

### Base

- App grouped background: `systemGroupedBackground`
- Primary surface: `secondarySystemGroupedBackground`
- Elevated readable surface: `systemBackground`
- Hairline: separator at 0.5-1 px equivalent
- Primary text: `primary`
- Secondary text: `secondary`
- Tertiary text: `tertiary`

### Accent Roles

Use color by semantic role, not decoration.

| Role | Prototype color | SwiftUI intent |
|---|---|---|
| Model / selected intelligence | indigo | `Color.indigo` |
| Agent context / attention | amber | `Color.orange` or custom amber |
| Tool success / online | green | `Color.green` |
| Search / network / reference | cyan | `Color.cyan` |
| Destructive | red | `Color.red` |

Rules:

- No single-hue purple/blue theme.
- Blue is not the default action color everywhere.
- Gradients are not used for ordinary icons or rows.
- Use tint sparingly, mostly inside badges and small status markers.

Apple-system color guidance:

- Colors should work across Light, Dark, and Increased Contrast appearances.
- Hue differences should clarify semantic role, not decorate unrelated UI.
- Liquid Glass tinting should respect the content behind it; in SwiftUI, prefer system material/tint APIs over hand-authored translucent color stacks.
- A selected or primary action may be tinted, but surrounding controls should remain neutral enough that hierarchy is obvious.

## 5. Typography

Use system typography, not custom display fonts.

| Use | SwiftUI style | Prototype size |
|---|---|---:|
| Large list title | `.largeTitle.weight(.bold)` | 31-34 |
| Navigation title | `.headline` | 17 |
| Message body | `.body` | 15-16 |
| Dense metadata | `.caption` / `.caption2` | 11-12 |
| Code | system monospaced | 12-13 |
| Form row title | `.body` | 15-16 |

Rules:

- No negative letter spacing in final SwiftUI.
- Do not use hero-scale type inside app screens.
- Preserve Dynamic Type compatibility; compact screens may use layout changes, not fixed tiny type.
- Prefer left-aligned hierarchy in key reading and decision moments: setup, settings, sheets, alerts, onboarding, and dense model/tool lists.
- Use weight and placement before color to create emphasis.

## 6. Layout Metrics

Prototype anchors:

- Phone content width: 390-430 px equivalent.
- Screen side padding: 16 pt.
- Dense transcript side padding: 14-16 pt.
- Form group side padding: 16 pt.
- Row min height: 48 pt.
- Toolbar icon hit area: 44 x 44 pt in SwiftUI, even when the visible glyph is smaller.
- Composer corner radius: 24-28 pt.
- Composer icon buttons: 32-36 pt visible, 44 pt hit target.
- Message max width: 76-80% of content width.
- Code block radius: 12-14 pt.
- Section spacing: 20-24 pt between groups.

Concentricity and shape rhythm:

- Nested rounded shapes must feel concentric: inner radius should relate to parent radius minus padding.
- Capsules are appropriate for bars, switches, sliders, compact buttons, and high-touch controls.
- Rounded rectangles are better for dense rows, settings groups, code blocks, and transcript blocks.
- Avoid pinched or flared corners, especially when a glass control sits near the screen edge or inside another rounded container.
- On iPhone, give capsules near screen edges extra margin so they feel intentional and touchable.
- Use consistent radii families instead of arbitrary one-off values.

## 7. Component Specifications

### Chat Workspace

Must contain:

- Native navigation title with conversation name.
- Toolbar model chip; opens model sheet.
- Context strip with memory, active tools, and context usage.
- Transcript with user turns, assistant turns, tool timeline, reasoning disclosure, and code block.
- Bottom composer as safe-area accessory.

Transcript rhythm:

- User messages align trailing and are solid.
- Assistant messages align leading and use solid surfaces or plain text blocks.
- Tool calls are compact timeline rows, not large cards.
- Reasoning is a `DisclosureGroup`-like component.
- Streaming indicator is subtle, near the assistant turn, not a decorative loading panel.

### Composer

Composer is the main Liquid Glass surface.

States:

- Idle: placeholder, attachment, tools, mic, send disabled.
- Draft: text grows vertically, send prominent.
- Tools armed: compact chips above text field.
- Recording: mic state replaces placeholder with waveform or timer.
- Expanded tools: morphs from tool button into palette using glass transition.

Expected SwiftUI shape:

```swift
safeAreaInset(edge: .bottom) {
    GlassEffectContainer(spacing: 8) {
        ComposerBar(...)
            .glassEffect(.regular.interactive(), in: .rect(cornerRadius: 26))
    }
}
```

Composer detail anchors:

- It should feel like an input accessory, not a web chat box.
- The text field should remain readable even when the surrounding composer is glass.
- Tool chips above the input can be glass-adjacent but should not compete with the text field.
- The send action should become prominent only when a draft exists.
- Expanded tool palettes should spatially originate from the tool button, not appear as unrelated panels.

### Model Sheet

Use a native sheet with:

- Medium and large detents.
- Provider segmented control or filter.
- Provider sections.
- Capability metadata: Vision, Tools, Reasoning, Context.
- Current model checkmark.
- Latency/cost/context metadata as secondary text.

Avoid:

- Dashboard grids.
- Giant provider logos.
- Colorful provider gradients.

Sheet detail anchors:

- Use a clear source relationship: toolbar model chip opens the model sheet.
- The sheet should use detent-like proportions and a dimming layer when modal.
- Provider rows should be grouped, readable, and metadata-rich.
- Capabilities are secondary metadata, not colorful badges fighting for attention.
- Selection state should be a checkmark plus persistent placement, not just color.

### Settings

Use iOS `Form`.

Sections:

- Providers
- Assistant
- Tools
- Memory
- Sync
- About

Rows:

- Leading SF Symbol in a small system-colored rounded square only when helpful.
- Title, optional subtitle, trailing value or toggle.
- Chevron for navigation rows.

Settings detail anchors:

- Settings must feel like system Settings: calm grouped surfaces, clear labels, restrained icons, and predictable trailing controls.
- Avoid dashboards, statistics cards, or custom panels in settings unless the row opens into a deeper feature.
- Tuning controls such as temperature/context size can use native sliders or steppers in detail screens, not crowded inline controls.

### Conversation List

Use `List` + `.searchable`.

Sections:

- Pinned
- Recent
- Archived if needed

Rows:

- Conversation title.
- Last message preview.
- Time, unread count, pin marker.
- Optional model/status marker, but no colorful avatars unless the assistant identity matters.

### Icons and Symbols

- Use SF Symbols directly in SwiftUI.
- In prototypes, use simple monochrome line symbols or text initials; never emoji as product controls.
- Use the same symbol across devices to preserve meaning.
- If no symbol clearly communicates an action, use a text label.
- Menus should use symbols where recognition improves scanability, but avoid repeating/tweaking near-identical symbols for related commands.
- Common AmberAgent mappings:
  - Conversations: `bubble.left.and.bubble.right`
  - New chat: `square.and.pencil`
  - Model: `cpu`
  - Tools: `wrench.and.screwdriver`
  - Memory: `brain`
  - Search: `magnifyingglass`
  - Settings: `gearshape`
  - Attach: `paperclip`
  - Voice: `waveform`
  - Send: `arrow.up.circle.fill`
  - Reasoning: `sparkles` or `brain.head.profile`
  - Code: `chevron.left.forwardslash.chevron.right`

## 8. Motion

Motion should feel system-native:

- Sheet presentation uses system sheet motion.
- Model chip selection updates with a short matched transition if feasible.
- Composer expansion uses springy but restrained animation.
- Reasoning disclosure uses standard expand/collapse.
- Avoid continuous ambient animation.
- Interactions should feel spatially anchored: sheets and palettes originate from the control that opened them.
- Maintain continuity across device sizes: the same task should feel like it continues, not restarts, when moving from iPhone to iPad.

Durations:

- Micro control feedback: 120-180 ms.
- Sheet/content transitions: 250-350 ms.
- Composer morph: 280-420 ms spring.

Continuity:

- Define shared component anatomy before designing iPhone/iPad variants.
- Keep core interactions consistent across iPhone, iPad, and Mac-style layouts even when presentation changes.
- The iPad version should expand the same content into `NavigationSplitView`, not invent a separate dashboard.

## 9. Accessibility

Non-negotiable:

- Hit targets 44 pt minimum.
- Text and controls respect Dynamic Type.
- Glass is never the only contrast mechanism.
- Reduce Transparency should fall back to opaque grouped surfaces.
- Reduce Motion should disable morphing transitions.
- Color cannot be the only status indicator; pair with label or symbol.
- Increased Contrast should make glass controls more clearly bordered and readable.
- Reduced Transparency should make glass frostier or more opaque, not remove hierarchy.
- Text and controls layered above glass must remain legible over varied content.

## 10. Prototype Quality Checklist

Before accepting an Open Design prototype:

- Does the app still look native if the fake phone shell is removed?
- Are long text, code, and form rows solid and readable?
- Is glass limited to chrome/transient controls?
- Would each custom component map to a real SwiftUI component?
- Are SF Symbols implied instead of emoji?
- Does the chat screen reveal AmberAgent-specific power: tools, memory, model, context?
- Does the palette avoid one-note blue/purple gradients?
- Are settings and lists using iOS grouped patterns?
- Are controls sized like touch targets, not web buttons?
- Did the design use official Apple resources as references, not invented web component shapes?
- Are rounded shapes concentric and consistent?
- Are toolbar items grouped by function/frequency, with secondary actions in menus?
- Does each glass element have a functional reason?
- Are scroll edge effects used only where floating UI overlaps content?
- Are symbols SF-symbol-like, repeated consistently across screens?

## 11. SwiftUI Implementation Notes

Use these as implementation anchors:

- `NavigationStack` for iPhone chat.
- `NavigationSplitView` for iPad.
- `List` / `Form` wherever possible.
- `.toolbar` for model and conversation actions.
- `.safeAreaInset(edge: .bottom)` for composer.
- `.sheet` with detents for model/provider selector.
- `DisclosureGroup` for reasoning and tool details.
- `Menu` for compact model/tool actions when no sheet is needed.
- `Label` + SF Symbols for controls.
- `GlassEffectContainer` for grouped glass controls.
- `.buttonStyle(.glass)` and `.buttonStyle(.glassProminent)` for iOS 26+ actions.
- Official Apple Design Resources / iOS UI Kit should be checked before finalizing custom dimensions or component anatomy.
- Prefer system bars and grouped bar items before custom toolbar backgrounds.
- Use `.searchable` for conversation search and any command/search surface that is a primary navigation affordance.
- Use `scrollContentBackground`, `safeAreaInset`, and system scroll edge behavior intentionally; avoid manual blur stacks where system effects exist.

## 12. Open Design Prompt Anchor

Every OD run for this iOS surface should include:

> Follow `IOS_LIQUID_GLASS_DESIGN_SYSTEM.md`. Use Apple official Design guidance as the source of truth: HIG, Apple Design Resources, SF Symbols, and the iOS 26/27 Liquid Glass design system. Do not create a web dashboard or decorative iPhone mockup. Use iOS system structures first. Glass only belongs to composer, toolbar/control clusters, sheets, and functional floating chrome. Keep transcript, code, forms, and lists solid and readable. Use concentric shapes, grouped toolbar hierarchy, semantic tinting, scroll-edge boundaries only where needed, and SF-symbol-like icons. Show AmberAgent-specific agent power through tool timeline, reasoning disclosure, memory/context status, model/provider selector, native search, and native settings forms.
