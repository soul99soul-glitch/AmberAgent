# Amber · Design System — "Terminal × Modern"

> A handoff spec for redesigning Amber (consumer AI assistant, mobile) in a warm-neutral
> graphite aesthetic borrowed from OpenCode Mobile. Hand this to Claude Code to extend the
> redesign to **any page not yet covered**, consistently.
>
> **Golden rule — reskin, never restructure.** Your job is to apply this *visual language*
> to Amber's existing screens and features. Do **not** remove, simplify, or invent product
> functionality. If a screen has an attach button, a filter, a tab — keep it; just restyle it.

---

## 0. How to use this document

When asked to redesign / build an uncovered Amber screen:

1. Read this whole file.
2. Reuse the existing implementation in `redesign/` (tokens + components) — don't re-derive.
   Load order matters (see §9).
3. Identify every functional element on the source screen. Keep all of them.
4. Map each element to a component pattern below (§6). If none fits, compose a new one from
   the **primitives** (§6.1) and the **tokens** (§2–5) — never hardcode a raw hex or a
   one-off font.
5. Obey the invariants in §7. They are the difference between "on-system" and "off."
6. Mount the screen in a 380×832 `PhoneScreen` (§8) and wire it into the router (§9).

---

## 1. Aesthetic direction

**Terminal × Modern, warm-neutral.** A calm, papery graphite world where the *interface
chrome* whispers and the *content* speaks. Personality comes from a single restrained move:
**monospace for machine-facts** (model ids, token counts, ctx sizes, timings, section
labels, version strings) set against a humanist sans for everything a person reads.

Feels like: a well-made terminal that grew up — precise, legible, unhurried. Warm off-white
paper, soft ink, one earthy accent (terracotta = "amber"). No glassmorphism, no gradients on
chrome, no glow, no emoji, no decorative SVG illustration. Flat surfaces, hairline borders,
generous quiet space.

**Anti-goals (these read as "AI slop" here):** gradient backgrounds on UI, rounded cards with
a colored left-border accent, neon, drop-shadow stacks, Inter/Roboto, emoji as iconography,
breathing orbs / gemstone blobs, multiple accent colors competing at once.

---

## 2. Color tokens

Tokens are CSS custom properties scoped to `.amb[data-theme="…"]`. **Never** write a literal
hex in a component — always `var(--token)`. The accent and the "live" signal are independent
of the neutral base (see §2.3).

### 2.1 Neutral bases (4 themes)

Each theme defines the same token names. Pick base via the `data-theme` attribute on the
`.amb` root: `light` · `dark` · `sage` · `sage-dark`.

| Token | light | dark | sage | sage-dark | Role |
|---|---|---|---|---|---|
| `--bg` | `#f4f2ec` | `#161512` | `#f0f2ea` | `#131711` | Page background |
| `--surface` | `#faf9f5` | `#1c1b17` | `#f6f8f0` | `#191d15` | Cards, trays (slightly lighter than bg in light themes) |
| `--surface-2` | `#efece4` | `#211f1a` | `#e7eadf` | `#1e2219` | Insets: pills, chips, toggles-off, badges |
| `--raised` | `#ffffff` | `#23211c` | `#ffffff` | `#20241b` | Focused field / segmented active thumb |
| `--ink` | `#1b1a17` | `#ece8df` | `#1b201a` | `#e6ebdf` | Primary text, also the "ink button" fill |
| `--ink-2` | `#57544c` | `#a8a298` | `#535a4d` | `#a0a896` | Secondary text, inactive icons |
| `--ink-3` | `#8f8b80` | `#756f64` | `#888f7e` | `#6e7563` | Tertiary / metadata text |
| `--ink-4` | `#b6b1a4` | `#564f45` | `#b0b5a4` | `#515845` | Faint: placeholders, disabled, list markers |
| `--line` | `#e4e0d6` | `#2e2b25` | `#e0e3d6` | `#2a2e22` | Hairline borders, dividers |
| `--line-2` | `#d6d1c4` | `#3a362e` | `#d0d4c4` | `#353a2c` | Stronger border (chips, active outlines) |
| `--user-bg` | `#1b1a17` | `#ece8df` | `#1b201a` | `#e6ebdf` | User chat bubble fill (inverts in dark) |
| `--user-ink` | `#f6f4ee` | `#1b1a17` | `#f3f5ec` | `#1b201a` | User bubble text |
| `--code-bg` | `#edeae1` | `#211f1a` | `#e8ebdf` | `#1e2219` | Inline code background |

