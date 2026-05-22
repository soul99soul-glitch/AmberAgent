package me.rerere.rikkahub.data.agent.modelcouncil

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.agent.terminal.TerminalRuntimeKind
import me.rerere.rikkahub.data.datastore.AgentRuntimeSetting
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ModelCouncilValidatorTest {
    private val externalCliHomeRoot = "/app/files/amberagent/external-cli-home"
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
    fun defaultModeFallsBackToCurrentChatModelWithoutDefaultSeats() {
        val council = ModelCouncilRuntimeSetting(enabled = true)

        val spec = ModelCouncilValidator.parseTask(taskInput(), settings(council), council)

        assertEquals(listOf("supporter", "opponent", "judge"), spec.seats.map { it.role })
        assertEquals(listOf(modelAId, modelBId, modelAId), spec.seats.map { it.modelId })
    }

    @Test
    fun explicitNonChatModelIsRejected() {
        val council = ModelCouncilRuntimeSetting(enabled = true)

        val error = runCatching {
            ModelCouncilValidator.parseTask(
                input = taskInput(
                    seats = buildJsonArray {
                        add(tempSeat("supporter", imageModelId.toString()))
                        add(tempSeat("opponent", modelAId.toString()))
                    }
                ),
                settings = settings(council),
                councilSetting = council,
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("CHAT"))
    }

    @Test
    fun compareUsesOneRoundAndAutoInjectedSeats() {
        val council = ModelCouncilRuntimeSetting(
            enabled = true,
            defaultSeats = listOf(seat("a", modelAId), seat("b", modelBId)),
            defaultRounds = 3,
        )

        val spec = ModelCouncilValidator.parseTask(taskInput(mode = "compare"), settings(council), council)

        assertEquals(ModelCouncilMode.COMPARE, spec.mode)
        assertEquals(1, spec.rounds)
        assertTrue(spec.seats.any { it.role == "supporter" })
        assertTrue(spec.seats.any { it.role == "opponent" })
        assertTrue(spec.seats.any { it.role == "judge" })
    }

    @Test
    fun defaultModeSkipsExternalCoreSeatsUnlessExplicitlyAllowed() {
        val council = ModelCouncilRuntimeSetting(
            enabled = true,
            defaultSeats = listOf(
                seat("external-supporter", modelAId).copy(
                    role = "supporter",
                    runnerType = ModelCouncilSeatRunner.EXTERNAL_CLI,
                    externalTool = "gemini_cli",
                ),
                seat("base", modelAId),
            ),
        )

        val spec = ModelCouncilValidator.parseTask(taskInput(mode = "compare"), settings(council), council)
        val supporter = spec.seats.single { it.role == "supporter" }

        assertEquals(ModelCouncilSeatRunner.PROVIDER_MODEL, supporter.runnerType)
        assertTrue(spec.seats.none { it.runnerType == ModelCouncilSeatRunner.EXTERNAL_CLI })
    }

    @Test
    fun defaultModeSkipsStaleAndNonChatDefaultSeats() {
        val staleModelId = Uuid.parse("44444444-4444-4444-4444-444444444444")
        val council = ModelCouncilRuntimeSetting(
            enabled = true,
            defaultSeats = listOf(
                seat("image-lens", imageModelId).copy(role = "risk"),
                seat("stale-lens", staleModelId).copy(role = "engineering"),
                seat("base", modelAId),
            ),
        )

        val spec = ModelCouncilValidator.parseTask(taskInput(mode = "compare"), settings(council), council)

        assertTrue(spec.seats.none { it.modelId == imageModelId })
        assertTrue(spec.seats.none { it.modelId == staleModelId })
    }

    @Test
    fun agentPlannedSeatsResolveModelRefAndAutoRotateFallback() {
        val council = ModelCouncilRuntimeSetting(
            enabled = true,
            defaultSeats = listOf(seat("kimi-default", modelBId), seat("deepseek-default", modelAId)),
        )

        val spec = ModelCouncilValidator.parseTask(
            input = taskInput(
                seatStrategy = "agent_planned",
                plannedSeats = buildJsonArray {
                    add(plannedSeat(name = "事实整理", role = "research", modelRef = "Model A"))
                    add(plannedSeat(name = "风险审查", role = "risk"))
                },
            ),
            settings = settings(council),
            councilSetting = council,
        )

        assertEquals(2, spec.seats.size)
        assertEquals(modelAId, spec.seats[0].modelId)
        assertEquals(modelBId, spec.seats[1].modelId)
        assertEquals("事实整理", spec.seats[0].name)
        assertTrue(spec.seats[0].systemPrompt.contains("focus"))
    }

    @Test
    fun agentPlannedSeatsCanFallbackToCurrentChatModelWithoutDefaultSeats() {
        val council = ModelCouncilRuntimeSetting(enabled = true)

        val spec = ModelCouncilValidator.parseTask(
            input = taskInput(
                seatStrategy = "agent_planned",
                plannedSeats = buildJsonArray {
                    add(plannedSeat(name = "A", role = "a"))
                    add(plannedSeat(name = "B", role = "b"))
                },
            ),
            settings = settings(council),
            councilSetting = council,
        )

        assertEquals(listOf(modelAId, modelBId), spec.seats.map { it.modelId })
    }

    @Test
    fun agentPlannedSeatsCanIncludeGeminiCliParticipantWithoutDefaultSeats() {
        val council = ModelCouncilRuntimeSetting(enabled = true)

        val spec = ModelCouncilValidator.parseTask(
            input = taskInput(
                seatStrategy = "agent_planned",
                allowExternalCli = true,
                plannedSeats = buildJsonArray {
                    add(
                        plannedSeat(
                            name = "Gemini CLI",
                            role = "external_reviewer",
                            runnerType = "external_cli",
                            externalTool = "gemini_cli",
                            externalRuntime = "termux_external",
                            externalModel = "gemini-2.5-pro",
                        )
                    )
                    add(plannedSeat(name = "本地裁判", role = "judge"))
                },
            ),
            settings = settings(council),
            councilSetting = council,
        )

        assertEquals(2, spec.seats.size)
        assertEquals(ModelCouncilSeatRunner.EXTERNAL_CLI, spec.seats[0].runnerType)
        assertEquals("gemini_cli", spec.seats[0].externalTool)
        assertEquals("termux_external", spec.seats[0].externalRuntime)
        assertEquals("gemini-2.5-pro", spec.seats[0].externalModel)
        assertEquals(ModelCouncilSeatRunner.PROVIDER_MODEL, spec.seats[1].runnerType)
    }

    @Test
    fun agentPlannedSeatsDeduplicateSeatIds() {
        val council = ModelCouncilRuntimeSetting(enabled = true)

        val spec = ModelCouncilValidator.parseTask(
            input = taskInput(
                seatStrategy = "agent_planned",
                plannedSeats = buildJsonArray {
                    add(plannedSeat(name = "A", role = "critic", seatId = "critic"))
                    add(plannedSeat(name = "B", role = "critic", seatId = "critic"))
                    add(plannedSeat(name = "C", role = "critic", seatId = "critic-2"))
                },
            ),
            settings = settings(council),
            councilSetting = council,
        )

        assertEquals(listOf("critic", "critic-2", "critic-2-2"), spec.seats.map { it.seatId })
    }

    @Test
    fun unknownSeatStrategyIsRejected() {
        val council = ModelCouncilRuntimeSetting(enabled = true)

        val error = runCatching {
            ModelCouncilValidator.parseTask(
                input = taskInput(seatStrategy = "agent_planend"),
                settings = settings(council),
                councilSetting = council,
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("seat_strategy"))
    }

    @Test
    fun externalCliCommandWrapsPromptAndRejectsUnsafeModelArg() {
        val seat = seat("gemini", modelAId).copy(
            runnerType = ModelCouncilSeatRunner.EXTERNAL_CLI,
            externalTool = "gemini_cli",
            externalModel = "gemini-2.5-pro",
        )

        val command = ModelCouncilExternalCliCommandBuilder.build(
            seat = seat,
            prompt = "hello ' world",
            timeoutMs = 5_000L,
            externalCliHomeRoot = externalCliHomeRoot,
        )

        assertTrue(command.contains("gemini --skip-trust --approval-mode plan --output-format text"))
        assertTrue(command.contains("setsid sh -lc"))
        assertTrue(command.contains("--model gemini-2.5-pro"))
        assertTrue(command.contains("trap cleanup EXIT INT TERM"))
        assertTrue(command.contains("sleep 5"))
        assertTrue(command.contains("pkill -TERM -P \"${'$'}target_pid\""))
        assertTrue(command.contains("kill -TERM \"-${'$'}target_pid\""))
        assertTrue(command.contains("cleanup() { stop_probe_tree; stop_cli_tree;"))
        assertTrue(command.contains("export HOME="))
        assertTrue(command.contains("home_root='$externalCliHomeRoot/gemini_cli'"))
        assertTrue(command.contains("export GEMINI_CLI_HOME=\"${'$'}home_root/.gemini\""))
        assertTrue(command.contains("External CLI Gemini CLI is not ready"))
        assertTrue(
            command.indexOf("External CLI Gemini CLI is not ready") <
                command.indexOf("__AMBERAGENT_MODEL_COUNCIL_CLI_OUTPUT_BEGIN__")
        )
        assertTrue(command.contains("export prompt_file"))
        assertTrue(command.contains("cd \"${'$'}tmp_root/work\""))
        assertTrue(command.contains("hello ' world"))
        assertTrue(command.contains("__AMBERAGENT_MODEL_COUNCIL_CLI_OUTPUT_BEGIN__"))

        val error = runCatching {
            ModelCouncilExternalCliCommandBuilder.build(
                seat = seat.copy(externalModel = "x; rm -rf /"),
                prompt = "hello",
                timeoutMs = 5_000L,
                externalCliHomeRoot = externalCliHomeRoot,
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)

        val optionInjection = runCatching {
            ModelCouncilExternalCliCommandBuilder.build(
                seat = seat.copy(externalModel = "--help"),
                prompt = "hello",
                timeoutMs = 5_000L,
                externalCliHomeRoot = externalCliHomeRoot,
            )
        }.exceptionOrNull()
        assertTrue(optionInjection is IllegalArgumentException)
    }

    @Test
    fun externalCliRegistryBuildsCommandsForSupportedTools() {
        val expected = mapOf(
            "gemini_cli" to "gemini --skip-trust --approval-mode plan --output-format text",
            "antigravity_cli" to "agy --help",
            "codex_cli" to "codex exec --sandbox read-only",
            "claude_code" to "claude -p --output-format text",
            "kimi_cli" to "kimi --print --final-message-only",
        )

        expected.forEach { (tool, snippet) ->
            val seat = seat(tool, modelAId).copy(
                runnerType = ModelCouncilSeatRunner.EXTERNAL_CLI,
                externalTool = tool,
                externalModel = "model-a",
            )

            val command = ModelCouncilExternalCliCommandBuilder.build(
                seat = seat,
                prompt = "reply ok",
                timeoutMs = 5_000L,
                externalCliHomeRoot = externalCliHomeRoot,
            )

            assertTrue("$tool command missing snippet", command.contains(snippet))
            assertTrue("$tool command missing private home", command.contains("$externalCliHomeRoot/$tool"))
            assertTrue("$tool command missing marker", command.contains("__AMBERAGENT_MODEL_COUNCIL_CLI_OUTPUT_BEGIN__"))
            assertTrue("$tool command missing cleanup", command.contains("trap cleanup EXIT INT TERM"))
            assertTrue("$tool command missing readiness probe", command.contains("probe_status"))
        }
    }

    @Test
    fun termuxExternalCliHomeStaysOutsideWorkspaceAndAppPrivateSandbox() {
        val seat = seat("termux-codex", modelAId).copy(
            runnerType = ModelCouncilSeatRunner.EXTERNAL_CLI,
            externalTool = "codex_cli",
            externalRuntime = "termux_external",
        )

        val command = ModelCouncilExternalCliCommandBuilder.build(
            seat = seat,
            prompt = "reply ok",
            timeoutMs = 5_000L,
            externalCliHomeRoot = externalCliHomeRoot,
            runtime = TerminalRuntimeKind.TERMUX_EXTERNAL,
        )

        assertTrue(command.contains("home_root=\"${'$'}HOME/.amberagent/external-cli-home/codex_cli\""))
        assertFalse(command.contains("home_root='$externalCliHomeRoot/codex_cli'"))
        assertFalse(command.contains(".amberagent-external-cli-home/codex_cli"))
    }

    @Test
    fun antigravityCliUsesAgyAndDoesNotAliasGeminiCli() {
        val seat = seat("antigravity", modelAId).copy(
            runnerType = ModelCouncilSeatRunner.EXTERNAL_CLI,
            externalTool = "antigravity_cli",
        )

        val command = ModelCouncilExternalCliCommandBuilder.build(
            seat = seat,
            prompt = "hello",
            timeoutMs = 5_000L,
            externalCliHomeRoot = externalCliHomeRoot,
        )

        assertTrue(command.contains("command -v 'agy'"))
        assertTrue(command.contains(".gemini/antigravity-cli"))
        assertTrue(command.contains("Antigravity CLI is installed"))
        assertFalse(command.contains("gemini --skip-trust"))
    }

    @Test
    fun loginHintDetectsUrlsCodesAndUserActionWithoutMarkingReady() {
        val claude = ExternalCliToolRegistry.detectLoginHint(
            "If the browser does not open, visit https://claude.ai/oauth and paste code ABCD-1234."
        )
        val plain = ExternalCliToolRegistry.detectLoginHint("Everything is already authenticated.")

        assertEquals("https://claude.ai/oauth", claude.url)
        assertEquals("ABCD-1234", claude.code)
        assertTrue(claude.needsUserAction)
        assertEquals(null, plain.url)
        assertFalse(plain.needsUserAction)
    }

    @Test
    fun externalCliRunnerTypeAliasDoesNotFallBackToGemini() {
        val council = ModelCouncilRuntimeSetting(enabled = true)

        ExternalCliToolRegistry.supportedToolIds
            .filterNot { it == EXTERNAL_CLI_DEFAULT_TOOL_ID }
            .forEach { tool ->
                val spec = ModelCouncilValidator.parseTask(
                    input = taskInput(
                        seatStrategy = "agent_planned",
                        allowExternalCli = true,
                        plannedSeats = buildJsonArray {
                            add(plannedSeat(tool, "external", runnerType = tool))
                            add(plannedSeat("Judge", "judge"))
                        },
                    ),
                    settings = settings(council),
                    councilSetting = council,
                )

                assertEquals(tool, spec.seats.first().externalTool)
            }
    }

    @Test
    fun externalCliSeatsRequireExplicitAllowFlagAndSafeModelArg() {
        val council = ModelCouncilRuntimeSetting(enabled = true)

        val withoutAllow = runCatching {
            ModelCouncilValidator.parseTask(
                input = taskInput(
                    seatStrategy = "agent_planned",
                    plannedSeats = buildJsonArray {
                        add(plannedSeat("Gemini", "external", runnerType = "external_cli", externalTool = "gemini_cli"))
                        add(plannedSeat("Judge", "judge"))
                    },
                ),
                settings = settings(council),
                councilSetting = council,
            )
        }.exceptionOrNull()

        assertTrue(withoutAllow is IllegalArgumentException)
        assertTrue(withoutAllow!!.message!!.contains("allow_external_cli"))

        val unsafeModel = runCatching {
            ModelCouncilValidator.parseTask(
                input = taskInput(
                    seatStrategy = "agent_planned",
                    allowExternalCli = true,
                    plannedSeats = buildJsonArray {
                        add(
                            plannedSeat(
                                "Gemini",
                                "external",
                                runnerType = "external_cli",
                                externalTool = "gemini_cli",
                                externalModel = "x; rm -rf /",
                            )
                        )
                        add(plannedSeat("Judge", "judge"))
                    },
                ),
                settings = settings(council),
                councilSetting = council,
            )
        }.exceptionOrNull()

        assertTrue(unsafeModel is IllegalArgumentException)
        assertTrue(unsafeModel!!.message!!.contains("Unsafe external_model"))

        val unsupportedTool = runCatching {
            ModelCouncilValidator.parseTask(
                input = taskInput(
                    seatStrategy = "agent_planned",
                    allowExternalCli = true,
                    plannedSeats = buildJsonArray {
                        add(plannedSeat("Unknown", "external", runnerType = "external_cli", externalTool = "unknown_cli"))
                        add(plannedSeat("Judge", "judge"))
                    },
                ),
                settings = settings(council),
                councilSetting = council,
            )
        }.exceptionOrNull()

        assertTrue(unsupportedTool is IllegalArgumentException)
        assertTrue(unsupportedTool!!.message!!.contains("Unsupported external_tool"))
    }

    @Test
    fun allExternalAgentPlannedSeatsStillRequireSynthesisChatModel() {
        val council = ModelCouncilRuntimeSetting(enabled = true)
        val noChatSettings = settings(council).copy(
            chatModelId = imageModelId,
            providers = listOf(
                ProviderSetting.OpenAI(
                    models = listOf(
                        Model(id = imageModelId, modelId = "image", displayName = "Image", type = ModelType.IMAGE),
                    )
                )
            ),
        )

        val error = runCatching {
            ModelCouncilValidator.parseTask(
                input = taskInput(
                    seatStrategy = "agent_planned",
                    allowExternalCli = true,
                    plannedSeats = buildJsonArray {
                        add(plannedSeat("Gemini A", "external-a", runnerType = "external_cli", externalTool = "gemini_cli"))
                        add(plannedSeat("Gemini B", "external-b", runnerType = "external_cli", externalTool = "gemini_cli"))
                    },
                ),
                settings = noChatSettings,
                councilSetting = council,
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("synthesis model"))
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

    @Test
    fun debateCanUseFiveRoundsEvenWithOldStoredMaxRounds() {
        val council = ModelCouncilRuntimeSetting(
            enabled = true,
            maxRounds = 3,
        )

        val spec = ModelCouncilValidator.parseTask(
            input = taskInput(
                mode = "debate",
                seats = buildJsonArray {
                    add(tempSeat("supporter", modelAId.toString()))
                    add(tempSeat("opponent", modelBId.toString()))
                },
                rounds = 5,
            ),
            settings = settings(council),
            councilSetting = council,
        )

        assertEquals(5, spec.rounds)
    }

    @Test
    fun optionalTemperatureIsParsedAndValidated() {
        val council = ModelCouncilRuntimeSetting(enabled = true)

        val spec = ModelCouncilValidator.parseTask(
            input = taskInput(
                seatStrategy = "agent_planned",
                plannedSeats = buildJsonArray {
                    add(plannedSeat(name = "Creative", role = "creative", temperature = 0.8f))
                    add(plannedSeat(name = "Judge", role = "judge"))
                },
            ),
            settings = settings(council),
            councilSetting = council,
        )

        assertEquals(0.8f, spec.seats[0].temperature)
        assertEquals(null, spec.seats[1].temperature)

        val error = runCatching {
            ModelCouncilValidator.parseTask(
                input = taskInput(
                    seatStrategy = "agent_planned",
                    plannedSeats = buildJsonArray {
                        add(plannedSeat(name = "Too Hot", role = "creative", temperature = 2.5f))
                        add(plannedSeat(name = "Judge", role = "judge"))
                    },
                ),
                settings = settings(council),
                councilSetting = council,
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("temperature"))
    }

    @Test
    fun explicitAndPlannedSeatsAcceptExtendedOutputBudgetWhenSettingAllowsIt() {
        val council = ModelCouncilRuntimeSetting(
            enabled = true,
            outputBudgetChars = EXTENDED_MODEL_COUNCIL_OUTPUT_BUDGET_CHARS,
        )

        val explicit = ModelCouncilValidator.parseTask(
            input = taskInput(
                seats = buildJsonArray {
                    add(tempSeat("supporter", modelAId.toString(), EXTENDED_MODEL_COUNCIL_OUTPUT_BUDGET_CHARS))
                    add(tempSeat("opponent", modelBId.toString(), EXTENDED_MODEL_COUNCIL_OUTPUT_BUDGET_CHARS))
                },
            ),
            settings = settings(council),
            councilSetting = council,
        )
        val planned = ModelCouncilValidator.parseTask(
            input = taskInput(
                seatStrategy = "agent_planned",
                plannedSeats = buildJsonArray {
                    add(plannedSeat("Research", "research", outputBudgetChars = EXTENDED_MODEL_COUNCIL_OUTPUT_BUDGET_CHARS))
                    add(plannedSeat("Judge", "judge", outputBudgetChars = EXTENDED_MODEL_COUNCIL_OUTPUT_BUDGET_CHARS))
                },
            ),
            settings = settings(council),
            councilSetting = council,
        )

        assertTrue(explicit.seats.all { it.outputBudgetChars == EXTENDED_MODEL_COUNCIL_OUTPUT_BUDGET_CHARS })
        assertTrue(planned.seats.all { it.outputBudgetChars == EXTENDED_MODEL_COUNCIL_OUTPUT_BUDGET_CHARS })
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
        seatStrategy: String? = null,
        plannedSeats: kotlinx.serialization.json.JsonArray? = null,
        allowExternalCli: Boolean = false,
        rounds: Int? = null,
    ) = buildJsonObject {
        put("mode", mode)
        put("objective", "Judge the plan.")
        if (allowExternalCli) put("allow_external_cli", true)
        seatStrategy?.let { put("seat_strategy", it) }
        plannedSeats?.let { put("planned_seats", it) }
        seats?.let { put("seats", it) }
        rounds?.let { put("rounds", it) }
    }

    private fun tempSeat(role: String, modelId: String, outputBudgetChars: Int? = null) = buildJsonObject {
        put("role", role)
        put("model_id", modelId)
        outputBudgetChars?.let { put("output_budget_chars", it) }
    }

    private fun plannedSeat(
        name: String,
        role: String,
        seatId: String? = null,
        modelRef: String? = null,
        runnerType: String? = null,
        externalTool: String? = null,
        externalRuntime: String? = null,
        externalModel: String? = null,
        temperature: Float? = null,
        outputBudgetChars: Int? = null,
    ) = buildJsonObject {
        seatId?.let { put("seat_id", it) }
        put("name", name)
        put("role", role)
        put("system_prompt", "Please focus on $role.")
        modelRef?.let { put("model_ref", it) }
        runnerType?.let { put("runner_type", it) }
        externalTool?.let { put("external_tool", it) }
        externalRuntime?.let { put("external_runtime", it) }
        externalModel?.let { put("external_model", it) }
        temperature?.let { put("temperature", it) }
        outputBudgetChars?.let { put("output_budget_chars", it) }
    }
}
