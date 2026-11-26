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
    )
}
