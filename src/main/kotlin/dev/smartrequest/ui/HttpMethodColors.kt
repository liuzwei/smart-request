package dev.smartrequest.ui

import com.intellij.ui.JBColor

/** Shared colors for the HTTP method badge, so the tree, the steps table and the run panel agree. */
internal object HttpMethodColors {
    private val GET = JBColor(0x1A7F37, 0x73BD79)
    private val POST = JBColor(0x0B5CAD, 0x6BA6E8)
    private val PUT = JBColor(0xA15C00, 0xD6A26A)
    private val DELETE = JBColor(0xC7222D, 0xFF6B68)

    fun of(method: String): JBColor = when (method.uppercase()) {
        "GET" -> GET
        "POST" -> POST
        "PUT", "PATCH" -> PUT
        "DELETE" -> DELETE
        else -> JBColor.GRAY
    }
}
