package app.amber.feature.webmount.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercise the parser used by the production bridge, including metadata hidden by labels. */
class WebMountGoalCandidateTest {
    @Test
    fun productionCandidatesExcludeSensitiveInputTypesAndDisabledControls() {
        val payload = Json.parseToJsonElement("""{
            "snapshot_id":"s1","nodes":[
                {"ref":"password","name":"Enter value","tag":"input","role":"textbox","input_type":"password"},
                {"ref":"send","name":"Send","tag":"button","role":"button"},
                {"ref":"disabled","name":"Open next","tag":"button","role":"button","disabled":true},
                {"ref":"search","name":"Search","tag":"input","role":"textbox","input_type":"search"},
                {"ref":"article","name":"Open article","tag":"a","role":"link"}
            ]
        }""")
        val elements = parseWebGoalElements(payload.jsonObject, 24)
        assertEquals(listOf("search", "article"), elements.map { it.ref })
        assertEquals(listOf("s1", "s1"), elements.map { it.snapshotId })
        assertEquals("search", elements.first().inputType)
        assertTrue(elements.first().editable)
        assertEquals("link", elements.last().role)
    }
    @Test
    fun ordinaryInputButtonRemainsAClickCandidate() {
        val payload = Json.parseToJsonElement("""{
            "snapshot_id":"s1","nodes":[
                {"ref":"next","name":"Next","tag":"input","role":"button","input_type":"button"},
                {"ref":"send","name":"Send","tag":"input","role":"button","input_type":"button"},
                {"ref":"submit","name":"Next","tag":"input","role":"button","input_type":"submit"}
            ]
        }""").jsonObject
        val candidates = parseWebGoalElements(payload, 24)
        assertEquals(listOf("next"), candidates.map { it.ref })
        assertEquals("button", candidates.single().inputType)
        org.junit.Assert.assertFalse("an input button is clickable, never editable", candidates.single().editable)
    }

}
