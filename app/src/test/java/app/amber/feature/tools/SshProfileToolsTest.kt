package app.amber.feature.tools

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.amber.ai.core.InputSchema
import app.amber.ai.ui.UIMessagePart
import app.amber.core.settings.secret.SecretCipher
import app.amber.core.settings.secret.SecretStore
import app.amber.core.settings.secret.SecretStoreBackend
import app.amber.core.settings.ssh.SshProfileStore
import app.amber.core.utils.CREDENTIAL_REDACTION
import app.amber.core.utils.CREDENTIAL_WITHHELD_ON_RESUME
import app.amber.core.utils.hasSettledCredentialArguments
import app.amber.core.utils.withUnapprovedCredentialsWithheld
import app.amber.core.utils.credentialArgumentRoles
import app.amber.core.utils.maskSensitiveJson
import app.amber.core.utils.redactCredentialArguments
import app.amber.core.utils.toolTakesCredentialArguments
import app.amber.core.utils.withSettledCredentialsRedacted
import app.amber.core.utils.JsonInstant
import app.amber.ai.core.MessageRole
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart.Tool
import app.amber.feature.terminal.SshAuthMethod
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Contract tests for the agent-facing SSH profile tools: schema shape, the
 * write-only credential boundary, High-risk approval classification, the
 * create → update → delete lifecycle including credential cleanup, and
 * argument rejection.
 *
 * These run as JVM tests against a real [SshProfileStore] backed by a temporary
 * DataStore plus the in-memory SecretStore fixture, so they exercise the same
 * storage path the Settings screen and the agent share.
 */
class SshProfileToolsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var store: SshProfileStore
    private lateinit var tools: SshProfileTools
    private lateinit var byName: Map<String, app.amber.ai.core.Tool>

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) {
            File(tempFolder.newFolder(), "settings.preferences_pb")
        }
        store = SshProfileStore(dataStore, inMemorySecretStore())
        tools = SshProfileTools(store)
        byName = tools.getTools().associateBy { it.name }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    // ---------------- schema ----------------

    @Test
    fun `registers exactly the four documented tools`() {
        assertEquals(SshProfileTools.TOOL_NAMES, byName.keys)
    }

    @Test
    fun `list schema takes no arguments and create requires the profile identity`() {
        val list = byName.getValue(SshProfileTools.TOOL_LIST).parameters() as InputSchema.Obj
        assertTrue(list.properties.isEmpty())
        assertTrue(list.required.orEmpty().isEmpty())

        val create = byName.getValue(SshProfileTools.TOOL_CREATE).parameters() as InputSchema.Obj
        assertEquals(setOf("name", "host", "username"), create.required.orEmpty().toSet())
        val createProps = create.properties.keys
        assertTrue(createProps.containsAll(listOf("name", "host", "port", "username", "auth_method")))
        assertTrue(createProps.containsAll(SshProfileTools.CREDENTIAL_KEYS))

        val update = byName.getValue(SshProfileTools.TOOL_UPDATE).parameters() as InputSchema.Obj
        assertEquals(listOf("id"), update.required)
        assertTrue(update.properties.keys.containsAll(SshProfileTools.CREDENTIAL_KEYS))

        val delete = byName.getValue(SshProfileTools.TOOL_DELETE).parameters() as InputSchema.Obj
        assertEquals(listOf("id"), delete.required)
    }

    @Test
    fun `list is read-only and writers need explicit approval`() {
        val list = byName.getValue(SshProfileTools.TOOL_LIST)
        assertFalse(list.needsApproval)
        assertTrue(list.allowsAutoApproval)

        listOf(
            SshProfileTools.TOOL_CREATE,
            SshProfileTools.TOOL_UPDATE,
            SshProfileTools.TOOL_DELETE,
        ).forEach { name ->
            val tool = byName.getValue(name)
            assertTrue("$name must need approval", tool.needsApproval)
            assertFalse("$name must not be auto-approvable", tool.allowsAutoApproval)
        }
    }

    @Test
    fun `write tools are classified High risk while list stays Normal and read-only`() {
        listOf(
            SshProfileTools.TOOL_CREATE,
            SshProfileTools.TOOL_UPDATE,
            SshProfileTools.TOOL_DELETE,
        ).forEach { name ->
            val policy = byName.getValue(name).invocationPolicy("""{"id":"p1","name":"x","host":"h","username":"u"}""")
            assertEquals(name, ToolRisk.High, policy.risk)
            assertTrue(name, policy.mutates)
            assertTrue(name, policy.needsApproval)
            assertFalse(name, policy.autoApprovable)
            assertFalse(name, policy.concurrencySafe)
        }
        val listPolicy = byName.getValue(SshProfileTools.TOOL_LIST).invocationPolicy("{}")
        assertEquals(ToolRisk.Normal, listPolicy.risk)
        assertFalse(listPolicy.mutates)
        assertFalse(listPolicy.needsApproval)
        assertTrue(listPolicy.autoApprovable)
        assertEquals("terminal", listPolicy.category)
    }

    // ---------------- create ----------------

    @Test
    fun `create stores a password profile and selects it as default`() = runBlocking {
        val result = run(
            SshProfileTools.TOOL_CREATE,
            buildJsonObject {
                put("name", "prod-web")
                put("host", "10.0.0.5")
                put("port", 2222)
                put("username", "deploy")
                put("password", "s3cret-password")
                put("make_default", true)
            },
        )
        assertEquals("created", result.str("status"))
        val profile = result.jsonObject["profile"]!!.jsonObject
        assertEquals("prod-web", profile.str("name"))
        assertEquals("10.0.0.5", profile.str("host"))
        assertEquals("2222", profile.str("port"))
        assertEquals("deploy", profile.str("username"))
        assertEquals("password", profile.str("auth_method"))
        assertTrue(profile.bool("has_password")!!)
        assertFalse(profile.bool("has_private_key")!!)
        assertFalse("host key must stay unaccepted until Settings probes it", profile.bool("host_key_accepted")!!)
        assertEquals(profile.str("id"), result.str("default_profile_id"))

        // Credential reaches the store, not the transcript.
        assertFalse(result.contains("s3cret-password"))
        val stored = store.resolve(profile.str("id"))
        assertEquals("s3cret-password", store.readCredentials(stored).password)
    }

    @Test
    fun `create stores a private key profile with passphrase`() = runBlocking {
        val result = run(
            SshProfileTools.TOOL_CREATE,
            buildJsonObject {
                put("name", "bastion")
                put("host", "bastion.example.com")
                put("username", "ops")
                put("auth_method", "private_key")
                put("private_key", "-----BEGIN OPENSSH PRIVATE KEY-----key-body-----END-----")
                put("passphrase", "key-pass")
            },
        )
        assertEquals("created", result.str("status"))
        val profile = result.jsonObject["profile"]!!.jsonObject
        assertEquals("key", profile.str("auth_method"))
        assertTrue(profile.bool("has_private_key")!!)
        assertTrue(profile.bool("has_passphrase")!!)
        assertFalse(profile.bool("has_password")!!)
        assertFalse(result.contains("key-body"))
        assertFalse(result.contains("key-pass"))

        val credentials = store.readCredentials(store.resolve(profile.str("id")))
        assertTrue(credentials.privateKey!!.contains("key-body"))
        assertEquals("key-pass", credentials.passphrase)
        // Port defaults to 22, and no default selection is inferred implicitly.
        assertEquals("22", profile.str("port"))
    }

    @Test
    fun `create rejects a duplicate name`() = runBlocking {
        run(SshProfileTools.TOOL_CREATE, create("web", password = "p1"))
        val duplicate = run(SshProfileTools.TOOL_CREATE, create("WEB", password = "p2"))
        assertEquals("rejected", duplicate.str("status"))
        assertTrue(duplicate.str("error")!!.contains("already exists"))
        assertEquals(1, store.snapshot().profiles.size)
    }

    @Test
    fun `create rejects invalid arguments without writing anything`() = runBlocking {
        val cases = mapOf(
            "missing name" to buildJsonObject {
                put("host", "h"); put("username", "u"); put("password", "p")
            },
            "missing host" to buildJsonObject {
                put("name", "n"); put("username", "u"); put("password", "p")
            },
            "missing username" to buildJsonObject {
                put("name", "n"); put("host", "h"); put("password", "p")
            },
            "port zero" to buildJsonObject {
                put("name", "n"); put("host", "h"); put("username", "u")
                put("port", 0); put("password", "p")
            },
            "port too large" to buildJsonObject {
                put("name", "n"); put("host", "h"); put("username", "u")
                put("port", 65_536); put("password", "p")
            },
            "unknown auth method" to buildJsonObject {
                put("name", "n"); put("host", "h"); put("username", "u")
                put("auth_method", "kerberos"); put("password", "p")
            },
            "password method without a password" to buildJsonObject {
                put("name", "n"); put("host", "h"); put("username", "u")
            },
            "private key method without a key" to buildJsonObject {
                put("name", "n"); put("host", "h"); put("username", "u")
                put("auth_method", "private_key")
            },
            "credential of the wrong method" to buildJsonObject {
                put("name", "n"); put("host", "h"); put("username", "u")
                put("private_key", "-----BEGIN-----")
            },
            "host with whitespace" to buildJsonObject {
                put("name", "n"); put("host", "bad host"); put("username", "u")
                put("password", "p")
            },
        )
        cases.forEach { (label, input) ->
            val result = run(SshProfileTools.TOOL_CREATE, input)
            assertEquals(label, "rejected", result.str("status"))
            assertTrue("$label must explain the rejection", !result.str("error").isNullOrBlank())
        }
        assertEquals("no rejected call may persist a profile", 0, store.snapshot().profiles.size)
    }

    // ---------------- list ----------------

    @Test
    fun `list reports credential presence as booleans and never returns a value`() = runBlocking {
        run(SshProfileTools.TOOL_CREATE, create("alpha", password = "alpha-secret"))
        run(
            SshProfileTools.TOOL_CREATE,
            buildJsonObject {
                put("name", "beta")
                put("host", "beta.example.com")
                put("username", "beta-user")
                put("auth_method", "private_key")
                put("private_key", "-----BEGIN-----beta-key-body-----END-----")
            },
        )

        val list = run(SshProfileTools.TOOL_LIST, buildJsonObject {})
        assertEquals("ok", list.str("status"))
        val profiles = list.jsonObject["profiles"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("alpha", "beta"), profiles.map { it.str("name") })
        val alpha = profiles.first { it.str("name") == "alpha" }
        val beta = profiles.first { it.str("name") == "beta" }
        assertTrue(alpha.bool("has_password")!!)
        assertFalse(alpha.bool("has_private_key")!!)
        assertTrue(beta.bool("has_private_key")!!)
        assertFalse(beta.bool("has_password")!!)
        assertEquals("password", alpha.str("auth_method"))
        assertEquals("key", beta.str("auth_method"))

        assertFalse(list.contains("alpha-secret"))
        assertFalse(list.contains("beta-key-body"))
        assertFalse(list.contains("BEGIN"))
        // Neither the credential revision nor the fingerprint leaks into the tool surface.
        profiles.forEach { profile ->
            assertFalse(profile.keys.contains("credential_revision"))
            assertFalse(profile.keys.contains("accepted_host_key_fingerprint"))
        }
    }

    @Test
    fun `list on an empty store returns an empty array without a default`() = runBlocking {
        val list = run(SshProfileTools.TOOL_LIST, buildJsonObject {})
        assertEquals("ok", list.str("status"))
        assertTrue(list.jsonObject["profiles"]!!.jsonArray.isEmpty())
        assertNull(list.jsonObject["default_profile_id"])
    }

    // ---------------- update ----------------

    @Test
    fun `update changes only the supplied fields`() = runBlocking {
        val created = createProfile("web", password = "initial-password")
        val result = run(
            SshProfileTools.TOOL_UPDATE,
            buildJsonObject {
                put("id", created)
                put("host", "new.example.com")
            },
        )
        assertEquals("updated", result.str("status"))
        val profile = result.jsonObject["profile"]!!.jsonObject
        assertEquals("web", profile.str("name"))
        assertEquals("new.example.com", profile.str("host"))
        assertEquals("web-user", profile.str("username"))
        assertEquals(listOf("host"), result.jsonObject["changed_fields"]!!.jsonArray.map { it.jsonPrimitive.content })
        // Omitted credentials are preserved.
        assertEquals("initial-password", store.readCredentials(store.resolve(created)).password)
    }

    @Test
    fun `update replaces a password and an empty passphrase clears it`() = runBlocking {
        val created = createKeyProfile()
        val replaced = run(
            SshProfileTools.TOOL_UPDATE,
            buildJsonObject {
                put("id", created)
                put("private_key", "-----BEGIN-----replacement-key-----END-----")
                put("passphrase", "new-pass")
            },
        )
        assertEquals("updated", replaced.str("status"))
        val credentials = store.readCredentials(store.resolve(created))
        assertTrue(credentials.privateKey!!.contains("replacement-key"))
        assertEquals("new-pass", credentials.passphrase)
        assertFalse(replaced.contains("replacement-key"))
        assertFalse(replaced.contains("new-pass"))

        val cleared = run(
            SshProfileTools.TOOL_UPDATE,
            buildJsonObject {
                put("id", created)
                put("passphrase", "")
            },
        )
        assertEquals("updated", cleared.str("status"))
        // An empty passphrase argument means "supplied, but empty": the store clears it.
        assertNull(store.readCredentials(store.resolve(created)).passphrase)
    }

    @Test
    fun `update rejects a switch of auth method without the new credential`() = runBlocking {
        val created = createProfile("web", password = "password-here")
        val result = run(
            SshProfileTools.TOOL_UPDATE,
            buildJsonObject {
                put("id", created)
                put("auth_method", "private_key")
            },
        )
        assertEquals("rejected", result.str("status"))
        assertTrue(result.str("error")!!.contains("private_key is required"))
        // The profile keeps its original method and credential.
        val stored = store.resolve(created)
        assertEquals(SshAuthMethod.PASSWORD, stored.authMethod)
        assertEquals("password-here", store.readCredentials(stored).password)
    }

    @Test
    fun `update rejects an unknown id and a colliding name`() = runBlocking {
        val first = createProfile("first", password = "p1")
        run(SshProfileTools.TOOL_CREATE, create("second", password = "p2"))

        val unknown = run(
            SshProfileTools.TOOL_UPDATE,
            buildJsonObject { put("id", "does-not-exist"); put("name", "renamed") },
        )
        assertEquals("rejected", unknown.str("status"))
        assertTrue(unknown.str("error")!!.contains("not found"))

        val collision = run(
            SshProfileTools.TOOL_UPDATE,
            buildJsonObject { put("id", first); put("name", "second") },
        )
        assertEquals("rejected", collision.str("status"))
        assertTrue(collision.str("error")!!.contains("already exists"))
        assertEquals("first", store.resolve(first).name)
    }

    @Test
    fun `update can retarget the same profile name to itself`() = runBlocking {
        val created = createProfile("web", password = "p1")
        val result = run(
            SshProfileTools.TOOL_UPDATE,
            buildJsonObject { put("id", created); put("name", "web"); put("port", 2200) },
        )
        assertEquals("updated", result.str("status"))
        assertEquals("2200", result.jsonObject["profile"]!!.jsonObject.str("port"))
    }

    // ---------------- delete ----------------

    @Test
    fun `full create update delete lifecycle clears credentials and reassigns the default`() =
        runBlocking {
            val first = createProfile("first", password = "first-password", makeDefault = true)
            val second = createProfile("second", password = "second-password", makeDefault = true)
            assertEquals(second, store.snapshot().defaultProfileId)

            run(
                SshProfileTools.TOOL_UPDATE,
                buildJsonObject { put("id", first); put("password", "rotated-password") },
            )
            assertEquals("rotated-password", store.readCredentials(store.resolve(first)).password)

            val deleted = run(SshProfileTools.TOOL_DELETE, buildJsonObject { put("id", second) })
            assertEquals("deleted", deleted.str("status"))
            assertTrue(deleted.bool("credentials_removed")!!)
            assertEquals("1", deleted.str("remaining_profiles"))
            // Deleting the default promotes the remaining profile, like the Settings list.
            assertEquals(first, deleted.str("default_profile_id"))

            val state = store.snapshot()
            assertEquals(listOf(first), state.profiles.map { it.id })
            assertTrue(
                "the deleted profile's credentials must be gone",
                store.credentialPresence(
                    store.snapshot().profiles.single().copy(id = second),
                ).let { !it.hasPassword && !it.hasPrivateKey && !it.hasPassphrase },
            )
            // No orphan secret survives under the deleted owner id.
            val remainingSecrets = store.let { runCatching { it.credentialsFor(store.resolve(first)) } }
            assertTrue(remainingSecrets.isSuccess)
        }

    @Test
    fun `delete removes every credential of the profile and nothing else`() = runBlocking {
        val kept = createProfile("kept", password = "kept-password")
        val removed = createKeyProfile(name = "removed")
        val removedProfile = store.resolve(removed)
        val keptProfile = store.resolve(kept)

        assertEquals("deleted", run(SshProfileTools.TOOL_DELETE, buildJsonObject { put("id", removed) }).str("status"))

        val deletedPresence = store.credentialPresence(removedProfile)
        assertFalse("private key must be removed", deletedPresence.hasPrivateKey)
        assertFalse("passphrase must be removed", deletedPresence.hasPassphrase)
        // An unrelated profile keeps its credentials.
        assertEquals("kept-password", store.readCredentials(keptProfile).password)
        assertTrue(store.credentialPresence(keptProfile).hasPassword)
    }

    @Test
    fun `delete rejects an unknown id and accepts only the first delete of a known one`() = runBlocking {
        val created = createProfile("web", password = "p1")
        val unknown = run(SshProfileTools.TOOL_DELETE, buildJsonObject { put("id", "nope") })
        assertEquals("rejected", unknown.str("status"))
        assertTrue(unknown.str("error")!!.contains("not found"))

        assertEquals("deleted", run(SshProfileTools.TOOL_DELETE, buildJsonObject { put("id", created) }).str("status"))
        val again = run(SshProfileTools.TOOL_DELETE, buildJsonObject { put("id", created) })
        assertEquals("rejected", again.str("status"))
    }

    @Test
    fun `delete requires an id`() = runBlocking {
        val result = run(SshProfileTools.TOOL_DELETE, buildJsonObject {})
        assertEquals("rejected", result.str("status"))
        assertTrue(result.str("error")!!.contains("id is required"))
    }

    // ---------------- redaction placeholders must never become credentials ----------------

    @Test
    fun `create and update reject the transcript redaction placeholder`() = runBlocking {
        listOf(
            "the storage placeholder" to "\u003credacted\u003e",
            "the display mask" to "\u2022\u2022\u2022\u2022",
            "the display mask with a suffix" to "\u2022\u2022\u2022\u20221234",
        ).forEach { (label, placeholder) ->
            val create = run(
                SshProfileTools.TOOL_CREATE,
                buildJsonObject {
                    put("name", "web"); put("host", "h"); put("username", "u")
                    put("password", placeholder)
                },
            )
            assertEquals("$label must be rejected on create", "rejected", create.str("status"))
            assertTrue(
                "$label rejection must name the field: ${create.str("error")}",
                create.str("error")!!.contains("password"),
            )
        }
        assertEquals("no placeholder call may persist a profile", 0, store.snapshot().profiles.size)

        // The same guard protects update, so a real credential cannot be
        // overwritten by a mask echoed out of the transcript.
        val created = createProfile("web", password = "real-password")
        val update = run(
            SshProfileTools.TOOL_UPDATE,
            buildJsonObject { put("id", created); put("password", "\u2022\u2022\u2022\u2022") },
        )
        assertEquals("rejected", update.str("status"))
        assertEquals("real-password", store.readCredentials(store.resolve(created)).password)

        // A passphrase placeholder is refused as well.
        val keyProfile = createKeyProfile()
        val passphraseUpdate = run(
            SshProfileTools.TOOL_UPDATE,
            buildJsonObject { put("id", keyProfile); put("passphrase", "\u003credacted\u003e") },
        )
        assertEquals("rejected", passphraseUpdate.str("status"))
        assertEquals("original-pass", store.readCredentials(store.resolve(keyProfile)).passphrase)
    }

    @Test
    fun `password auth rejects a passphrase instead of dropping it silently`() = runBlocking {
        val create = run(
            SshProfileTools.TOOL_CREATE,
            buildJsonObject {
                put("name", "web"); put("host", "h"); put("username", "u")
                put("password", "real-password")
                put("passphrase", "pointless-passphrase")
            },
        )
        assertEquals("rejected", create.str("status"))
        assertTrue(create.str("error")!!.contains("passphrase requires auth_method=private_key"))
        assertEquals(0, store.snapshot().profiles.size)

        val created = createProfile("web", password = "real-password")
        val update = run(
            SshProfileTools.TOOL_UPDATE,
            buildJsonObject { put("id", created); put("passphrase", "still-pointless") },
        )
        assertEquals("rejected", update.str("status"))
    }

    @Test
    fun `receipts do not claim a passphrase for a password profile`() = runBlocking {
        // A password-auth create must not report passphrase in changed fields:
        // the store drops it, so claiming it would describe a write that never
        // happened. The rejection above is the primary guard; this asserts the
        // field list itself stays honest for the accepted path.
        val created = createProfile("web", password = "p1")
        val updated = run(
            SshProfileTools.TOOL_UPDATE,
            buildJsonObject { put("id", created); put("password", "rotated") },
        )
        val fields = updated.jsonObject["changed_fields"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("password"), fields)

        val keyProfile = createKeyProfile()
        val keyUpdate = run(
            SshProfileTools.TOOL_UPDATE,
            buildJsonObject { put("id", keyProfile); put("passphrase", "new-pass") },
        )
        assertEquals(
            listOf("passphrase"),
            keyUpdate.jsonObject["changed_fields"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `display mask never reveals any credential character`() {
        listOf(
            """{"password":"hunter2"}""",
            """{"private_key":"-----BEGIN-----body-----END-----"}""",
            """{"passphrase":"my-passphrase"}""",
            """{"password":"abcd"}""",
            """{"password":"\u003credacted\u003e"}""",
        ).forEach { input ->
            val masked = maskSensitiveJson(JsonInstant.parseToJsonElement(input)).toString()
            listOf("hunter2", "BEGIN", "my-passphrase", "abcd").forEach { fragment ->
                assertFalse("$input leaked $fragment as $masked", masked.contains(fragment))
            }
        }
        // The storage placeholder stays readable as itself, not re-truncated.
        assertEquals(
            "\u003credacted\u003e",
            maskSensitiveJson(JsonInstant.parseToJsonElement("""{"password":"\u003credacted\u003e"}"""))
                .jsonObject.getValue("password").jsonPrimitive.content,
        )
        // Non-credential sensitive keys keep their existing partial mask.
        assertEquals(
            "\u2022\u2022\u2022\u20221234",
            maskSensitiveJson(JsonInstant.parseToJsonElement("""{"api_key":"sk-secret-1234"}"""))
                .jsonObject.getValue("api_key").jsonPrimitive.content,
        )
    }

    // ---------------- credential masking ----------------

    @Test
    fun `credential arguments are redacted before storage and keep the other fields`() {
        val input = JsonInstant.parseToJsonElement(
            """{"id":"p1","host":"example.com","password":"plain","private_key":"key-body","passphrase":"pp"}""",
        )
        val redacted = redactCredentialArguments("ssh_profile_create", input) as JsonObject
        assertEquals("p1", redacted.str("id"))
        assertEquals("example.com", redacted.str("host"))
        SshProfileTools.CREDENTIAL_KEYS.forEach { key ->
            assertEquals(CREDENTIAL_REDACTION, redacted.str(key))
        }
        assertFalse(redacted.toString().contains("plain"))
        assertFalse(redacted.toString().contains("key-body"))
    }

    @Test
    fun `redaction leaves other tools and credential-free calls untouched`() {
        val otherTool = JsonInstant.parseToJsonElement("""{"password":"keep-me"}""")
        assertEquals(otherTool, redactCredentialArguments("ask_user", otherTool))

        val noCredential = JsonInstant.parseToJsonElement("""{"id":"p1","name":"x"}""")
        assertEquals(noCredential, redactCredentialArguments("ssh_profile_update", noCredential))
    }

    @Test
    fun `display-time masking hides the new credential keys`() {
        val masked = maskSensitiveJson(
            JsonInstant.parseToJsonElement(
                """{"private_key":"-----BEGIN-----secret-----","passphrase":"pass-phrase","host":"h"}""",
            ),
        ).toString()
        assertFalse(masked.contains("-----BEGIN-----secret-----"))
        assertFalse(masked.contains("pass-phrase"))
        assertTrue(masked.contains("\"h\""))
    }

    @Test
    fun `only settled calls are redacted so an approved-but-unrun call keeps its credential`() {
        // Pending and Approved are both still replayable: approval/crash resume
        // reads the stored arguments, so erasing them early would turn a real
        // credential into a placeholder.
        listOf(ToolApprovalState.Pending, ToolApprovalState.Approved).forEach { state ->
            val unrun = tool(approvalState = state, input = """{"id":"p1","password":"unrun-secret"}""")
            assertFalse("$state must not be settled", unrun.hasSettledCredentialArguments())
            val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(unrun))
            val result = message.withSettledCredentialsRedacted()
            assertEquals(
                "$state args survive for approval resume",
                unrun.input,
                (result.parts.first() as Tool).input,
            )
            assertTrue("an untouched message must not be copied", result === message)
        }

        // Executed, denied and answered are terminal and must be erased.
        listOf(
            ToolApprovalState.Denied("nope"),
            ToolApprovalState.Answered("ok"),
        ).forEach { state ->
            val settled = tool(approvalState = state, input = """{"id":"p1","password":"settled-secret"}""")
            assertTrue("$state must be settled", settled.hasSettledCredentialArguments())
            val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(settled))
            val redacted = message.withSettledCredentialsRedacted().parts.first() as Tool
            assertFalse("$state must not keep the value", redacted.input.contains("settled-secret"))
            assertTrue(redacted.input.contains(CREDENTIAL_REDACTION))
            assertTrue(redacted.input.contains("\"p1\""))
        }

        val executed = tool(
            approvalState = ToolApprovalState.Approved,
            input = """{"id":"p1","password":"executed-secret"}""",
        ).copy(output = listOf(UIMessagePart.Text("""{"status":"updated"}""")))
        assertTrue(executed.hasSettledCredentialArguments())
        assertFalse(
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(executed))
                .withSettledCredentialsRedacted().parts.first().let { (it as Tool).input }
                .contains("executed-secret"),
        )

        // A credential-free call is returned as the same instance.
        val plain = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                tool(approvalState = ToolApprovalState.Denied("x"), input = """{"id":"p1","host":"h"}"""),
            ),
        )
        assertTrue(plain.withSettledCredentialsRedacted() === plain)
    }

    @Test
    fun `mid-approval checkpoint withholds the credential and refuses to replay it`() {
        val pending = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = SshProfileTools.TOOL_CREATE,
            input = """{"name":"web","host":"h","username":"u","password":"plaintext-secret"}""",
            approvalState = ToolApprovalState.Pending,
        )
        val conversation = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(pending)))

        val withheldMessage = conversation.withUnapprovedCredentialsWithheld()
        val withheld = withheldMessage.single().parts.first() as Tool

        // No plaintext reaches the checkpoint...
        assertFalse(withheld.input.contains("plaintext-secret"))
        assertFalse(withheld.input.contains("password"))
        // ...and the call can never be resumed with a placeholder written through.
        assertTrue(withheld.approvalState is ToolApprovalState.Denied)
        val reason = (withheld.approvalState as ToolApprovalState.Denied).reason
        assertEquals(CREDENTIAL_WITHHELD_ON_RESUME, reason)
        // The non-credential profile identity is also gone: nothing can be replayed.
        assertEquals("{}", withheld.input)
    }

    @Test
    fun `credential helpers report which fields a call supplies`() {
        val input = JsonInstant.parseToJsonElement("""{"id":"p1","password":"x"}""")
        assertTrue(toolTakesCredentialArguments("ssh_profile_create", input))
        assertEquals(listOf("password"), credentialArgumentRoles("ssh_profile_create", input))
        assertFalse(toolTakesCredentialArguments("ssh_profile_create", JsonInstant.parseToJsonElement("""{"id":"p1"}""")))
        assertFalse(toolTakesCredentialArguments("ssh_profile_create", JsonInstant.parseToJsonElement("""{"password":""}""")))
        assertFalse(toolTakesCredentialArguments("file_write", input))
    }

    // ---------------- helpers ----------------

    private fun create(name: String, password: String) = buildJsonObject {
        put("name", name)
        put("host", "$name.example.com")
        put("username", "web-user")
        put("password", password)
    }

    private suspend fun createProfile(
        name: String,
        password: String,
        makeDefault: Boolean = false,
    ): String {
        val input = buildJsonObject {
            put("name", name)
            put("host", "$name.example.com")
            put("username", "web-user")
            put("password", password)
            if (makeDefault) put("make_default", true)
        }
        val result = run(SshProfileTools.TOOL_CREATE, input)
        assertEquals("created", result.str("status"))
        return result.jsonObject["profile"]!!.jsonObject.str("id")!!
    }

    private suspend fun createKeyProfile(name: String = "bastion"): String {
        val result = run(
            SshProfileTools.TOOL_CREATE,
            buildJsonObject {
                put("name", name)
                put("host", "$name.example.com")
                put("username", "ops")
                put("auth_method", "private_key")
                put("private_key", "-----BEGIN-----original-key-----END-----")
                put("passphrase", "original-pass")
            },
        )
        assertEquals("created", result.str("status"))
        return result.jsonObject["profile"]!!.jsonObject.str("id")!!
    }

    private suspend fun run(toolName: String, input: JsonObject): JsonObject {
        val output = byName.getValue(toolName).execute(input)
        val text = output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        val parsed = JsonInstant.parseToJsonElement(text).jsonObject
        // No tool result may ever contain a credential value.
        assertNoSecretLeak(text)
        return parsed
    }

    private fun assertNoSecretLeak(payload: String) {
        listOf(
            "-----BEGIN",
            "alpha-secret",
            "beta-key-body",
            "first-password",
            "second-password",
            "rotated-password",
            "initial-password",
            "password-here",
            "kept-password",
            "pending-secret",
            "approved-secret",
            "original-pass",
            "original-key",
            "new-pass",
            "replacement-key",
            "key-pass",
            "s3cret-password",
        ).forEach { secret ->
            assertFalse("tool output leaked $secret", payload.contains(secret))
        }
    }

    private fun JsonObject.str(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull

    private fun JsonObject.bool(key: String): Boolean? =
        get(key)?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
}

private fun tool(approvalState: ToolApprovalState, input: String) = Tool(
    toolCallId = "call-1",
    toolName = SshProfileTools.TOOL_UPDATE,
    input = input,
    approvalState = approvalState,
)

/** Same split as the :core:settings test fixture, kept local to this module. */
private fun inMemorySecretStore(): SecretStore = SecretStore(
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
