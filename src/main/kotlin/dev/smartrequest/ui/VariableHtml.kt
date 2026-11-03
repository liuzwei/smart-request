package dev.smartrequest.ui

import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Font

/**
 * Produces a small HTML fragment for table cell renderers. Swing's HTML labels do not support rich
 * document models cheaply, so variable tokens are highlighted with a background span instead.
 */
object VariableHtml {
    private val PATTERN = Regex("""\{\{\s*([^{}\s]+?)\s*}}""")

    fun render(value: String, knownNames: Set<String>, baseColor: Color, font: Font): String {
        val escaped = escapeHtml(value)
        val body = PATTERN.replace(escaped) { match ->
            val name = match.groupValues[1]
            val background = if (knownNames.contains(name)) KNOWN_BACKGROUND else UNKNOWN_BACKGROUND
            "<span style=\"background-color:${hex(background)};\">${match.value}</span>"
        }
        val family = font.family.replace("'", "")
        return "<html><body style=\"font-family:'$family';font-size:${font.size}px;" +
                "color:${hex(baseColor)};\">$body</body></html>"
    }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun hex(color: Color): String =
        "#" + Integer.toHexString(color.rgb and 0xFFFFFF).padStart(6, '0')

    private val KNOWN_BACKGROUND = JBColor(0xDDEBFF, 0x2C405B)
    private val UNKNOWN_BACKGROUND = JBColor(0xFFE0E0, 0x4A2B2B)
}
