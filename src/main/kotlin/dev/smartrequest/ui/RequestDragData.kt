package dev.smartrequest.ui

/**
 * The tiny payload exchanged when a request is dragged from the request tree onto the scenario
 * canvas. A prefixed string keeps the drag local and lets both ends reject unrelated text drops.
 */
internal object RequestDragData {
    private const val PREFIX = "smart-request/request:"

    fun encode(requestId: String): String = PREFIX + requestId

    fun decode(text: String?): String? {
        val value = text?.trim().orEmpty()
        if (!value.startsWith(PREFIX)) return null
        return value.removePrefix(PREFIX).trim().takeIf { it.isNotEmpty() }
    }
}
