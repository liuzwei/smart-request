package dev.smartrequest.ui

import com.intellij.openapi.components.service
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.model.RequestGroup
import dev.smartrequest.service.SmartRequestProjectService
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPopupMenu

class RequestTreePanelMenuTest : BasePlatformTestCase() {

    fun testMoveToGroupSubmenuListsOtherGroupsAndInvokesCallback() {
        val service = project.service<SmartRequestProjectService>()
        val request = resetGroups(service)
        val currentGroup = service.groupOf(request.id)!!
        val target = service.addGroup("Target Group")
        service.addGroup("Another Group")
        var movedTo: RequestGroup? = null
        var movedToNewGroup: HttpRequestConfig? = null

        val panel = EdtTestUtil.runInEdtAndGet<RequestTreePanel, RuntimeException> {
            RequestTreePanel(
                service = service,
                onSelect = {},
                onCreateRequest = {},
                onCreateGroup = {},
                onDeleteRequest = {},
                onDeleteGroup = {},
                onRenameRequest = {},
                onRenameGroup = {},
                onDuplicateRequest = {},
                onImportSpringIntoGroup = {},
                onCopyCurl = { _, _ -> },
                onMoveRequest = { _, group -> movedTo = group },
                onMoveRequestToNewGroup = { movedToNewGroup = it }
            )
        }
        val menu = EdtTestUtil.runInEdtAndGet<JPopupMenu, RuntimeException> {
            panel.buildRequestMenu(request)
        }
        val moveMenu = menu.components.filterIsInstance<JMenu>()
            .first { it.text == "Move to Group" }
        val labels = (0 until moveMenu.menuComponentCount)
            .mapNotNull { moveMenu.getMenuComponent(it) as? JMenuItem }
            .map { it.text }

        assertTrue(labels.contains("Target Group"))
        assertTrue(labels.contains("Another Group"))
        assertFalse(labels.contains(currentGroup.name))
        assertTrue(labels.any { it.startsWith("New Group") })

        val targetItem = (0 until moveMenu.menuComponentCount)
            .mapNotNull { moveMenu.getMenuComponent(it) as? JMenuItem }
            .first { it.text == "Target Group" }
        EdtTestUtil.runInEdtAndWait<RuntimeException> { targetItem.doClick() }
        assertSame(target, movedTo)

        val newGroupItem = (0 until moveMenu.menuComponentCount)
            .mapNotNull { moveMenu.getMenuComponent(it) as? JMenuItem }
            .first { it.text.startsWith("New Group") }
        EdtTestUtil.runInEdtAndWait<RuntimeException> { newGroupItem.doClick() }
        assertSame(request, movedToNewGroup)
    }

    fun testMoveToGroupSubmenuExplainsWhenNoOtherGroupExists() {
        val service = project.service<SmartRequestProjectService>()
        val request = resetGroups(service)

        val panel = EdtTestUtil.runInEdtAndGet<RequestTreePanel, RuntimeException> {
            RequestTreePanel(
                service = service,
                onSelect = {},
                onCreateRequest = {},
                onCreateGroup = {},
                onDeleteRequest = {},
                onDeleteGroup = {},
                onRenameRequest = {},
                onRenameGroup = {},
                onDuplicateRequest = {},
                onImportSpringIntoGroup = {},
                onCopyCurl = { _, _ -> },
                onMoveRequest = { _, _ -> },
                onMoveRequestToNewGroup = {}
            )
        }
        val menu = EdtTestUtil.runInEdtAndGet<JPopupMenu, RuntimeException> {
            panel.buildRequestMenu(request)
        }
        val moveMenu = menu.components.filterIsInstance<JMenu>()
            .first { it.text == "Move to Group" }
        val labels = (0 until moveMenu.menuComponentCount)
            .mapNotNull { moveMenu.getMenuComponent(it) as? JMenuItem }
            .map { it.text }

        assertTrue("labels=$labels", labels.contains("No other groups"))
        val firstItem = moveMenu.getMenuComponent(0) as JMenuItem
        assertFalse(firstItem.isEnabled)
        assertTrue(labels.any { it.startsWith("New Group") })
    }

    fun testGoToSourceIsOnlyOfferedForSpringRequests() {
        val service = project.service<SmartRequestProjectService>()
        val request = resetGroups(service)
        var opened: HttpRequestConfig? = null

        val panel = EdtTestUtil.runInEdtAndGet<RequestTreePanel, RuntimeException> {
            newPanel(service) { opened = it }
        }
        val plainLabels = EdtTestUtil.runInEdtAndGet<List<String>, RuntimeException> {
            panel.buildRequestMenu(request).components
                .filterIsInstance<JMenuItem>()
                .map { it.text }
        }
        assertFalse(plainLabels.contains("Go to Source"))

        request.springKey = "com.example.WorkshopController#candidateMembers:GET:/workshop/{id}/members"
        val springMenu = EdtTestUtil.runInEdtAndGet<JPopupMenu, RuntimeException> {
            panel.buildRequestMenu(request)
        }
        val sourceItem = springMenu.components
            .filterIsInstance<JMenuItem>()
            .firstOrNull { it.text == "Go to Source" }
            ?: error("Go to Source missing for a Spring request")

        EdtTestUtil.runInEdtAndWait<RuntimeException> { sourceItem.doClick() }
        assertSame(request, opened)
    }

    private fun newPanel(
        service: SmartRequestProjectService,
        onGoToSource: (HttpRequestConfig) -> Unit = {}
    ): RequestTreePanel = RequestTreePanel(
        service = service,
        onSelect = {},
        onCreateRequest = {},
        onCreateGroup = {},
        onDeleteRequest = {},
        onDeleteGroup = {},
        onRenameRequest = {},
        onRenameGroup = {},
        onDuplicateRequest = {},
        onImportSpringIntoGroup = {},
        onCopyCurl = { _, _ -> },
        onMoveRequest = { _, _ -> },
        onMoveRequestToNewGroup = {},
        onGoToSource = onGoToSource
    )

    private fun resetGroups(service: SmartRequestProjectService): HttpRequestConfig {
        val state = service.state()
        state.groups.clear()
        val request = HttpRequestConfig(name = "Health check", url = "{{baseUrl}}/actuator/health")
        state.groups.add(RequestGroup(name = "My Requests").apply { requests.add(request) })
        return request
    }
}
