package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import com.example.model.WatermarkRegion
import kotlin.math.sqrt

private enum class DragHandle {
  NONE, BODY, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
}

@Composable
fun WatermarkSelectionOverlay(
  regions: List<WatermarkRegion>,
  selectedRegionId: String?,
  videoAspect: Float, // width / height
  onRegionSelected: (String) -> Unit,
  onRegionBoundsChanged: (String, Float, Float, Float, Float) -> Unit,
  modifier: Modifier = Modifier
) {
  var activeHandle by remember { mutableStateOf(DragHandle.NONE) }
  var initialBounds by remember { mutableStateOf<Rect?>(null) }
  var dragStartOffset by remember { mutableStateOf(Offset.Zero) }

  Box(
    modifier = modifier
      .fillMaxSize()
      .testTag("watermark_selection_overlay")
      .pointerInput(regions, selectedRegionId, videoAspect) {
        detectTapGestures { tapOffset ->
          val contentRect = calculateContentRect(size.width.toFloat(), size.height.toFloat(), videoAspect)
          if (contentRect.width <= 0 || contentRect.height <= 0) return@detectTapGestures

          // Check if tapped inside any region (reverse order for top-most)
          val tappedRegion = regions.reversed().find { region ->
            val rect = toScreenRect(region, contentRect)
            rect.contains(tapOffset)
          }

          if (tappedRegion != null) {
            onRegionSelected(tappedRegion.id)
          }
        }
      }
      .pointerInput(regions, selectedRegionId, videoAspect) {
        detectDragGestures(
          onDragStart = { startOffset ->
            val contentRect = calculateContentRect(size.width.toFloat(), size.height.toFloat(), videoAspect)
            val selected = regions.find { it.id == selectedRegionId } ?: regions.firstOrNull()
            if (selected == null || contentRect.width <= 0 || contentRect.height <= 0) {
              activeHandle = DragHandle.NONE
              return@detectDragGestures
            }

            val screenRect = toScreenRect(selected, contentRect)
            initialBounds = screenRect
            dragStartOffset = startOffset

            val handleRadius = 40f
            val nearTopLeft = distance(startOffset, screenRect.topLeft) < handleRadius
            val nearTopRight = distance(startOffset, screenRect.topRight) < handleRadius
            val nearBottomLeft = distance(startOffset, screenRect.bottomLeft) < handleRadius
            val nearBottomRight = distance(startOffset, screenRect.bottomRight) < handleRadius

            activeHandle = when {
              nearTopLeft -> DragHandle.TOP_LEFT
              nearTopRight -> DragHandle.TOP_RIGHT
              nearBottomLeft -> DragHandle.BOTTOM_LEFT
              nearBottomRight -> DragHandle.BOTTOM_RIGHT
              screenRect.contains(startOffset) -> DragHandle.BODY
              else -> DragHandle.NONE
            }
          },
          onDragEnd = {
            activeHandle = DragHandle.NONE
            initialBounds = null
          },
          onDragCancel = {
            activeHandle = DragHandle.NONE
            initialBounds = null
          },
          onDrag = { change, dragAmount ->
            change.consume()
            val selected = regions.find { it.id == selectedRegionId } ?: return@detectDragGestures
            val contentRect = calculateContentRect(size.width.toFloat(), size.height.toFloat(), videoAspect)
            val currentBounds = initialBounds ?: return@detectDragGestures

            if (contentRect.width <= 0 || contentRect.height <= 0) return@detectDragGestures

            var l = currentBounds.left
            var t = currentBounds.top
            var r = currentBounds.right
            var b = currentBounds.bottom

            val totalDx = change.position.x - dragStartOffset.x
            val totalDy = change.position.y - dragStartOffset.y

            val minSizePx = 32f

            when (activeHandle) {
              DragHandle.BODY -> {
                val w = r - l
                val h = b - t
                l = (l + totalDx).coerceIn(contentRect.left, contentRect.right - w)
                t = (t + totalDy).coerceIn(contentRect.top, contentRect.bottom - h)
                r = l + w
                b = t + h
              }
              DragHandle.TOP_LEFT -> {
                l = (l + totalDx).coerceIn(contentRect.left, r - minSizePx)
                t = (t + totalDy).coerceIn(contentRect.top, b - minSizePx)
              }
              DragHandle.TOP_RIGHT -> {
                r = (r + totalDx).coerceIn(l + minSizePx, contentRect.right)
                t = (t + totalDy).coerceIn(contentRect.top, b - minSizePx)
              }
              DragHandle.BOTTOM_LEFT -> {
                l = (l + totalDx).coerceIn(contentRect.left, r - minSizePx)
                b = (b + totalDy).coerceIn(t + minSizePx, contentRect.bottom)
              }
              DragHandle.BOTTOM_RIGHT -> {
                r = (r + totalDx).coerceIn(l + minSizePx, contentRect.right)
                b = (b + totalDy).coerceIn(t + minSizePx, contentRect.bottom)
              }
              DragHandle.NONE -> {}
            }

            if (activeHandle != DragHandle.NONE) {
              val normL = ((l - contentRect.left) / contentRect.width).coerceIn(0f, 0.95f)
              val normT = ((t - contentRect.top) / contentRect.height).coerceIn(0f, 0.95f)
              val normR = ((r - contentRect.left) / contentRect.width).coerceIn(normL + 0.04f, 1f)
              val normB = ((b - contentRect.top) / contentRect.height).coerceIn(normT + 0.04f, 1f)
              onRegionBoundsChanged(selected.id, normL, normT, normR, normB)
            }
          }
        )
      }
  ) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      val contentRect = calculateContentRect(size.width, size.height, videoAspect)
      if (contentRect.width <= 0 || contentRect.height <= 0) return@Canvas

      // Draw each watermark removal region
      regions.forEach { region ->
        val isSelected = region.id == selectedRegionId
        val rect = toScreenRect(region, contentRect)
        drawWatermarkBox(rect, region, isSelected)
      }
    }
  }
}

