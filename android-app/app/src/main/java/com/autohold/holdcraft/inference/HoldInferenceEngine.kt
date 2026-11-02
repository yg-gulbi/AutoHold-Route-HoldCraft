package com.autohold.holdcraft.inference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.autohold.holdcraft.model.HoldCandidate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

class HoldInferenceEngine(context: Context) : AutoCloseable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val decoder = YoloSegDecoder()

    private val letterboxBitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(640 * 640)
    private val inputFloats = FloatArray(3 * 640 * 640)
    private val inputBuffer = ByteBuffer.allocateDirect(3 * 640 * 640 * 4).order(ByteOrder.nativeOrder())

    init {
        val modelBytes = context.assets.open("yolo11m_seg_climbing.onnx").use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
        }
        session = env.createSession(modelBytes, options)
        options.close()
    }

    @Synchronized
    fun detectHolds(bitmap: Bitmap): List<HoldCandidate> {
        val t0 = SystemClock.elapsedRealtime()
        val origWidth = bitmap.width
        val origHeight = bitmap.height

        val gain = min(640f / origWidth, 640f / origHeight)
        val left = (640f - origWidth * gain) / 2f
        val top = (640f - origHeight * gain) / 2f

        val canvas = Canvas(letterboxBitmap)
        canvas.drawColor(Color.rgb(114, 114, 114))
        canvas.drawBitmap(
            bitmap,
            null,
            RectF(left, top, left + origWidth * gain, top + origHeight * gain),
            paint
        )

        letterboxBitmap.getPixels(pixels, 0, 640, 0, 0, 640, 640)
        val count = 640 * 640
        val offsetG = count
        val offsetB = count * 2

        for (i in 0 until count) {
            val p = pixels[i]
            inputFloats[i] = ((p shr 16) and 0xFF) / 255f
            inputFloats[offsetG + i] = ((p shr 8) and 0xFF) / 255f
            inputFloats[offsetB + i] = (p and 0xFF) / 255f
        }

        inputBuffer.rewind()
        inputBuffer.asFloatBuffer().put(inputFloats)
        inputBuffer.rewind()

        OnnxTensor.createTensor(env, inputBuffer.asFloatBuffer(), longArrayOf(1, 3, 640, 640)).use { inputTensor ->
            val inputName = session.inputNames.first()
            session.run(mapOf(inputName to inputTensor)).use { result ->
                val out0Tensor = result[0] as OnnxTensor
                val out1Tensor = result[1] as OnnxTensor

                val out0Floats = FloatArray(38 * 8400)
                val out1Floats = FloatArray(32 * 160 * 160)
                out0Tensor.floatBuffer.get(out0Floats)
                out1Tensor.floatBuffer.get(out1Floats)

                val tInfer = SystemClock.elapsedRealtime()
                val rawHolds = decoder.decode(out0Floats, out1Floats, origWidth, origHeight)
                val holds = HoldColorExtractor.enrichWithColors(bitmap, rawHolds)
                val tDecode = SystemClock.elapsedRealtime()
                Log.i(
                    "HoldInferenceEngine",
                    "Detected ${holds.size} holds (infer=${tInfer - t0}ms, decode=${tDecode - tInfer}ms)"
                )
                return holds
            }
        }
    }

    override fun close() {
        session.close()
        letterboxBitmap.recycle()
    }
}

