package com.autohold.holdcraft.inference

import com.autohold.holdcraft.model.HoldCandidate
import com.autohold.holdcraft.model.Point2D
import kotlin.math.*

/**
 * YOLO11m-seg ONNX 출력 디코더
 * - output0: [1, 38, 8400] (cx, cy, w, h [0..640px], 2 class scores [0..1], 32 mask coeffs)
 * - output1: [1, 32, 160, 160] (prototype masks)
 * - 4x 바이리니어 업샘플링(160->640) + Chaikin 코너 스무딩을 적용해 세밀하고 매끄러운 홀드 폴리곤을 복원한다.
 */
class YoloSegDecoder(
    private val labels: List<String> = listOf("hold", "volume"),
    private val candidateCount: Int = 8400,
    private val maskSize: Int = 160,
    private val maskChannels: Int = 32,
    private val confThreshold: Float = 0.35f,
    private val iouThreshold: Float = 0.45f,
    private val maxDetections: Int = 180
) {
    private data class RawCandidate(
        val index: Int,
        val label: Int,
        val score: Float,
        val box: FloatArray // [x1, y1, x2, y2] in 640x640 letterbox space
    )

    fun decode(
        output0: FloatArray,
        output1: FloatArray,
        origWidth: Int,
        origHeight: Int
    ): List<HoldCandidate> {
        val totalChannels = 4 + labels.size + maskChannels
        require(output0.size == totalChannels * candidateCount) {
            "Unexpected output0 size: ${output0.size}, expected ${totalChannels * candidateCount}"
        }
        require(output1.size == maskChannels * maskSize * maskSize) {
            "Unexpected output1 size: ${output1.size}, expected ${maskChannels * maskSize * maskSize}"
        }

        val gain = min(640f / origWidth, 640f / origHeight)
        val padX = (640f - origWidth * gain) / 2f
        val padY = (640f - origHeight * gain) / 2f

        var maxCoord = 0f
        for (i in 0 until min(100, candidateCount)) {
            maxCoord = max(maxCoord, output0[i])
        }
        val coordScale = if (maxCoord <= 2.0f) 640f else 1f

        val candidates = ArrayList<RawCandidate>(256)
        for (i in 0 until candidateCount) {
            var bestLabel = 0
            var bestScore = -1f
            for (c in labels.indices) {
                val s = output0[(4 + c) * candidateCount + i]
                if (s > bestScore) {
                    bestScore = s
                    bestLabel = c
                }
            }
            if (!bestScore.isFinite() || bestScore < confThreshold) continue

            val cx = output0[i] * coordScale
            val cy = output0[candidateCount + i] * coordScale
            val bw = output0[2 * candidateCount + i] * coordScale
            val bh = output0[3 * candidateCount + i] * coordScale
            if (bw <= 2f || bh <= 2f) continue

            val x1 = (cx - bw / 2f).coerceIn(0f, 640f)
            val y1 = (cy - bh / 2f).coerceIn(0f, 640f)
            val x2 = (cx + bw / 2f).coerceIn(0f, 640f)
            val y2 = (cy + bh / 2f).coerceIn(0f, 640f)
            if (x2 <= x1 || y2 <= y1) continue

            candidates.add(RawCandidate(i, bestLabel, bestScore, floatArrayOf(x1, y1, x2, y2)))
        }

        candidates.sortByDescending { it.score }
        val kept = ArrayList<RawCandidate>(min(candidates.size, maxDetections))
        for (cand in candidates.take(600)) {
            var suppressed = false
            for (k in kept) {
                if (k.label == cand.label && iou(k.box, cand.box) > iouThreshold) {
                    suppressed = true
                    break
                }
            }
            if (!suppressed) {
                kept.add(cand)
                if (kept.size >= maxDetections) break
            }
        }

        val results = ArrayList<HoldCandidate>(kept.size)
        var nextId = 1
        for (cand in kept) {
            val left = ((cand.box[0] - padX) / gain).roundToInt().coerceIn(0, origWidth - 1)
            val top = ((cand.box[1] - padY) / gain).roundToInt().coerceIn(0, origHeight - 1)
            val right = ((cand.box[2] - padX) / gain).roundToInt().coerceIn(0, origWidth - 1)
            val bottom = ((cand.box[3] - padY) / gain).roundToInt().coerceIn(0, origHeight - 1)
            if (right - left < 4 || bottom - top < 4) continue

            val bbox = listOf(left, top, right, bottom)
            val polygon = extractHighResContour(
                cand, output0, output1, origWidth, origHeight, gain, padX, padY
            ) ?: listOf(
                Point2D(left, top),
                Point2D(right, top),
                Point2D(right, bottom),
                Point2D(left, bottom)
            )

            results.add(
                HoldCandidate(
                    id = nextId++,
                    className = labels[cand.label],
                    confidence = cand.score,
                    bbox = bbox,
                    polygon = polygon
                )
            )
        }
        return results
    }

    private fun iou(a: FloatArray, b: FloatArray): Float {
        val ix1 = max(a[0], b[0])
        val iy1 = max(a[1], b[1])
        val ix2 = min(a[2], b[2])
        val iy2 = min(a[3], b[3])
        val inter = max(0f, ix2 - ix1) * max(0f, iy2 - iy1)
        if (inter <= 0f) return 0f
        val areaA = (a[2] - a[0]) * (a[3] - a[1])
        val areaB = (b[2] - b[0]) * (b[3] - b[1])
        return inter / (areaA + areaB - inter).coerceAtLeast(1e-4f)
    }

    /**
     * 160x160 프로토타입 내적 로짓을 BBox 주변에서 계산한 뒤,
     * 4배(640x640급) 바이리니어 보간(Bilinear Upsampling)과 Chaikin 스무딩으로 정밀 폴리곤을 추출한다.
     */
    private fun extractHighResContour(
        candidate: RawCandidate,
        output0: FloatArray,
        prototypes: FloatArray,
        origWidth: Int,
        origHeight: Int,
        gain: Float,
        padX: Float,
        padY: Float
    ): List<Point2D>? {
        val step = 640f / maskSize // 기본 4.0f
        // 바이리니어 보간 경계 여유분을 위해 1셀씩 확장
        val minMx = ((candidate.box[0] / step).floorToInt() - 1).coerceIn(0, maskSize - 1)
        val maxMx = ((candidate.box[2] / step).ceilToInt() + 1).coerceIn(0, maskSize - 1)
        val minMy = ((candidate.box[1] / step).floorToInt() - 1).coerceIn(0, maskSize - 1)
        val maxMy = ((candidate.box[3] / step).ceilToInt() + 1).coerceIn(0, maskSize - 1)

        val coeffs = FloatArray(maskChannels) { c ->
            output0[(4 + labels.size + c) * candidateCount + candidate.index]
        }

        val coarseW = maxMx - minMx + 1
        val coarseH = maxMy - minMy + 1
        if (coarseW <= 1 || coarseH <= 1) return null

        // 1단계: BBox 국소 영역의 160x160 연속 로짓(float) 계산
        val logits = FloatArray(coarseW * coarseH)
        val planeSize = maskSize * maskSize
        for (cy in 0 until coarseH) {
            val my = minMy + cy
            val protoRow = my * maskSize
            val logitRow = cy * coarseW
            for (cx in 0 until coarseW) {
                val mx = minMx + cx
                val protoIdx = protoRow + mx
                var dot = 0f
                for (c in 0 until maskChannels) {
                    dot += coeffs[c] * prototypes[c * planeSize + protoIdx]
                }
                logits[logitRow + cx] = dot
            }
        }

        // 2단계: 4x 서브픽셀 바이리니어 보간 (640x640 해상도 수준 격자 생성)
        // 대형 볼륨(coarseW > 60)은 연산 속도를 위해 2x 보간, 일반 홀드는 4x 보간 적용
        val upFactor = if (max(coarseW, coarseH) > 64) 2 else 4
        val subStep = step / upFactor
        val fineW = coarseW * upFactor
        val fineH = coarseH * upFactor
        val fineMask = BooleanArray(fineW * fineH)

        fun sampleLogit(fx: Float, fy: Float): Float {
            val x0 = fx.floorToInt().coerceIn(0, coarseW - 1)
            val y0 = fy.floorToInt().coerceIn(0, coarseH - 1)
            val x1 = (x0 + 1).coerceIn(0, coarseW - 1)
            val y1 = (y0 + 1).coerceIn(0, coarseH - 1)
            val dx = (fx - x0).coerceIn(0f, 1f)
            val dy = (fy - y0).coerceIn(0f, 1f)
            val v00 = logits[y0 * coarseW + x0]
            val v10 = logits[y0 * coarseW + x1]
            val v01 = logits[y1 * coarseW + x0]
            val v11 = logits[y1 * coarseW + x1]
            val topVal = v00 + (v10 - v00) * dx
            val botVal = v01 + (v11 - v01) * dx
            return topVal + (botVal - topVal) * dy
        }

        for (fy in 0 until fineH) {
            val pos640Y = minMy * step + (fy + 0.5f) * subStep
            if (pos640Y < candidate.box[1] || pos640Y > candidate.box[3]) continue
            val cPosY = (fy + 0.5f) / upFactor - 0.5f
            val rowOffset = fy * fineW
            for (fx in 0 until fineW) {
                val pos640X = minMx * step + (fx + 0.5f) * subStep
                if (pos640X < candidate.box[0] || pos640X > candidate.box[2]) continue
                val cPosX = (fx + 0.5f) / upFactor - 0.5f
                fineMask[rowOffset + fx] = sampleLogit(cPosX, cPosY) > 0f
            }
        }

        // 3단계: 고해상도 격자 외곽선(Boundary Loop) 추출
        val edges = HashMap<Int, MutableList<Int>>()
        val stride = fineW + 1
        fun filled(x: Int, y: Int): Boolean =
            x in 0 until fineW && y in 0 until fineH && fineMask[y * fineW + x]
        fun addEdge(x1: Int, y1: Int, x2: Int, y2: Int) {
            edges.getOrPut(y1 * stride + x1) { ArrayList(2) }.add(y2 * stride + x2)
        }

        for (y in 0 until fineH) {
            for (x in 0 until fineW) {
                if (filled(x, y)) {
                    if (!filled(x, y - 1)) addEdge(x, y, x + 1, y)
                    if (!filled(x + 1, y)) addEdge(x + 1, y, x + 1, y + 1)
                    if (!filled(x, y + 1)) addEdge(x + 1, y + 1, x, y + 1)
                    if (!filled(x - 1, y)) addEdge(x, y + 1, x, y)
                }
            }
        }

        var bestLoop = emptyList<Int>()
        var bestArea = 0L
        while (edges.isNotEmpty()) {
            val start = edges.keys.first()
            var current = start
            val loop = ArrayList<Int>()
            do {
                loop.add(current)
                val nextList = edges[current] ?: break
                val dest = nextList.removeAt(nextList.lastIndex)
                if (nextList.isEmpty()) edges.remove(current)
                current = dest
            } while (current != start)

            if (current != start || loop.size < 3) continue
            var area2 = 0L
            for (i in loop.indices) {
                val a = loop[i]
                val b = loop[(i + 1) % loop.size]
                area2 += (a % stride).toLong() * (b / stride) - (b % stride).toLong() * (a / stride)
            }
            val absArea = abs(area2)
            if (absArea > bestArea) {
                bestArea = absArea
                bestLoop = loop
            }
        }

        if (bestLoop.size < 3) return null

        // 일직선 중복 정점 제거
        val corners = bestLoop.indices.filter { i ->
            val a = bestLoop[(i + bestLoop.size - 1) % bestLoop.size]
            val b = bestLoop[i]
            val c = bestLoop[(i + 1) % bestLoop.size]
            (b % stride - a % stride) * (c / stride - b / stride) !=
                (b / stride - a / stride) * (c % stride - b % stride)
        }.map { bestLoop[it] }

        val rawPoints = corners.ifEmpty { bestLoop }.map { pt ->
            val gx = minMx * step + (pt % stride) * subStep
            val gy = minMy * step + (pt / stride) * subStep
            val ox = ((gx - padX) / gain).coerceIn(0f, (origWidth - 1).toFloat())
            val oy = ((gy - padY) / gain).coerceIn(0f, (origHeight - 1).toFloat())
            ox to oy
        }

        if (rawPoints.size < 3) return null

        // 4단계: Chaikin 코너 커팅 스무딩(1회)으로 계단 현상을 제거하고 홀드 실제 곡면 복원
        val smoothed = ArrayList<Pair<Float, Float>>(rawPoints.size * 2)
        for (i in rawPoints.indices) {
            val p0 = rawPoints[i]
            val p1 = rawPoints[(i + 1) % rawPoints.size]
            smoothed.add(
                (0.75f * p0.first + 0.25f * p1.first) to (0.75f * p0.second + 0.25f * p1.second)
            )
            smoothed.add(
                (0.25f * p0.first + 0.75f * p1.first) to (0.25f * p0.second + 0.75f * p1.second)
            )
        }

        // 최대 180개 정점까지 고밀도 유지
        val maxVertices = 180
        val sampling = ceil(smoothed.size / maxVertices.toDouble()).toInt().coerceAtLeast(1)
        val polygon = smoothed
            .filterIndexed { index, _ -> index % sampling == 0 }
            .map { (fx, fy) ->
                Point2D(
                    fx.roundToInt().coerceIn(0, origWidth - 1),
                    fy.roundToInt().coerceIn(0, origHeight - 1)
                )
            }
            .distinct()

        return polygon.takeIf { it.size >= 3 }
    }

    private fun Float.floorToInt(): Int = floor(this).toInt()
    private fun Float.ceilToInt(): Int = ceil(this).toInt()
}
