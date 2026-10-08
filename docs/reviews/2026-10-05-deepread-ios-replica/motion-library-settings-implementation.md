# Library / Composer / Settings — iOS detail and motion pass

Reference is the current DeepReadApp Swift sources, especially DeepReadLibraryView, DeepReadComposerView, DeepReadSettingsView, and DeepReadDesign.DeepReadPressableStyle. This pass preserves the existing Harmony generation/runtime capability and host product behavior. It does not introduce unsupported reading modes or appearance configuration.

## Changes

- Settings accent selection uses the five existing exact color pairs, a 42-point shared ring, 32-point swatch, unselected scale 0.9, selected check scale/opacity transition, selected caption weight 600 and ink color, and the iOS response 0.4 / damping 0.7 spring. The row uses iOS vertical padding 4 and selection haptics. Search steppers now have 44×44 bounds. Native menus indicate their selected item. The reading size label rounds for display while retaining the actual configured value.
- Library filters use one shared selected capsule, a 0.35 / 0.75 spring, 44-point tap bounds, and selected semibold text. Running cards pulse their status dot between 1 and 0.25 opacity over 700 ms. Card visibility applies scale 0.95 / opacity 0.5 at the scroll edge. Presses use scale 0.975, brightness 0.98, and response 0.28 / damping 0.62, matching the reference pressable values.
- Running-dot timers stop on page hide, tab change, offscreen clipping, application background, reduced motion, and component disposal. A late callback cannot restart a disposed timer. The Library page makes page visibility observable for those children. Root Library / Settings body content consumes deepreadActiveTab opacity, and only root onPageShow updates the index; paper and the shared tab capsule retain their own ownership.
- Composer file insertion/removal updates the existing files state inside animateTo and uses a leading translation / opacity transition. Error feedback fades and moves from above. The create button uses the existing spark icon. The existing committed-draft route retry and keyboard ownership paths remain intact.
- Save uses a check-circle icon, one finite spring bounce after successful persistence, a top-move / fade notice, a three-second dismissal, and success haptics. Editing clears the prior confirmation. Timer teardown prevents a delayed bounce after disposal/background/reduced-motion changes. The check-circle mapping was verified in the installed SDK id_defined.json and added as one Icon alias; spark already existed.

## Confirmed state / persistence defects fixed

1. Search draft state lived in the theme-remounted Form and was replaced on every child return. It now lives in SettingDeepReadPage and is linked into the Form. Returning refreshes the actual service directory and enabled IDs while preserving staged result size, free source toggles and preferred service identity. A deleted preferred service remains an explicit ID requiring reselection, with an actionable error, rather than silently switching or saving an unavailable ID.
2. Template selection persisted in the child could be overwritten by the parent’s stale ID. The parent now compares the persisted template baseline on return: a new child choice replaces the stale parent selection, while unchanged storage preserves a parent-only staged choice.
3. Independent generation-model selection previously persisted immediately. It now stages the provider/model pair in the page and applies through the existing saveDeepReadModelSelection during Save. Provider catalog refresh preserves the staged pair and flags deleted/unavailable choices. Host selection retains its earlier immediate-save behavior. Failure does not show a saved confirmation, and the staged model remains available for retry. Save still follows the existing sequential storage writes; no transaction layer was added.
4. Template preview routes receive readerFontMode default/serif and readerFontScale as 70…180 percent directly from the parent draft. The template worker owns validation and downstream forwarding. Those values are not persisted by opening a preview.
5. Independent discovery settings now persist on change through the existing preference keys. Captured writes run in event order so rapid toggles cannot finish backwards, and pending authorized writes continue after Back. On failure, the page reloads actual preferences and shows the storage error. Host discovery retains its explicit Save, including refresh interval, Wi-Fi, translation, keywords, focus mode and source switches.

## API and verification evidence

Local official docs were read before using geometryTransition (effective API 10), onVisibleAreaChange (API 9), curves.springMotion (API 9), MenuItem selected/selectIcon (API 9), and brightness (API 7). Only API-12-compatible forms were used. geometryTransition follows an explicit animateTo transaction, as required by its docs. onVisibleAreaChange is the API-9 two-argument form and uses the ratio, not the callback’s expanding/shrinking flag, for visibility.

All owned motion consumes the existing reduceMotion key. Spring paths use a zero-duration linear curve for reduced motion, because springMotion ignores a supplied duration. This pass only consumes the preference; system preference detection and compatibility gates belong to the parent’s separate platform bridge. Haptics use the parent’s helper and are not disabled merely because reduceMotion is enabled.

- Behavior suite: **58 / 58 pass**, six focused files; evidence: motion-library-settings-tests.log.
- Nine-file scoped lint: **0 errors / 18 warnings**, evidence: motion-library-settings-lint.log. Warnings are the established JSON deep-clone/custom-component advice and local-state performance suggestions.
- A final scoped lint covers the last card brightness / swatch-weight / feedback edits: motion-library-settings-final-lint.log.
- No build, signing, install or device actions were performed by this worker. Parent integration owns those checks.

Device validation still needs to assess the actual selected-ring / capsule spring, file/error transition, scroll-edge visual behavior, and saved icon bounce. In particular, accent changes also trigger the repository’s required themeEpoch remount, so the visual continuity of the shared ring must be checked on the real UI. Composer text-field expansion remains outside this pass pending the parent’s device evidence; existing input preservation and import/generation behavior are covered by the focused tests.

## Final deferred-frame follow-up

Library and Settings root entrance now commit their initial ±30-point offset, then start the existing spring on a 16-ms deferred frame. Hidden/disposed pages cancel that timer and settle to zero. Reduced motion, background, or a different active tab cancel pending motion without replaying it when the policy returns to normal. firstArrival remains consumed once, and Settings scroll restoration is preserved.

Library empty-state feedback now runs only when the filter actually changes and the loaded query has no rows. It waits one 16-ms frame and uses one finite API-11 keyframe animation (scale 1 → 1.15 → 1); a local motion ID invalidates late keyframes after hide, cancellation, or a newer selection. No repeated timer was added. Composer’s primary button now binds success to the real committed pendingTopicId, with no delay in onCreated.

Focused follow-up: **57 / 57 pass** across Library, Settings and Composer tests; **0 errors / 5 warnings** for those three production files. Evidence: library-settings-arrival-tests.log and library-settings-arrival-lint.log. The existing UI-07 lifecycle fixture also includes the new production teardown methods and passed without removing its return/completion assertions.
