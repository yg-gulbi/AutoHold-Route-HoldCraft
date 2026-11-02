package com.autohold.holdcraft.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import com.autohold.holdcraft.model.*
import kotlin.math.*

@Composable
fun InteractiveWallCanvas(
    bitmap: Bitmap,
    holds: List<HoldCandidate>,
    selectedMap: Map<Int, SelectedHold>,
    mode: RouteMode,
    activeTool: EditorTool = EditorTool.SELECT_ROUTE,
    readOnly: Boolean,
    showUnselectedHolds: Boolean,
    showColorLabels: Boolean = true,
    dimBackground: Boolean,
    onHoldTapped: (HoldCandidate) -> Unit,
    onAddManualHold: (List<Point2D>) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    // 자유 그리기(DRAW_LASSO) 드래그 중 실시간 선 궤적
    var lassoPoints by remember { mutableStateOf<List<Point2D>>(emptyList()) }

    val textPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }

    val subLabelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }

    val isLassoTool = !readOnly && activeTool == EditorTool.DRAW_LASSO

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF101418))
            // 1. 핀치 줌/패닝 제스처 (그리기 모드가 아닐 때 활성화)
            .pointerInput(isLassoTool) {
                if (isLassoTool) return@pointerInput
                detectTransformGestures { _, panChange, zoomChange, _ ->
                    val newZoom = (zoom * zoomChange).coerceIn(1f, 4.5f)
                    val maxPanX = (size.width * (newZoom - 1f)) / 2f
                    val maxPanY = (size.height * (newZoom - 1f)) / 2f
                    val newPanX = (pan.x + panChange.x).coerceIn(-maxPanX, maxPanX)
                    val newPanY = (pan.y + panChange.y).coerceIn(-maxPanY, maxPanY)
                    zoom = newZoom
                    pan = if (newZoom == 1f) Offset.Zero else Offset(newPanX, newPanY)
                }
            }
            // 2. 탭 제스처 (루트 선택 / 객체 삭제)
            .pointerInput(holds, bitmap, readOnly, activeTool, zoom, pan) {
                if (readOnly || isLassoTool) return@pointerInput
                detectTapGestures(
                    onDoubleTap = {
                        zoom = if (zoom > 1.2f) 1f else 2.2f
                        pan = Offset.Zero
                    },
                    onTap = { tapOffset ->
                        val canvasW = size.width.toFloat()
                        val canvasH = size.height.toFloat()
                        val baseScale = min(canvasW / bitmap.width, canvasH / bitmap.height)
                        val effScale = baseScale * zoom
                        val drawW = bitmap.width * effScale
                        val drawH = bitmap.height * effScale
                        val left = (canvasW - drawW) / 2f + pan.x
                        val top = (canvasH - drawH) / 2f + pan.y

                        val imgX = (tapOffset.x - left) / effScale
                        val imgY = (tapOffset.y - top) / effScale
                        if (imgX < 0f || imgX > bitmap.width || imgY < 0f || imgY > bitmap.height) {
                            return@detectTapGestures
                        }

                        val insideCandidates = holds.filter { it.contains(imgX, imgY) }
                        val hit = if (insideCandidates.isNotEmpty()) {
                            insideCandidates.minByOrNull { it.area }
                        } else {
                            val tolerance = 28f / zoom
                            holds.filter {
                                hypot(it.centerX - imgX, it.centerY - imgY) <= tolerance
                            }.minByOrNull {
                                hypot(it.centerX - imgX, it.centerY - imgY)
                            }
                        }
                        if (hit != null) {
                            onHoldTapped(hit)
                        }
                    }
                )
            }
            // 3. 드래그 제스처 (DRAW_LASSO: 홀드 테두리를 펜처럼 그대로 따라 그리기)
            .pointerInput(bitmap, readOnly, activeTool, zoom, pan) {
                if (!isLassoTool) return@pointerInput

                fun screenToImage(offset: Offset): Point2D {
                    val canvasW = size.width.toFloat()
                    val canvasH = size.height.toFloat()
                    val baseScale = min(canvasW / bitmap.width, canvasH / bitmap.height)
                    val effScale = baseScale * zoom
                    val drawW = bitmap.width * effScale
                    val drawH = bitmap.height * effScale
                    val left = (canvasW - drawW) / 2f + pan.x
                    val top = (canvasH - drawH) / 2f + pan.y
                    val ix = ((offset.x - left) / effScale).roundToInt().coerceIn(0, bitmap.width - 1)
                    val iy = ((offset.y - top) / effScale).roundToInt().coerceIn(0, bitmap.height - 1)
                    return Point2D(ix, iy)
                }

                detectDragGestures(
                    onDragStart = { startOffset ->
                        lassoPoints = listOf(screenToImage(startOffset))
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val pt = screenToImage(change.position)
                        val last = lassoPoints.lastOrNull()
                        if (last == null || hypot((pt.x - last.x).toFloat(), (pt.y - last.y).toFloat()) >= 2.0f) {
                            lassoPoints = lassoPoints + pt
                        }
                    },
                    onDragCancel = {
                        lassoPoints = emptyList()
                    },
                    onDragEnd = {
                        val pts = lassoPoints
                        lassoPoints = emptyList()
                        val closedLoop = extractClosedLoopPolygon(pts, bitmap.width, bitmap.height)
                        if (closedLoop != null) {
                            onAddManualHold(closedLoop)
                        }
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasW = size.width
            val canvasH = size.height
            val baseScale = min(canvasW / bitmap.width, canvasH / bitmap.height)
            val effScale = baseScale * zoom
            val drawW = bitmap.width * effScale
            val drawH = bitmap.height * effScale
            val left = (canvasW - drawW) / 2f + pan.x
            val top = (canvasH - drawH) / 2f + pan.y

            fun toScreen(pt: Point2D): Offset = Offset(
                x = left + pt.x * effScale,
                y = top + pt.y * effScale
            )

            // 1. 원본 암벽 이미지 렌더링
            drawIntoCanvas { canvas ->
                val dstRect = android.graphics.RectF(left, top, left + drawW, top + drawH)
                val imgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
                canvas.nativeCanvas.drawBitmap(bitmap, null, dstRect, imgPaint)
                if (dimBackground) {
                    val dimPaint = Paint().apply {
                        color = android.graphics.Color.argb(125, 0, 0, 0)
                    }
                    canvas.nativeCanvas.drawRect(dstRect, dimPaint)
                }
            }

            // 2. 홀드 폴리곤 렌더링
            for (hold in holds) {
                val selected = selectedMap[hold.id]
                val shouldShow = selected != null || showUnselectedHolds || activeTool == EditorTool.DELETE_HOLD
                if (!shouldShow) continue

                val path = Path()
                if (hold.polygon.size >= 3) {
                    val first = toScreen(hold.polygon[0])
                    path.moveTo(first.x, first.y)
                    for (i in 1 until hold.polygon.size) {
                        val p = toScreen(hold.polygon[i])
                        path.lineTo(p.x, p.y)
                    }
                    path.close()
                } else {
                    val tl = toScreen(Point2D(hold.bbox[0], hold.bbox[1]))
                    val br = toScreen(Point2D(hold.bbox[2], hold.bbox[3]))
                    path.addRect(androidx.compose.ui.geometry.Rect(tl.x, tl.y, br.x, br.y))
                }

                val holdColor = Color(hold.colorRgb)

                if (selected != null) {
                    val roleStrokeColor = when (mode) {
                        RouteMode.BOULDERING -> when (selected.role) {
                            HoldRole.START -> Color(0xFF00E676)
                            HoldRole.MOVE -> Color(0xFF00B0FF)
                            HoldRole.TOP -> Color(0xFFFF1744)
                            HoldRole.NONE -> Color.White
                        }
                        RouteMode.SEQUENCE -> Color(0xFFFFD600)
                    }
                    drawPath(
                        path = path,
                        color = holdColor.copy(alpha = 0.55f)
                    )
                    drawPath(
                        path = path,
                        color = Color.Black.copy(alpha = 0.85f),
                        style = Stroke(
                            width = 6.5f * zoom.coerceAtMost(2f),
                            join = StrokeJoin.Round,
                            cap = StrokeCap.Round
                        )
                    )
                    drawPath(
                        path = path,
                        color = roleStrokeColor,
                        style = Stroke(
                            width = 4.0f * zoom.coerceAtMost(2f),
                            join = StrokeJoin.Round,
                            cap = StrokeCap.Round
                        )
                    )
                } else {
                    val borderColor = if (activeTool == EditorTool.DELETE_HOLD) {
                        Color(0xFFFF5252)
                    } else {
                        holdColor
                    }
                    drawPath(
                        path = path,
                        color = holdColor.copy(alpha = 0.34f)
                    )
                    drawPath(
                        path = path,
                        color = Color.Black.copy(alpha = 0.75f),
                        style = Stroke(
                            width = 4.2f,
                            join = StrokeJoin.Round,
                            cap = StrokeCap.Round
                        )
                    )
                    drawPath(
                        path = path,
                        color = borderColor,
                        style = Stroke(
                            width = 2.4f,
                            join = StrokeJoin.Round,
                            cap = StrokeCap.Round
                        )
                    )
                    val centerOffset = Offset(
                        x = left + hold.centerX * effScale,
                        y = top + hold.centerY * effScale
                    )
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.8f),
                        radius = 5.5f,
                        center = centerOffset
                    )
                    drawCircle(
                        color = holdColor,
                        radius = 3.8f,
                        center = centerOffset
                    )
                }
            }

            // 3. 자유 그리기(DRAW_LASSO) 드래그 중: 내부 채우기나 자동 닫힘선 없이 손가락이 지나가는 순수 펜 궤적만 표시!
            if (lassoPoints.size >= 2) {
                val strokePath = Path()
                val first = toScreen(lassoPoints.first())
                strokePath.moveTo(first.x, first.y)
                for (i in 1 until lassoPoints.size) {
                    val p = toScreen(lassoPoints[i])
                    strokePath.lineTo(p.x, p.y)
                }
                drawPath(
                    path = strokePath,
                    color = Color.Black.copy(alpha = 0.75f),
                    style = Stroke(
                        width = 6.0f,
                        join = StrokeJoin.Round,
                        cap = StrokeCap.Round
                    )
                )
                drawPath(
                    path = strokePath,
                    color = Color(0xFF00E5FF),
                    style = Stroke(
                        width = 3.5f,
                        join = StrokeJoin.Round,
                        cap = StrokeCap.Round
                    )
                )
            }

            // 4. SEQUENCE 모드일 경우 선택된 홀드 간 순서 연결선(점선) 표시
            if (mode == RouteMode.SEQUENCE && selectedMap.size >= 2) {
                val ordered = selectedMap.values.sortedBy { it.orderNumber }
                val holdById = holds.associateBy { it.id }
                for (i in 0 until ordered.size - 1) {
                    val h1 = holdById[ordered[i].holdId] ?: continue
                    val h2 = holdById[ordered[i + 1].holdId] ?: continue
                    val p1 = Offset(left + h1.centerX * effScale, top + h1.centerY * effScale)
                    val p2 = Offset(left + h2.centerX * effScale, top + h2.centerY * effScale)
                    drawLine(
                        color = Color(0xAAFFD600),
                        start = p1,
                        end = p2,
                        strokeWidth = 2.8f * zoom.coerceAtMost(2f),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                    )
                }
            }

            // 5. 홀드 색상 라벨 및 선택 배지 렌더링
            drawIntoCanvas { canvas ->
                val holdById = holds.associateBy { it.id }

                if (showColorLabels && showUnselectedHolds) {
                    val labelTextSize = (18f * zoom.coerceIn(0.85f, 1.4f))
                    subLabelPaint.textSize = labelTextSize
                    for (hold in holds) {
                        if (selectedMap.containsKey(hold.id)) continue
                        val label = if (hold.className == "volume") {
                            "볼륨(${hold.colorName.ifEmpty { "벽면" }})"
                        } else {
                            hold.colorName
                        }
                        if (label.isEmpty()) continue

                        val cx = left + hold.centerX * effScale
                        val cy = top + hold.bbox[3] * effScale + labelTextSize + 4f
                        val textW = subLabelPaint.measureText(label)
                        val swatchSize = labelTextSize * 0.75f
                        val totalW = swatchSize + 6f + textW
                        val padH = 7f
                        val padV = 4f

                        val bgRect = android.graphics.RectF(
                            cx - totalW / 2f - padH,
                            cy - labelTextSize - padV + 3f,
                            cx + totalW / 2f + padH,
                            cy + padV
                        )
                        val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.argb(195, 18, 22, 26)
                        }
                        canvas.nativeCanvas.drawRoundRect(bgRect, 8f, 8f, pillBgPaint)

                        val swatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = hold.colorRgb
                        }
                        val swatchStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.WHITE
                            style = Paint.Style.STROKE
                            strokeWidth = 1.5f
                        }
                        val swatchCx = cx - totalW / 2f + swatchSize / 2f
                        val swatchCy = bgRect.centerY()
                        canvas.nativeCanvas.drawCircle(swatchCx, swatchCy, swatchSize / 2f, swatchPaint)
                        canvas.nativeCanvas.drawCircle(swatchCx, swatchCy, swatchSize / 2f, swatchStroke)

                        canvas.nativeCanvas.drawText(
                            label,
                            cx + (swatchSize + 6f) / 2f,
                            cy - 2f,
                            subLabelPaint
                        )
                    }
                }

                for (sel in selectedMap.values) {
                    val hold = holdById[sel.holdId] ?: continue
                    val cx = left + hold.centerX * effScale
                    val cy = top + hold.bbox[1] * effScale - 8f

                    val rolePrefix = when (mode) {
                        RouteMode.BOULDERING -> when (sel.role) {
                            HoldRole.START -> "START"
                            HoldRole.TOP -> "TOP"
                            HoldRole.MOVE -> "MOVE"
                            HoldRole.NONE -> ""
                        }
                        RouteMode.SEQUENCE -> "#${sel.orderNumber}"
                    }
                    if (rolePrefix.isEmpty()) continue

                    val badgeText = if (hold.colorName.isNotEmpty()) {
                        "$rolePrefix · ${hold.colorName}"
                    } else {
                        rolePrefix
                    }

                    val badgeColor = when (mode) {
                        RouteMode.BOULDERING -> when (sel.role) {
                            HoldRole.START -> android.graphics.Color.rgb(0, 200, 83)
                            HoldRole.TOP -> android.graphics.Color.rgb(213, 0, 0)
                            else -> android.graphics.Color.rgb(0, 145, 234)
                        }
                        RouteMode.SEQUENCE -> android.graphics.Color.rgb(255, 143, 0)
                    }

                    val textSizePx = 24f * zoom.coerceIn(0.9f, 1.5f)
                    textPaint.textSize = textSizePx
                    val textWidth = textPaint.measureText(badgeText)
                    val swatchR = textSizePx * 0.36f
                    val totalW = swatchR * 2f + 8f + textWidth
                    val padH = 11f
                    val padV = 6f
                    val bgRect = android.graphics.RectF(
                        cx - totalW / 2f - padH,
                        cy - textSizePx - padV,
                        cx + totalW / 2f + padH,
                        cy + padV
                    )
                    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = badgeColor
                    }
                    canvas.nativeCanvas.drawRoundRect(bgRect, 12f, 12f, bgPaint)

                    val swatchCx = cx - totalW / 2f + swatchR
                    val swatchCy = bgRect.centerY()
                    val swatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = hold.colorRgb
                    }
                    val swatchBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.BLACK
                        style = Paint.Style.STROKE
                        strokeWidth = 2f
                    }
                    canvas.nativeCanvas.drawCircle(swatchCx, swatchCy, swatchR, swatchPaint)
                    canvas.nativeCanvas.drawCircle(swatchCx, swatchCy, swatchR, swatchBorder)

                    canvas.nativeCanvas.drawText(
                        badgeText,
                        cx + (swatchR * 2f + 8f) / 2f,
                        cy - 2f,
                        textPaint
                    )
                }
            }
        }
    }
}

