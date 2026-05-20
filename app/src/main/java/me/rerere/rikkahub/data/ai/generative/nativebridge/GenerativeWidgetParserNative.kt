package me.rerere.rikkahub.data.ai.generative.nativebridge

import android.util.Log
import me.rerere.rikkahub.data.ai.generative.GenerativeWidgetAction
import me.rerere.rikkahub.data.ai.generative.GenerativeWidgetSegment
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridge to the `generative-widget-parser` Rust crate.
 *
 * Spike-phase: NOT wired into JVM `GenerativeWidgetParser.parse(...)`;
 * benchmarks call directly. Adapter returns null when native is unavailable so
 * caller MUST fall back to the JVM implementation.
 */
internal object GenerativeWidgetParserNative {

    private const val TAG = "GenerativeWidgetParserNative"
    private const val LIB_NAME = "generative_widget_parser"
    private const val SUPPORTED_WIRE_VERSION = 1

    private val loaded = AtomicBoolean(false)
    private var loadError: Throwable? = null

    val available: Boolean
        get() {
            ensureLoaded()
            return loaded.get()
        }

    private fun ensureLoaded() {
        if (loaded.get() || loadError != null) return
        synchronized(this) {
            if (loaded.get() || loadError != null) return
            try {
                System.loadLibrary(LIB_NAME)
                loaded.set(true)
                Log.i(TAG, "loaded native library: $LIB_NAME")
            } catch (t: Throwable) {
                loadError = t
                Log.w(TAG, "failed to load native library $LIB_NAME — will fall back to JVM", t)
            }
        }
    }

    fun parse(content: String, streaming: Boolean): List<GenerativeWidgetSegment>? {
        ensureLoaded()
        if (!loaded.get()) return null
        val blob = parseNative(content, streaming)
        if (blob.size < 8) return null
        return decode(blob)
    }

    private fun decode(blob: ByteArray): List<GenerativeWidgetSegment>? {
        if (blob[0] != 'P'.code.toByte() || blob[1] != 'W'.code.toByte()
            || blob[2] != 'G'.code.toByte() || blob[3] != 'S'.code.toByte()
        ) return null
        // Version gate — refuse wire formats we can't decode (see cross-component review P2).
        val version = blob[4].toInt() and 0xFF
        if (version != SUPPORTED_WIRE_VERSION) return null

        var cursor = 8
        val (segCount, c1) = readVarint(blob, cursor); cursor = c1
        val out = ArrayList<GenerativeWidgetSegment>(segCount.toInt())

        repeat(segCount.toInt()) {
            val kind = blob[cursor].toInt() and 0xFF; cursor++
            when (kind) {
                0 -> {
                    // Text
                    val (content, nc) = readString(blob, cursor); cursor = nc
                    out.add(GenerativeWidgetSegment.Text(content))
                }
                1 -> {
                    // Widget
                    val (title, c2) = readOptString(blob, cursor); cursor = c2
                    val (widgetCode, c3) = readString(blob, cursor); cursor = c3
                    val complete = blob[cursor].toInt() and 0xFF != 0; cursor++
                    val (renderer, c4) = readString(blob, cursor); cursor = c4
                    val (specJson, c5) = readOptString(blob, cursor); cursor = c5
                    val (actionCount, c6) = readVarint(blob, cursor); cursor = c6
                    val actions = ArrayList<GenerativeWidgetAction>(actionCount.toInt())
                    repeat(actionCount.toInt()) {
                        val (id, ca) = readString(blob, cursor); cursor = ca
                        val (label, cb) = readString(blob, cursor); cursor = cb
                        val (instr, cd) = readString(blob, cursor); cursor = cd
                        actions.add(GenerativeWidgetAction(id = id, label = label, instruction = instr))
                    }
                    out.add(
                        GenerativeWidgetSegment.Widget(
                            title = title,
                            widgetCode = widgetCode,
                            complete = complete,
                            renderer = renderer,
                            actions = actions,
                            specJson = specJson,
                        )
                    )
                }
                2 -> {
                    out.add(GenerativeWidgetSegment.Loading)
                }
                else -> {
                    Log.w(TAG, "unknown segment kind=$kind at cursor=$cursor; aborting decode")
                    return null
                }
            }
        }
        return out
    }

    private fun readString(blob: ByteArray, start: Int): Pair<String, Int> {
        val (len, c1) = readVarint(blob, start)
        val s = String(blob, c1, len.toInt(), Charsets.UTF_8)
        return s to (c1 + len.toInt())
    }

    private fun readOptString(blob: ByteArray, start: Int): Pair<String?, Int> {
        val flag = blob[start].toInt() and 0xFF
        if (flag == 0) return null to (start + 1)
        return readString(blob, start + 1)
    }

    private fun readVarint(blob: ByteArray, start: Int): Pair<Long, Int> {
        var value = 0L
        var shift = 0
        var cursor = start
        while (true) {
            val byte = blob[cursor].toInt() and 0xFF
            value = value or ((byte and 0x7F).toLong() shl shift)
            cursor++
            if (byte and 0x80 == 0) return value to cursor
            shift += 7
            if (shift > 63) error("varint too long at offset $start")
        }
    }

    @JvmStatic
    private external fun parseNative(content: String, streaming: Boolean): ByteArray
}
