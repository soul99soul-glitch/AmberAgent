package me.rerere.rikkahub.data.agent.modelcouncil

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.AgentRuntimeSetting
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ModelCouncilValidatorTest {
    private val modelAId = Uuid.parse("11111111-1111-1111-1111-111111111111")
    private val modelBId = Uuid.parse("22222222-2222-2222-2222-222222222222")
    private val imageModelId = Uuid.parse("33333333-3333-3333-3333-333333333333")

    @Test
    fun disabledModeRejectsStart() {
        val error = runCatching {
            ModelCouncilValidator.parseTask(
                input = taskInput(),
                settings = settings(council = ModelCouncilRuntimeSetting(enabled = false)),
                councilSetting = ModelCouncilRuntimeSetting(enabled = false),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("disabled"))
    }

    @Test
    fun emptyDefaultSeatsAreRejected() {
        val council = ModelCouncilRuntimeSetting(enabled = true)

        val error = runCatching {
            ModelCouncilValidator.parseTask(taskInput(), settings(council), council)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("2.."))
    }

    @Test
    fun nonChatModelIsRejected() {
        val council = ModelCouncilRuntimeSetting(
            enabled = true,
            defaultSeats = listOf(
                seat("a", modelAId),
                seat("image", imageModelId),
            )
        )

        val error = runCatching {
            ModelCouncilValidator.parseTask(taskInput(), settings(council), council)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("CHAT"))
    }

    @Test
    fun compareUsesOneRoundAndDefaultSeats() {
        val council = ModelCouncilRuntimeSetting(
            enabled = true,
            defaultSeats = listOf(seat("a", modelAId), seat("b", modelBId)),
            defaultRounds = 3,
        )

        val spec = ModelCouncilValidator.parseTask(taskInput(mode = "compare"), settings(council), council)

        assertEquals(ModelCouncilMode.COMPARE, spec.mode)
        assertEquals(1, spec.rounds)
        assertEquals(2, spec.seats.size)
    }

    @Test
    fun temporarySeatsCanUseRolePresets() {
        val council = ModelCouncilRuntimeSetting(enabled = true)

        val spec = ModelCouncilValidator.parseTask(
            input = taskInput(
                mode = "debate",
                seats = buildJsonArray {
                    add(tempSeat("supporter", modelAId.toString()))
                    add(tempSeat("opponent", modelBId.toString()))
                },
                rounds = 2,
            ),
            settings = settings(council),
            councilSetting = council,
        )

        assertEquals(ModelCouncilMode.DEBATE, spec.mode)
        assertEquals(2, spec.rounds)
        assertEquals("支持者", spec.seats[0].name)
        assertTrue(spec.seats[0].systemPrompt.contains("支持者"))
    }

    private fun settings(council: ModelCouncilRuntimeSetting): Settings =
        Settings(
            chatModelId = modelAId,
            providers = listOf(
                ProviderSetting.OpenAI(
                    models = listOf(
                        Model(id = modelAId, modelId = "model-a", displayName = "Model A"),
                        Model(id = modelBId, modelId = "model-b", displayName = "Model B"),
                        Model(id = imageModelId, modelId = "image", displayName = "Image", type = ModelType.IMAGE),
                    )
                )
            ),
            agentRuntime = AgentRuntimeSetting(modelCouncil = council),
        )

    private fun seat(name: String, modelId: Uuid) = ModelCouncilSeat(
        seatId = name,
        name = name,
        role = name,
        modelId = modelId,
    )

    private fun taskInput(
        mode: String = "compare",
        seats: kotlinx.serialization.json.JsonArray? = null,
        rounds: Int? = null,
    ) = buildJsonObject {
        put("mode", mode)
        put("objective", "Judge the plan.")
        seats?.let { put("seats", it) }
        rounds?.let { put("rounds", it) }
    }

    private fun tempSeat(role: String, modelId: String) = buildJsonObject {
        put("role", role)
        put("model_id", modelId)
    }
}
