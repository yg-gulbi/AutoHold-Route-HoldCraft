package com.autohold.holdcraft.inference

import android.graphics.Bitmap
import android.graphics.Color
import com.autohold.holdcraft.model.HoldCandidate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object HoldColorExtractor {

    fun enrichWithColors(bitmap: Bitmap, holds: List<HoldCandidate>): List<HoldCandidate> {
        val width = bitmap.width
        val height = bitmap.height
        return holds.map { hold ->
            enrichSingleHold(bitmap, hold, width, height)
        }
    }

    fun enrichSingleHold(
        bitmap: Bitmap,
        hold: HoldCandidate,
        width: Int = bitmap.width,
        height: Int = bitmap.height
    ): HoldCandidate {
        val (rgb, name) = extractHoldColor(bitmap, hold, width, height)
        return hold.copy(colorRgb = rgb, colorName = name)
    }

    private fun extractHoldColor(
        bitmap: Bitmap,
        hold: HoldCandidate,
        width: Int,
        height: Int
    ): Pair<Int, String> {
        val left = hold.bbox[0].coerceIn(0, width - 1)
        val top = hold.bbox[1].coerceIn(0, height - 1)
        val right = hold.bbox[2].coerceIn(0, width - 1)
        val bottom = hold.bbox[3].coerceIn(0, height - 1)

        val bw = (right - left).coerceAtLeast(1)
        val bh = (bottom - top).coerceAtLeast(1)
        // 중심부 70% 영역을 집중 샘플링하여 벽면 배경 픽셀 혼입 최소화
        val marginX = (bw * 0.15f).roundToInt()
        val marginY = (bh * 0.15f).roundToInt()
        val sLeft = (left + marginX).coerceAtMost(right)
        val sRight = (right - marginX).coerceAtLeast(sLeft)
        val sTop = (top + marginY).coerceAtMost(bottom)
        val sBottom = (bottom - marginY).coerceAtLeast(sTop)

        val stepX = max(1, (sRight - sLeft) / 8)
        val stepY = max(1, (sBottom - sTop) / 8)

        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var weightSum = 0L

        val hsv = FloatArray(3)
        for (y in sTop..sBottom step stepY) {
            for (x in sLeft..sRight step stepX) {
                if (!hold.contains(x.toFloat(), y.toFloat())) continue
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                Color.RGBToHSV(r, g, b, hsv)
                // 채도(Saturation)가 높은 픽셀에 가중치를 부여하여 회색 암벽 먼지/초크 영향 제거
                val weight = (1 + (hsv[1] * 10f).roundToInt()).toLong()
                rSum += r * weight
                gSum += g * weight
                bSum += b * weight
                weightSum += weight
            }
        }

        if (weightSum == 0L) {
            val cx = ((left + right) / 2).coerceIn(0, width - 1)
            val cy = ((top + bottom) / 2).coerceIn(0, height - 1)
            val p = bitmap.getPixel(cx, cy)
            return classifyAndBoostColor(Color.red(p), Color.green(p), Color.blue(p))
        }

        val avgR = (rSum / weightSum).toInt().coerceIn(0, 255)
        val avgG = (gSum / weightSum).toInt().coerceIn(0, 255)
        val avgB = (bSum / weightSum).toInt().coerceIn(0, 255)
        return classifyAndBoostColor(avgR, avgG, avgB)
    }

    private fun classifyAndBoostColor(r: Int, g: Int, b: Int): Pair<Int, String> {
        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)
        val h = hsv[0] // 0..360
        val s = hsv[1] // 0..1
        val v = hsv[2] // 0..1

        val colorName = when {
            v < 0.22f -> "검정"
            s < 0.18f && v > 0.78f -> "흰색"
            s < 0.20f -> "회색/볼륨"
            h < 14f || h >= 345f -> "빨강"
            h < 38f -> if (s < 0.55f && v < 0.75f) "나무/갈색" else "주황"
            h < 68f -> "노랑"
            h < 165f -> "초록"
            h < 200f -> "민트/하늘"
            h < 255f -> "파랑"
            h < 295f -> "보라"
            else -> "핑크"
        }

        // 화면에서 홀드 인식이 한눈에 보이도록 채도와 명도를 선명하게 보정한 표시용 RGB 생성
        val boostedHsv = floatArrayOf(
            h,
            if (s < 0.18f) s else max(0.75f, min(1.0f, s * 1.25f)),
            when {
                v < 0.22f -> 0.25f
                s < 0.18f && v > 0.78f -> 0.95f
                else -> max(0.88f, min(1.0f, v * 1.15f))
            }
        )
        val displayColor = Color.HSVToColor(255, boostedHsv)
        return displayColor to colorName
    }
}

