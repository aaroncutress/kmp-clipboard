package io.github.aaroncutress.clipboard.html

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.sp

/**
 * The inline CSS this library reads and writes.
 *
 * A deliberately small dialect — `color`, `background-color`, `font-weight`,
 * `font-style`, `font-size`, `text-decoration` — because those are the
 * properties [SpanStyle] can hold and there is no value in parsing a `float`
 * that has nowhere to go. Anything else in a `style` attribute is skipped.
 */
internal object Css {

    /** Serialises the parts of [style] that CSS can carry. Empty when there are none. */
    fun declarations(style: SpanStyle): String = buildList {
        style.color.takeIf { it != Color.Unspecified }?.let { add("color:${it.toCssColor()}") }
        style.background.takeIf { it != Color.Unspecified }
            ?.let { add("background-color:${it.toCssColor()}") }
        style.fontSize.toCssLength()?.let { add("font-size:$it") }
        // Weights other than the two the `<b>` tag covers. 400 and 700 are
        // emitted as tags rather than CSS, so what is left is the in-between —
        // a 300 or a 900 that would be flattened to "not bold" by a tag.
        style.fontWeight?.weight
            ?.takeIf { it != FontWeight.Normal.weight && it != FontWeight.Bold.weight }
            ?.let { add("font-weight:$it") }
    }.joinToString(";")

    /**
     * Reads a `style` attribute into a [SpanStyle].
     *
     * Tolerant by design: an unknown property, a malformed colour or a unit this
     * library cannot resolve is skipped rather than failing the parse. HTML on a
     * clipboard comes from other people's applications, and half-understanding
     * it beats refusing it.
     */
    fun parse(declarations: String): SpanStyle {
        var style = SpanStyle()
        for (declaration in declarations.split(';')) {
            val name = declaration.substringBefore(':', "").trim().lowercase()
            val value = declaration.substringAfter(':', "").trim()
            if (name.isEmpty() || value.isEmpty()) continue
            style = when (name) {
                "color" -> value.toColor()?.let { style.copy(color = it) } ?: style
                "background-color", "background" ->
                    value.toColor()?.let { style.copy(background = it) } ?: style
                "font-size" -> value.toTextUnit()?.let { style.copy(fontSize = it) } ?: style
                "font-weight" -> value.toFontWeight()?.let { style.copy(fontWeight = it) } ?: style
                "font-style" -> when (value.lowercase()) {
                    "italic", "oblique" -> style.copy(fontStyle = FontStyle.Italic)
                    "normal" -> style.copy(fontStyle = FontStyle.Normal)
                    else -> style
                }
                "text-decoration", "text-decoration-line" -> {
                    val words = value.lowercase().split(' ', ',').map { it.trim() }
                    val decorations = buildList {
                        if ("underline" in words) add(TextDecoration.Underline)
                        if ("line-through" in words) add(TextDecoration.LineThrough)
                    }
                    if (decorations.isEmpty()) style
                    else style.copy(textDecoration = TextDecoration.combine(decorations))
                }
                else -> style
            }
        }
        return style
    }

    /**
     * `#rrggbb`, or `rgba(…)` when the colour is not opaque.
     *
     * Hex for the common case because it is what every editor writes and the
     * shortest thing that round-trips exactly.
     */
    private fun Color.toCssColor(): String {
        val r = (red * 255f + 0.5f).toInt()
        val g = (green * 255f + 0.5f).toInt()
        val b = (blue * 255f + 0.5f).toInt()
        if (alpha >= 1f) {
            return "#" + listOf(r, g, b).joinToString("") { it.toString(16).padStart(2, '0') }
        }
        // Three decimal places: enough to survive a round trip through a byte,
        // short enough not to print `0.5019607843137255`.
        val a = ((alpha * 1000f + 0.5f).toInt() / 1000f).toString()
        return "rgba($r,$g,$b,$a)"
    }

    /** `12px`, or null for a size this library cannot express in CSS. */
    private fun TextUnit.toCssLength(): String? = when {
        this == TextUnit.Unspecified -> null
        // Em is relative to something the clipboard does not carry — the
        // recipient's own base size — so writing it out would change meaning on
        // paste. Only absolute sizes survive.
        type != TextUnitType.Sp -> null
        else -> "${value.toCssNumber()}px"
    }

