package com.example.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.example.model.RemovalMode
import com.example.model.WatermarkRegion
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object WatermarkFilter {

  /**
   * Applies the watermark removal algorithms for all given regions directly to a mutable Bitmap copy.
   */
  fun processBitmap(source: Bitmap, regions: List<WatermarkRegion>): Bitmap {
    if (regions.isEmpty()) return source

    val result = source.copy(Bitmap.Config.ARGB_8888, true)
    val width = result.width
    val height = result.height

    for (region in regions) {
      applyRegion(result, region, width, height)
    }
    return result
  }

  private fun applyRegion(bitmap: Bitmap, region: WatermarkRegion, width: Int, height: Int) {
    val pxLeft = (region.left * width).roundToInt().coerceIn(0, width - 2)
    val pxTop = (region.top * height).roundToInt().coerceIn(0, height - 2)
    val pxRight = (region.right * width).roundToInt().coerceIn(pxLeft + 2, width)
    val pxBottom = (region.bottom * height).roundToInt().coerceIn(pxTop + 2, height)

    val regionWidth = pxRight - pxLeft
    val regionHeight = pxBottom - pxTop
    if (regionWidth <= 2 || regionHeight <= 2) return

    when (region.mode) {
      RemovalMode.INPAINT -> applySmartInpaint(bitmap, pxLeft, pxTop, pxRight, pxBottom, region.feather)
      RemovalMode.BLUR -> applyBlur(bitmap, pxLeft, pxTop, pxRight, pxBottom, region.intensity, region.feather)
      RemovalMode.MOSAIC -> applyMosaic(bitmap, pxLeft, pxTop, pxRight, pxBottom, region.intensity, region.feather)
      RemovalMode.SOLID_FILL -> applySolidFill(bitmap, pxLeft, pxTop, pxRight, pxBottom, region.feather)
    }
  }

  /**
   * Bilinear edge-guided inpainting with distance weights and boundary feathering.
   */
  private fun applySmartInpaint(
    bitmap: Bitmap,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    feather: Float
  ) {
    val width = right - left
    val height = bottom - top
    val bmpWidth = bitmap.width
    val bmpHeight = bitmap.height

    val featherPx = ((min(width, height) / 2) * feather).roundToInt().coerceAtLeast(1)

    // Sample boundary pixels (clamped to bitmap edges)
    val topBorder = IntArray(width) { xOffset ->
      val sampleY = (top - 1).coerceIn(0, bmpHeight - 1)
      bitmap.getPixel((left + xOffset).coerceIn(0, bmpWidth - 1), sampleY)
    }
    val bottomBorder = IntArray(width) { xOffset ->
      val sampleY = bottom.coerceIn(0, bmpHeight - 1)
      bitmap.getPixel((left + xOffset).coerceIn(0, bmpWidth - 1), sampleY)
    }
    val leftBorder = IntArray(height) { yOffset ->
      val sampleX = (left - 1).coerceIn(0, bmpWidth - 1)
      bitmap.getPixel(sampleX, (top + yOffset).coerceIn(0, bmpHeight - 1))
    }
    val rightBorder = IntArray(height) { yOffset ->
      val sampleX = right.coerceIn(0, bmpWidth - 1)
      bitmap.getPixel(sampleX, (top + yOffset).coerceIn(0, bmpHeight - 1))
    }

    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, left, top, width, height)

    for (y in 0 until height) {
      val dTop = (y + 1).toFloat()
      val dBottom = (height - y).toFloat()

      for (x in 0 until width) {
        val dLeft = (x + 1).toFloat()
        val dRight = (width - x).toFloat()

        // Inverse distance weights
        val wL = 1.0f / (dLeft * dLeft + 1f)
        val wR = 1.0f / (dRight * dRight + 1f)
        val wT = 1.0f / (dTop * dTop + 1f)
        val wB = 1.0f / (dBottom * dBottom + 1f)
        val totalWeight = wL + wR + wT + wB

        val cL = leftBorder[y]
        val cR = rightBorder[y]
        val cT = topBorder[x]
        val cB = bottomBorder[x]

        val r = (Color.red(cL) * wL + Color.red(cR) * wR + Color.red(cT) * wT + Color.red(cB) * wB) / totalWeight
        val g = (Color.green(cL) * wL + Color.green(cR) * wR + Color.green(cT) * wT + Color.green(cB) * wB) / totalWeight
        val b = (Color.blue(cL) * wL + Color.blue(cR) * wR + Color.blue(cT) * wT + Color.blue(cB) * wB) / totalWeight

        // Calculate feather alpha near boundaries
        val distToEdge = min(min(x, width - 1 - x), min(y, height - 1 - y))
        val alphaBlend = (distToEdge.toFloat() / featherPx).coerceIn(0f, 1f)

        val origColor = pixels[y * width + x]
        val origR = Color.red(origColor)
        val origG = Color.green(origColor)
        val origB = Color.blue(origColor)

        val finalR = (r * alphaBlend + origR * (1f - alphaBlend)).roundToInt().coerceIn(0, 255)
        val finalG = (g * alphaBlend + origG * (1f - alphaBlend)).roundToInt().coerceIn(0, 255)
        val finalB = (b * alphaBlend + origB * (1f - alphaBlend)).roundToInt().coerceIn(0, 255)

        pixels[y * width + x] = Color.rgb(finalR, finalG, finalB)
      }
    }

    bitmap.setPixels(pixels, 0, width, left, top, width, height)
  }

  /**
   * Fast multi-pass box blur with downsampling and soft edge feathering.
   */
  private fun applyBlur(
    bitmap: Bitmap,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    intensity: Float,
    feather: Float
  ) {
    val width = right - left
    val height = bottom - top
    val regionBitmap = Bitmap.createBitmap(bitmap, left, top, width, height)

    // Downscale factor based on intensity (4x to 24x)
    val scaleFactor = (4 + (intensity * 20f)).roundToInt().coerceIn(2, 32)
    val smallW = max(1, width / scaleFactor)
    val smallH = max(1, height / scaleFactor)

    val scaledDown = Bitmap.createScaledBitmap(regionBitmap, smallW, smallH, true)
    val blurred = Bitmap.createScaledBitmap(scaledDown, width, height, true)
    scaledDown.recycle()
    regionBitmap.recycle()

    // Blend back with feathering
    blendBackWithFeather(bitmap, blurred, left, top, width, height, feather)
    blurred.recycle()
  }

  /**
   * Pixelates the region into mosaic blocks with adjustable tile size.
   */
  private fun applyMosaic(
    bitmap: Bitmap,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    intensity: Float,
    feather: Float
  ) {
    val width = right - left
    val height = bottom - top
    val regionBitmap = Bitmap.createBitmap(bitmap, left, top, width, height)

    // Block size from 8px to 36px depending on intensity
    val blockSize = (6 + (intensity * 30f)).roundToInt().coerceAtLeast(4)
    val blocksX = max(1, width / blockSize)
    val blocksY = max(1, height / blockSize)

    // Nearest-neighbor scaling for sharp pixel blocks
    val pixelatedSmall = Bitmap.createScaledBitmap(regionBitmap, blocksX, blocksY, false)
    val mosaic = Bitmap.createScaledBitmap(pixelatedSmall, width, height, false)
    pixelatedSmall.recycle()
    regionBitmap.recycle()

    blendBackWithFeather(bitmap, mosaic, left, top, width, height, feather)
    mosaic.recycle()
  }

  /**
   * Fills region with the perimeter average color and feathers inward.
   */
  private fun applySolidFill(
    bitmap: Bitmap,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    feather: Float
  ) {
    val width = right - left
    val height = bottom - top
    val bmpWidth = bitmap.width
    val bmpHeight = bitmap.height

    var sumR = 0L
    var sumG = 0L
    var sumB = 0L
    var sampleCount = 0

    // Sample perimeter
    for (x in left until right step max(1, width / 20)) {
      val yTop = (top - 1).coerceIn(0, bmpHeight - 1)
      val yBottom = bottom.coerceIn(0, bmpHeight - 1)
      val cT = bitmap.getPixel(x.coerceIn(0, bmpWidth - 1), yTop)
      val cB = bitmap.getPixel(x.coerceIn(0, bmpWidth - 1), yBottom)
      sumR += Color.red(cT) + Color.red(cB)
      sumG += Color.green(cT) + Color.green(cB)
      sumB += Color.blue(cT) + Color.blue(cB)
      sampleCount += 2
    }
    for (y in top until bottom step max(1, height / 20)) {
      val xLeft = (left - 1).coerceIn(0, bmpWidth - 1)
      val xRight = right.coerceIn(0, bmpWidth - 1)
      val cL = bitmap.getPixel(xLeft, y.coerceIn(0, bmpHeight - 1))
      val cR = bitmap.getPixel(xRight, y.coerceIn(0, bmpHeight - 1))
      sumR += Color.red(cL) + Color.red(cR)
      sumG += Color.green(cL) + Color.green(cR)
      sumB += Color.blue(cL) + Color.blue(cR)
      sampleCount += 2
    }

    val avgColor = if (sampleCount > 0) {
      Color.rgb(
        (sumR / sampleCount).toInt().coerceIn(0, 255),
        (sumG / sampleCount).toInt().coerceIn(0, 255),
        (sumB / sampleCount).toInt().coerceIn(0, 255)
      )
    } else {
      Color.DKGRAY
    }

    val solidBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(solidBitmap)
    val paint = Paint().apply { color = avgColor }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

    blendBackWithFeather(bitmap, solidBitmap, left, top, width, height, feather)
    solidBitmap.recycle()
  }

  private fun blendBackWithFeather(
    target: Bitmap,
    patch: Bitmap,
    left: Int,
    top: Int,
    width: Int,
    height: Int,
    feather: Float
  ) {
    val featherPx = ((min(width, height) / 2) * feather).roundToInt().coerceAtLeast(1)

    val targetPixels = IntArray(width * height)
    val patchPixels = IntArray(width * height)
    target.getPixels(targetPixels, 0, width, left, top, width, height)
    patch.getPixels(patchPixels, 0, width, 0, 0, width, height)

    for (y in 0 until height) {
      for (x in 0 until width) {
        val distToEdge = min(min(x, width - 1 - x), min(y, height - 1 - y))
        val alphaBlend = (distToEdge.toFloat() / featherPx).coerceIn(0f, 1f)

        if (alphaBlend >= 0.999f) {
          targetPixels[y * width + x] = patchPixels[y * width + x]
        } else if (alphaBlend > 0f) {
          val pColor = patchPixels[y * width + x]
          val tColor = targetPixels[y * width + x]

          val r = (Color.red(pColor) * alphaBlend + Color.red(tColor) * (1f - alphaBlend)).roundToInt().coerceIn(0, 255)
          val g = (Color.green(pColor) * alphaBlend + Color.green(tColor) * (1f - alphaBlend)).roundToInt().coerceIn(0, 255)
          val b = (Color.blue(pColor) * alphaBlend + Color.blue(tColor) * (1f - alphaBlend)).roundToInt().coerceIn(0, 255)

          targetPixels[y * width + x] = Color.rgb(r, g, b)
        }
      }
    }

    target.setPixels(targetPixels, 0, width, left, top, width, height)
  }
}
