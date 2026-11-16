package com.example.editor.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.example.diagnostics.CrashLogger
import com.example.editor.model.ChromaKeyBgStyle
import com.example.editor.model.ChromaKeyConfig
import com.example.editor.model.ChromaKeyMode
import com.example.editor.model.TimelineVideoClip
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HybridChromaKeyProcessor {

    private var activeMode: ChromaKeyMode? = null
    private var selfieSegmenter: Segmenter? = null
    private var objectDetector: ObjectDetector? = null

    /**
     * Strictly guarantees Moving (Selfie Segmentation) and Still (Object Detection)
     * NEVER run concurrently or remain open together.
     */
    @Synchronized
    private fun ensureExclusiveDetector(mode: ChromaKeyMode) {
        if (activeMode == mode) return
        closeAllDetectors()
        activeMode = mode
        when (mode) {
            ChromaKeyMode.MOVING -> {
                val opts = SelfieSegmenterOptions.Builder()
                    .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                    .build()
                selfieSegmenter = Segmentation.getClient(opts)
            }
            ChromaKeyMode.STILL -> {
                val opts = ObjectDetectorOptions.Builder()
                    .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                    .enableMultipleObjects()
                    .build()
                objectDetector = ObjectDetection.getClient(opts)
            }
        }
    }

    @Synchronized
    fun closeAllDetectors() {
        runCatching { selfieSegmenter?.close() }
        selfieSegmenter = null
        runCatching { objectDetector?.close() }
        objectDetector = null
        activeMode = null
    }

    suspend fun previewSegmentedFrame(
        sourceFrame: Bitmap,
        config: ChromaKeyConfig
    ): Bitmap = withContext(Dispatchers.Default) {
        val alphaMask = computeAlphaMaskBitmap(sourceFrame, config.mode)
        compositePreview(sourceFrame, alphaMask, config.bgStyle)
    }

    suspend fun generateAlphaMatteVideo(
        context: Context,
        stagedVideoFile: File,
        clip: TimelineVideoClip,
        outWidth: Int,
        outHeight: Int,
        onProgress: (Float) -> Unit
    ): File? = withContext(Dispatchers.Default) {
        val matteDir = File(context.cacheDir, "editor_chroma_frames_${System.currentTimeMillis()}").apply {
            mkdirs()
        }
        val outMatteVideo = File(context.cacheDir, "editor_matte_${clip.id}_${System.currentTimeMillis()}.mp4")
        val retriever = MediaMetadataRetriever()

        try {
            retriever.setDataSource(stagedVideoFile.absolutePath)
            val sampleFps = 10
            val totalEffectiveMs = clip.effectiveDurationMs.coerceAtLeast(500L)
            val totalFrames = ((totalEffectiveMs / 1000.0) * sampleFps).toInt().coerceIn(2, 450)
            val analysisW = 320
            val analysisH = ((analysisW.toFloat() * outHeight) / outWidth.coerceAtLeast(1)).toInt().coerceIn(180, 640)

            for (i in 0 until totalFrames) {
                val frac = i.toFloat() / (totalFrames - 1).coerceAtLeast(1)
                val sourceOffsetMs = (clip.sourceSpanMs * frac).toLong()
                val rawTimeMs = if (clip.isReversed) {
                    (clip.trimEndMs - sourceOffsetMs).coerceIn(clip.trimStartMs, clip.trimEndMs)
                } else {
                    (clip.trimStartMs + sourceOffsetMs).coerceIn(clip.trimStartMs, clip.trimEndMs)
                }

                val sampled = retriever.getFrameAtTime(
                    rawTimeMs * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
                val frame = if (sampled != null) {
                    Bitmap.createScaledBitmap(sampled, analysisW, analysisH, true)
                } else {
                    Bitmap.createBitmap(analysisW, analysisH, Bitmap.Config.ARGB_8888)
                }
                val mask = computeAlphaMaskBitmap(frame, clip.chromaKey.mode)
                val frameFile = File(matteDir, String.format(Locale.US, "mask_%05d.png", i))
                frameFile.outputStream().use { out ->
                    mask.compress(Bitmap.CompressFormat.PNG, 90, out)
                }
                if (sampled != null && sampled !== frame) sampled.recycle()
                frame.recycle()
                mask.recycle()
                onProgress((i + 1).toFloat() / totalFrames.toFloat())
            }

            val pattern = File(matteDir, "mask_%05d.png").absolutePath
            val args = arrayOf(
                "-y",
                "-framerate", sampleFps.toString(),
                "-i", pattern,
                "-vf", "scale=$outWidth:$outHeight:flags=lanczos,format=yuv420p",
                "-c:v", "mpeg4",
                "-q:v", "4",
                outMatteVideo.absolutePath
            )
            val session = FFmpegKit.executeWithArguments(args)
            if (ReturnCode.isSuccess(session.returnCode) && outMatteVideo.exists() && outMatteVideo.length() > 0L) {
                outMatteVideo
            } else {
                CrashLogger.logFfmpegFailure(
                    operation = "BuildChromaMatte",
                    command = args.joinToString(" "),
                    returnCode = session.returnCode?.toString(),
                    logs = session.allLogsAsString ?: ""
                )
                null
            }
        } catch (t: Throwable) {
            CrashLogger.logError("HybridChromaKeyProcessor", "Failed generating alpha matte", t)
            null
        } finally {
            runCatching { retriever.release() }
            runCatching { matteDir.deleteRecursively() }
        }
    }

    private suspend fun computeAlphaMaskBitmap(frame: Bitmap, mode: ChromaKeyMode): Bitmap {
        ensureExclusiveDetector(mode)
        val inputImage = InputImage.fromBitmap(frame, 0)
        val w = frame.width
        val h = frame.height
        val maskBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        return when (mode) {
            ChromaKeyMode.MOVING -> {
                val segmenter = selfieSegmenter ?: return fillWhiteMask(maskBitmap)
                val segMask = runCatching { segmenter.process(inputImage).awaitResult() }.getOrNull()
                    ?: return fillWhiteMask(maskBitmap)
                val buf = segMask.buffer
                val maskW = segMask.width
                val maskH = segMask.height
                buf.rewind()
                val pixels = IntArray(maskW * maskH)
                for (i in pixels.indices) {
                    val conf = buf.float.coerceIn(0f, 1f)
                    val gray = if (conf > 0.35f) ((conf * 255f).toInt().coerceIn(0, 255)) else 0
                    pixels[i] = Color.rgb(gray, gray, gray)
                }
                val rawMask = Bitmap.createBitmap(pixels, maskW, maskH, Bitmap.Config.ARGB_8888)
                val scaled = Bitmap.createScaledBitmap(rawMask, w, h, true)
                rawMask.recycle()
                maskBitmap.recycle()
                scaled
            }
            ChromaKeyMode.STILL -> {
                val detector = objectDetector ?: return fillWhiteMask(maskBitmap)
                val objects = runCatching { detector.process(inputImage).awaitResult() }.getOrNull()
                    .orEmpty()
                val canvas = Canvas(maskBitmap)
                canvas.drawColor(Color.BLACK)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.FILL
                }
                if (objects.isEmpty()) {
                    // Graceful center-subject spotlight fallback if static scene has low contrast
                    val padX = w * 0.16f
                    val padY = h * 0.12f
                    canvas.drawRoundRect(
                        RectF(padX, padY, w - padX, h - padY),
                        36f,
                        36f,
                        paint
                    )
                } else {
                    objects.forEach { obj ->
                        val box = RectF(obj.boundingBox)
                        canvas.drawRoundRect(box, 28f, 28f, paint)
                    }
                }
                maskBitmap
            }
        }
    }

    private fun fillWhiteMask(bitmap: Bitmap): Bitmap {
        Canvas(bitmap).drawColor(Color.WHITE)
        return bitmap
    }

    private fun compositePreview(
        src: Bitmap,
        alphaMask: Bitmap,
        bgStyle: ChromaKeyBgStyle
    ): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        // Draw target background style
        when (bgStyle) {
            ChromaKeyBgStyle.TRANSPARENT_DARK -> {
                val p = Paint()
                val tile = 24
                for (y in 0 until h step tile) {
                    for (x in 0 until w step tile) {
                        val even = ((x / tile) + (y / tile)) % 2 == 0
                        p.color = if (even) Color.rgb(18, 26, 44) else Color.rgb(26, 36, 58)
                        canvas.drawRect(x.toFloat(), y.toFloat(), (x + tile).toFloat(), (y + tile).toFloat(), p)
                    }
                }
            }
            ChromaKeyBgStyle.NEON_GAMING -> {
                val grad = Paint().apply {
                    shader = LinearGradient(
                        0f, 0f, w.toFloat(), h.toFloat(),
                        Color.rgb(22, 18, 66), Color.rgb(10, 48, 92), Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), grad)
            }
            ChromaKeyBgStyle.BLUR_BG -> {
                val dim = Paint().apply { color = Color.rgb(16, 23, 39) }
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), dim)
            }
            ChromaKeyBgStyle.SOLID_GREEN -> {
                canvas.drawColor(Color.rgb(0, 177, 64))
            }
        }

        val srcPixels = IntArray(w * h)
        val maskPixels = IntArray(w * h)
        val outPixels = IntArray(w * h)
        src.getPixels(srcPixels, 0, w, 0, 0, w, h)
        alphaMask.getPixels(maskPixels, 0, w, 0, 0, w, h)
        out.getPixels(outPixels, 0, w, 0, 0, w, h)

        for (i in outPixels.indices) {
            val a = Color.red(maskPixels[i]) / 255f
            if (a > 0.02f) {
                val fg = srcPixels[i]
                val bg = outPixels[i]
                val r = (Color.red(fg) * a + Color.red(bg) * (1f - a)).toInt().coerceIn(0, 255)
                val g = (Color.green(fg) * a + Color.green(bg) * (1f - a)).toInt().coerceIn(0, 255)
                val b = (Color.blue(fg) * a + Color.blue(bg) * (1f - a)).toInt().coerceIn(0, 255)
                outPixels[i] = Color.rgb(r, g, b)
            }
        }
        out.setPixels(outPixels, 0, w, 0, 0, w, h)
        alphaMask.recycle()
        return out
    }

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { res ->
            if (cont.isActive) cont.resume(res)
        }.addOnFailureListener { err ->
            if (cont.isActive) cont.resumeWithException(err)
        }
    }
}