### 2.2 Shadows (per-theme)

- `--shadow` — resting elevation for cards/segmented thumbs. Light: `0 1px 2px rgba(40,36,28,.05), 0 8px 24px -16px rgba(40,36,28,.18)`.
- `--shadow-lg` — overlays (dropdown menu, sheets). Light: `0 2px 6px rgba(40,36,28,.06), 0 24px 48px -24px rgba(40,36,28,.28)`.
- Dark/sage-dark shadows are deeper and pure-black based. Use the token; don't roll your own.

### 2.3 Accent + signal (independent of base)

- `--accent` (default `#b8623a` terracotta) — **selection & "what I'm using now."** Applied
  inline on the screen root so it can be themed live. Curated palette:
  `#b8623a` terracotta · `#5e9c6e` sage-green · `#4f86d6` blue · `#9277c4` purple · `#c2607a` rose.
- `--accent-ink` — text/icon color drawn *on* an accent fill. Light accents need dark ink;
  use the `inkFor(hex)` map: green→`#0f150e`, gold→`#1a1408`, everything else→`#ffffff`.
- `--signal` — the **"live / online / just-finished"** green. Deliberately *separate* from the
  accent so a green dot still reads as "live" even when the accent is also green. Per-base
  value (light `#5e9c6e`, sage `#2f8f76`, etc.). Used for: the breathing `.dot`, the ✓ on a
  completed tool call.

**Accent vs. signal — the rule:**
- Use **accent** for *user selection*: the chosen model, the active provider, the selected
  swatch, the send button once there's a draft, pinned-marker fills, "recommended" emphasis.
- Use **signal** for *system liveness*: streaming/online dots, a tool call that just
  succeeded. Never use signal to mean "selected."

---

## 3. Typography

Three families, loaded from Google Fonts:

```
--font-ui:   "Hanken Grotesk", "Noto Sans SC", system-ui, sans-serif;   /* Latin UI */
--font-cn:   "Noto Sans SC", "Hanken Grotesk", system-ui, sans-serif;   /* Chinese body */
--font-mono: "JetBrains Mono", "Noto Sans SC", ui-monospace, monospace; /* machine facts */
```

Helper classes: `.mono` (also enables tabular & slashed-zero), `.cn` (Chinese), `.tnum`.

- **Base `.amb`** uses `--font-ui` with `letter-spacing: -0.01em` and antialiasing.
- **Chinese text** → wrap in `.cn` (resets letter-spacing to 0). All human-readable body,
  titles, chips, labels in this product are Chinese.
- **Monospace is the signature.** Put `--font-mono` (class `.mono`, letter-spacing 0) on
  **everything that is a machine-fact**: model ids (`claude-sonnet-4-5`), `200K ctx`, `$3/M`,
  timings (`3.2s · auto`), section eyebrows (`// AGENT`, `SELECT MODEL`), version strings,
  the `amber` wordmark, tool names. This mono/sans split *is* the brand. Don't mono-set
  human prose; don't sans-set machine facts.

### Type scale (px, mobile 380-wide)

| Use | size / weight | font |
|---|---|---|
| Screen/section title | 18.5–19 / 700 | cn |
| Header session title | 16 / 700 | cn |
| Body (chat, list rows) | 15–15.5 / 400–500 | cn |
| Secondary / descriptions | 12.5–13.5 / 400 | cn |
| Metadata, ids, ctx, price | 11–12.5 / 400–500 | **mono** |
| Section eyebrow label | 11 / 600, `letter-spacing .12em`, UPPERCASE | **mono** |
| Tiny tag / badge | 10.5 / 600 | cn or mono |

Never below ~10.5px. Minimum touch target 44px (we use 48px circles in the composer).

---

## 4. Spacing, radius, borders

- **Spacing rhythm:** screen gutters `16–18px`. Vertical gaps between chat turns `22px`;
  list row padding `13–14px`; section-label top margin `20–26px`.
- **Radii:** cards & fields `14px`; chips/pills/toggles fully round `999px`; composer input
  pill `26px`; small badges `5–8px`; phone screen `40px`. Ink buttons use
  `corner-shape: superellipse(4)` where supported (progressive enhancement).
- **Borders:** hairlines are `1px solid var(--line)`; stronger outlines (chips, active)
  `var(--line-2)`. Dividers inside cards are `1px solid var(--line)`. Prefer **hairline +
  flat fill** over shadow for separation. Reserve shadow for true overlays.
