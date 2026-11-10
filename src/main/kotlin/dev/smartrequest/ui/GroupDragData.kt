package dev.smartrequest.ui

/**
 * Payload used when a request group is dragged inside the request tree. Requests keep using
 * [RequestDragData] so dropping one onto the scenario canvas is still recognised there; groups use a
 * separate prefix the canvas rejects.
 */
internal object GroupDragData {
    private const val PREFIX = "smart-request/group:"

    fun encode(groupId: String): String = PREFIX + groupId

    fun decode(text: String?): String? {
        val value = text?.trim().orEmpty()
        if (!value.startsWith(PREFIX)) return null
        return value.removePrefix(PREFIX).trim().takeIf { it.isNotEmpty() }
    }
}
