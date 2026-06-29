---
name: amberagent-ios-taste
description: AmberAgent iOS SwiftUI design-taste gate for chat UI, Liquid Glass chrome, sheets, settings, generated-image cards, tool/reasoning pills, streaming markdown, palettes, spacing, hierarchy, and motion. Use when Codex is asked to audit, redesign, polish, or implement visual/UI changes in this repository, especially after screenshots show “grey”, “ugly”, “漏风”, weak hierarchy, janky streaming, awkward sheets, or inconsistent iOS taste.
---

# AmberAgent iOS Taste

## Core Posture

Treat AmberAgent as a quiet, capable iOS agent workspace, not a marketing site. The UI should feel native, calm, glass-aware, readable, and operationally precise.

Use this skill as a taste gate before and after SwiftUI UI changes. Read the real code first, inspect screenshots or run the app when possible, then make the smallest coherent change that fixes the visible problem without spreading style hacks.

This skill is inspired by public taste-skill ideas such as anti-generic layout, concrete design dials, and reference-driven rules, but it is intentionally specialized for AmberAgent iOS. Do not import web habits such as GSAP thinking, landing-page hero composition, or decorative frontend flourishes.

Platform rules outrank generic taste rules. When a task involves SwiftUI screen structure, state, sheets, navigation, lists, or component composition, use `swiftui-ui-patterns` as the primary implementation guide and this skill as the visual review gate. When a task involves iOS 26+ glass chrome, Liquid Glass buttons, activity islands, or grouped glass controls, use `swiftui-liquid-glass` first and this skill second. Do not let image-generation aesthetics override platform-native behavior.

## Workflow

1. **Inspect the actual surface**
   - Read the SwiftUI component being changed and the theme tokens it uses.
   - If the task is visual, inspect the screenshot or run the app before concluding.
   - Use `rg` to find token/component usage before changing shared colors, radii, shadows, or button styles.

2. **Choose the platform guide before taste decisions**
   - Use `swiftui-ui-patterns` for list screens, settings screens, sheets, navigation, state ownership, and control composition.
   - Use `swiftui-liquid-glass` for glass surfaces, top chrome, grouped glass controls, prominent glass buttons, morphing transitions, and iOS 26+ availability/fallback concerns.
   - Use this skill to judge hierarchy, restraint, color, density, rhythm, and whether the result feels like AmberAgent rather than a generic mockup.

3. **Classify the surface**
   - Chat canvas, composer, top chrome, sheet, settings/form list, tool detail, tool pill, reasoning pill, generated image, activity island, or model/provider picker.
   - Each class has different density and contrast needs. Do not apply one card style everywhere.

4. **Define the visual failure**
   - Name the precise issue: weak hierarchy, collapsed surfaces, over-grey background, insufficient insets, duplicate chrome, low contrast, cramped touch target, wrong alignment, janky motion, or stale state.
   - Avoid vague labels like “make it premium”. Translate taste into concrete constraints.

5. **Patch narrowly**
   - Prefer semantic token fixes over per-screen patches only when every major usage has been checked.
   - Prefer component-level fixes when only one surface is wrong.
   - Do not add decorative layers to hide hierarchy problems.

6. **Verify in context**
   - Build when code changed.
   - For visual changes, check at least the changed surface plus one adjacent surface that shares tokens.
   - For global palette changes, check chat, settings, sheets, and tool detail sheets.

## Visual Standards

### Color And Surfaces

- Keep the main chat canvas close to true white in light mode when the user asks for neutral white.
- Do not set `background`, `surface`, and `surface2` to the same value. That collapses hierarchy in settings, sheets, code blocks, and tool detail screens.
- Use surface roles intentionally:
  - `background`: page/canvas.
  - `surface`: grouped forms, sheets, cards that need separation.
  - `surface2`: code blocks, image placeholders, subtle nested surfaces.
  - `accentTint`: tool/reasoning/image action capsules and selected low-emphasis states.
- Accent color should be localized. Avoid recoloring large surfaces with the accent.
- If a global token changes, run usage search and inspect all impacted component families before installing.

### Layout And Hierarchy