- **Layout:** always flex/grid with `gap`. Never space siblings with bare inline whitespace
  or per-element margins.

---

## 5. Motion

Keyframes live in `oc-amber.css`. Standard easing: `cubic-bezier(.2,.85,.25,1)` (and
`.2,.8,.2,1` for press).

- `.pressable` — add to every tappable control. Gives `scale(.975)` on `:active` + color
  transitions. Non-negotiable for tactility.
- `blink` — terminal cursor (1.05s steps).
- `breathe` — the live `.dot` halo (2.4s).
- `fadeRise` — chat turns / revealed content entering (translateY 4px).
- `screenIn` — overlay sheets.
- `slideInR` / `slideInL` — router push / pop transitions (respect a "reduce motion" toggle:
  fall back to `none`).
- Capsule/accordion morphs (attach button, model groups) animate `width` / `grid-template-rows`
  with the standard easing over `.28–.34s`.

Avoid infinite decorative loops on content; only the cursor and the live dot loop.

---

## 6. Component patterns

### 6.1 Primitives (classes in CSS)

- `.card` — surface + hairline + 14px radius. The default container for grouped rows.
- `.field` — input surface; `:focus-within` lifts to `--raised` + `--ink-3` border.
- `.btn-ink` — primary action: `--ink` fill, `--bg` text, superellipse. `.btn-accent` swaps
  to accent fill + `--accent-ink`.
- `.hair` — bottom hairline (headers).
- `.dot` — 7px live dot with breathing halo (`.idle` = grey, no halo).
- `.cursor` — blinking block caret (`.accent` / `.ink` / `.signal` variants).
- `.mono` / `.cn` / `.tnum` — font switches.
- `.noscroll` — scroll without visible scrollbar (used on all scroll regions).
- `.md` — markdown content (code/strong/ul/li styling).

### 6.2 Signature components (in `redesign/aa-*.jsx`)

- **Wordmark** — `amber` in mono 700 + a terracotta blinking cursor block. The logo.
- **StatusBar / HomeIndicator** — iOS-style chrome. The home indicator strip takes a
  `footerBg` so it can continue the composer tray color for an immersive bottom (see §7.4).
- **Two-line ChatHeader** — left icon (history / back chevron) · a two-line title block
  (bold session title + **mono model-id** with a dropdown chevron — this row is the model
  menu trigger) · optional context meter · edit/new icon.
- **Context meter** — 5 tiny mono bars + `%`, accent-filled proportionally. Compact, no donut.
- **Top model menu** — dropdown that *expands downward from under the header* (animated
  `grid-template-rows`), dimming the page below. Provider groups are an accordion (`+`/`−`).
  The **active provider name and the selected model name are accent-colored**; everything else
  is neutral ink. Rows are `name … ctx` in mono — **no note badges, no check icon** (color is
  the only selection signal, which keeps the `ctx` column aligned). This is the reusable
  "expanding menu" template for any future bottom/ча menu.
- **ThinkingStrip** — collapsible. Brain icon + mono `Thinking` + mono `Ns · mode` + chevron.
  Expanded: thoughts in `--ink-3` behind a 2px `--line-2` left rule.
- **ToolCall row** — mono, on `--code-bg`: status square + accent tool-name + faint arg +
  `--signal` ✓ when done (or live dot when running).
- **AskUser card** — accent `?` + "询问 N 个问题" + question blocks with pill options.
- **User bubble** — `--user-bg` fill, `--user-ink`, asymmetric radius
  `16px 16px 5px 16px`, right-aligned, max-width 82%.
- **Assistant turn** — mono `amber` label (accent) + optional streaming cursor, then content,
  then a row of faint action icons (copy / retry / tts / more).
- **Composer (InputBar)** — three separate rounded `--surface-2` surfaces on a `--surface`
  tray with a top hairline: a circular **+** that *morphs* into a `[× · image · file]` capsule
  (animated width, + rotates 135° to ×), the center input pill (26px radius), and a circular
  send that fills with **accent** once there's a draft. Enter sends, Shift+Enter newlines.
- **List row (SRow)** — icon · cn label · optional mono value · chevron, hairline-separated
  inside a `.card`. `danger` variant tints `#c2553f`-ish.
- **Segmented control (Seg)** — `--surface-2` track, active thumb `--raised` + `--shadow`.
- **Toggle** — accent fill when on, `--line-2` when off, white knob.
- **Section label** — mono uppercase eyebrow, prefixed with an accent `//`.
- **Status pill** — small outlined cn tag; "connected" uses accent border+text.

