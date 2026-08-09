package app.amber.ai.core

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Codex image2 垫图接线契约：工具声明必须暴露 `use_attached_image`，
 * 描述须引导模型在用户附图风格转换/基于原图修改时打开该开关。
 */
class ImageGenToolDeclarationTest {

    @Test
    fun imageGenDeclarationExposesAttachedImageParameter() {
        val tool = createImageGenToolDeclaration()
        assertEquals("generate_image", tool.name)

        val params = tool.parameters()
        assertIs<InputSchema.Obj>(params)
        assertEquals(listOf("prompt"), params.required)

        val useAttached = assertNotNull(params.properties["use_attached_image"]).jsonObject
        assertEquals("boolean", useAttached["type"]?.jsonPrimitive?.contentOrNull)

        val source = assertNotNull(params.properties["source_image_url"]).jsonObject
        assertEquals("string", source["type"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun imageGenDescriptionGuidesReferenceEdits() {
        val description = createImageGenToolDeclaration().description
        assertTrue("use_attached_image" in description)
        assertTrue(
            "reference" in description.lowercase() || "attached" in description.lowercase(),
            "描述必须说明用户附图可作垫图/参考图"
        )
    }

    @Test
    fun imageGenResolvesThroughIosToolDeclarationCatalog() {
        val declarations = iosToolDeclarations(listOf("generate_image"))
        assertEquals(listOf("generate_image"), declarations.map { it.name })
        assertNotNull(iosToolDeclaration("generate_image"))
    }
}
