# Pulse Redesign — Handoff

This branch (`amberagent/ui-pulse-redesign`) is an experimental UI
rebuild of RikkaHub in the "Pulse Performance" fitness-tech aesthetic.
This file is the canonical handoff for any agent (Claude or otherwise)
picking up work on a new machine.

Read `CLAUDE.md` first for project-wide architecture / conventions,
then this file for branch-specific context.

## What this branch is

A full UI restyle of RikkaHub. The app is renamed to "Pulse"
(`applicationId = me.rerere.pulse`, debug build suffix `.debug`,
launcher label "Pulse"). It coexists with the original RikkaHub on the
same device because of the package-id rename.

The branch has shipped ~32 commits across multiple waves: theme
tokens, button vocabulary, drawer extraction, conversations-home
replacing drawer-as-primary, page-by-page polish (Provider, Skills,
Memory, Permissions, Model), then a 6-phase finishing pass on
settings rows + contrast cleanup (`pA`–`pF`).

## Design system at a glance

### Palette
Source: `app/src/main/java/me/rerere/rikkahub/ui/theme/presets/PulseTheme.kt`

| Token | Hex | M3 role | Meaning |
|---|---|---|---|
| cream | `#F5F1EA` | surface / background | warm off-white canvas |
| tan family | `#EDE5D8`+ | surfaceVariant / surfaceContainer | modular cards |
| ink | `#1A1A1A` | tertiary / onSurface | spotlight cards + text |
| chartreuse | `#D6FF3F` | primary | "active / brand" accent |
| sport-orange | `#E85D2F` | secondary / error | hero numerals + critical CTAs |

**Critical rule:** `primary` (chartreuse) is an *accent background*,
not a text color. Pairing `primary` text on cream is the most common
contrast bug — yellow-green on warm off-white fails WCAG AA. When
text needs to read on cream, use `onSurface`. When a colored chip
needs both fill and content, the safe pair is `xxxContainer` BG +
`onXxxContainer` content. See Phase C/D commits for the canonical fix.

### Button vocabulary
Source: `app/src/main/java/me/rerere/rikkahub/ui/components/ui/PulseButtons.kt`

| Variant | Shape | Color | Use for |
|---|---|---|---|
| `PulsePrimaryButton` | asymmetric squircle | chartreuse + ink | Send, Confirm, primary "go" |
| `PulseSecondaryButton` | pill | sport-orange + cream | Add Provider, critical CTA |
| `PulseGhostButton` | pill + 1.5dp hairline | transparent + ink | Cancel, dismiss |
| `PulseDarkButton` | pill | ink + cream | Confirm Sign Out, strong neutral |
| `PulseDialogButton` | pill | varies by `PulseDialogVariant` | AlertDialog footer (compact) |
| `PulseIconButton` | circle + 1.5dp hairline | cream + ink | top-bar icon affordance |

### Row + leading-icon vocabulary
- `CardGroup` (`app/src/main/java/me/rerere/rikkahub/ui/components/ui/CardGroup.kt`):
  the canonical settings list-row container. Supports a `selected`
  underline accent (Phase B) and an automatic trailing chevron when
  the row is navigable (Phase F). Pass `trailingContent = {}` to
  suppress the auto-chevron on a navigable row.
- `WorkspaceLeadingIcon` (`app/src/main/java/me/rerere/rikkahub/ui/components/ui/WorkspaceStyle.kt`):
  circular leading-icon chip. Neutral tone = ink fill + chartreuse
  glyph (Phase A); colored tones (Accent/Success/Warning/Danger) use
  `xxxContainer` + `onXxxContainer`.
- `SettingLeadingIcon` (private to `SettingPage.kt`): 30dp/15dp
  wrapper around `WorkspaceLeadingIcon` for settings rows.

### Floating bottom nav
Source: `app/src/main/java/me/rerere/rikkahub/ui/components/nav/PulseBottomBar.kt`

- Five slots: Chats / Search / [+] / Skills / Settings
- Active slot = chartreuse pill with ALL-CAPS label; inactive = 55%
  cream icon-only
- Center "+" = cream-filled circle with ink "new chat" glyph; calls
  `navigateToChatPage` to start a fresh conversation
- Width clamped to `widthIn(max = 480.dp)` so the pill stays compact
  on tablets

## Workflow

```bash
# Build (replace JAVA_HOME with your JDK 17 path)
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:assembleDebug

# Install (replace SERIAL with adb devices output)
~/Library/Android/sdk/platform-tools/adb -s <SERIAL> install -r \
  app/build/outputs/apk/debug/app-arm64-v8a-debug.apk

# Launch
~/Library/Android/sdk/platform-tools/adb -s <SERIAL> shell am start \
  -n me.rerere.pulse.debug/me.rerere.rikkahub.RouteActivity

# 5-second smoke test — should produce no output
~/Library/Android/sdk/platform-tools/adb -s <SERIAL> logcat -d -t 300 \
  | grep -E "FATAL|me.rerere.pulse.*Exception"
```

**Per-phase contract:** every phase = one focused commit. The cycle is
implement → build → install → 5-second logcat scan for FATAL → commit.
If the smoke surfaces a regression, revert the commit (don't stack on
top of a broken base).

**Optional sub-agent review** after each commit: spawn a read-only
agent to diff-review the commit + take screenshots + confirm
acceptance criteria. The original 6-phase wave used this protocol;
results are visible in the commit messages.

