package app.amber.feature.ui.components.message

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.dp
import app.amber.ai.ui.UIMessagePart
import app.amber.core.utils.JsonInstant
import app.amber.feature.tools.SshProfileTools
import app.amber.feature.ui.theme.AmberBase
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.feature.ui.theme.buildAmberTokens
import app.amber.feature.ui.theme.defaultAmberTextStyles
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Visual + copy check for the new SSH profile tool surfaces: the write receipt
 * names the target profile and the changed fields without ever echoing a
 * credential, and the list receipt stays a plain read.
 *
 * Two screenshots (light and dark) are written to `build/reports/` so the
 * approval wording can be inspected in both themes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h900dp-xxhdpi")
class SshProfileToolReceiptTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun updateApprovalNamesProfileAndFieldsButNotTheCredential() {
        val arguments = JsonInstant.parseToJsonElement(
            """{"id":"prod-web","host":"new.example.com","password":"super-secret-value"}""",
        )
        val title = resolveTitles(listOf(SshProfileTools.TOOL_UPDATE to arguments)).single()

        org.junit.Assert.assertTrue(title.contains("prod-web"))
        org.junit.Assert.assertTrue(title.contains("host"))
        org.junit.Assert.assertTrue(title.contains("password"))
        org.junit.Assert.assertFalse("the receipt must not echo the password", title.contains("super-secret-value"))
    }

    @Test
    fun createDeleteAndListTitles() {
        val titles = resolveTitles(
            listOf(
                SshProfileTools.TOOL_CREATE to JsonInstant.parseToJsonElement(
                    """{"name":"prod-web","host":"h","username":"u","password":"super-secret-value"}""",
                ),
                SshProfileTools.TOOL_DELETE to JsonInstant.parseToJsonElement("""{"id":"prod-web"}"""),
                SshProfileTools.TOOL_LIST to JsonInstant.parseToJsonElement("{}"),
            ),
        )
        org.junit.Assert.assertTrue(titles[0].contains("prod-web"))
        org.junit.Assert.assertFalse(titles[0].contains("super-secret-value"))
        org.junit.Assert.assertTrue(titles[1].contains("prod-web"))
        org.junit.Assert.assertTrue(titles[2].isNotBlank())
        // All four belong to the terminal family, which drives icon and kind label.
        SshProfileTools.TOOL_NAMES.forEach { name ->
            org.junit.Assert.assertEquals(name, AgentToolKind.TERMINAL, getToolKind(name))
        }
    }

    @Test
    fun createTitleCarriesEndpointAuthMethodAndCredentialAction() {
        val title = resolveTitles(
            listOf(
                SshProfileTools.TOOL_CREATE to JsonInstant.parseToJsonElement(
                    """{"name":"prod-web","host":"h.example.com","port":2222,"username":"deploy",""" +
                        """"auth_method":"password","password":"super-secret-value"}""",
                ),
            ),
        ).single()
        assertContains(title, "prod-web")
        assertContains(title, "deploy@h.example.com:2222")
        assertContains(title, "password")
        assertContains(title, "set/replace the password")
        assertNotContains(title, "super-secret-value")
    }

    @Test
    fun createTitleNamesThePrivateKeyCredential() {
        val title = resolveTitles(
            listOf(
                SshProfileTools.TOOL_CREATE to JsonInstant.parseToJsonElement(
                    """{"name":"bastion","host":"b","username":"ops","auth_method":"private_key",""" +
                        """"private_key":"-----BEGIN-----body-----END-----","passphrase":"pp"}""",
                ),
            ),
        ).single()
        assertContains(title, "set/replace the private key")
        assertNotContains(title, "BEGIN")
        assertNotContains(title, "pp")
    }

    @Test
    fun updateTitleNamesEveryChangedFieldIncludingDefaultAndPassphraseClear() {
        val titles = resolveTitles(
            listOf(
                SshProfileTools.TOOL_UPDATE to JsonInstant.parseToJsonElement(
                    """{"id":"prod-web","port":2200,"make_default":true}""",
                ),
                SshProfileTools.TOOL_UPDATE to JsonInstant.parseToJsonElement(
                    """{"id":"prod-web","passphrase":""}""",
                ),
                SshProfileTools.TOOL_UPDATE to JsonInstant.parseToJsonElement(
                    """{"id":"prod-web","password":"super-secret-value"}""",
                ),
            ),
        )
        val setDefault = titles[0]
        assertContains(setDefault, "prod-web")
        assertContains(setDefault, "port")
        assertContains(setDefault, "default")

        // Clearing a stored passphrase is its own action, not "setting" one.
        val clearPassphrase = titles[1]
        assertContains(clearPassphrase, "prod-web")
        assertContains(clearPassphrase, "clear the key passphrase")

        val setPassword = titles[2]
        assertContains(setPassword, "set/replace the password")
        assertNotContains(setPassword, "super-secret-value")
    }

    @Test
    fun rendersLightApprovalCards() = renderAndCapture("light", AmberBase.LIGHT)

    @Test
    fun rendersDarkApprovalCards() = renderAndCapture("dark", AmberBase.DARK)

    private fun renderAndCapture(label: String, base: AmberBase) {
        compose.setContent { receipt(base) }
        compose.onAllNodesWithText("prod-web", substring = true).assertCountEquals(3)
        // The listing is read-only, so it must not look like an approval card.
        compose.onNodeWithTag("ssh-profile-list-capsule").assertIsDisplayed()
        compose.onAllNodesWithText("super-secret-value", substring = true).assertCountEquals(0)
        capture("ssh-profile-tool-$label.png")
    }

    @Composable
    private fun receipt(base: AmberBase) {
        val tokens = buildAmberTokens(base, Color(0xFFB8623A))
        CompositionLocalProvider(
            LocalAmberTokens provides tokens,
            LocalAmberType provides defaultAmberTextStyles(),
        ) {
            MaterialTheme {
                Column(
                    modifier = Modifier
                        .testTag("ssh-receipts")
                        .width(380.dp)
                        .background(MaterialTheme.colorScheme.background)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // The approval card is exactly what the user sees for a
                    // High-risk SSH profile write: the title names the profile
                    // and the changed fields, never the credential value.
                    listOf(
                        tool("ssh_profile_update", """{"id":"prod-web","host":"new.example.com","password":"super-secret-value"}"""),
                        tool("ssh_profile_create", """{"name":"prod-web","host":"h","username":"u","password":"super-secret-value"}"""),
                        tool("ssh_profile_delete", """{"id":"prod-web"}"""),
                        tool("ssh_profile_list", "{}"),
                    ).forEach { call ->
                        val arguments = MessageRenderCache.toolInputJson(call.input)
                        val needsApproval = call.toolName != SshProfileTools.TOOL_LIST
                        if (needsApproval) {
                            ChatToolApprovalCard(
                                title = toolDisplayTitle(call.toolName, arguments, memoryAction = null),
                                toolName = call.toolName,
                                kindLabel = toolKindLabel(getToolKind(call.toolName), call.toolName),
                                icon = getToolIcon(call.toolName, null),
                                statusLabel = "等待批准",
                                detailsLabel = "查看详情",
                                denyLabel = "拒绝",
                                approveLabel = "批准",
                                onDetails = {},
                                onDeny = {},
                                onApprove = {},
                            )
                        } else {
                            // ssh_profile_list is read-only: it renders as a
                            // plain executed capsule, never an approval card.
                            Text(
                                text = toolDisplayTitle(call.toolName, arguments, memoryAction = null),
                                modifier = Modifier.testTag("ssh-profile-list-capsule"),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun tool(name: String, input: String) = UIMessagePart.Tool(
        toolCallId = "call-$name",
        toolName = name,
        input = input,
    )

    /** Resolves several titles in ONE composition; setContent is single-use. */
    private fun resolveTitles(
        calls: List<Pair<String, kotlinx.serialization.json.JsonElement>>,
    ): List<String> {
        val titles = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                calls.forEach { (name, arguments) ->
                    titles += toolDisplayTitle(name, arguments, memoryAction = null)
                }
            }
        }
        compose.waitForIdle()
        return titles
    }

    private fun assertContains(haystack: String, needle: String) =
        org.junit.Assert.assertTrue("expected \"$needle\" in \"$haystack\"", haystack.contains(needle))

    private fun assertNotContains(haystack: String, needle: String) =
        org.junit.Assert.assertFalse("did not expect \"$needle\" in \"$haystack\"", haystack.contains(needle))

    private fun capture(fileName: String) {
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { root.draw(Canvas(bitmap)) }
        File("build/reports/$fileName").apply { parentFile?.mkdirs() }
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun assertFalse(message: String, value: Boolean) = org.junit.Assert.assertFalse(message, value)
}