private fun DrawScope.drawWatermarkBox(
  rect: Rect,
  region: WatermarkRegion,
  isSelected: Boolean
) {
  val strokeColor = if (isSelected) Color(0xFF6366F1) else Color(0xAAFFFFFF)
  val fillColor = if (isSelected) Color(0x336366F1) else Color(0x22000000)

  // Fill region area with subtle tint
  drawRect(
    color = fillColor,
    topLeft = rect.topLeft,
    size = rect.size
  )

  // Border outline
  val dashEffect = if (isSelected) null else PathEffect.dashPathEffect(floatArrayOf(16f, 12f), 0f)
  drawRect(
    color = strokeColor,
    topLeft = rect.topLeft,
    size = rect.size,
    style = Stroke(
      width = if (isSelected) 3.5f else 2f,
      pathEffect = dashEffect
    )
  )

  // If selected, draw corner handles and corner accents
  if (isSelected) {
    val handleColor = Color(0xFF38BDF8)
    val handleBorder = Color.White
    val radius = 18f

    val corners = listOf(rect.topLeft, rect.topRight, rect.bottomLeft, rect.bottomRight)
    corners.forEach { corner ->
      // Outer shadow/border
      drawCircle(
        color = handleBorder,
        radius = radius + 3f,
        center = corner
      )
      // Inner handle
      drawCircle(
        color = handleColor,
        radius = radius,
        center = corner
      )
    }

    // Center indicator crosshair
    val center = rect.center
    val crossLen = 14f
    drawLine(
      color = Color.White.copy(alpha = 0.8f),
      start = Offset(center.x - crossLen, center.y),
      end = Offset(center.x + crossLen, center.y),
      strokeWidth = 2.5f
    )
    drawLine(
      color = Color.White.copy(alpha = 0.8f),
      start = Offset(center.x, center.y - crossLen),
      end = Offset(center.x, center.y + crossLen),
      strokeWidth = 2.5f
    )
  }
}

private fun calculateContentRect(containerWidth: Float, containerHeight: Float, aspect: Float): Rect {
  if (containerWidth <= 0 || containerHeight <= 0) return Rect.Zero
  val safeAspect = if (aspect <= 0.01f) 16f / 9f else aspect
  val containerAspect = containerWidth / containerHeight

  return if (containerAspect > safeAspect) {
    // Height constrained
    val contentWidth = containerHeight * safeAspect
    val left = (containerWidth - contentWidth) / 2f
    Rect(left, 0f, left + contentWidth, containerHeight)
  } else {
    // Width constrained
    val contentHeight = containerWidth / safeAspect
    val top = (containerHeight - contentHeight) / 2f
    Rect(0f, top, containerWidth, top + contentHeight)
  }
}

private fun toScreenRect(region: WatermarkRegion, contentRect: Rect): Rect {
  val left = contentRect.left + region.left * contentRect.width
  val top = contentRect.top + region.top * contentRect.height
  val right = contentRect.left + region.right * contentRect.width
  val bottom = contentRect.top + region.bottom * contentRect.height
  return Rect(left, top, right, bottom)
}

private fun distance(p1: Offset, p2: Offset): Float {
  val dx = p1.x - p2.x
  val dy = p1.y - p2.y
  return sqrt(dx * dx + dy * dy)
}
