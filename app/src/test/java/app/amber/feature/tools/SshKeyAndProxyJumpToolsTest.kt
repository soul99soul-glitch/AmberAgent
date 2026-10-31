package app.amber.feature.tools

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.amber.ai.core.MessageRole
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.ui.UIMessagePart.Tool
import app.amber.core.settings.secret.SecretCipher
import app.amber.core.settings.secret.SecretStore
import app.amber.core.settings.secret.SecretStoreBackend
import app.amber.core.settings.ssh.SshKeyStore
import app.amber.core.settings.ssh.SshProfileStore
import app.amber.core.utils.CREDENTIAL_REDACTION
import app.amber.core.utils.CREDENTIAL_WITHHELD_ON_RESUME
import app.amber.core.utils.JsonInstant
import app.amber.core.utils.credentialArgumentRoles
import app.amber.core.utils.hasSettledCredentialArguments
import app.amber.core.utils.redactCredentialArguments
import app.amber.core.utils.toolTakesCredentialArguments
import app.amber.core.utils.withSettledCredentialsRedacted
import app.amber.core.utils.withUnapprovedCredentialsWithheld
import app.amber.feature.terminal.SshKeyGenType
import app.amber.feature.terminal.SshKeyPairs
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SshKeyAndProxyJumpToolsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var keyStore: SshKeyStore
    private lateinit var profileStore: SshProfileStore
    private lateinit var keyTools: SshKeyTools
    private lateinit var profileTools: SshProfileTools
    private lateinit var byName: Map<String, app.amber.ai.core.Tool>

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) {
            File(tempFolder.newFolder(), "ssh-key-tools.preferences_pb")
        }
        val secretStore = localSecretStore()
        keyStore = SshKeyStore(dataStore, secretStore)
        profileStore = SshProfileStore(dataStore, secretStore, keyStore)
        keyTools = SshKeyTools(profileStore, keyStore)
        profileTools = SshProfileTools(profileStore)
        byName = (keyTools.getTools() + profileTools.getTools()).associateBy { it.name }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `ssh_key tools register with expected approval and risk policies and workflow guidance`() {
        assertTrue(byName.keys.containsAll(SshKeyTools.TOOL_NAMES))

        val listTool = byName.getValue(SshKeyTools.TOOL_LIST)
        assertFalse(listTool.needsApproval)
        assertTrue(listTool.allowsAutoApproval)
        assertEquals(ToolRisk.Normal, listTool.invocationPolicy("{}").risk)

        listOf(
            SshKeyTools.TOOL_GENERATE,
            SshKeyTools.TOOL_IMPORT,
            SshKeyTools.TOOL_DELETE,
        ).forEach { name ->
            val tool = byName.getValue(name)
            assertTrue("$name must need approval", tool.needsApproval)
            assertFalse("$name must not allow auto approval", tool.allowsAutoApproval)
            val policy = tool.invocationPolicy("{}")
            assertEquals(name, ToolRisk.High, policy.risk)
            assertTrue(name, policy.mutates)
            assertTrue(name, policy.needsApproval)
            assertFalse(name, policy.autoApprovable)
        }

        // Verify workflow description is included in ssh_key_generate, ssh_profile_create, ssh_profile_update
        listOf(
            SshKeyTools.TOOL_GENERATE,
            SshProfileTools.TOOL_CREATE,
            SshProfileTools.TOOL_UPDATE,
        ).forEach { name ->
            val desc = byName.getValue(name).description
            assertTrue("$name description must mention authorized_keys workflow", desc.contains("authorized_keys"))
            assertTrue("$name description must mention ssh_key_generate", desc.contains("ssh_key_generate"))
        }
    }

    @Test
    fun `ssh_key_generate creates Ed25519 and ECDSA keys and binds to ssh_profile_create via key_id`() = runBlocking {
        val genEd = run(
            SshKeyTools.TOOL_GENERATE,
            buildJsonObject {
                put("name", "prod-ed25519")
                put("type", "ed25519")
            },
        )
        assertEquals("generated", genEd.str("status"))
        val keyObj = genEd.jsonObject["key"]!!.jsonObject
        val keyId = keyObj.str("key_id")!!
        assertEquals("ssh-ed25519", keyObj.str("algorithm"))
        assertTrue(keyObj.str("public_key")!!.startsWith("ssh-ed25519 "))
        assertTrue(keyObj.str("fingerprint_sha256")!!.startsWith("SHA256:"))
        assertFalse(genEd.toString().contains("PRIVATE KEY"))

        val genEcdsa = run(
            SshKeyTools.TOOL_GENERATE,
            buildJsonObject {
                put("name", "prod-ecdsa")
                put("type", "ecdsa-p256")
            },
        )
        assertEquals("generated", genEcdsa.str("status"))
        assertEquals("ecdsa-sha2-nistp256", genEcdsa.jsonObject["key"]!!.jsonObject.str("algorithm"))

        // Create bastion profile using key_id
        val bastionRes = run(
            SshProfileTools.TOOL_CREATE,
            buildJsonObject {
                put("name", "bastion")
                put("host", "1.2.3.4")
                put("username", "ops")
                put("auth_method", "key")
                put("key_id", keyId)
            },
        )
        assertEquals("created", bastionRes.str("status"))
        val bastionId = bastionRes.jsonObject["profile"]!!.jsonObject.str("id")!!
        assertEquals(keyId, bastionRes.jsonObject["profile"]!!.jsonObject.str("key_id"))

        // Create inner profile jumping through bastion and reusing keyId
        val appRes = run(
            SshProfileTools.TOOL_CREATE,
            buildJsonObject {
                put("name", "inner-app")
                put("host", "10.0.1.8")
                put("username", "ubuntu")
                put("key_id", keyId)
                put("jump_profile_id", bastionId)
            },
        )
        assertEquals("created", appRes.str("status"))
        val appProfile = appRes.jsonObject["profile"]!!.jsonObject
        assertEquals(bastionId, appProfile.str("jump_profile_id"))
        assertEquals(2, appProfile.jsonObject["route"]!!.jsonArray.size)
        assertFalse("Untrusted hops mean route_ready is false", appProfile.bool("route_ready")!!)

        // Deleting key while referenced by profiles is blocked with key_in_use
        val delKeyBlocked = run(
            SshKeyTools.TOOL_DELETE,
            buildJsonObject { put("key_id", keyId) },
        )
        assertEquals("rejected", delKeyBlocked.str("status"))
        assertEquals("key_in_use", delKeyBlocked.str("code"))
        assertEquals(2, delKeyBlocked.jsonObject["dependent_profiles"]!!.jsonArray.size)

        // Deleting bastion while referenced by inner-app is blocked with in_use_as_jump
        val delJumpBlocked = run(
            SshProfileTools.TOOL_DELETE,
            buildJsonObject { put("id", bastionId) },
        )
        assertEquals("rejected", delJumpBlocked.str("status"))
        assertEquals("in_use_as_jump", delJumpBlocked.str("code"))
    }

    @Test
    fun `ssh_key_import imports valid key and rejects all 5 placeholders`() = runBlocking {
        val samplePair = SshKeyPairs.generate(name = "imported", type = SshKeyGenType.ED25519)
        val imported = run(
            SshKeyTools.TOOL_IMPORT,
            buildJsonObject {
                put("name", "imported-ed25519")
                put("private_key", samplePair.privateKeyOpenSsh)
            },
        )
        assertEquals("imported", imported.str("status"))
        val keyObj = imported.jsonObject["key"]!!.jsonObject
        assertEquals(samplePair.key.fingerprintSha256, keyObj.str("fingerprint_sha256"))
        assertFalse(imported.toString().contains("BEGIN OPENSSH PRIVATE KEY"))

        val placeholders = listOf("<redacted>", "[redacted]", "***", "••••", "(empty)")
        for (placeholder in placeholders) {
            val rejectedImport = run(
                SshKeyTools.TOOL_IMPORT,
                buildJsonObject {
                    put("name", "bad-$placeholder")
                    put("private_key", placeholder)
                },
            )
            assertEquals("rejected", rejectedImport.str("status"))

            val rejectedCreate = run(
                SshProfileTools.TOOL_CREATE,
                buildJsonObject {
                    put("name", "bad-prof")
                    put("host", "1.2.3.4")
                    put("username", "root")
                    put("password", placeholder)
                },
            )
            assertEquals("rejected", rejectedCreate.str("status"))
        }
    }

    @Test
    fun `ssh_profile_create and update enforce jump_cycle and jump_chain_too_long`() = runBlocking {
        val p1 = createPasswordProfile("j1", "10.0.0.1")
        val p2 = createPasswordProfile("j2", "10.0.0.2", jumpId = p1)
        val p3 = createPasswordProfile("j3", "10.0.0.3", jumpId = p2)
        val p4 = createPasswordProfile("target", "10.0.0.4", jumpId = p3)

        // 5th hop (4 jump hosts) must be rejected with jump_chain_too_long
        val tooLong = run(
            SshProfileTools.TOOL_CREATE,
            buildJsonObject {
                put("name", "too-deep")
                put("host", "10.0.0.5")
                put("username", "root")
                put("password", "secret")
                put("jump_profile_id", p4)
            },
        )
        assertEquals("rejected", tooLong.str("status"))
        assertEquals("jump_chain_too_long", tooLong.str("code"))

        // Pointing j1 -> target must be rejected with jump_cycle
        val cycle = run(
            SshProfileTools.TOOL_UPDATE,
            buildJsonObject {
                put("id", p1)
                put("jump_profile_id", p4)
            },
        )
        assertEquals("rejected", cycle.str("status"))
        assertEquals("jump_cycle", cycle.str("code"))
    }

    @Test
    fun `ssh_key_import redacts private_key and passphrase across all settled and checkpoint paths`() {
        val rawInput = """{"name":"k1","private_key":"-----BEGIN OPENSSH PRIVATE KEY-----\nsecret\n-----END-----","passphrase":"my-pp"}"""
        val element = JsonInstant.parseToJsonElement(rawInput)
        assertTrue(toolTakesCredentialArguments("ssh_key_import", element))
        assertEquals(listOf("private_key", "passphrase"), credentialArgumentRoles("ssh_key_import", element))

        val directRedacted = redactCredentialArguments("ssh_key_import", element)!!.jsonObject
        assertEquals(CREDENTIAL_REDACTION, directRedacted.str("private_key"))
        assertEquals(CREDENTIAL_REDACTION, directRedacted.str("passphrase"))
        assertEquals("k1", directRedacted.str("name"))

        // Terminal states: Denied, Answered, Executed (including error/timeout/cancelled outputs)
        val terminalTools = listOf(
            Tool("c1", "ssh_key_import", rawInput, approvalState = ToolApprovalState.Denied("denied")),
            Tool("c2", "ssh_key_import", rawInput, approvalState = ToolApprovalState.Answered("ok")),
            Tool(
                "c3",
                "ssh_key_import",
                rawInput,
                approvalState = ToolApprovalState.Approved,
                output = listOf(UIMessagePart.Text("""{"status":"imported"}""")),
            ),
            Tool(
                "c4",
                "ssh_key_import",
                rawInput,
                approvalState = ToolApprovalState.Approved,
                output = listOf(UIMessagePart.Text("""{"status":"rejected","error":"timeout"}""")),
            ),
            Tool("cancelled", "ssh_key_import", rawInput, output = listOf(UIMessagePart.Text("""{"status":"cancelled"}"""))),
            Tool("abandoned", "ssh_key_import", rawInput, approvalState = ToolApprovalState.Denied("abandoned")),
            Tool("failed", "ssh_key_import", rawInput, output = listOf(UIMessagePart.Text("""{"status":"failed"}"""))),
        )
        for (settled in terminalTools) {
            assertTrue(settled.hasSettledCredentialArguments())
            val msg = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(settled)).withSettledCredentialsRedacted()
            val sanitizedInput = (msg.parts.single() as Tool).input
            assertFalse(sanitizedInput.contains("secret"))
            assertFalse(sanitizedInput.contains("my-pp"))
            assertTrue(sanitizedInput.contains(CREDENTIAL_REDACTION))
        }

        // Unapproved checkpoint path withholds credentials and marks Denied(CREDENTIAL_WITHHELD_ON_RESUME)
        val pending = Tool("c5", "ssh_key_import", rawInput, approvalState = ToolApprovalState.Pending)
        val withheld = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(pending)))
            .withUnapprovedCredentialsWithheld()
            .single().parts.single() as Tool
        assertEquals("{}", withheld.input)
        assertEquals(CREDENTIAL_WITHHELD_ON_RESUME, (withheld.approvalState as ToolApprovalState.Denied).reason)
        val incomplete = pending.copy(input = """{"private_key":"partial-secret""")
        val checkpoint = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(incomplete)))
            .withUnapprovedCredentialsWithheld().single().parts.single() as Tool
        assertFalse(checkpoint.input.contains("partial-secret"))
    }

    @Test
    fun `subagent ToolProfileFilter excludes all 6 SSH profile and key write tools`() {
        assertEquals(
            setOf(
                "ssh_profile_create",
                "ssh_profile_update",
                "ssh_profile_delete",
                "ssh_key_generate",
                "ssh_key_import",
                "ssh_key_delete",
            ),
            SSH_PROFILE_WRITE_TOOL_NAMES,
        )
        val allTools = byName.values.toList()
        val filtered = allTools.filterNot { it.name in SSH_PROFILE_WRITE_TOOL_NAMES }
            .map { it.name }
            .toSet()

        assertTrue(filtered.contains("ssh_profile_list"))
        assertTrue(filtered.contains("ssh_key_list"))
        SSH_PROFILE_WRITE_TOOL_NAMES.forEach { writeTool ->
            assertFalse("Subagent must not receive $writeTool", filtered.contains(writeTool))
            val part = Tool("id-$writeTool", writeTool, "{}")
            assertTrue(
                "$writeTool must require in-app approval only (notification/lockscreen allow=true blocked)",
                app.amber.feature.runtime.AgentLiveStatusNotifier.requiresInAppApprovalOnly(part),
            )
        }
    }

    private suspend fun createPasswordProfile(name: String, host: String, jumpId: String? = null): String {
        val res = run(
            SshProfileTools.TOOL_CREATE,
            buildJsonObject {
                put("name", name)
                put("host", host)
                put("username", "ubuntu")
                put("password", "pw-$name")
                if (jumpId != null) put("jump_profile_id", jumpId)
            },
        )
        assertEquals("created", res.str("status"))
        return res.jsonObject["profile"]!!.jsonObject.str("id")!!
    }

    private suspend fun run(toolName: String, input: JsonObject): JsonObject {
        val output = byName.getValue(toolName).execute(input)
        val text = output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        return JsonInstant.parseToJsonElement(text).jsonObject
    }

    private fun JsonObject.str(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull
    private fun JsonObject.bool(key: String): Boolean? = get(key)?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
}

private fun localSecretStore(): SecretStore = SecretStore(
    backend = object : SecretStoreBackend {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String) {
            values[key] = value
        }
        override fun remove(key: String) {
            values.remove(key)
        }
        override fun keys(): Set<String> = values.keys.toSet()
    },
    cipher = object : SecretCipher {
        override fun encrypt(plaintext: String): String = "enc:$plaintext"
        override fun decrypt(stored: String): String? = stored.removePrefix("enc:")
    },
)