    /**
     * Sp and CSS px are treated as the same unit.
     *
     * They are not the same thing — sp scales with the user's font-size setting
     * and a CSS px does not — but they are the same *number* at default
     * settings, and a clipboard has nowhere to record which one it meant. The
     * alternative is dropping font sizes entirely, which is worse.
     */
    private fun String.toTextUnit(): TextUnit? {
        val number = takeWhile { it.isDigit() || it == '.' || it == '-' }
        val unit = drop(number.length).trim().lowercase()
        val value = number.toFloatOrNull() ?: return null
        return when (unit) {
            "px", "" -> value.sp
            // 1pt = 4/3 px, which is the one other absolute unit that turns up.
            "pt" -> (value * 4f / 3f).sp
            else -> null
        }
    }

    private fun String.toFontWeight(): FontWeight? = when (lowercase()) {
        "normal" -> FontWeight.Normal
        "bold" -> FontWeight.Bold
        "lighter" -> FontWeight.Light
        "bolder" -> FontWeight.ExtraBold
        else -> toIntOrNull()?.coerceIn(1, 1000)?.let(::FontWeight)
    }

    /** `#rgb`, `#rrggbb`, `#rrggbbaa`, `rgb(…)`, `rgba(…)`, and the CSS colour names. */
    private fun String.toColor(): Color? {
        val value = trim().lowercase()
        if (value.startsWith("#")) return value.drop(1).hexToColor()
        if (value.startsWith("rgb")) {
            val parts = value.substringAfter('(').substringBefore(')').split(',')
            if (parts.size < 3) return null
            val channels = parts.take(3).map { it.trim().toChannel() ?: return null }
            val alpha = parts.getOrNull(3)?.trim()?.toFloatOrNull() ?: 1f
            return Color(channels[0], channels[1], channels[2], (alpha * 255f).toInt())
        }
        return NAMED_COLORS[value]
    }

    /** A `rgb()` channel, which CSS lets you write as `128` or as `50%`. */
    private fun String.toChannel(): Int? =
        if (endsWith("%")) dropLast(1).toFloatOrNull()?.let { (it * 255f / 100f).toInt() }
        else toIntOrNull()

    private fun String.hexToColor(): Color? {
        val hex = when (length) {
            // `#abc` is shorthand for `#aabbcc`.
            3 -> map { "$it$it" }.joinToString("")
            4 -> map { "$it$it" }.joinToString("")
            6, 8 -> this
            else -> return null
        }
        val value = hex.toLongOrNull(16) ?: return null
        return if (hex.length == 6) {
            Color(0xFF000000L.toInt() or value.toInt())
        } else {
            // CSS writes alpha last, `Color(Int)` wants it first.
            val rgb = (value shr 8).toInt()
            val alpha = (value and 0xFF).toInt()
            Color((alpha shl 24) or rgb)
        }
    }

    /** `12` rather than `12.0`, which is what every editor writes. */
    private fun Float.toCssNumber(): String =
        if (this == toInt().toFloat()) toInt().toString() else toString()

    /**
     * The colour names worth carrying.
     *
     * CSS defines 148 of them and a clipboard sees about a dozen: the sixteen
     * from HTML 4, plus the greys that word processors default to. The rest fall
     * through to null and the span keeps whatever colour it inherited, which is
     * a better outcome than a table nobody maintains.
     */
    private val NAMED_COLORS: Map<String, Color> = mapOf(
        "black" to Color(0xFF000000),
        "silver" to Color(0xFFC0C0C0),
        "gray" to Color(0xFF808080),
        "grey" to Color(0xFF808080),
        "white" to Color(0xFFFFFFFF),
        "maroon" to Color(0xFF800000),
        "red" to Color(0xFFFF0000),
        "purple" to Color(0xFF800080),
        "fuchsia" to Color(0xFFFF00FF),
        "magenta" to Color(0xFFFF00FF),
        "green" to Color(0xFF008000),
        "lime" to Color(0xFF00FF00),
        "olive" to Color(0xFF808000),
        "yellow" to Color(0xFFFFFF00),
        "navy" to Color(0xFF000080),
        "blue" to Color(0xFF0000FF),
        "teal" to Color(0xFF008080),
        "aqua" to Color(0xFF00FFFF),
        "cyan" to Color(0xFF00FFFF),
        "orange" to Color(0xFFFFA500),
        "transparent" to Color.Transparent,
    )
}
