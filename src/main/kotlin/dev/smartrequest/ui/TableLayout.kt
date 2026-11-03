package dev.smartrequest.ui

import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.table.TableModel

/**
 * Splits [available] pixels across columns following [weights] (for example 3:4 for key/value)
 * while never going below [minimums].
 *
 * The result always sums to [available] when there is enough room, so tables can fill their viewport
 * without leaving a dead strip on the right. When the minimums do not fit, the widest columns are
 * shaved first.
 */
internal fun proportionalWidths(
    available: Int,
    weights: List<Double>,
    minimums: List<Int>
): IntArray {
    require(weights.size == minimums.size) { "weights and minimums must have the same size" }
    val count = weights.size
    if (count == 0) return IntArray(0)
    if (available <= 0) return IntArray(count) { minimums[it] }

    val totalWeight = weights.sum().takeIf { it > 0.0 } ?: return IntArray(count) { minimums[it] }
    val widths = IntArray(count) { index ->
        maxOf((available * weights[index] / totalWeight).toInt(), minimums[index])
    }

    var remainder = available - widths.sum()
    if (remainder > 0) {
        // Integer division lost a few pixels; give them to the widest column.
        widths[weights.indices.maxByOrNull { weights[it] } ?: 0] += remainder
    } else if (remainder < 0) {
        // The minimums overflow the viewport: shrink from the widest column down.
        val order = widths.indices.sortedByDescending { widths[it] }
        while (remainder < 0) {
            var shaved = false
            for (index in order) {
                if (remainder == 0) break
                if (widths[index] > minimums[index]) {
                    widths[index]--
                    remainder++
                    shaved = true
                }
            }
            if (!shaved) break
        }
    }
    return widths
}

/**
 * A table whose columns always fill the viewport with fixed proportions.
 *
 * Subclasses return one width per column from [computeWidths]. Widths are not touched while a cell
 * is being edited, because JTable drops the open editor as soon as a column width changes - a single
 * click would otherwise open the editor and close it again on the next layout pass. The pending
 * widths are applied as soon as editing ends.
 */
internal abstract class ProportionalTable(model: TableModel) : JBTable(model) {

    private var deferred = false

    init {
        autoResizeMode = JTable.AUTO_RESIZE_OFF
        addPropertyChangeListener("cellEditor") { event ->
            if (event.newValue == null) applyPendingWidths()
        }
    }

    /** One entry per column, in view order. */
    protected abstract fun computeWidths(tableWidth: Int): List<Int>

    override fun getScrollableTracksViewportWidth(): Boolean = true

    override fun doLayout() {
        if (isEditing) {
            deferred = true
            return
        }
        super.doLayout()
        applyWidths()
    }

    /** Re-applies the column widths, for example after a column was hidden. */
    internal fun relayoutColumns() = applyWidths()

    private fun applyPendingWidths() {
        if (!deferred) return
        deferred = false
        SwingUtilities.invokeLater { applyWidths() }
    }

    private fun applyWidths() {
        if (isEditing || width <= 0) return
        val widths = computeWidths(width)
        if (widths.size != columnModel.columnCount) return
        widths.forEachIndexed { index, value ->
            val column = columnModel.getColumn(index)
            if (column.width != value) column.width = value
        }
    }
}

/**
 * Starts the inline editor on a single left click, so a cell behaves like a form field that becomes
 * editable when clicked instead of needing a double click.
 *
 * [onClick] runs first and returns true when it already handled the click (for example to toggle a
 * checkbox cell). [beginEdit] starts editing and reports whether it did.
 */
internal fun JTable.installSingleClickEditing(
    onClick: (row: Int, viewColumn: Int) -> Boolean = { _, _ -> false },
    beginEdit: (row: Int, viewColumn: Int) -> Boolean
) {
    addMouseListener(object : MouseAdapter() {
        override fun mousePressed(event: MouseEvent) {
            if (event.button != MouseEvent.BUTTON1) return
            val row = rowAtPoint(event.point)
            val viewColumn = columnAtPoint(event.point)
            if (row < 0 || viewColumn < 0) return
            if (onClick(row, viewColumn)) return
            if (event.clickCount != 1) return
            // Defer until the table has finished its own selection handling for this click,
            // otherwise it can drop the editor we just opened.
            SwingUtilities.invokeLater { beginEdit(row, viewColumn) }
        }
    })
}

/** Focuses the visible cell editor and selects its content so typing replaces the old value. */
internal fun focusCellEditor(table: JTable): JTextField? {
    val field = table.editorComponent as? JTextField ?: return null
    field.requestFocusInWindow()
    field.selectAll()
    return field
}

/** Scrolls [row] into view so a newly added row is never created off-screen. */
internal fun JTable.scrollRowIntoView(row: Int) {
    if (row < 0) return
    scrollRectToVisible(getCellRect(row, 0, true))
}

/** Padding shared by the editable cell renderers. */
internal val cellPadding get() = JBUI.Borders.empty(0, 8)
