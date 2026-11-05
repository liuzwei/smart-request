package dev.smartrequest.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.model.ScenarioConfig
import dev.smartrequest.model.ScenarioEdge
import dev.smartrequest.model.ScenarioNode
import dev.smartrequest.scenario.NodeRunState
import dev.smartrequest.scenario.ScenarioGraph
import dev.smartrequest.scenario.ScenarioLayout
import dev.smartrequest.scenario.ScenarioNodeResult
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.datatransfer.DataFlavor
import java.awt.event.ActionEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.event.MouseWheelListener
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager
import javax.swing.TransferHandler
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The graph view of a scenario: an activity-on-node canvas painted with plain Swing.
 *
 * The canvas is deliberately a view over the same [ScenarioConfig] the steps table edits; it never
 * owns a second copy of the data. All mutations go through [ScenarioGraph] and are announced with
 * [onGraphChanged], which lets the editor revalidate, refresh the steps table and record undo steps.
 *
 * Interactions: drag nodes, drag from a right port to another node's left port to add a dependency,
 * click to select, Delete to remove, Ctrl+wheel to zoom, drag empty space to pan, context menu for
 * add/delete/auto-layout/fit, double-click to open the request.
 */
class ScenarioGraphCanvas(
    private val scenarioProvider: () -> ScenarioConfig?,
    private val requestProvider: (String) -> HttpRequestConfig?,
    private val onSelectionChanged: (nodeId: String?, edgeId: String?) -> Unit,
    private val onOpenRequest: (String) -> Unit,
    private val onAddStep: () -> Unit,
    private val onBeforeEdit: () -> Unit,
    private val onGraphChanged: () -> Unit,
    private val onHint: (String?) -> Unit = {},
    /** A request dropped from the request tree; coordinates are world (model) pixels. */
    private val onRequestDropped: (requestId: String, x: Int, y: Int) -> Unit = { _, _, _ -> }
) : JComponent() {

    private var zoom = 1.0
    private var panX = 0.0
    private var panY = 0.0
    private var initialFit = false

    private var selectedNodeId: String? = null
    private var selectedEdgeId: String? = null
    private var hoveredNodeId: String? = null

    private var edgeSourceId: String? = null
    private var edgePointer: Point2D? = null
    private var edgeTargetId: String? = null
    private var edgeError: String? = null

    private var dragNodeId: String? = null
    private var dragOffsetX = 0.0
    private var dragOffsetY = 0.0
    private var dragMoved = false
    private var dragSnapshotTaken = false

    private var panning = false
    private var panPressedAt: Point? = null
    private var panStartX = 0.0
    private var panStartY = 0.0
    private var panMoved = false

    private var runStates: Map<String, NodeRunState> = emptyMap()
    private var runResults: Map<String, ScenarioNodeResult> = emptyMap()

    private var lastHint: String? = null

    init {
        isOpaque = true
        isFocusable = true
        minimumSize = Dimension(200, 160)
        preferredSize = Dimension(800, 420)
        ToolTipManager.sharedInstance().registerComponent(this)
        addMouseListener(MouseHandler())
        addMouseMotionListener(MouseHandler())
        addMouseWheelListener(MouseWheelListener { event -> handleWheel(event) })
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) = fitOnFirstShow()
            override fun componentShown(event: ComponentEvent) = fitOnFirstShow()
        })
        transferHandler = object : TransferHandler() {
            override fun getSourceActions(component: JComponent?): Int = TransferHandler.NONE

            override fun canImport(support: TransferHandler.TransferSupport): Boolean {
                if (!support.isDataFlavorSupported(DataFlavor.stringFlavor)) return false
                return runCatching {
                    val text = support.transferable.getTransferData(DataFlavor.stringFlavor) as? String
                    RequestDragData.decode(text) != null
                }.getOrDefault(false)
            }

            override fun importData(support: TransferHandler.TransferSupport): Boolean {
                val text = runCatching {
                    support.transferable.getTransferData(DataFlavor.stringFlavor) as? String
                }.getOrNull() ?: return false
                val requestId = RequestDragData.decode(text) ?: return false
                val drop = support.dropLocation ?: return false
                val point = drop.dropPoint
                val world = world(point.x.toDouble(), point.y.toDouble())
                onRequestDropped(
                    requestId,
                    (world.x - NODE_WIDTH / 2).roundToInt(),
                    (world.y - NODE_HEIGHT / 2).roundToInt()
                )
                return true
            }
        }
        installKeyBindings()
    }

    // ---------------------------------------------------------------- public API

    fun refresh() {
        repaint()
    }

    fun selectNodeSilently(nodeId: String?) {
        if (selectedNodeId == nodeId && selectedEdgeId == null) return
        selectedNodeId = nodeId
        selectedEdgeId = null
        repaint()
    }

    fun selectEdgeSilently(edgeId: String?) {
        if (selectedEdgeId == edgeId && selectedNodeId == null) return
        selectedEdgeId = edgeId
        selectedNodeId = null
        repaint()
    }

    fun clearRunState() {
        if (runStates.isEmpty() && runResults.isEmpty()) return
        runStates = emptyMap()
        runResults = emptyMap()
        repaint()
    }

    /** Paints every request node as pending at the start of a run. */
    fun startRun(scenario: ScenarioConfig) {
        runStates = ScenarioGraph.requestNodes(scenario).associate { it.id to NodeRunState.PENDING }
        runResults = emptyMap()
        repaint()
    }

    fun setRunState(nodeId: String, state: NodeRunState) {
        val node = scenarioProvider()?.nodes?.firstOrNull { it.id == nodeId } ?: return
        if (!node.isRequestNode()) return
        runStates = runStates + (nodeId to state)
        repaint()
    }

    fun setRunResult(result: ScenarioNodeResult) {
        if (result.method.isEmpty()) return
        runResults = runResults + (result.nodeId to result)
        runStates = runStates + (result.nodeId to result.state)
        repaint()
    }

    fun setRunResults(nodes: Map<String, ScenarioNodeResult>) {
        runResults = nodes.filterValues { it.method.isNotEmpty() }
        runStates = runResults.mapValues { it.value.state }
        repaint()
    }

    fun fitToContent() {
        val scenario = scenarioProvider() ?: return
        val nodes = scenario.nodes
        if (nodes.isEmpty() || width <= 0 || height <= 0) return
        var bounds: Rectangle2D.Double? = null
        nodes.forEach { node ->
            val rect = nodeBounds(node)
            bounds = bounds?.also { it.add(rect) } ?: rect
        }
        val content = bounds ?: return
        if (content.width <= 0 || content.height <= 0) return
        val usableWidth = (width - FIT_MARGIN * 2).coerceAtLeast(40.0)
        val usableHeight = (height - FIT_MARGIN * 2).coerceAtLeast(40.0)
        val fitted = min(usableWidth / content.width, usableHeight / content.height)
            .coerceIn(MIN_ZOOM, 1.15)
        zoom = fitted
        panX = width / 2.0 - content.centerX * zoom
        panY = height / 2.0 - content.centerY * zoom
        repaint()
    }

    fun autoLayout() {
        val scenario = scenarioProvider() ?: return
        if (scenario.nodes.isEmpty()) return
        onBeforeEdit()
        ScenarioLayout.apply(scenario)
        onGraphChanged()
        fitToContent()
        showHint("Auto layout applied")
    }

    // ---------------------------------------------------------------- painting

    /** A component built outside a window (tests) has no font yet; painting still needs one. */
    private val baseFont: Font
        get() = font ?: Font(Font.DIALOG, Font.PLAIN, 12)

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.color = UIUtil.getPanelBackground()
            g2.fillRect(0, 0, width, height)

            val scenario = scenarioProvider()
            if (scenario == null || width <= 0 || height <= 0) {
                drawCenteredHint(g2, "No scenario")
                return
            }
            drawEdges(g2, scenario)
            drawNodes(g2, scenario)
            drawEdgeDraft(g2, scenario)
            if (ScenarioGraph.requestNodes(scenario).isEmpty()) {
                drawCenteredHint(g2, "Add a step to build the flow")
            }
        } finally {
            g2.dispose()
        }
    }

    private fun drawCenteredHint(g: Graphics2D, text: String) {
        g.font = baseFont.deriveFont(Font.PLAIN, 13f)
        val metrics = g.fontMetrics
        g.color = UIUtil.getInactiveTextColor()
        g.drawString(text, (width - metrics.stringWidth(text)) / 2, height / 2)
    }

    private fun drawEdges(g: Graphics2D, scenario: ScenarioConfig) {
        val nodes = scenario.nodes.associateBy { it.id }
        scenario.edges.forEach { edge ->
            val from = nodes[edge.from] ?: return@forEach
            val to = nodes[edge.to] ?: return@forEach
            val derived = !from.isRequestNode() || !to.isRequestNode()
            val selected = edge.id == selectedEdgeId
            val color = when {
                selected -> ACCENT
                derived -> UIUtil.getInactiveTextColor()
                else -> EDGE
            }
            val stroke = when {
                selected -> BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                derived -> BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                else -> BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            }
            drawEdge(g, from, to, color, stroke, arrow = true)
        }
    }

    private fun drawEdge(
        g: Graphics2D,
        from: ScenarioNode,
        to: ScenarioNode,
        color: Color,
        stroke: BasicStroke,
        arrow: Boolean
    ) {
        val start = screen(outPort(from))
        val end = screen(inPort(to))
        val curve = edgeCurve(start, end)
        g.color = color
        g.stroke = stroke
        g.draw(curve.path)
        if (arrow) {
            g.fill(arrowHead(curve.control2, end))
        }
    }

    private fun drawNodes(g: Graphics2D, scenario: ScenarioConfig) {
        scenario.nodes
            .sortedBy { it.id == selectedNodeId }
            .forEach { node -> drawNode(g, node) }
    }

    private fun drawNode(g: Graphics2D, node: ScenarioNode) {
        val rect = screenRect(nodeBounds(node))
        val arc = 16.0 * zoom
        val shape = RoundRectangle2D.Double(rect.x, rect.y, rect.width, rect.height, arc, arc)
        val selected = node.id == selectedNodeId
        val hovered = node.id == hoveredNodeId
        val state = runStates[node.id]

        g.color = when {
            selected -> NODE_SELECTED_FILL
            hovered -> NODE_HOVER_FILL
            else -> NODE_FILL
        }
        g.fill(shape)

        val borderColor = when (state) {
            NodeRunState.PASSED -> RUN_PASSED
            NodeRunState.FAILED -> RUN_FAILED
            NodeRunState.RUNNING -> RUN_RUNNING
            NodeRunState.SKIPPED, NodeRunState.CANCELLED -> UIUtil.getInactiveTextColor()
            NodeRunState.PENDING -> RUN_PENDING
            null -> if (selected) ACCENT else if (hovered) ACCENT_LIGHT else NODE_BORDER
        }
        g.color = borderColor
        g.stroke = if (state == NodeRunState.SKIPPED || state == NodeRunState.CANCELLED) {
            BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(6f, 4f), 0f)
        } else {
            BasicStroke(
                if (selected || state != null) 2.6f else 1.3f,
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND
            )
        }
        g.draw(shape)

        if (node.isRequestNode()) {
            drawRequestNode(g, node, rect)
            drawPorts(g, node)
            drawRunBadge(g, node, rect, state)
        } else {
            drawTerminalNode(g, node, rect)
        }
    }

    private fun drawRequestNode(g: Graphics2D, node: ScenarioNode, rect: Rectangle2D.Double) {
        val request = node.requestId?.let(requestProvider)
        val label = ScenarioGraph.label(node, request?.name)
        val method = request?.method.orEmpty()
        val padding = 12.0 * zoom
        val top = rect.y + 10 * zoom
        val fontSize = (12.5 * zoom).coerceIn(7.0, 16.0).toFloat()
        val smallSize = (10.5 * zoom).coerceIn(6.5, 13.0).toFloat()

        g.font = baseFont.deriveFont(Font.BOLD, fontSize)
        var textX = rect.x + padding
        if (method.isNotEmpty()) {
            g.color = HttpMethodColors.of(method)
            g.drawString(method, textX.toInt(), (top + g.fontMetrics.ascent).toInt())
            textX += g.fontMetrics.stringWidth(method) + 8 * zoom
        }
        g.color = NODE_TEXT
        val nameMetrics = g.fontMetrics
        val nameWidth = rect.width - (textX - rect.x) - padding
        g.drawString(
            ellipsize(label, nameMetrics, nameWidth),
            textX.toInt(),
            (top + nameMetrics.ascent).toInt()
        )

        if (zoom >= 0.55) {
            g.font = baseFont.deriveFont(Font.PLAIN, smallSize)
            g.color = NODE_MUTED
            val url = request?.url.orEmpty()
            g.drawString(
                ellipsize(url, g.fontMetrics, rect.width - padding * 2),
                (rect.x + padding).toInt(),
                (rect.y + 46 * zoom + g.fontMetrics.ascent).toInt()
            )
            val captures = node.outputs.count { it.enabled && !it.isBlank() }
            if (captures > 0) {
                val text = "$captures capture${if (captures == 1) "" else "s"}"
                g.drawString(
                    text,
                    (rect.x + rect.width - padding - g.fontMetrics.stringWidth(text)).toInt(),
                    (rect.y + rect.height - 10 * zoom).toInt()
                )
            }
        }
    }

    private fun drawTerminalNode(g: Graphics2D, node: ScenarioNode, rect: Rectangle2D.Double) {
        g.font = baseFont.deriveFont(Font.BOLD, (12.5 * zoom).coerceIn(8.0, 16.0).toFloat())
        g.color = NODE_TEXT
        val text = if (node.kind == ScenarioNode.KIND_START) "Start" else "End"
        val metrics = g.fontMetrics
        g.drawString(
            text,
            (rect.x + (rect.width - metrics.stringWidth(text)) / 2).toInt(),
            (rect.y + (rect.height + metrics.ascent - metrics.descent) / 2).toInt()
        )
    }

    private fun drawPorts(g: Graphics2D, node: ScenarioNode) {
        val radius = PORT_RADIUS * zoom.coerceAtLeast(0.7)
        listOf(inPort(node) to false, outPort(node) to true).forEach { (world, output) ->
            val point = screen(world)
            val highlighted = edgeSourceId != null && !output && edgeTargetId == node.id
            g.color = when {
                highlighted && edgeError == null -> RUN_PASSED
                highlighted -> RUN_FAILED
                output && hoveredNodeId == node.id -> ACCENT
                else -> NODE_BORDER
            }
            g.fillOval(
                (point.x - radius).toInt(),
                (point.y - radius).toInt(),
                (radius * 2).toInt().coerceAtLeast(4),
                (radius * 2).toInt().coerceAtLeast(4)
            )
        }
    }

    private fun drawRunBadge(
        g: Graphics2D,
        node: ScenarioNode,
        rect: Rectangle2D.Double,
        state: NodeRunState?
    ) {
        if (state == null) return
        val result = runResults[node.id]
        val text = when {
            result != null && result.statusCode > 0 -> result.statusCode.toString()
            state == NodeRunState.RUNNING -> "running"
            state == NodeRunState.PASSED -> "pass"
            state == NodeRunState.FAILED -> "fail"
            state == NodeRunState.SKIPPED -> "skipped"
            state == NodeRunState.CANCELLED -> "cancelled"
            else -> return
        }
        g.font = baseFont.deriveFont(Font.BOLD, (9.5 * zoom).coerceIn(6.0, 12.0).toFloat())
        val metrics = g.fontMetrics
        val padding = 4.0 * zoom
        val badgeWidth = metrics.stringWidth(text) + padding * 2
        val badgeHeight = metrics.height + padding
        val x = rect.x + rect.width - badgeWidth - 6 * zoom
        val y = rect.y - badgeHeight / 2
        g.color = when (state) {
            NodeRunState.PASSED -> RUN_PASSED
            NodeRunState.FAILED -> RUN_FAILED
            NodeRunState.RUNNING -> RUN_RUNNING
            else -> UIUtil.getInactiveTextColor()
        }
        g.fillRoundRect(
            x.toInt(),
            y.toInt(),
            badgeWidth.toInt(),
            badgeHeight.toInt(),
            (badgeHeight).toInt(),
            (badgeHeight).toInt()
        )
        g.color = Color.WHITE
        g.drawString(text, (x + padding).toInt(), (y + padding + metrics.ascent - 1).toInt())
    }

    private fun drawEdgeDraft(g: Graphics2D, scenario: ScenarioConfig) {
        val sourceId = edgeSourceId ?: return
        val source = scenario.nodes.firstOrNull { it.id == sourceId } ?: return
        val pointer = edgePointer ?: return
        val start = screen(outPort(source))
        val targetNode = edgeTargetId?.let { id -> scenario.nodes.firstOrNull { it.id == id } }
        val end = targetNode?.let { screen(inPort(it)) } ?: pointer
        val color = when {
            edgeError != null -> RUN_FAILED
            targetNode != null -> RUN_PASSED
            else -> ACCENT
        }
        val curve = edgeCurve(start, end)
        g.color = color
        g.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(curve.path)
        g.fill(arrowHead(curve.control2, end))
    }

    // ---------------------------------------------------------------- geometry

    private fun nodeWidth(node: ScenarioNode): Double =
        if (node.isRequestNode()) NODE_WIDTH else TERMINAL_WIDTH

    private fun nodeHeight(node: ScenarioNode): Double = NODE_HEIGHT

    private fun nodeBounds(node: ScenarioNode): Rectangle2D.Double = Rectangle2D.Double(
        node.x.toDouble(),
        node.y.toDouble(),
        nodeWidth(node),
        NODE_HEIGHT
    )

    private fun inPort(node: ScenarioNode): Point2D.Double =
        Point2D.Double(node.x.toDouble(), node.y + nodeHeight(node) / 2)

    private fun outPort(node: ScenarioNode): Point2D.Double =
        Point2D.Double(node.x + nodeWidth(node), node.y + nodeHeight(node) / 2)

    private fun screen(point: Point2D): Point2D.Double =
        Point2D.Double(point.x * zoom + panX, point.y * zoom + panY)

    private fun screenRect(rect: Rectangle2D): Rectangle2D.Double =
        Rectangle2D.Double(
            rect.x * zoom + panX,
            rect.y * zoom + panY,
            rect.width * zoom,
            rect.height * zoom
        )

    private fun world(x: Double, y: Double): Point2D.Double =
        Point2D.Double((x - panX) / zoom, (y - panY) / zoom)

    private fun nodeAt(point: Point): ScenarioNode? {
        val scenario = scenarioProvider() ?: return null
        val world = world(point.x.toDouble(), point.y.toDouble())
        return scenario.nodes.asReversed().firstOrNull { node ->
            nodeBounds(node).contains(world.x, world.y)
        }
    }

    private fun outPortAt(point: Point): ScenarioNode? {
        return ScenarioGraph.requestNodes(scenarioProvider() ?: return null).firstOrNull { node ->
            distance(screen(outPort(node)), point) <= PORT_HIT_RADIUS
        }
    }

    private fun inPortAt(point: Point): ScenarioNode? {
        return ScenarioGraph.requestNodes(scenarioProvider() ?: return null).firstOrNull { node ->
            distance(screen(inPort(node)), point) <= PORT_HIT_RADIUS
        }
    }

    private fun distance(point: Point2D, target: Point): Double =
        Point2D.Double(target.x.toDouble(), target.y.toDouble()).distance(point)

    private fun edgeCurve(start: Point2D, end: Point2D): Curve {
        val dx = max(45.0 * zoom, abs(end.x - start.x) * 0.45)
        val control1 = Point2D.Double(start.x + dx, start.y)
        val control2 = Point2D.Double(end.x - dx, end.y)
        val path = Path2D.Double().apply {
            moveTo(start.x, start.y)
            curveTo(control1.x, control1.y, control2.x, control2.y, end.x, end.y)
        }
        return Curve(path, control1, control2)
    }

    private fun cubic(start: Point2D, c1: Point2D, c2: Point2D, end: Point2D, t: Double): Point2D.Double {
        val u = 1 - t
        val a = u * u * u
        val b = 3 * u * u * t
        val c = 3 * u * t * t
        val d = t * t * t
        return Point2D.Double(
            a * start.x + b * c1.x + c * c2.x + d * end.x,
            a * start.y + b * c1.y + c * c2.y + d * end.y
        )
    }

    private fun arrowHead(from: Point2D, to: Point2D): Path2D.Double {
        val angle = atan2(to.y - from.y, to.x - from.x)
        val size = (9 * zoom).coerceIn(4.0, 11.0)
        val leftAngle = angle - Math.PI / 7
        val rightAngle = angle + Math.PI / 7
        return Path2D.Double().apply {
            moveTo(to.x, to.y)
            lineTo(to.x - size * cos(leftAngle), to.y - size * sin(leftAngle))
            lineTo(to.x - size * cos(rightAngle), to.y - size * sin(rightAngle))
            closePath()
        }
    }

    private fun edgeAt(point: Point): ScenarioEdge? {
        val scenario = scenarioProvider() ?: return null
        val nodes = scenario.nodes.associateBy { it.id }
        val tolerance = 7.0
        return scenario.edges.asReversed().firstOrNull { edge ->
            val from = nodes[edge.from] ?: return@firstOrNull false
            val to = nodes[edge.to] ?: return@firstOrNull false
            if (!from.isRequestNode() || !to.isRequestNode()) return@firstOrNull false
            val start = screen(outPort(from))
            val end = screen(inPort(to))
            val curve = edgeCurve(start, end)
            var previous = start
            (1..24).any { step ->
                val current = cubic(start, curve.control1, curve.control2, end, step / 24.0)
                val hit = distanceToSegment(point, previous, current) <= tolerance
                previous = current
                hit
            }
        }
    }

    private fun distanceToSegment(point: Point, start: Point2D, end: Point2D): Double {
        val px = point.x.toDouble()
        val py = point.y.toDouble()
        val dx = end.x - start.x
        val dy = end.y - start.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 0.0001) return Point2D.Double(px, py).distance(start)
        val t = (((px - start.x) * dx + (py - start.y) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        val projectionX = start.x + t * dx
        val projectionY = start.y + t * dy
        return Point2D.Double(px, py).distance(projectionX, projectionY)
    }

    // ---------------------------------------------------------------- interactions

    private fun fitOnFirstShow() {
        if (initialFit || width <= 0 || height <= 0) return
        initialFit = true
        fitToContent()
    }

    private fun installKeyBindings() {
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "smartRequest.graphDelete")
        actionMap.put("smartRequest.graphDelete", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent?) = deleteSelection()
        })
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "smartRequest.graphCancelEdge")
        actionMap.put("smartRequest.graphCancelEdge", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent?) {
                cancelEdgeDraft()
                repaint()
            }
        })
    }

    private inner class MouseHandler : MouseAdapter() {
        override fun mousePressed(event: MouseEvent) {
            requestFocusInWindow()
            if (event.isPopupTrigger) {
                showPopup(event)
                return
            }
            if (!SwingUtilities.isLeftMouseButton(event)) return

            val output = outPortAt(event.point)
            if (output != null) {
                edgeSourceId = output.id
                edgePointer = event.point
                edgeTargetId = null
                edgeError = null
                showHint("Drag to another step's left port")
                repaint()
                return
            }

            val node = nodeAt(event.point)
            if (node != null) {
                selectNode(node.id)
                dragNodeId = node.id
                dragOffsetX = world(event.x.toDouble(), event.y.toDouble()).x - node.x
                dragOffsetY = world(event.x.toDouble(), event.y.toDouble()).y - node.y
                dragMoved = false
                dragSnapshotTaken = false
                showHint(null)
                return
            }

            val edge = edgeAt(event.point)
            if (edge != null) {
                selectEdge(edge.id)
                showHint("Dependency selected")
                return
            }

            panning = true
            panPressedAt = event.point
            panStartX = panX
            panStartY = panY
            panMoved = false
            hoveredNodeId = null
            repaint()
        }

        override fun mouseDragged(event: MouseEvent) {
            if (edgeSourceId != null) {
                val scenario = scenarioProvider() ?: return
                edgePointer = event.point
                val target = inPortAt(event.point)
                edgeTargetId = target?.id
                edgeError = target?.let { ScenarioGraph.dependencyError(scenario, edgeSourceId!!, it.id) }
                if (target != null) {
                    showHint(edgeError ?: "Release to connect")
                } else {
                    showHint("Drag to another step's left port")
                }
                repaint()
                return
            }

            val nodeId = dragNodeId
            if (nodeId != null) {
                val node = scenarioProvider()?.nodes?.firstOrNull { it.id == nodeId } ?: return
                val world = world(event.x.toDouble(), event.y.toDouble())
                val nextX = snap(world.x - dragOffsetX)
                val nextY = snap(world.y - dragOffsetY)
                if (nextX != node.x || nextY != node.y) {
                    if (!dragSnapshotTaken) {
                        onBeforeEdit()
                        dragSnapshotTaken = true
                    }
                    node.x = nextX
                    node.y = nextY
                    dragMoved = true
                    repaint()
                }
                return
            }

            if (panning) {
                panX = panStartX + (event.x - (panPressedAt?.x ?: event.x))
                panY = panStartY + (event.y - (panPressedAt?.y ?: event.y))
                panMoved = true
                repaint()
            }
        }

        override fun mouseReleased(event: MouseEvent) {
            if (event.isPopupTrigger) {
                showPopup(event)
                return
            }
            if (edgeSourceId != null) {
                finishEdgeDraft()
                return
            }
            if (dragNodeId != null) {
                val moved = dragMoved
                dragNodeId = null
                dragMoved = false
                if (moved) {
                    onGraphChanged()
                    showHint("Position saved")
                }
                return
            }
            if (panning) {
                val clicked = !panMoved && panPressedAt?.let { it.distance(event.point) < 4 } == true
                panning = false
                panPressedAt = null
                if (clicked) {
                    clearSelection()
                }
            }
        }

        override fun mouseMoved(event: MouseEvent) {
            hoveredNodeId = nodeAt(event.point)?.id
            val overPort = outPortAt(event.point) != null || inPortAt(event.point) != null
            cursor = Cursor.getPredefinedCursor(
                when {
                    overPort -> Cursor.HAND_CURSOR
                    hoveredNodeId != null -> Cursor.HAND_CURSOR
                    edgeAt(event.point) != null -> Cursor.HAND_CURSOR
                    else -> Cursor.DEFAULT_CURSOR
                }
            )
            repaint()
        }

        override fun mouseClicked(event: MouseEvent) {
            if (event.clickCount == 2 && SwingUtilities.isLeftMouseButton(event)) {
                val node = nodeAt(event.point) ?: return
                val requestId = node.requestId ?: return
                onOpenRequest(requestId)
            }
        }

        override fun mouseExited(event: MouseEvent) {
            hoveredNodeId = null
            repaint()
        }
    }

    private fun handleWheel(event: MouseWheelEvent) {
        if (event.isControlDown) {
            val before = world(event.x.toDouble(), event.y.toDouble())
            val factor = if (event.preciseWheelRotation < 0) 1.12 else 1 / 1.12
            zoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
            panX = event.x - before.x * zoom
            panY = event.y - before.y * zoom
        } else if (event.isShiftDown) {
            panX -= event.preciseWheelRotation * 40
        } else {
            panY -= event.preciseWheelRotation * 40
        }
        repaint()
    }

    private fun finishEdgeDraft() {
        val scenario = scenarioProvider() ?: return
        val sourceId = edgeSourceId ?: return
        val targetId = edgeTargetId
        val error = edgeError
        if (targetId == null || error != null) {
            showHint(error ?: "Drop on another step's left port")
            cancelEdgeDraft()
            repaint()
            return
        }
        if (!connect(sourceId, targetId)) {
            cancelEdgeDraft()
            repaint()
            return
        }
        cancelEdgeDraft()
        repaint()
    }

    private fun connect(sourceId: String, targetId: String): Boolean {
        val scenario = scenarioProvider() ?: return false
        val error = ScenarioGraph.dependencyError(scenario, sourceId, targetId)
        if (error != null) {
            showHint(error)
            return false
        }
        onBeforeEdit()
        val result = ScenarioGraph.addDependency(scenario, sourceId, targetId)
        if (!result.ok) {
            showHint(result.error)
            return false
        }
        onGraphChanged()
        showHint("Dependency added")
        return true
    }

    private fun cancelEdgeDraft() {
        edgeSourceId = null
        edgePointer = null
        edgeTargetId = null
        edgeError = null
    }

    private fun selectNode(nodeId: String?) {
        selectedNodeId = nodeId
        selectedEdgeId = null
        repaint()
        onSelectionChanged(nodeId, null)
    }

    private fun selectEdge(edgeId: String?) {
        selectedEdgeId = edgeId
        selectedNodeId = null
        repaint()
        onSelectionChanged(null, edgeId)
    }

    private fun clearSelection() {
        if (selectedNodeId == null && selectedEdgeId == null) return
        selectedNodeId = null
        selectedEdgeId = null
        repaint()
        onSelectionChanged(null, null)
    }

    private fun deleteSelection() {
        val scenario = scenarioProvider() ?: return
        val nodeId = selectedNodeId
        if (nodeId != null) {
            val node = scenario.nodes.firstOrNull { it.id == nodeId } ?: return
            if (!node.isRequestNode()) {
                showHint("Start and End cannot be removed")
                return
            }
            onBeforeEdit()
            ScenarioGraph.removeNode(scenario, nodeId)
            selectedNodeId = null
            onGraphChanged()
            showHint("Step removed")
            repaint()
            return
        }
        val edgeId = selectedEdgeId ?: return
        val edge = scenario.edges.firstOrNull { it.id == edgeId } ?: return
        val from = scenario.nodes.firstOrNull { it.id == edge.from }
        val to = scenario.nodes.firstOrNull { it.id == edge.to }
        if (from == null || to == null || !from.isRequestNode() || !to.isRequestNode()) {
            showHint("Start/End dependencies are derived and cannot be removed")
            return
        }
        onBeforeEdit()
        ScenarioGraph.removeDependency(scenario, from.id, to.id)
        selectedEdgeId = null
        onGraphChanged()
        showHint("Dependency removed")
        repaint()
    }

    private fun showPopup(event: MouseEvent) {
        val scenario = scenarioProvider() ?: return
        val node = nodeAt(event.point)
        val edge = if (node == null) edgeAt(event.point) else null
        if (node != null) {
            selectNode(node.id)
        } else if (edge != null) {
            selectEdge(edge.id)
        }
        val menu = JPopupMenu()
        menu.add(menuItem("Add Step…") { onAddStep() })
        menu.addSeparator()
        if (node != null) {
            if (node.isRequestNode() && node.requestId != null) {
                menu.add(menuItem("Open Request") { onOpenRequest(node.requestId!!) })
                menu.add(menuItem("Delete Step") { deleteSelection() })
                menu.addSeparator()
            } else {
                menu.add(JMenuItem("Start/End cannot be removed").apply { isEnabled = false })
                menu.addSeparator()
            }
        }
        if (edge != null) {
            val from = scenario.nodes.firstOrNull { it.id == edge.from }
            val to = scenario.nodes.firstOrNull { it.id == edge.to }
            if (from != null && to != null && from.isRequestNode() && to.isRequestNode()) {
                menu.add(menuItem("Delete Dependency") { deleteSelection() })
                menu.addSeparator()
            }
        }
        menu.add(menuItem("Auto Layout") { autoLayout() })
        menu.add(menuItem("Fit to Content") { fitToContent() })
        menu.show(this, event.x, event.y)
    }

    private fun menuItem(text: String, action: () -> Unit): JMenuItem = JMenuItem(text).apply {
        addActionListener { action() }
    }

    private fun snap(value: Double): Int = (value / 10.0).roundToInt() * 10

    private fun showHint(text: String?) {
        lastHint = text
        onHint(text)
    }

    private fun ellipsize(text: String, metrics: FontMetrics, maxWidth: Double): String {
        if (text.isEmpty() || maxWidth <= 0) return ""
        if (metrics.stringWidth(text) <= maxWidth) return text
        val suffix = "…"
        var end = text.length
        while (end > 1 && metrics.stringWidth(text.substring(0, end) + suffix) > maxWidth) end--
        return text.substring(0, end) + suffix
    }

    override fun getToolTipText(event: MouseEvent): String? {
        val scenario = scenarioProvider() ?: return null
        val node = nodeAt(event.point)
        if (node != null) {
            if (!node.isRequestNode()) {
                return if (node.kind == ScenarioNode.KIND_START) "Start of the flow" else "End of the flow"
            }
            val request = node.requestId?.let(requestProvider)
            val name = ScenarioGraph.label(node, request?.name)
            val outputs = node.outputs.count { it.enabled && !it.isBlank() }
            return buildString {
                append(request?.method.orEmpty()).append(' ').append(name)
                request?.url?.takeIf { it.isNotBlank() }?.let { append('\n').append(it) }
                if (outputs > 0) append("\nCaptures: ").append(outputs)
                if (node.expectStatus.isNotBlank()) append("\nExpect: ").append(node.expectStatus)
            }
        }
        val edge = edgeAt(event.point) ?: return null
        val from = scenario.nodes.firstOrNull { it.id == edge.from } ?: return null
        val to = scenario.nodes.firstOrNull { it.id == edge.to } ?: return null
        return "${ScenarioGraph.label(from, from.requestId?.let(requestProvider)?.name)} → " +
                ScenarioGraph.label(to, to.requestId?.let(requestProvider)?.name)
    }

    // ---------------------------------------------------------------- test hooks

    internal fun nodeBoundsForTest(nodeId: String): Rectangle2D? {
        val node = scenarioProvider()?.nodes?.firstOrNull { it.id == nodeId } ?: return null
        return screenRect(nodeBounds(node))
    }

    internal fun outputPortForTest(nodeId: String): Point2D? {
        val node = scenarioProvider()?.nodes?.firstOrNull { it.id == nodeId } ?: return null
        if (!node.isRequestNode()) return null
        return screen(outPort(node))
    }

    internal fun inputPortForTest(nodeId: String): Point2D? {
        val node = scenarioProvider()?.nodes?.firstOrNull { it.id == nodeId } ?: return null
        if (!node.isRequestNode()) return null
        return screen(inPort(node))
    }

    internal fun zoomForTest(): Double = zoom

    internal fun selectNodeForTest(nodeId: String) {
        selectNode(nodeId)
    }

    internal fun clearSelectionForTest() {
        clearSelection()
    }

    internal fun selectedNodeForTest(): String? = selectedNodeId

    internal fun selectedEdgeForTest(): String? = selectedEdgeId

    internal fun selectEdgeForTest(edgeId: String) {
        selectEdge(edgeId)
    }

    internal fun edgeIdAtForTest(x: Int, y: Int): String? = edgeAt(Point(x, y))?.id

    internal fun deleteSelectionForTest() {
        deleteSelection()
    }

    internal fun autoLayoutForTest() {
        autoLayout()
    }

    internal fun fitForTest() {
        fitToContent()
    }

    internal fun connectForTest(sourceId: String, targetId: String): Boolean = connect(sourceId, targetId)

    internal fun hintForTest(): String? = lastHint

    internal fun runStateForTest(nodeId: String): NodeRunState? = runStates[nodeId]

    internal fun edgeDragForTest(sourceId: String, targetId: String): Boolean = connect(sourceId, targetId)

    internal fun nodeIdAtForTest(x: Int, y: Int): String? = nodeAt(Point(x, y))?.id

    /** Applies a drop without a Swing drag gesture, at model coordinates. */
    internal fun dropRequestForTest(requestId: String, worldX: Int, worldY: Int) {
        onRequestDropped(requestId, worldX, worldY)
    }

    private data class Curve(val path: Path2D.Double, val control1: Point2D, val control2: Point2D)

    companion object {
        internal const val NODE_WIDTH = 200.0
        internal const val NODE_HEIGHT = 76.0
        internal const val TERMINAL_WIDTH = 110.0

        private const val PORT_RADIUS = 4.5
        private const val PORT_HIT_RADIUS = 12.0
        private const val MIN_ZOOM = 0.35
        private const val MAX_ZOOM = 2.0
        private const val FIT_MARGIN = 48.0

        private val NODE_FILL = JBColor(0xFFFFFF, 0x2B2D30)
        private val NODE_HOVER_FILL = JBColor(0xF2F6FF, 0x343A43)
        private val NODE_SELECTED_FILL = JBColor(0xE8F0FE, 0x2E436E)
        private val NODE_BORDER = JBColor(0xC9CFD7, 0x50565E)
        private val NODE_TEXT = JBColor(0x1F2328, 0xE6E8EA)
        private val NODE_MUTED = JBColor(0x6E7781, 0x9DA7B3)
        private val EDGE = JBColor(0x8C959F, 0x9AA4AE)

        internal val ACCENT = JBColor(0x3574F0, 0x548AF7)
        private val ACCENT_LIGHT = JBColor(0x8AB4F8, 0x7AA7F7)

        private val RUN_PASSED = JBColor(0x1A7F37, 0x73BD79)
        private val RUN_FAILED = JBColor(0xC7222D, 0xFF6B68)
        private val RUN_RUNNING = JBColor(0x0B5CAD, 0x6BA6E8)
        private val RUN_PENDING = JBColor(0x9A6700, 0xD6A26A)
    }
}
