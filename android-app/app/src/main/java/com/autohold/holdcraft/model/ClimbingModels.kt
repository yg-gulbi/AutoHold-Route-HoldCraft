package com.autohold.holdcraft.model

import kotlin.math.abs

enum class RouteMode {
    BOULDERING, // Mode 1: Start / Move / Top 구분
    SEQUENCE    // Mode 2: 터치 순서대로 1, 2, 3... 번호 부여
}

enum class EditorTool {
    SELECT_ROUTE, // 루트 홀드 선택/해제 (기존 터치 모드)
    DRAW_LASSO,   // 누락된 홀드 테두리를 펜처럼 따라 그리는 모드
    DELETE_HOLD   // 잘못 인식된 홀드 객체 삭제
}

enum class HoldRole {
    NONE,
    START,
    MOVE,
    TOP
}

data class Point2D(
    val x: Int,
    val y: Int
)

data class HoldCandidate(
    val id: Int,
    val className: String, // "hold" or "volume"
    val confidence: Float,
    val bbox: List<Int>,   // [left, top, right, bottom] in original image pixel coords
    val polygon: List<Point2D>, // contour points in original image pixel coords
    val colorRgb: Int = 0xFF00E5FF.toInt(), // dominant hold color in ARGB
    val colorName: String = "" // Korean color name (e.g. "빨강", "파랑")
) {
    val centerX: Float get() = (bbox[0] + bbox[2]) / 2f
    val centerY: Float get() = (bbox[1] + bbox[3]) / 2f
    val area: Int get() = ((bbox[2] - bbox[0]).coerceAtLeast(1)) * ((bbox[3] - bbox[1]).coerceAtLeast(1))

    fun contains(px: Float, py: Float): Boolean {
        if (px < bbox[0] || px > bbox[2] || py < bbox[1] || py > bbox[3]) return false
        if (polygon.size < 3) return true
        var inside = false
        var previous = polygon.last()
        for (point in polygon) {
            if ((point.y > py) != (previous.y > py) &&
                px < point.x + (py - point.y) * (previous.x - point.x).toFloat() / (previous.y - point.y).toFloat()
            ) {
                inside = !inside
            }
            previous = point
        }
        return inside
    }
}

data class SelectedHold(
    val holdId: Int,
    val role: HoldRole = HoldRole.MOVE,
    val orderNumber: Int = 0
)

data class ClimbingProblem(
    val id: Long = 0L,
    val title: String,
    val grade: String,
    val mode: RouteMode,
    val imagePath: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val holds: List<HoldCandidate>,
    val selectedHolds: List<SelectedHold>,
    val createdAt: Long = System.currentTimeMillis()
)