**Commit-message convention:**
- `feat(pulse-pX): <title>` — new features
- `fix(pulse-pX): <title>` — bug fixes
- `refactor(pulse-pX): <title>` — sweeps / restyle
- The X is the phase letter or number; see git log.

## Recent phase history (most recent on top)

| Phase | Commit | TLDR |
|---|---|---|
| pF | `a3b031e7` | CardGroup default trailing chevron on navigable rows |
| pE | `6be1953f` | Experimental page bespoke leading chips → Phase A treatment |
| pD | `20f54fcd` | 4 MEDIUM contrast fixes (ChatInput ×4, syntax span, ImgGen, ImagePreview) |
| pC | `b95bd583` | 4 HIGH contrast fixes (ExperimentBooleanPill, BackupReminderCard, BackgroundPicker, TagList) |
| pB | `e1d15f9b` | CardGroup `selected` flag + chartreuse underline (API dormant) |
| pA | `e2060c08` | WorkspaceLeadingIcon: circular ink chip with chartreuse glyph |
| p25 | `5e62bf65` | Scattered Button() sweep — AssistantPrompt + Translator |
| p24 | `99d1554d` | Permissions page hero — trust-level streak |
| p21 | `9f4de056` | Memory page polish — ink stat hero + dialog buttons |
| p20 | `a3e3ac91` | SkillsPage polish — stat hero + spotlight + filter chips |
| p19 | `488d2c83` | SettingProviderPage rebuild — stat cards + modular rows |
| pulse-fix | `260d14af` | Three regressions fixed (icon contrast, dock width, back-nav) |
| p18 | `2d784f73` | SettingModelPage spotlight + theme cleanup |
| p16 | `00e3a4f4` | Dialog button token sweep — Pulse vocab via shared wrapper |
| p15 | `352b07c5` | Drawer scaffold deletion — −1500 LOC |

For everything older, `git log --oneline` or `git log --grep="pulse-"`.

## Open follow-ups

1. **`CardGroup.selected` API is dormant.** Phase B added the
   parameter but no caller passes `selected = true` yet. Wire it on
   whichever row should be highlighted (e.g. the active settings
   sub-page in a nav rail layout, if one is added).

2. **Two ChatInput slash-command chips at lines ~2292 / ~2387** still
   pair `workspace.blueContainer` (= `primaryContainer`) with
   `workspace.blue` (= `primary`) — same chartreuse-on-pale-chartreuse
   pattern that Phase D fixed at 4 other sites in the same file. Out
   of Phase D's mandate; small follow-up sweep.

3. **Missing KDoc on `CardGroupItem.trailingContent`** — the chevron
   suppression escape hatch (`trailingContent = {}` to disable the
   Phase F default) is documented only in commit messages. One-line
   KDoc would help future callers.

4. **`HistoryPage.kt:220` is deliberately NOT changed.** The
   conversations-home "04 ACTIVE THREADS · READY" 64sp ExtraBold
   sport-orange numeral is a signature Pulse element. The contrast
   audit flagged it as MEDIUM but at that point size + weight it
   passes WCAG AA Large (3:1 threshold). Do not "fix" without checking
   with the user.

## Where to make common changes

| Want to… | Edit |
|---|---|
| Restyle a settings list row | `CardGroup` / `WorkspaceLeadingIcon` (cascades) |
| Add a button intent | `PulseButtons.kt` (only add a new variant if no existing one fits) |
| Change a theme color | `PulseTheme.kt` only — never hardcode hex elsewhere |
| Modify the bottom nav | `PulseBottomBar.kt` + `RouteActivity.derivePulseDestination` |
| Add a destination to the bottom nav | `PulseNavDestination` enum + `derivePulseDestination` mapping |
| Override `LaunchStartMode` defaults | `LaunchStartMode.kt` |

## Things not to do

- **Don't push to `origin`** if `origin` points at `rikkahub/rikkahub`.
  Push only to `github-private` (= `soul99soul-glitch/AmberAgent`).
- **Don't touch the Notion build flavor styling.** Out of scope.
- **Don't add hardcoded `Color(0xFF...)` content tokens** on widget
  surfaces — they bypass theme switching and create contrast bugs.
  Always reach for `MaterialTheme.colorScheme`.
- **Don't bundle unrelated fixes into one commit.** The per-phase
  review loop relies on focused diffs.
- **Don't `--no-verify` or skip pre-commit hooks** unless the user
  explicitly asks.
- **Don't force-push** to a branch you didn't just rewrite locally,
  and never to `main` / `master`.

## Repo layout pointers

- `app/src/main/java/me/rerere/rikkahub/ui/components/ui/` — shared UI
  primitives (PulseButtons, CardGroup, WorkspaceStyle, etc.)
- `app/src/main/java/me/rerere/rikkahub/ui/components/nav/` — nav
  scaffolding (PulseBottomBar, BackButton)
- `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/` — settings
  hierarchy (most pages use CardGroup; outliers TTS / Display /
  Experimental / Provider / Model / Search have bespoke layouts)
- `app/src/main/java/me/rerere/rikkahub/ui/theme/presets/PulseTheme.kt`
  — the brand palette
- `app/src/main/java/me/rerere/rikkahub/RouteActivity.kt` — top-level
  Scaffold + NavDisplay + bottom-bar wire-up
- `app/src/main/java/me/rerere/rikkahub/Screen.kt` (in
  `RouteActivity.kt`) — `Screen` sealed interface enumerating
  destinations
