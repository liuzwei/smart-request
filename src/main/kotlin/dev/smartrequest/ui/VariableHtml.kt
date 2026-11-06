package dev.smartrequest.ui

import com.intellij.ui.JBColor
import dev.smartrequest.template.TemplateEngine
import dev.smartrequest.template.TemplateParser
import java.awt.Color
import java.awt.Font

/**
 * Produces a small HTML fragment for table cell renderers. Swing's HTML labels do not support rich
 * document models cheaply, so `{{...}}` expressions are highlighted with a background span instead.
 */
object VariableHtml {

    fun render(value: String, knownNames: Set<String>, baseColor: Color, font: Font): String {
        val analysis = TemplateEngine.analyze(value, allowUnknownFunctions = true)
        val body = StringBuilder()
        TemplateParser.parse(value).segments.forEach { segment ->
            val raw = value.substring(segment.start, segment.end)
            if (segment.expression == null) {
                body.append(escapeHtml(raw))
                return@forEach
            }
            val hasIssue = analysis.issues.any { it.start < segment.end && it.end > segment.start }
            val hasUnknown = analysis.references.any { reference ->
                reference.start < segment.end &&
                    reference.end > segment.start &&
                    !knownNames.contains(reference.name)
            }
            val background = if (hasIssue || hasUnknown) UNKNOWN_BACKGROUND else KNOWN_BACKGROUND
            body.append("<span style=\"background-color:${hex(background)};\">")
            body.append(escapeHtml(raw))
            body.append("</span>")
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
