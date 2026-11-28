package app.amber.core.automation.display

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VirtualDisplayProbeStoreTest {

    @Test
    fun verifiedResultInvalidatesWhenBuildFingerprintOrVersionCodeChanges() {
        val kv = InMemoryProbeKvStore()
        var fingerprint = "vendor/device/android14:14/UQ1A.240205.002/100:user/release-keys"
        var versionCode = 396L
        var envSupported = true

        val store = VirtualDisplayProbeStore(
            kv = kv,
            fingerprintProvider = { fingerprint },
            versionCodeProvider = { versionCode },
            environmentSupportedProvider = { envSupported },
        )

        // Fresh state -> UNVERIFIED.
        assertEquals(VirtualDisplayCompatibility.UNVERIFIED, store.compatibility())
        assertNull(store.lastResult())

        // Record passing isolation probe -> VERIFIED.
        val pass = passingResult()
        store.record(pass)
        assertEquals(VirtualDisplayCompatibility.VERIFIED, store.compatibility())
        assertEquals(pass, store.lastResult())
        assertEquals(VirtualDisplayCompatibility.UNVERIFIED, store.compatibility("embedded_adb"))
        store.record(pass.copy(transportName = "embedded_adb"))
        assertEquals(VirtualDisplayCompatibility.VERIFIED, store.compatibility("embedded_adb"))
        assertEquals(VirtualDisplayCompatibility.UNVERIFIED, store.compatibility("shizuku"))

        // OS OTA (Build.FINGERPRINT changes) -> immediately reverts to UNVERIFIED.
        fingerprint = "vendor/device/android15:15/AP3A.241005.015/200:user/release-keys"
        assertEquals(VirtualDisplayCompatibility.UNVERIFIED, store.compatibility())
        assertNull(store.lastResult())

        // Re-verify on new OS fingerprint -> VERIFIED.
        store.record(pass)
        assertEquals(VirtualDisplayCompatibility.VERIFIED, store.compatibility())

        // App upgrade (versionCode changes) -> immediately reverts to UNVERIFIED.
        versionCode = 397L
        assertEquals(VirtualDisplayCompatibility.UNVERIFIED, store.compatibility())
        assertNull(store.lastResult())

        // Unsupported environment (e.g. API < 34 or missing secondary activities) -> UNSUPPORTED.
        envSupported = false
        assertEquals(VirtualDisplayCompatibility.UNSUPPORTED, store.compatibility())
    }

    @Test
    fun strictCriteriaRejectPartialOrLeakingProbeResultsEvenIfPassedFlagIsTrue() {
        val kv = InMemoryProbeKvStore()
        val store = VirtualDisplayProbeStore(
            kv = kv,
            fingerprintProvider = { "fp-strict" },
            versionCodeProvider = { 396L },
            environmentSupportedProvider = { true },
        )

        val invalidVariants = listOf(
            passingResult().copy(mainScreenInjectedEvents = 1),
            passingResult().copy(targetReceivedEvents = 19),
            passingResult().copy(targetReceivedEvents = 21),
            passingResult().copy(mainInputObserved = false),
            passingResult().copy(targetBackObserved = false),
            passingResult().copy(disconnectObserved = false),
            passingResult().copy(targetDestroyed = false),
            passingResult().copy(textReadBackOk = false),
            passingResult().copy(frameMarkerOk = false),
            passingResult().copy(focusStable = false),
            passingResult().copy(residualAfterDisconnect = 1),
            passingResult().copy(leakedToDefaultOnDestroy = true),
            passingResult().copy(passed = false),
        )

        for (candidate in invalidVariants) {
            store.record(candidate)
            assertEquals(
                "Expected FAILED for $candidate",
                VirtualDisplayCompatibility.FAILED,
                store.compatibility(),
            )
            assertNotNull(store.lastResult())
            assertEquals(false, store.lastResult()!!.passed)
        }

        store.clear()
        assertEquals(VirtualDisplayCompatibility.UNVERIFIED, store.compatibility())
    }

    @Test
    fun sameBuildLegacyPassWithOnlyOrderlyCloseEvidenceRequiresNewProbe() {
        val kv = InMemoryProbeKvStore()
        kv.write(
            mapOf(
                "build_fingerprint" to "same-build", "app_version_code" to 396L, "passed" to true,
                "main_screen_injected_events" to 0, "target_received_events" to 20, "text_read_back_ok" to true,
                "frame_marker_ok" to true, "focus_stable" to true, "residual_after_disconnect" to 0,
                "leaked_to_default_on_destroy" to false, "detail" to "ok",
            ),
        )
        val store = VirtualDisplayProbeStore(kv, { "same-build" }, { 396L }, { true })
        assertEquals(VirtualDisplayCompatibility.UNVERIFIED, store.compatibility())
        assertNull(store.lastResult())
        kv.write(mapOf("disconnect_observed" to true, "target_destroyed" to true, "main_input_observed" to true))
        assertEquals(VirtualDisplayCompatibility.UNVERIFIED, store.compatibility())
        assertNull(store.lastResult())
    }

    @Test
    fun staleTargetCallbacksCannotProvideFreshRunEvidence() {
        val (oldToken, _) = IsolationProbeChannel.resetForNewRun()
        val (token, _) = IsolationProbeChannel.resetForNewRun()
        try {
            IsolationProbeChannel.setExpectedDisplayId(3)
            IsolationProbeChannel.setInjectionBurstActive(true)
            IsolationProbeChannel.recordTargetBack(oldToken, 3)
            IsolationProbeChannel.recordTargetBack(token, 0)
            assertEquals(false, IsolationProbeChannel.targetBackReceived())
            IsolationProbeChannel.recordTargetBack(token, 3)
            assertEquals(true, IsolationProbeChannel.targetBackReceived())
            IsolationProbeChannel.recordTargetTap(oldToken, 3)
            IsolationProbeChannel.recordTargetDestroyed(oldToken, 3)
            assertEquals(0, IsolationProbeChannel.targetTapCount())
            assertEquals(false, IsolationProbeChannel.targetDestroyed())
            IsolationProbeChannel.recordTargetTap(token, 3)
            IsolationProbeChannel.recordTargetDestroyed(token, 3)
            assertEquals(1, IsolationProbeChannel.targetTapCount())
            assertEquals(true, IsolationProbeChannel.targetDestroyed())
        } finally {
            IsolationProbeChannel.setInjectionBurstActive(false)
            IsolationProbeChannel.endRun()
        }
    }

    @Test
    fun missingAccessibilityIsNotAnIsolationVerdictIncludingOlderRecords() {
        val kv = InMemoryProbeKvStore()
        val store = VirtualDisplayProbeStore(kv, { "fp" }, { 1L }, { true })
        val unavailable = passingResult().copy(passed = false, detail = "open_virtual_display_failed:accessibility_service_unavailable")
        store.record(passingResult())
        kv.write(mapOf("passed" to false, "detail" to unavailable.detail))
        assertEquals(VirtualDisplayCompatibility.UNVERIFIED, store.compatibility())
        assertEquals(unavailable.detail, store.lastResult()?.detail)

        store.record(unavailable)
        assertNull(store.lastResult())
        assertEquals(VirtualDisplayCompatibility.UNVERIFIED, store.compatibility())
    }

    private fun passingResult() = IsolationProbeResult(
        passed = true,
        mainScreenInjectedEvents = 0,
        targetReceivedEvents = 20,
        textReadBackOk = true,
        frameMarkerOk = true,
        focusStable = true,
        residualAfterDisconnect = 0,
        leakedToDefaultOnDestroy = false,
        detail = "ok",
        targetBackObserved = true,
        mainInputObserved = true,
        disconnectObserved = true,
        targetDestroyed = true,
    )
}
