package app.amber.ai.provider

import java.util.Locale
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BalanceResultPathTest {

    @Test
    fun defaultProviderPathsResolvePrimitiveValues() {
        val root = buildJsonObject {
            putJsonArray("balance_infos") {
                add(buildJsonObject { put("total_balance", 17.5) })
            }
            putJsonObject("data") {
                put("total_credits", "20.10")
                put("total_usage", "3.25")
                put("available_balance", 9.75)
            }
        }

        assertEquals("17.5", BalanceResultPath.extract(root, "balance_infos[0].total_balance"))
        assertEquals("16.85", BalanceResultPath.extract(root, "data.total_credits - data.total_usage"))
        assertEquals("9.75", BalanceResultPath.extract(root, "data.available_balance"))
    }

    @Test
    fun subtractionIsIndependentOfTheDefaultLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            val root = buildJsonObject {
                putJsonObject("data") {
                    put("total_credits", "20.10")
                    put("total_usage", "3.25")
                }
            }

            assertEquals("16.85", BalanceResultPath.extract(root, "data.total_credits - data.total_usage"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun validationRejectsExpressionsOutsideTheNarrowPathLanguage() {
        assertTrue(BalanceResultPath.isValid("data.total_credits - data.total_usage"))
        assertTrue(BalanceResultPath.isValid("items[0].value"))
        assertFalse(BalanceResultPath.isValid("data.total_credits + data.total_usage"))
        assertFalse(BalanceResultPath.isValid("data[foo]"))
        assertFalse(BalanceResultPath.isValid("data.total_credits - data.total_usage - data.tax"))
    }

    @Test
    fun missingPathsAndNonPrimitiveResultsFailExplicitly() {
        val root = buildJsonObject {
            putJsonObject("data") {
                put("value", 1)
            }
        }

        assertTrue(runCatching { BalanceResultPath.extract(root, "data.missing") }.isFailure)
        assertTrue(runCatching { BalanceResultPath.extract(root, "data") }.isFailure)
        assertTrue(runCatching { BalanceResultPath.extract(root, "data.value - data.missing") }.isFailure)
    }
}
