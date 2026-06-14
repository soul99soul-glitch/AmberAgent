# Amber Agent iOS Dynamic Island Final Design Debug Plan

Date: 2026-06-14
Status: Design direction accepted; implementation scope frozen; remaining work is debugging and refinement only.

## Goal

Bring the Amber Agent iOS Dynamic Island experience to final design quality:

- Present Agent tool execution as a native-feeling expanded Dynamic Island Live Activity.
- Use the agreed direction: "Siri-style glass result surface + Agent tool step track".
- Keep the UI structurally precise, compact, elegant, and faithful to Dynamic Island constraints.
- Avoid adding new product features during this phase. All remaining work is tuning, verification, and bug fixing.

## Current Stage

Current stage is considered complete.

Completed decisions:

- The Dynamic Island should represent Agent/tool execution state, not become a mini chat window.
- The expanded view should show a public, structured summary of the current tool flow.
- The accepted content structure is:

  ```text
  Amber 正在阅读 Apple 文档

  网页搜索
  ✓ 搜索 ActivityKit
  ● 阅读 Apple 文档
  ○ 生成适配方案
  ```

- The visual direction is:
  - Native Dynamic Island expanded Live Activity.
  - Compact, dark, system-like, and anchored to the island body.
  - Subtle iOS 27-style liquid glass feeling through depth, edge highlights, blur, and restrained translucency.
  - No standalone notification card.
  - No large floating glass panel detached from the island.
  - No icons, avatars, illustrations, marketing graphics, progress bars, or decorative controls.

## Non-Goals

Do not add new features in this phase.

Specifically out of scope:

- Adding new tool types.
- Adding new Agent execution states beyond the already defined display states.
- Adding approval actions inside Dynamic Island.
- Adding chat preview text, full AI responses, or full tool parameters.
- Adding extra controls such as approve, retry, expand logs, copy, or settings.
- Building a separate notification-style UI.
- Reworking the Chat page tool card design.

## Final Design Definition

The final design is an expanded Dynamic Island Live Activity that shows one concise Agent status and one compact tool step track.

### Content Model

The Dynamic Island consumes a sanitized public state, not raw Chat UI data.

Required display fields:

- `statusText`: one-line natural-language status.
  - Example: `Amber 正在阅读 Apple 文档`
- `toolTitle`: short tool category.
  - Example: `网页搜索`
- `steps`: up to three public summary steps.
  - Done: `✓ 搜索 ActivityKit`
  - Current: `● 阅读 Apple 文档`
  - Pending: `○ 生成适配方案`

Rules:

- Show at most three steps.
- Prefer one done step, one current step, and one next step.
- If there are more than three real tool calls, compress them into a short public summary.
- Never display raw prompt text, full URL, local file path, command arguments, API key, secret, or long tool result.

### Visual Model

The expanded UI must feel like the Dynamic Island itself has expanded.

Required visual properties:

- Dark base, visually connected to the TrueDepth/Dynamic Island pill.
- Compact height close to a small Siri-style expanded surface.
- White or near-white text with clear hierarchy.
- Subtle warm Amber emphasis on the current step only.
- Slight glass impression through restrained blur, transparency, refraction-like highlight, and soft edge light.
- No separate card that appears detached from the island.

Avoid:

- Large floating notification card.
- Heavy borders.
- Bright gradients.
- Big decorative glow.
- Multiple nested cards.
- Icon-led layout.
- Large vertical spacing.

## Dynamic Island Region Mapping

Target WidgetKit mapping:

- Expanded center:
  - Primary `statusText`.
- Expanded bottom:
  - `toolTitle` and three compact step rows.
- Compact leading:
  - Minimal text or status mark only, if needed.
- Compact trailing:
  - Current short phase, if needed.
- Minimal:
  - A single minimal state indicator.

The final tuning pass should prefer expanded layout quality first, then ensure compact and minimal states degrade cleanly.

## Debug Plan

### 1. Shape And Anchor Debugging

Success criteria:

- Expanded view reads as Dynamic Island expansion, not a notification banner.
- The island pill remains the visual anchor.
- The expanded region does not appear detached from the top cutout.
- Rounded corners and dark material feel system-like.

