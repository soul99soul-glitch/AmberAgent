# Discovery / Tab motion implementation

2026-10-05. Scope: standalone DeepRead Board, discovery components, shared bottom Tab bar, and their behavior tests. The host Board UI and domain services are preserved. Build, installation, frame capture, system reduce-motion acceptance and physical-device shake/vibration verification are owned by the main agent.

## Reference and confirmed gaps

The reference is the current read-only iOS source, rebuilt by the main agent for `reference/ios-current-discovery-light.jpg`, rather than the previously installed binary:

- `amberagent-ios/iosApp/DeepReadApp/Sources/Discovery/DeepReadDiscoveryView.swift`: real visibility/create-modal shake guard, scatter → random lucky banner, 360 ms landing, 8-second cancellation-aware expiry, redacted placeholder phase, five quick headline taps and re-inking.
- `.../Sources/Design/DeepReadDesign.swift`: `DeepReadEntrance` (26-point rise, 550 ms spring, stagger capped at eight rows); `DeepReadStampSlam` (2.6 → 0.92 → 1, 8° → -10°, reduced motion uses opacity); `DeepReadTabVisibility` (immediate deselection hiding, directional 30-point arrival, article Back does not count as an arrival).
- `.../Sources/App/DeepReadApp.swift`: three tabs, directional selection and selection feedback.

Before modification, the actual Harmony implementation had static placeholder opacity, a static masthead rule, an abbreviated stamp scale, no shake or lucky banner, no source-card press/entrance, and topic transitions declared without an insertion animation transaction. Root arrival ignored reduced motion. A consumed previous-tab selection was missing, so theme remounts could replay the indicator. These concrete gaps were reported to the main agent before modifying the slice.

## Changes

| Behavior | Implementation | Lifecycle / accessibility behavior |
| --- | --- | --- |
| Topic/source-card and section-header arrival; newspaper scatter | `DeepReadDiscoveryMotion.ets`, both discovery cards, `DeepReadDiscoverySectionHeader` | Initial opacity 0 / y 26 is retained for a 16 ms render turn, then a 550 ms spring with capped 50 ms stagger. Section header uses index 0, topics index +1, and source groups follow the topic count. Saved root-list appearance prevents theme/root reconstruction from replaying arrival. The header does not scatter. Card scatter is 280 ms; landing is 620 ms. Reduced motion removes all geometry but retains a deferred 250 ms opacity entrance. Deselection, hiding and background settle transforms and clear deferred work. |
| Press feedback | Topic card, source rows, toolbar controls and standalone refresh control | Spring press feedback uses the existing real click/long-press actions. Reduced motion suppresses scale, keeping immediate pressed color. Up/cancel, background, hide and disposal clear pressed state. |
| Real-loading skeleton | `DeepReadDiscoveryPlaceholder` | The existing actual empty/loading condition owns the skeleton; there is no synthetic loading mode. Opacity alternates 0.45 / 1 every 900 ms. The interval is released when hidden, backgrounded, reduced or disposed. |
| Error/notice arrival | `DeepReadDiscoveryNotice` | Deferred first frame and actual `animateTo`, using opacity plus y -18 → 0. Reduced motion uses opacity only. Deferred work is canceled on hide/background/disposal. Existing errors remain honest messages. |
| Masthead rule and seal | `DeepReadDiscoveryHeader.ets` | Rule grows horizontally from its leading edge. Five taps in 1.6 seconds insert the double-frame seal; subsequent taps choose the next inscription and re-ink. The initial 2.6 scale / 8° / clear ink frame is deferred 16 ms before impact and rebound. Ink has its own 140 ms fade, separate from the 180 ms impact geometry; reduced motion uses a 250 ms fade with fixed geometry. Light mode uses `BlendMode.MULTIPLY`, dark mode uses `NONE`, matching the existing article Press. Timer cancellation and a trigger counter reject delayed callbacks after re-inking, background or disposal. |
| Seasonal/night badge bounce and empty-state wiggle | `DeepReadDiscoverySymbol.ets` | One small component with a builder content and variant, used by both real interfaces. Badges move only their glyph; the empty glyph wiggles -8° → 6° → 0°. Initial geometry has a 16 ms render turn; both sequences are finite. Reduced motion skips symbol movement. Active/background/reduced/disposal state clears the deferred timer and rejects late finish callbacks. |
| Shake / explicit random button | `DeepReadDiscoveryShake.ets` | The explicit button and real accelerometer event use the same current Board candidates. Two strong samples within 350 ms and a 1.2-second cooldown reject gravity/isolated knocks. Normal motion scatters and lands after 360 ms; reduced motion picks immediately. No job starts until “读这篇” calls the existing `openArticle`. |
| Lucky banner | Same component | Real original hotspot inputs/seed URLs, dismiss/read controls, explicit animated insertion/removal, 8-second expiry. The local dice uses a 22 vp rounded stroke square plus five monochrome circles, with one deferred spring bounce per pick; reduced motion suppresses that bounce. A new pick cancels previous expiry. Background/hide/create/action-modal clears the timers and pick; a queued canceled callback cannot affect a newer pick. The owner is outside the theme-remounted content; theme refresh changes only its visual subtree. Its measured height adds to the Scroll bottom inset so the last random/refresh controls remain reachable. |
| Bottom Tab indicator / root visibility | `DeepReadTabBar.ets`, Board | Stored previous selection is consumed once. Initial indicator frame is deferred 16 ms; reduced motion jumps to the current tab. Before replacement, `deepreadActiveTab` immediately hides/disables outgoing text while retaining paper and navigation. Failure restores the current selection and visible route-error toast. Root `arrive()` restores only the Board root key and keeps its no-article-Back-replay guard. Existing captured UIContext Router and detail `clearHistory` semantics remain. |
| Feedback | Main agent's shared `DeepReadHaptics` | Tab selection, seal impact and lucky success call the shared real vibration helper. Hardware/product/background gating remains in that helper. No claim of iOS-identical tactile waveform is made. |

