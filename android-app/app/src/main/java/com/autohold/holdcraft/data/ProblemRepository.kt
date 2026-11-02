package com.autohold.holdcraft.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import com.autohold.holdcraft.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class ProblemRepository(private val context: Context) :
    SQLiteOpenHelper(context, "holdcraft_problems.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE problems (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                grade TEXT NOT NULL,
                mode TEXT NOT NULL,
                image_path TEXT NOT NULL,
                image_width INTEGER NOT NULL,
                image_height INTEGER NOT NULL,
                holds_json TEXT NOT NULL,
                selected_json TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS problems")
        onCreate(db)
    }

    fun saveWallBitmap(bitmap: Bitmap): String {
        val dir = File(context.filesDir, "walls")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "wall_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        return file.absolutePath
    }

    fun saveProblem(problem: ClimbingProblem): Long {
        val values = ContentValues().apply {
            put("title", problem.title)
            put("grade", problem.grade)
            put("mode", problem.mode.name)
            put("image_path", problem.imagePath)
            put("image_width", problem.imageWidth)
            put("image_height", problem.imageHeight)
            put("holds_json", serializeHolds(problem.holds))
            put("selected_json", serializeSelected(problem.selectedHolds))
            put("created_at", problem.createdAt)
        }
        return if (problem.id > 0L) {
            writableDatabase.update(
                "problems",
                values,
                "id = ?",
                arrayOf(problem.id.toString())
            )
            problem.id
        } else {
            writableDatabase.insert("problems", null, values)
        }
    }

    fun getAllProblems(): List<ClimbingProblem> {
        val list = ArrayList<ClimbingProblem>()
        readableDatabase.query(
            "problems",
            null,
            null,
            null,
            null,
            null,
            "created_at DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow("id"))
                val title = cursor.getString(cursor.getColumnIndexOrThrow("title"))
                val grade = cursor.getString(cursor.getColumnIndexOrThrow("grade"))
                val modeStr = cursor.getString(cursor.getColumnIndexOrThrow("mode"))
                val imagePath = cursor.getString(cursor.getColumnIndexOrThrow("image_path"))
                val imageWidth = cursor.getInt(cursor.getColumnIndexOrThrow("image_width"))
                val imageHeight = cursor.getInt(cursor.getColumnIndexOrThrow("image_height"))
                val holdsJson = cursor.getString(cursor.getColumnIndexOrThrow("holds_json"))
                val selectedJson = cursor.getString(cursor.getColumnIndexOrThrow("selected_json"))
                val createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))

                val mode = runCatching { RouteMode.valueOf(modeStr) }.getOrDefault(RouteMode.BOULDERING)
                list.add(
                    ClimbingProblem(
                        id = id,
                        title = title,
                        grade = grade,
                        mode = mode,
                        imagePath = imagePath,
                        imageWidth = imageWidth,
                        imageHeight = imageHeight,
                        holds = deserializeHolds(holdsJson),
                        selectedHolds = deserializeSelected(selectedJson),
                        createdAt = createdAt
                    )
                )
            }
        }
        return list
    }

    fun deleteProblem(problem: ClimbingProblem) {
        writableDatabase.delete("problems", "id = ?", arrayOf(problem.id.toString()))
    }

    companion object {
        fun serializeHolds(holds: List<HoldCandidate>): String {
            val arr = JSONArray()
            for (h in holds) {
                val obj = JSONObject()
                obj.put("id", h.id)
                obj.put("className", h.className)
                obj.put("confidence", h.confidence.toDouble())
                val bboxArr = JSONArray()
                h.bbox.forEach { bboxArr.put(it) }
                obj.put("bbox", bboxArr)

                val polyArr = JSONArray()
                for (pt in h.polygon) {
                    val ptArr = JSONArray()
                    ptArr.put(pt.x)
                    ptArr.put(pt.y)
                    polyArr.put(ptArr)
                }
                obj.put("polygon", polyArr)
                obj.put("colorRgb", h.colorRgb)
                obj.put("colorName", h.colorName)
                arr.put(obj)
            }
            return arr.toString()
        }

        fun deserializeHolds(json: String): List<HoldCandidate> {
            val arr = JSONArray(json)
            val result = ArrayList<HoldCandidate>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val bboxArr = obj.getJSONArray("bbox")
                val bbox = listOf(
                    bboxArr.getInt(0),
                    bboxArr.getInt(1),
                    bboxArr.getInt(2),
                    bboxArr.getInt(3)
                )
                val polyArr = obj.getJSONArray("polygon")
                val poly = ArrayList<Point2D>(polyArr.length())
                for (j in 0 until polyArr.length()) {
                    val pt = polyArr.getJSONArray(j)
                    poly.add(Point2D(pt.getInt(0), pt.getInt(1)))
                }
                result.add(
                    HoldCandidate(
                        id = obj.getInt("id"),
                        className = obj.getString("className"),
                        confidence = obj.getDouble("confidence").toFloat(),
                        bbox = bbox,
                        polygon = poly,
                        colorRgb = obj.optInt("colorRgb", 0xFF00E5FF.toInt()),
                        colorName = obj.optString("colorName", "")
                    )
                )
            }
            return result
        }

        fun serializeSelected(selected: List<SelectedHold>): String {
            val arr = JSONArray()
            for (s in selected) {
                val obj = JSONObject()
                obj.put("holdId", s.holdId)
                obj.put("role", s.role.name)
                obj.put("orderNumber", s.orderNumber)
                arr.put(obj)
            }
            return arr.toString()
        }

        fun deserializeSelected(json: String): List<SelectedHold> {
            val arr = JSONArray(json)
            val result = ArrayList<SelectedHold>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val role = runCatching { HoldRole.valueOf(obj.getString("role")) }.getOrDefault(HoldRole.MOVE)
                result.add(
                    SelectedHold(
                        holdId = obj.getInt("holdId"),
                        role = role,
                        orderNumber = obj.optInt("orderNumber", 0)
                    )
                )
            }
            return result
        }
    }
}