Checks:

- Inspect expanded state on simulator/device screenshots.
- Compare against reference scale from the Siri-style image.
- Reduce height and padding until it feels compact.

### 2. Text Density Debugging

Success criteria:

- All content is readable at a glance.
- No line wraps in the default accepted example.
- Three steps fit comfortably without making the island feel oversized.
- Chinese text remains legible in dark and light backgrounds.

Checks:

- Test the accepted Chinese copy exactly.
- Test longer but realistic summaries.
- Test Dynamic Type/default text sizing behavior.
- Confirm truncation is graceful.

### 3. Step Track Debugging

Success criteria:

- Done/current/pending states are obvious without extra icons.
- Current step has subtle Amber emphasis.
- The track feels structured but not like a full log list.

Checks:

- Verify `✓ / ● / ○` remain visually balanced.
- Tune row spacing and font weights.
- Ensure active row is distinguishable without excessive glow.

### 4. Glass And Material Debugging

Success criteria:

- UI feels premium and modern.
- Glass effect supports readability instead of fighting it.
- No excessive transparency that harms contrast.

Checks:

- Test on bright wallpaper, dark wallpaper, and busy wallpaper.
- Reduce transparency if text contrast drops.
- Keep edge highlights subtle.

### 5. Privacy Debugging

Success criteria:

- Only `publicSummary` content appears in Dynamic Island.
- Sensitive content remains inside the app.
- Tool calls with unsafe or private details degrade to generic titles.

Checks:

- File path example becomes `读取文件`.
- Terminal command example becomes `等待命令确认` or `执行终端命令`.
- URL example becomes `阅读网页`.
- Long user query becomes a short intent label.

### 6. State Debugging

Required states:

- Running:

  ```text
  Amber 正在阅读 Apple 文档

  网页搜索
  ✓ 搜索 ActivityKit
  ● 阅读 Apple 文档
  ○ 生成适配方案
  ```

- Waiting for user:

  ```text
  Amber 需要你确认

  终端命令
  ✓ 准备命令
  ● 等待确认
  ○ 执行任务
  ```

- Completed:

  ```text
  Amber 已完成处理

  网页搜索
  ✓ 搜索资料
  ✓ 阅读文档
  ✓ 生成方案
  ```

- Failed:

  ```text
  Amber 遇到问题

  网页搜索
  ✓ 搜索资料
  ● 阅读失败
  ○ 返回应用查看
  ```

Success criteria:

- Each state is understandable without opening the app.
- Failure and waiting states do not imply work is still progressing.
- Completed state can dismiss naturally after a short period.

### 7. Device And Mode Debugging

Success criteria:

- Expanded, compact, and minimal presentations are all acceptable.
- Non-Dynamic-Island devices degrade to Lock Screen Live Activity or no activity as appropriate.
- Dark and light appearances remain legible.

Checks:

- Test at least one Dynamic Island simulator/device.
- Test lock screen presentation.
- Test compact/minimal transitions.
- Test app backgrounding while an Agent run is active.

## Acceptance Criteria

The design is final when all of the following are true:

- The expanded UI is clearly a Dynamic Island expansion.
- The accepted content example fits without wrapping or visual crowding.
- The visual style is compact, dark, elegant, and system-like.
- The current step is visible but not over-decorated.
- No icons, avatars, buttons, or progress bars are present in the content-only version.
- Sensitive tool details are not shown.
- Running, waiting, completed, and failed states all have polished layouts.
- Compact and minimal states degrade cleanly.
- No additional features are introduced during the debugging phase.

## Final Freeze Rule

From this point forward, do not expand scope.

Allowed changes:

- Layout tuning.
- Copy shortening.
- Material/contrast tuning.
- Truncation fixes.
- State mapping fixes.
- Privacy redaction fixes.
- Simulator/device visual bugs.

Not allowed:

- New tool UI features.
- New actions inside Dynamic Island.
- New Agent behavior.
- New Chat page redesign.
- New notification card concept.

The remaining work is to make the accepted design feel native, compact, legible, and polished in the real Dynamic Island surface.