The Board remains 998 lines; visual/timer/sensor responsibility lives in the small discovery modules. The existing `randomRead` business method remains available to prior domain/controller tests; the standalone UI now requests a lucky recommendation, matching the iOS discovery behavior. Composer remains bound outside the theme `ForEach`, and only closes after the saved article route succeeds.

## Official API evidence

Before adding a new sensor API, the official local documentation was searched and read through `harmony/scripts/devecocli.sh docs search sensor.on` and `docs read 开发指南/硬件/Sensor_Service_Kit_传感器服务/传感器/传感器开发指导_ArkTS/sensor-guidelines`. The full local guide is captured in [motion-discovery-sensor-official.log](motion-discovery-sensor-official.log).

The current SDK declaration `/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/api/@ohos.sensor.d.ts` confirms:

- Lines 199–228: `sensor.on(SensorId.ACCELEROMETER, Callback<AccelerometerResponse>, Options?)`; API 9; `SystemCapability.Sensors.Sensor`; `ohos.permission.ACCELEROMETER`.
- Lines 818–840: `sensor.off(SensorId.ACCELEROMETER, callback?)`; API 9. This implementation removes its own exact callback, preserving other subscribers.
- Sampling interval is nanoseconds; 50,000,000 ns is used. `x/y/z` are actual acceleration values, not invented motion events.

The capability check and documented throwing paths leave the explicit random control operational when a simulator/device cannot subscribe. The main agent added the sensor permission only to the standalone DeepRead manifest. `BlendMode.MULTIPLY` / `blendMode` are existing API 11 forms, checked in the current SDK and recorded in [the article implementation evidence](motion-article-implementation.md). The glyphs use existing basic Shapes or text and the new helper uses existing animation APIs. No API above the baseline was added by this slice. The API 23 system animation-preference bridge is main-agent-owned; this slice consumes its shared `reduceMotion` state and does not claim API 12 can read that system option.

## Validation

`node --test harmony/entry/src/test/deepread_discovery_motion.test.cjs harmony/entry/src/test/deepread_ios_discovery_navigation.test.cjs harmony/entry/src/test/deepread_board_ui.test.cjs harmony/deepread/src/test/hotlist_board.test.cjs`: **41 / 41 pass**, including 18 motion behaviors. [Test log](motion-discovery-tests.log).

The tests execute production controller members and the production shake detector with controllable sensor delivery, timer queue, AppStorage and UIContext. They cover sensor callback identity, visibility/modal/background release, canceled callbacks, unsupported hardware/manual pick, actual metadata, scatter timing, expiry replacement, reduced motion mid-toss, no-return replay, deferred first render, press cancellation, seal trigger windows and root-navigation route failure. Existing host/novel Board and real hotlist/network/cancellation checks also pass.

Seven-file scoped `lint-arkts.sh`: **exit 0; 0 errors / 3 warnings**. The warnings recommend `@Builder` over the small section-header, header-action and empty-glyph components. The header action retains per-button state; the visual wrappers keep the Board under 1,000 lines and their motion responsibilities local. [Lint log](motion-discovery-lint.log).

These checks prove logic and wiring. They do not prove a visible animation frame, smooth frame delivery, actual physical shaking, system accessibility settings or motor output. Those runtime acceptance checks remain with the main agent's unified build/device pass.
