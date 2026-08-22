package com.BalancedLight.dynamicdriving.shared.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Fixed height used by every reorderable row so drag distance maps cleanly onto index steps. */
val ReorderableRowHeight: Dp = 72.dp

/**
 * State for a long-press drag reorder.
 *
 * Rows have a known fixed height, so instead of measuring item bounds the handle accumulates drag
 * distance and emits a one-position move each time it crosses a row. That keeps the gesture exact
 * without needing a layout pass, and the same [onMove] callback also backs the accessible
 * move-up/move-down buttons on each row.
 */
class ReorderState internal constructor(
    private val rowHeightPx: Float,
    private val onMove: (fromIndex: Int, toIndex: Int) -> Unit
) {
    var draggingIndex by mutableIntStateOf(-1)
        private set

    private var accumulatedDragPx by mutableStateOf(0f)

    internal fun onDragStart(index: Int) {
        draggingIndex = index
        accumulatedDragPx = 0f
    }

    internal fun onDrag(deltaPx: Float, itemCount: Int) {
        if (draggingIndex < 0) {
            return
        }
        accumulatedDragPx += deltaPx
        while (accumulatedDragPx >= rowHeightPx && draggingIndex < itemCount - 1) {
            accumulatedDragPx -= rowHeightPx
            val target = draggingIndex + 1
            onMove(draggingIndex, target)
            draggingIndex = target
        }
        while (accumulatedDragPx <= -rowHeightPx && draggingIndex > 0) {
            accumulatedDragPx += rowHeightPx
            val target = draggingIndex - 1
            onMove(draggingIndex, target)
            draggingIndex = target
        }
    }

    internal fun onDragEnd() {
        draggingIndex = -1
        accumulatedDragPx = 0f
    }
}

@Composable
fun rememberReorderState(onMove: (fromIndex: Int, toIndex: Int) -> Unit): ReorderState {
    val rowHeightPx = with(LocalDensity.current) { ReorderableRowHeight.toPx() }
    return remember(rowHeightPx, onMove) { ReorderState(rowHeightPx, onMove) }
}

/** The grab handle that starts a long-press drag for the row at [index]. */
@Composable
fun ReorderHandle(
    state: ReorderState,
    index: Int,
    itemCount: Int,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    Icon(
        imageVector = Icons.Rounded.DragHandle,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .semantics { this.contentDescription = contentDescription }
            .pointerInput(index, itemCount) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { state.onDragStart(index) },
                    onDragEnd = { state.onDragEnd() },
                    onDragCancel = { state.onDragEnd() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        state.onDrag(dragAmount.y, itemCount)
                    }
                )
            }
    )
}