- Keep iOS touch targets stable and predictable. Icon-only buttons should still have enough hit area.
- Preserve consistent horizontal rhythm:
  - Chat content and tool/image cards should align by intent, not accidentally right-align because of parent stacks.
  - Tool pills and generated image placeholders should share leading/trailing rhythm when visually grouped.
- Avoid nested card-in-card styling. Use full-width bands or simple grouped surfaces unless the item is genuinely repeated or modal.
- Settings/form pages need visible grouping separation from the page background. If the canvas is white, grouped surfaces need a slight off-white fill or border.
- Sheets need deliberate top padding, handle treatment, internal margins, and content width. Native sheet chrome is fine, but the content cannot look like raw table rows pasted into a modal.

### Top Chrome And Activity Island

- The top center can show a title or activity summary, but avoid a black capsule that competes with the real Dynamic Island.
- Prefer “logical capsule without hard outline”: glassy, subtle, low-border, content-driven shape.
- When activity is live, the center chrome should explain what the agent is doing without stealing input focus.
- When inactive, the title should be short and stable. It should not jitter with streaming content.
- Use native Liquid Glass APIs and grouping rules before custom blur or hand-painted translucency. Multiple nearby glass controls should be visually related through shared shape, spacing, and `GlassEffectContainer` where appropriate.

### Chat Composer

- The composer must keep provider, reasoning level, and context controls reachable while editing.
- Keyboard focus must not be stolen by scroll or overlay state changes.
- The send button state must be derived from the committed text source, not a stale/truncated intermediate binding.
- Do not disable typing just because provider configuration has issues; surface the issue near send or model controls.

### Tool And Reasoning Pills

- Tool and reasoning pills should be legible against a white canvas. Use a faint accent-tinted background plus a soft border when needed.
- Status must be visually distinct:
  - pending/running: subtle accent tint and spinner/timer.
  - success: green check without shouting.
  - failure: red icon/tint with an explanatory card if the result is user-actionable.
- Reasoning can auto-expand while active and auto-collapse after completion if the body has not started; avoid large height jumps during text streaming.

### Generated Images

- Loading placeholders should align with final image card margins and dimensions.
- Share can be compact; save and modify should be equally prominent if both are primary post-generation actions.
- Save means system Photos unless explicitly labeled otherwise. If storing only inside the app, label it as app storage.
- Reinstalling the app deletes sandboxed generated image files. If historical images are missing, show a clear missing-file state instead of infinite loading.
- Full-screen preview should support tap or large vertical drag to dismiss.

### Markdown Streaming And Motion

- Do not apply spring animation to high-frequency markdown text layout changes.
- Spring is appropriate for discrete events: new bubble entry, sheet detent changes, activity island state changes, full-screen image drag rebound.
- For streaming markdown smoothness, prefer:
  - throttled chunk flushing,
  - non-animated auto-scroll while following the bottom,
  - optional opacity-only reveal for new words/spans,
  - stable view identity for markdown blocks.
- Avoid animating content height during token streaming. Height spring plus auto-scroll produces visible jitter.

## Implementation Guardrails

- Read the existing component before changing it. Do not infer structure from screenshots alone.
- Do not rewrite broad UI architecture to fix a local visual issue.
- Do not introduce a new design system layer unless an existing token/component cannot express the needed state.
- If a file is already large, prefer a small extracted subview only when it reduces immediate complexity.
- When changing shared tokens in `PlaceholderViews.swift` / `AmberTheme`, search usage across:
  - `ChatView.swift`
  - `MessageBubbleView.swift`
  - `ChatToolDetailSheet.swift`
  - settings/provider/model screens
  - generated image components
- When touching PhotoKit, background closures, async tasks, or SwiftUI state from callbacks, check actor isolation. PhotoKit `performChanges` runs its changes block off the main actor.

## Review Checklist

Before finalizing a UI change, answer these:

- Does the changed surface still have clear separation from its background?
- Did shared token changes preserve settings, sheets, chat, and tool detail hierarchy?
- Are buttons reachable, stable, and not blocked by overlays or keyboard transitions?
- Does the animation match event frequency?
- Does the implementation remove the visible cause rather than adding cover-up decoration?
- Did the app build, and was the installed build actually the latest one?

For code review output, lead with concrete issues and file references. If no issues are found, state remaining visual or device-verification risks plainly.
