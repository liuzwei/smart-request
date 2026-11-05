package dev.smartrequest.scenario

import com.intellij.util.messages.Topic

/** Requests opened from another surface, for example by double-clicking a scenario step. */
fun interface OpenRequestListener {
    fun openRequest(requestId: String)
}

object ScenarioTopics {
    val OPEN_REQUEST: Topic<OpenRequestListener> = Topic.create(
        "Smart Request open request",
        OpenRequestListener::class.java
    )
}
