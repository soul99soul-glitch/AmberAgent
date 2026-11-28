package app.amber.feature.ui.pages.phoneuse

import app.amber.agent.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProbeDetailMessageTest {
    @Test
    fun knownPrerequisitesAreTranslatedAndUnknownDiagnosticsArePreserved() {
        val open = "open_virtual_display_failed:"
        assertEquals(R.string.vd_probe_access_unavailable, probeDetailMessage(open + "accessibility_service_unavailable", false))
        assertEquals(R.string.vd_probe_locked, probeDetailMessage(open + "device_locked_or_screen_off", false))
        assertEquals(R.string.vd_probe_need_pair, probeDetailMessage(open + "display_bridge_unavailable:wireless_adb: adb_not_paired, shizuku: offline", false))
        assertEquals(R.string.vd_probe_target_missing, probeDetailMessage("target_did_not_resume_on_virtual_display(actual=-1)", false))
        assertNull(probeDetailMessage(open + "unknown_failure", false))
    }
}
