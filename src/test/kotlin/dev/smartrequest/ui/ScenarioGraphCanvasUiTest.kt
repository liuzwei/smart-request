package dev.smartrequest.ui

import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.model.ScenarioConfig
import dev.smartrequest.scenario.NodeRunState
import dev.smartrequest.scenario.ScenarioGraph
import dev.smartrequest.scenario.ScenarioNodeResult
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage

class ScenarioGraphCanvasUiTest : BasePlatformTestCase() {

    private fun <T> onEdtGet(action: () -> T): T = EdtTestUtil.runInEdtAndGet<T, RuntimeException> { action() }

    private fun onEdt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { action() }

    private fun canvasFor(
        scenario: ScenarioConfig,
        onSelection: (String?, String?) -> Unit = { _, _ -> },
        onBeforeEdit: () -> Unit = {},
        onChanged: () -> Unit = {},
        onHint: (String?) -> Unit = {}
    ): ScenarioGraphCanvas = onEdtGet {
        ScenarioGraphCanvas(
            scenarioProvider = { scenario },
            requestProvider = { id -> HttpRequestConfig(id = id, name = "Request $id", method = "GET") },
            onSelectionChanged = { nodeId, edgeId -> onSelection(nodeId, edgeId) },
            onOpenRequest = {},
            onAddStep = {},
            onBeforeEdit = onBeforeEdit,
            onGraphChanged = onChanged,
            onHint = onHint
        ).apply {
            setSize(900, 600)
            fitForTest()
        }
    }

    private fun scenario(vararg requestIds: String, sequential: Boolean = true): ScenarioConfig =
        ScenarioConfig(name = "Canvas").apply {
            normalize()
            var previous: dev.smartrequest.model.ScenarioNode? = null
            requestIds.forEach { id ->
                val node = ScenarioGraph.addRequestNode(
                    this,
                    id,
                    afterNodeId = if (sequential) previous?.id else null
                )
                if (sequential) previous = node
            }
        }

    private fun mouse(
        canvas: ScenarioGraphCanvas,
        id: Int,
        x: Int,
        y: Int,
        button: Int = MouseEvent.BUTTON1,
        modifiers: Int = 0
    ): MouseEvent = MouseEvent(
        canvas,
        id,
        System.currentTimeMillis(),
        modifiers,
        x,
        y,
        1,
        false,
        button
    )

    fun testNodesAndPortsAreHitTestableAfterFit() {
        val scenario = scenario("r1", "r2")
        val first = ScenarioGraph.requestNodes(scenario).first()
        val second = ScenarioGraph.requestNodes(scenario).last()
        var selection: Pair<String?, String?> = "initial" to "initial"
        val canvas = canvasFor(
            scenario,
            onSelection = { nodeId, edgeId -> selection = nodeId to edgeId }
        )

        onEdt {
            assertTrue(canvas.zoomForTest() in 0.35..2.0)
            val bounds = canvas.nodeBoundsForTest(first.id)!!
            assertTrue(bounds.width > 0)
            assertEquals(first.id, canvas.nodeIdAtForTest(bounds.centerX.toInt(), bounds.centerY.toInt()))
            val out = canvas.outputPortForTest(first.id)!!
            val input = canvas.inputPortForTest(second.id)!!
            assertTrue(out.x < input.x)

            canvas.selectNodeForTest(first.id)
            assertEquals(first.id, selection.first)
            assertNull(selection.second)
        }
    }

