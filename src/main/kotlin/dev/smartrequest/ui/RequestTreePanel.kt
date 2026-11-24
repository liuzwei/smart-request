package dev.smartrequest.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.smartrequest.http.CurlFormat
import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.model.RequestGroup
import dev.smartrequest.service.SmartRequestProjectService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Point
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.DropMode
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.TransferHandler
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class RequestTreePanel(
    private val service: SmartRequestProjectService,
    private val onSelect: (HttpRequestConfig) -> Unit,
    private val onCreateRequest: (String) -> Unit,
    private val onCreateGroup: () -> Unit,
    private val onDeleteRequest: (HttpRequestConfig) -> Unit,
    private val onDeleteGroup: (RequestGroup) -> Unit,
    private val onRenameRequest: (HttpRequestConfig) -> Unit,
    private val onRenameGroup: (RequestGroup) -> Unit,
    private val onDuplicateRequest: (HttpRequestConfig) -> Unit,
    private val onImportSpringIntoGroup: (RequestGroup) -> Unit,
    private val onCopyCurl: (HttpRequestConfig, CurlFormat) -> Unit,
    private val onMoveRequest: (HttpRequestConfig, RequestGroup) -> Unit,
    private val onMoveRequestToNewGroup: (HttpRequestConfig) -> Unit,
    private val onGoToSource: ((HttpRequestConfig) -> Unit)? = null,
    private val onAddToScenario: ((HttpRequestConfig) -> Unit)? = null,
    /** Exports the given group, or every group when null. */
    private val onExportRequests: ((RequestGroup?) -> Unit)? = null,
    private val onExportSingleRequest: ((HttpRequestConfig) -> Unit)? = null,
    private val onImportRequests: (() -> Unit)? = null
) : JBPanel<RequestTreePanel>(BorderLayout()) {

    private val root = DefaultMutableTreeNode()
    private val tree = object : JTree(root) {
        override fun getToolTipText(event: MouseEvent): String? {
            val path = getPathForLocation(event.x, event.y) ?: return null
            val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return null
            return when (val data = node.userObject) {
                is NodeData.Group -> data.group.name
                is NodeData.Request -> "${data.request.method} ${data.request.url}"
                else -> null
            }
        }
    }.apply {
        isRootVisible = false
        showsRootHandles = true
        rowHeight = 0
        isOpaque = true
        cellRenderer = RequestTreeRenderer()
    }
    private val model = DefaultTreeModel(root)
    private val nodeById = HashMap<String, DefaultMutableTreeNode>()
    private var rebuilding = false
    private var lastSelectedRequestId: String? = null
    private var filterText: String = ""
    private lateinit var filterField: com.intellij.ui.components.JBTextField

    init {
        border = JBUI.Borders.empty(4, 4, 4, 4)
        tree.model = model
        javax.swing.ToolTipManager.sharedInstance().registerComponent(tree)
        add(buildHeader(), BorderLayout.NORTH)
        add(JBScrollPane(tree).apply { border = JBUI.Borders.emptyTop(4) }, BorderLayout.CENTER)

        tree.addTreeSelectionListener {
            if (rebuilding) return@addTreeSelectionListener
            selectedRequest()?.let {
                lastSelectedRequestId = it.id
                onSelect(it)
            }
        }
        tree.addTreeExpansionListener(object : javax.swing.event.TreeExpansionListener {
            override fun treeExpanded(event: javax.swing.event.TreeExpansionEvent) {
                (event.path.lastPathComponent as? DefaultMutableTreeNode)?.userObject
                    ?.let { it as? NodeData.Group }
                    ?.let { it.group.expanded = true }
            }

            override fun treeCollapsed(event: javax.swing.event.TreeExpansionEvent) {
                (event.path.lastPathComponent as? DefaultMutableTreeNode)?.userObject
                    ?.let { it as? NodeData.Group }
                    ?.let { it.group.expanded = false }
            }
        })
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) = maybeShowPopup(event)
            override fun mouseReleased(event: MouseEvent) = maybeShowPopup(event)
            override fun mouseClicked(event: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(event)) return
                // A click on the already-selected request must still bring its editor back after the
                // user looked at a scenario: the selection listener only fires when the row changes.
                selectedRequest()?.let(onSelect)
            }
        })
        installDeleteShortcut()
        installDragSupport()
        rebuild()
    }

    /**
     * Enables two drag-and-drop behaviours on the tree:
     * - dragging a request onto the scenario canvas still creates a step there ([RequestDragData]);
     * - dragging a request or a group onto another row reorders it ([GroupDragData] marks groups).
     *
     * The drop position decides whether the dragged row is inserted before or after the row under
     * the cursor. Dropping a request on a group row appends it to that group.
     */
    private fun installDragSupport() {
        tree.dragEnabled = true
        tree.dropMode = DropMode.ON
        tree.transferHandler = object : TransferHandler() {
            override fun getSourceActions(component: JComponent?): Int = COPY or MOVE

            override fun createTransferable(component: JComponent?): Transferable? {
                val node = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode ?: return null
                return when (val data = node.userObject) {
                    is NodeData.Request -> StringSelection(RequestDragData.encode(data.request.id))
                    is NodeData.Group -> StringSelection(GroupDragData.encode(data.group.id))
                    else -> null
                }
            }

            override fun canImport(support: TransferHandler.TransferSupport): Boolean {
                if (!support.isDrop) return false
                val payload = payloadOf(support) ?: return false
                val target = targetNodeOf(support)?.userObject as? NodeData ?: return false
                return when (target) {
                    is NodeData.Group ->
                        payload.kind == DragKind.REQUEST || target.group.id != payload.id
                    is NodeData.Request ->
                        payload.kind == DragKind.REQUEST && target.request.id != payload.id
                }
            }

            override fun importData(support: TransferHandler.TransferSupport): Boolean {
                val payload = payloadOf(support) ?: return false
                val targetNode = targetNodeOf(support) ?: return false
                val dropPoint = (support.dropLocation as? JTree.DropLocation)?.dropPoint ?: return false
                val after = isBelowRowCenter(dropPoint, targetNode)
                val moved = when (val target = targetNode.userObject) {
                    is NodeData.Request -> {
                        if (payload.kind != DragKind.REQUEST) return false
                        val group = service.groupOf(target.request.id) ?: return false
                        val index = group.requests.indexOfFirst { it.id == target.request.id }
                        if (index < 0) {
                            false
                        } else {
                            service.moveRequestToIndex(payload.id, group.id, index + if (after) 1 else 0)
                        }
                    }
                    is NodeData.Group -> when (payload.kind) {
                        // A request dropped on the group row joins the end of that group.
                        DragKind.REQUEST ->
                            service.moveRequestToIndex(payload.id, target.group.id, target.group.requests.size)
                        DragKind.GROUP -> {
                            val index = service.state().groups.indexOfFirst { it.id == target.group.id }
                            if (index < 0) false else service.moveGroup(payload.id, index + if (after) 1 else 0)
                        }
                    }
                    else -> false
                }
                if (moved) rebuild(payload.id)
                return moved
            }
        }
    }

    private fun payloadOf(support: TransferHandler.TransferSupport): DragPayload? {
        if (!support.isDataFlavorSupported(DataFlavor.stringFlavor)) return null
        val text = runCatching {
            support.transferable.getTransferData(DataFlavor.stringFlavor) as? String
        }.getOrNull() ?: return null
        RequestDragData.decode(text)?.let { return DragPayload(DragKind.REQUEST, it) }
        GroupDragData.decode(text)?.let { return DragPayload(DragKind.GROUP, it) }
        return null
    }

    private fun targetNodeOf(support: TransferHandler.TransferSupport): DefaultMutableTreeNode? {
        val drop = support.dropLocation as? JTree.DropLocation ?: return null
        return drop.path?.lastPathComponent as? DefaultMutableTreeNode
    }

    private fun isBelowRowCenter(point: Point, node: DefaultMutableTreeNode): Boolean {
        val row = tree.getRowForPath(TreePath(node.path))
        if (row < 0) return false
        val bounds = tree.getRowBounds(row) ?: return false
        return point.y > bounds.y + bounds.height / 2
    }

    private fun buildHeader(): JComponent {
        val header = JBPanel<JBPanel<*>>(BorderLayout(0, 4)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(2)
        }
        val title = JBLabel("Requests").apply {
            font = font.deriveFont(Font.BOLD)
        }
        val field = com.intellij.ui.components.JBTextField().apply {
            emptyText.text = "Filter requests"
            document.addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent) = applyFilter()
                override fun removeUpdate(e: javax.swing.event.DocumentEvent) = applyFilter()
                override fun changedUpdate(e: javax.swing.event.DocumentEvent) = applyFilter()
            })
        }
        filterField = field
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(smallButton("+", "New request") {
                val groupId = selectedGroup()?.id ?: service.state().groups.firstOrNull()?.id
                if (groupId != null) onCreateRequest(groupId)
            })
            add(smallButton("+ Group", "New group") { onCreateGroup() })
            if (onImportRequests != null) {
                add(smallButton("Import", "Import requests from a JSON file") { onImportRequests.invoke() })
            }
            if (onExportRequests != null) {
                add(smallButton("Export", "Export all requests to a JSON file") { onExportRequests.invoke(null) })
            }
        }
        header.add(title, BorderLayout.NORTH)
        header.add(buttons, BorderLayout.CENTER)
        header.add(field, BorderLayout.SOUTH)
        return header
    }

    private fun applyFilter() {
        filterText = filterField.text.trim()
        rebuild()
    }

    private fun smallButton(text: String, tooltip: String, action: () -> Unit): JButton = JButton(text).apply {
        this.toolTipText = tooltip
        addActionListener { action() }
        isFocusPainted = false
        font = font.deriveFont(Font.BOLD, font.size2D - 1f)
        preferredSize = Dimension(JBUI.scale(if (text.length <= 1) 28 else 64), JBUI.scale(26))
        margin = JBUI.insets(2, 4, 2, 4)
    }

    private fun installDeleteShortcut() {
        tree.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "smartRequest.delete")
        tree.actionMap.put("smartRequest.delete", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent?) {
                val path = tree.selectionPath ?: return
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                when (val data = node.userObject) {
                    is NodeData.Request -> onDeleteRequest(data.request)
                    is NodeData.Group -> onDeleteGroup(data.group)
                }
            }
        })
    }

    fun rebuild(selectRequestId: String? = null) {
        val requestToSelect = selectRequestId ?: lastSelectedRequestId
        rebuilding = true
        try {
            root.removeAllChildren()
            nodeById.clear()
            val query = filterText
            service.state().groups.forEach { group ->
                val filtered = if (query.isEmpty()) {
                    group.requests
                } else {
                    group.requests.filter { request ->
                        request.name.contains(query, ignoreCase = true) ||
                            request.method.contains(query, ignoreCase = true) ||
                            request.url.contains(query, ignoreCase = true)
                    }
                }
                if (query.isNotEmpty() && filtered.isEmpty()) return@forEach
                val groupNode = DefaultMutableTreeNode(NodeData.Group(group))
                nodeById[group.id] = groupNode
                filtered.forEach { request ->
                    val requestNode = DefaultMutableTreeNode(NodeData.Request(request))
                    nodeById[request.id] = requestNode
                    groupNode.add(requestNode)
                }
                root.add(groupNode)
            }
            model.reload()
            service.state().groups.forEach { group ->
                val node = nodeById[group.id] ?: return@forEach
                if (group.expanded || filterText.isNotEmpty()) {
                    tree.expandPath(TreePath(node.path))
                }
            }
            if (requestToSelect != null) {
                val node = nodeById[requestToSelect]
                if (node != null) {
                    val path = TreePath(node.path)
                    tree.selectionPath = path
                    tree.scrollPathToVisible(path)
                }
            }
        } finally {
            rebuilding = false
        }
        selectedRequest()?.let {
            lastSelectedRequestId = it.id
            onSelect(it)
        }
    }

    fun selectRequest(requestId: String) {
        val node = nodeById[requestId] ?: return
        val path = TreePath(node.path)
        path.parentPath?.let { tree.expandPath(it) }
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
    }

    fun selectFirstRequest() {
        val first = service.state().groups.asSequence()
            .flatMap { it.requests.asSequence() }
            .firstOrNull() ?: return
        selectRequest(first.id)
    }

    fun repaintTree() {
        tree.repaint()
    }

    fun selectedRequest(): HttpRequestConfig? {
        val node = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode ?: return null
        return (node.userObject as? NodeData.Request)?.request
    }

    fun selectedGroup(): RequestGroup? {
        val node = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode ?: return null
        return when (val data = node.userObject) {
            is NodeData.Group -> data.group
            is NodeData.Request -> service.groupOf(data.request.id)
            else -> null
        }
    }

    private fun maybeShowPopup(event: MouseEvent) {
        if (!event.isPopupTrigger) return
        val path = tree.getPathForLocation(event.x, event.y) ?: return
        tree.selectionPath = path
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        val menu = when (val data = node.userObject) {
            is NodeData.Request -> buildRequestMenu(data.request)
            is NodeData.Group -> buildGroupMenu(data.group)
            else -> buildRootMenu()
        }
        menu.show(tree, event.x, event.y)
    }

    internal fun buildRequestMenu(request: HttpRequestConfig): JPopupMenu {
        val menu = JPopupMenu()
        menu.add(menuItem("Open") { onSelect(request) })
        if (onGoToSource != null && request.springKey != null) {
            menu.add(menuItem("Go to Source") { onGoToSource.invoke(request) })
        }
        menu.add(menuItem("Duplicate") { onDuplicateRequest(request) })
        menu.add(menuItem("Rename…") { onRenameRequest(request) })
        menu.add(buildMoveToGroupMenu(request))
        if (onAddToScenario != null) {
            menu.addSeparator()
            menu.add(menuItem("Add to Scenario") { onAddToScenario.invoke(request) })
        }
        menu.addSeparator()
        val curlMenu = JMenu("Copy as cURL")
        curlMenu.add(menuItem(CurlFormat.LINUX.label) { onCopyCurl(request, CurlFormat.LINUX) })
        curlMenu.add(menuItem(CurlFormat.POWERSHELL.label) { onCopyCurl(request, CurlFormat.POWERSHELL) })
        menu.add(curlMenu)
        if (onExportRequests != null) {
            menu.add(menuItem("Export This Request…") { onExportSingleRequest?.invoke(request) })
        }
        menu.addSeparator()
        menu.add(menuItem("Delete") { onDeleteRequest(request) })
        return menu
    }

    private fun buildGroupMenu(group: RequestGroup): JPopupMenu = JPopupMenu().apply {
        add(menuItem("New Request") { onCreateRequest(group.id) })
        add(menuItem("Import Spring Endpoints") { onImportSpringIntoGroup(group) })
        addSeparator()
        add(menuItem("Rename…") { onRenameGroup(group) })
        add(menuItem("Delete Group") { onDeleteGroup(group) })
        if (onExportRequests != null) {
            addSeparator()
            add(menuItem("Export This Group…") { onExportRequests.invoke(group) })
        }
    }

    private fun buildRootMenu(): JPopupMenu = JPopupMenu().apply {
        add(menuItem("New Request") {
            service.state().groups.firstOrNull()?.let { onCreateRequest(it.id) }
        })
        add(menuItem("New Group") { onCreateGroup() })
        if (onExportRequests != null || onImportRequests != null) {
            addSeparator()
            if (onExportRequests != null) add(menuItem("Export All Requests…") { onExportRequests.invoke(null) })
            if (onImportRequests != null) add(menuItem("Import Requests…") { onImportRequests.invoke() })
        }
    }

    private fun buildMoveToGroupMenu(request: HttpRequestConfig): JMenu {
        val menu = JMenu("Move to Group")
        val currentGroupId = service.groupOf(request.id)?.id
        val targets = service.state().groups.filter { it.id != currentGroupId }
        if (targets.isEmpty()) {
            menu.add(JMenuItem("No other groups").apply { isEnabled = false })
        } else {
            targets.forEach { group ->
                menu.add(menuItem(group.name) { onMoveRequest(request, group) })
            }
        }
        menu.addSeparator()
        menu.add(menuItem("New Group…") { onMoveRequestToNewGroup(request) })
        return menu
    }

    private fun menuItem(text: String, action: () -> Unit): JMenuItem = JMenuItem(text).apply {
        addActionListener { action() }
    }

    private inner class RequestTreeRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ) {
            val node = value as? DefaultMutableTreeNode ?: return
            when (val data = node.userObject) {
                is NodeData.Group -> {
                    icon = AllIcons.Nodes.Folder
                    append(data.group.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  ${data.group.requests.size}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
                is NodeData.Request -> {
                    icon = AllIcons.Nodes.Method
                    append(
                        data.request.method,
                        SimpleTextAttributes(
                            SimpleTextAttributes.STYLE_BOLD,
                            HttpMethodColors.of(data.request.method)
                        )
                    )
                    append("  ${data.request.name}", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
                else -> Unit
            }
            border = JBUI.Borders.empty(2, 4)
        }
    }

    private enum class DragKind { REQUEST, GROUP }

    private data class DragPayload(val kind: DragKind, val id: String)

    private sealed interface NodeData {
        data class Group(val group: RequestGroup) : NodeData
        data class Request(val request: HttpRequestConfig) : NodeData
    }
}