/**
 * 사용자가 그린 자유 궤적(raw)에서 선분이 서로 교차하거나 맞닿아 닫힌 '폐루프(Closed Loop)' 구간만 추출하고,
 * 시작/끝의 삐져나온 꼬리(tail)는 모두 잘라낸다. 폐루프가 형성되지 않은 단순 직선/열린 선은 null을 반환한다.
 */
private fun extractClosedLoopPolygon(
    raw: List<Point2D>,
    imgW: Int,
    imgH: Int
): List<Point2D>? {
    if (raw.size < 6) return null

    // 1. 자기 교차(Self-Intersection) 탐색: 선분 i(p1..p2)와 선분 j(p3..p4)가 실제로 교차하는 가장 큰 루프 구간 추출
    var bestStartIdx = -1
    var bestEndIdx = -1
    var bestCrossPt: Point2D? = null
    var maxLoopSpan = 0

    for (i in 0 until raw.size - 3) {
        val a = raw[i]
        val b = raw[i + 1]
        for (j in i + 2 until raw.size - 1) {
            val c = raw[j]
            val d = raw[j + 1]
            val cross = segmentIntersection(a, b, c, d)
            if (cross != null) {
                val span = j - i
                if (span > maxLoopSpan) {
                    maxLoopSpan = span
                    bestStartIdx = i + 1
                    bestEndIdx = j
                    bestCrossPt = cross
                }
            }
        }
    }

    val loopPoints: List<Point2D> = if (bestCrossPt != null && bestStartIdx <= bestEndIdx) {
        // 교차점부터 교차점까지의 순수 폐루프 구간만 추출 (앞뒤로 삐져나온 꼬리 완벽 제거)
        buildList {
            add(bestCrossPt)
            for (k in bestStartIdx..bestEndIdx) {
                add(raw[k])
            }
        }
    } else {
        // 2. 선분이 직접 교차하지 않았더라도 시작부(앞쪽 25%)와 끝부(뒤쪽 25%)가 충분히 가까워져 닫힌 경우 검사
        val minX = raw.minOf { it.x }
        val maxX = raw.maxOf { it.x }
        val minY = raw.minOf { it.y }
        val maxY = raw.maxOf { it.y }
        val diag = hypot((maxX - minX).toFloat(), (maxY - minY).toFloat())
        if (diag < 8f) return null

        val headLimit = (raw.size * 0.30f).toInt().coerceAtLeast(1)
        val tailStart = (raw.size * 0.70f).toInt().coerceAtMost(raw.size - 1)
        var minGap = Float.MAX_VALUE
        var closeI = 0
        var closeJ = raw.lastIndex
        for (i in 0..headLimit) {
            for (j in tailStart until raw.size) {
                if (j - i < 4) continue
                val d = hypot((raw[i].x - raw[j].x).toFloat(), (raw[i].y - raw[j].y).toFloat())
                if (d < minGap) {
                    minGap = d
                    closeI = i
                    closeJ = j
                }
            }
        }
        // 시작점과 끝점 사이 거리가 도형 대각선의 28% 이내(또는 36px 이내)로 닫혔을 때만 폐루프로 인정
        val closeThreshold = max(36f, diag * 0.28f)
        if (minGap > closeThreshold) {
            return null // 열린 선(직선/곡선)은 홀드를 생성하지 않음
        }
        raw.subList(closeI, closeJ + 1)
    }

    if (loopPoints.size < 3) return null
    val lMinX = loopPoints.minOf { it.x }
    val lMaxX = loopPoints.maxOf { it.x }
    val lMinY = loopPoints.minOf { it.y }
    val lMaxY = loopPoints.maxOf { it.y }
    if (lMaxX - lMinX < 5 || lMaxY - lMinY < 5) return null

    // Chaikin 코너 스무딩 1회 적용하여 손떨림을 매끄러운 윤곽선으로 보정
    val smoothed = ArrayList<Point2D>(loopPoints.size * 2)
    for (i in loopPoints.indices) {
        val p0 = loopPoints[i]
        val p1 = loopPoints[(i + 1) % loopPoints.size]
        smoothed.add(
            Point2D(
                (0.75f * p0.x + 0.25f * p1.x).roundToInt().coerceIn(0, imgW - 1),
                (0.75f * p0.y + 0.25f * p1.y).roundToInt().coerceIn(0, imgH - 1)
            )
        )
        smoothed.add(
            Point2D(
                (0.25f * p0.x + 0.75f * p1.x).roundToInt().coerceIn(0, imgW - 1),
                (0.25f * p0.y + 0.75f * p1.y).roundToInt().coerceIn(0, imgH - 1)
            )
        )
    }
    return smoothed.distinct().takeIf { it.size >= 3 }
}

private fun segmentIntersection(a: Point2D, b: Point2D, c: Point2D, d: Point2D): Point2D? {
    val rX = (b.x - a.x).toFloat()
    val rY = (b.y - a.y).toFloat()
    val sX = (d.x - c.x).toFloat()
    val sY = (d.y - c.y).toFloat()
    val denom = rX * sY - rY * sX
    if (abs(denom) < 1e-4f) return null

    val qpX = (c.x - a.x).toFloat()
    val qpY = (c.y - a.y).toFloat()
    val t = (qpX * sY - qpY * sX) / denom
    val u = (qpX * rY - qpY * rX) / denom
    if (t in 0f..1f && u in 0f..1f) {
        return Point2D(
            (a.x + t * rX).roundToInt(),
            (a.y + t * rY).roundToInt()
        )
    }
    return null
}