    fun testEdgeIsCreatedByDraggingFromOnePortToAnother() {
        val scenario = scenario("r1", "r2", sequential = false)
        val first = ScenarioGraph.requestNodes(scenario).first()
        val second = ScenarioGraph.requestNodes(scenario).last()
        var edits = 0
        var changes = 0
        var hint: String? = null
        val canvas = canvasFor(scenario, onBeforeEdit = { edits++ }, onChanged = { changes++ }, onHint = { hint = it })

        onEdt {
            val from = canvas.outputPortForTest(first.id)!!
            val to = canvas.inputPortForTest(second.id)!!
            canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_PRESSED, from.x.toInt(), from.y.toInt()))
            canvas.dispatchEvent(
                mouse(
                    canvas,
                    MouseEvent.MOUSE_DRAGGED,
                    to.x.toInt(),
                    to.y.toInt(),
                    button = MouseEvent.NOBUTTON,
                    modifiers = InputEvent.BUTTON1_DOWN_MASK
                )
            )
            canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_RELEASED, to.x.toInt(), to.y.toInt()))

            assertTrue(
                "expected a dependency",
                ScenarioGraph.requiresDependency(scenario, second.id, first.id)
            )
            assertEquals(1, edits)
            assertEquals(1, changes)
            assertTrue(hint, hint?.contains("added") == true)
        }
    }

    fun testDependenciesCanAlsoBeCreatedWithoutMouseEvents() {
        val scenario = scenario("r1", "r2", sequential = false)
        val first = ScenarioGraph.requestNodes(scenario).first()
        val second = ScenarioGraph.requestNodes(scenario).last()
        val canvas = canvasFor(scenario)

        onEdt {
            assertTrue(canvas.connectForTest(first.id, second.id))
            assertTrue(ScenarioGraph.requiresDependency(scenario, second.id, first.id))
        }
    }

    fun testInvalidEdgesAreRejectedWithAHint() {
        val scenario = scenario("r1", "r2")
        val first = ScenarioGraph.requestNodes(scenario).first()
        val second = ScenarioGraph.requestNodes(scenario).last()
        val start = ScenarioGraph.start(scenario)!!
        val end = ScenarioGraph.end(scenario)!!
        var edits = 0
        var hint: String? = null
        val canvas = canvasFor(scenario, onBeforeEdit = { edits++ }, onHint = { hint = it })

        onEdt {
            assertFalse(canvas.connectForTest(second.id, first.id))
            assertTrue(hint, hint?.contains("cycle") == true)

            assertFalse(canvas.connectForTest(first.id, first.id))
            assertTrue(hint, hint?.contains("itself") == true)

            assertFalse(canvas.connectForTest(first.id, second.id))
            assertTrue(hint, hint?.contains("Already") == true)

            assertFalse(canvas.connectForTest(start.id, first.id))
            assertTrue(hint, hint?.contains("Start") == true)

            assertFalse(canvas.connectForTest(first.id, end.id))
            assertTrue(hint, hint?.contains("End") == true)

            assertEquals(0, edits)
        }
    }

    fun testDeleteRemovesStepsAndDependenciesButProtectsStartAndEnd() {
        val scenario = scenario("r1", "r2")
        val first = ScenarioGraph.requestNodes(scenario).first()
        val second = ScenarioGraph.requestNodes(scenario).last()
        val start = ScenarioGraph.start(scenario)!!
        var changes = 0
        var hint: String? = null
        val canvas = canvasFor(scenario, onChanged = { changes++ }, onHint = { hint = it })

        onEdt {
            val edge = scenario.edges.first { it.from == first.id && it.to == second.id }
            val firstBounds = canvas.nodeBoundsForTest(first.id)!!
            val secondBounds = canvas.nodeBoundsForTest(second.id)!!
            val middleX = ((firstBounds.maxX + secondBounds.minX) / 2).toInt()
            val middleY = (firstBounds.centerY).toInt()
            assertEquals(edge.id, canvas.edgeIdAtForTest(middleX, middleY))
            canvas.selectEdgeForTest(edge.id)
            assertNotNull(canvas.selectedEdgeForTest())
            canvas.deleteSelectionForTest()
            assertFalse(ScenarioGraph.requiresDependency(scenario, second.id, first.id))
            assertTrue(hint, hint?.contains("removed") == true)
            assertEquals(1, changes)

            canvas.selectNodeForTest(first.id)
            canvas.deleteSelectionForTest()
            assertNull(scenario.nodes.firstOrNull { it.id == first.id })
            assertTrue(changes >= 2)

            canvas.selectNodeForTest(start.id)
            canvas.deleteSelectionForTest()
            assertNotNull(ScenarioGraph.start(scenario))
            assertTrue(hint, hint?.contains("cannot") == true)
        }
    }

    fun testAutoLayoutMovesNodesAndFitsTheView() {
        val scenario = scenario("r1", "r2", "r3")
        val nodes = ScenarioGraph.requestNodes(scenario)
        nodes.forEach { it.x = 0; it.y = 0 }
        var edits = 0
        var changes = 0
        val canvas = canvasFor(scenario, onBeforeEdit = { edits++ }, onChanged = { changes++ })

        onEdt {
            canvas.autoLayoutForTest()
            assertEquals(1, edits)
            assertEquals(1, changes)
            assertTrue(canvas.zoomForTest() in 0.35..2.0)
            assertTrue(nodes[0].x < nodes[1].x)
            assertTrue(nodes[1].x < nodes[2].x)
            assertTrue(ScenarioGraph.start(scenario)!!.x < nodes[0].x)
            assertTrue(nodes.last().x < ScenarioGraph.end(scenario)!!.x)
        }
    }

    fun testZoomAndPanChangeTheViewWithoutEditingTheModel() {
        val scenario = scenario("r1")
        val first = ScenarioGraph.requestNodes(scenario).single()
        var edits = 0
        val canvas = canvasFor(scenario, onBeforeEdit = { edits++ })

        onEdt {
            val beforeZoom = canvas.zoomForTest()
            val wheel = MouseWheelEvent(
                canvas,
                MouseEvent.MOUSE_WHEEL,
                System.currentTimeMillis(),
                InputEvent.CTRL_DOWN_MASK,
                450,
                300,
                0,
                false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL,
                1,
                -1
            )
            canvas.dispatchEvent(wheel)
            assertTrue(canvas.zoomForTest() > beforeZoom)

            val beforeX = canvas.nodeBoundsForTest(first.id)!!.x
            canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_PRESSED, 5, 5))
            canvas.dispatchEvent(
                mouse(
                    canvas,
                    MouseEvent.MOUSE_DRAGGED,
                    65,
                    35,
                    button = MouseEvent.NOBUTTON,
                    modifiers = InputEvent.BUTTON1_DOWN_MASK
                )
            )
            canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_RELEASED, 65, 35))
            assertTrue(canvas.nodeBoundsForTest(first.id)!!.x > beforeX)
            assertEquals(0, edits)
        }
    }

    fun testRunStateIsPaintedWithoutChangingTheModel() {
        val scenario = scenario("r1")
        val node = ScenarioGraph.requestNodes(scenario).single()
        val canvas = canvasFor(scenario)
        val modelX = node.x

        onEdt {
            canvas.setRunState(node.id, NodeRunState.RUNNING)
            assertEquals(NodeRunState.RUNNING, canvas.runStateForTest(node.id))
            canvas.setRunResult(
                ScenarioNodeResult(
                    nodeId = node.id,
                    name = "Request",
                    state = NodeRunState.PASSED,
                    method = "GET",
                    statusCode = 200,
                    durationMs = 5
                )
            )
            assertEquals(NodeRunState.PASSED, canvas.runStateForTest(node.id))

            val image = BufferedImage(900, 600, BufferedImage.TYPE_INT_ARGB)
            canvas.paint(image.createGraphics())

            canvas.clearRunState()
            assertNull(canvas.runStateForTest(node.id))
            assertEquals(modelX, node.x)
        }
    }
}