---

## 7. Invariants (the make-or-break rules)

1. **Reskin, don't restructure.** Preserve every functional control on the source screen.
2. **Tokens only.** No literal colors/fonts/radii in components. New shade needed? Derive with
   `color-mix(in srgb, var(--token) X%, …)`, don't invent a hex.
3. **Mono = machine, sans/cn = human.** Honor the split rigorously (§3).
4. **Immersive bottom.** On screens with a composer, the footer tray is `--surface`, carries a
   top hairline, and the home-indicator strip continues `--surface` (pass `footerBg`). The
   three composer surfaces all share `--surface-2`.
5. **Accent = selection, signal-green = liveness.** Never conflate (§2.3). Only the *currently
   used* provider/model is accent-colored in lists.
6. **One accent at a time.** Don't introduce a second hue. Accent is user-themeable across the
   curated 5; the screen must look right in all of them and in all 4 bases × light/dark.
7. **Flat & hairline first.** Shadow only for overlays. No gradients/glow on chrome.
8. **No badge/check noise.** Don't add "recommended/fast" chips or ✓ marks where color or
   position already communicates state (keeps mono columns aligned).
9. **`.pressable` on every control.** Touch targets ≥ 44px.
10. **Works in all themes.** Test light, dark, sage, sage-dark, and a couple accents before
    declaring done.

---

## 8. Frame & scaling

Every screen mounts inside `PhoneScreen` — a 380×832, 40px-radius device surface:
`StatusBar (44) · content (flex:1, position:relative) · HomeIndicator (24, footerBg)`.
Props: `theme` (base), `accent` (hex, applied as inline `--accent`/`--accent-ink`),
`footerBg`. A global override context (`AmbCtx { locked, theme, accent }`) lets a Tweaks
panel theme all screens at once. Screens are static frames: size content to fit; never
`height:100% + overflow:auto` on inner wrappers — give the scroll region `flex:1; min-height:0;`
and class `noscroll`.

---

## 9. Implementation map (what to reuse)

```
redesign/
  oc-amber.css     # all tokens, primitives, keyframes  ← source of truth for §2–6.1
  aa-base.jsx      # Icons, StatusBar, PhoneScreen, HomeIndicator, Cursor, Dot, Wordmark,
                   #   AmbCtx, inkFor   (load FIRST after tweaks-panel)
  aa-chat.jsx      # Markdown, ContextMeter, ThinkingStrip, ToolCall, AskUserCard,
                   #   UserBubble, AgentTurn/Body/Actions, ChatHeader, InputBar (composer)
  aa-model.jsx     # PROVIDERS data, TopModelMenu (the expanding-menu template)
  aa-settings.jsx  # SectionLabel, SRow, Seg, Toggle, SettingsHeader, ACCENTS
  aa-pages.jsx     # PageHeader, IconBtn, Body, + Sessions/Providers/Skills/Privacy views
  aa-app.jsx       # App: theme store (useTweaks) + back-stack router + wired screens
```

**Script load order** (React 18.3.1 + Babel standalone, pinned):
`tweaks-panel.jsx → aa-base → aa-chat → aa-model → aa-settings → aa-pages → aa-app`.
Each component file ends with `Object.assign(window, {…})` to share across Babel scopes —
follow that pattern; give any styles object a unique name (never a bare `const styles`).

### Recipe — adding an uncovered page

1. Write a view component returning a fragment: a `PageHeader` (back chevron + cn title +
   optional mono eyebrow + right action) then a `<Body>` scroll region.
2. Build content from `.card` groups of `SRow`s, `Seg`/`Toggle` for settings, mono
   `SectionLabel` eyebrows between groups. Reuse chat components for conversational surfaces.
3. Keep every control the source screen had; restyle, don't drop.
4. Add a `case` in `App`'s router switch and a navigation entry point (an `SRow onClick` →
   `nav.push("yourPage")`). Use `nav.push/back/reset`.
5. If it has a composer, set `footerBg="var(--surface)"` on `PhoneScreen` for that screen.
6. Verify in all 4 bases × light/dark and ≥2 accents.

---

## 10. Reference deliverables

- `Amber Prototype.html` — the interactive app (router, live theming, all wired screens).
- `Amber Redesign.html` — the static design-canvas showing every screen + theme/accent matrix.

Match these. When in doubt, open them and copy the existing pattern rather than inventing.
