package com.example.editor.engine

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.example.diagnostics.CrashLogger
import com.example.editor.model.EditorFontChoice
import com.example.editor.model.TextAnimationType
import com.example.editor.model.TimelineTextOverlay
import com.example.editor.model.TimelineVideoClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

sealed interface GeminiCaptionResult {
    data class Success(val captions: List<TimelineTextOverlay>) : GeminiCaptionResult
    data class FallbackToOffline(val reason: String) : GeminiCaptionResult
}

class HybridAutoCaptionEngine(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var listenStartWallMs: Long = 0L

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun isSpeechRecognitionAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context)

    fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    suspend fun generateWithGemini(
        clip: TimelineVideoClip,
        clipTimelineStartMs: Long,
        apiKey: String
    ): GeminiCaptionResult = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty()) {
            return@withContext GeminiCaptionResult.FallbackToOffline(
                "Gemini API Key is not set. Switched to offline SpeechRecognizer."
            )
        }
        if (!isNetworkAvailable()) {
            return@withContext GeminiCaptionResult.FallbackToOffline(
                "No internet connection. Switched to offline SpeechRecognizer."
            )
        }

        val stagedVideo = FFmpegEditorEngine.stageUriForExternal(context, clip.sourceUri)
            ?: return@withContext GeminiCaptionResult.FallbackToOffline(
                "Unable to read clip media. Falling back to offline SpeechRecognizer."
            )

        val tempAudioDir = File(context.cacheDir, "editor_captions").apply { if (!exists()) mkdirs() }
        val tempAudioFile = File(tempAudioDir, "gemini_cap_${System.currentTimeMillis()}.m4a")

        val startSec = String.format(Locale.US, "%.3f", clip.trimStartMs / 1000.0)
        val durSec = String.format(Locale.US, "%.3f", clip.sourceSpanMs.coerceAtMost(180_000L) / 1000.0)
        val args = arrayOf(
            "-y",
            "-ss", startSec,
            "-t", durSec,
            "-i", stagedVideo.absolutePath,
            "-vn",
            "-ac", "1",
            "-ar", "16000",
            "-c:a", "aac",
            "-b:a", "32k",
            tempAudioFile.absolutePath
        )

        val session = FFmpegKit.executeWithArguments(args)
        if (!ReturnCode.isSuccess(session.returnCode) || !tempAudioFile.exists() || tempAudioFile.length() == 0L) {
            runCatching { tempAudioFile.delete() }
            return@withContext GeminiCaptionResult.FallbackToOffline(
                "Clip has no extractable speech audio. Using offline SpeechRecognizer instead."
            )
        }

        try {
            val audioBytes = tempAudioFile.readBytes()
            if (audioBytes.size > 14 * 1024 * 1024) {
                return@withContext GeminiCaptionResult.FallbackToOffline(
                    "Audio payload too large for inline upload. Falling back to offline SpeechRecognizer."
                )
            }
            val audioB64 = Base64.encodeToString(audioBytes, Base64.NO_WRAP)

            val prompt = """
                Transcribe the spoken audio accurately in its original language (multi-language supported).
                Return ONLY a valid JSON array of caption segments, no markdown fences, with each item shaped like:
                {"startSec": 0.0, "endSec": 2.5, "text": "Caption words"}
                Keep each segment concise (max 8 words) suitable for mobile video subtitles.
                If no speech is heard, return [].
            """.trimIndent()

            val bodyJson = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", prompt) })
                            put(JSONObject().apply {
                                put("inline_data", JSONObject().apply {
                                    put("mime_type", "audio/mp4")
                                    put("data", audioB64)
                                })
                            })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.2)
                    put("response_mime_type", "application/json")
                })
            }

            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$trimmedKey"
            val request = Request.Builder()
                .url(url)
                .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    CrashLogger.logError(
                        "GeminiAutoCaptions",
                        "HTTP ${response.code}: $rawBody"
                    )
                    return@withContext GeminiCaptionResult.FallbackToOffline(
                        "Gemini API returned HTTP ${response.code}. Falling back to offline SpeechRecognizer."
                    )
                }

                val parsed = parseGeminiCaptionsJson(rawBody, clipTimelineStartMs, clip.effectiveDurationMs, clip.speed)
                return@withContext GeminiCaptionResult.Success(parsed)
            }
        } catch (t: Throwable) {
            CrashLogger.logError("GeminiAutoCaptions", "Gemini caption request failed", t)
            return@withContext GeminiCaptionResult.FallbackToOffline(
                "Gemini request failed (${t.localizedMessage ?: "network error"}). Using offline SpeechRecognizer."
            )
        } finally {
            runCatching { tempAudioFile.delete() }
        }
    }

    fun startOfflineSpeechCapture(
        playheadAnchorMs: Long,
        onPartial: (String) -> Unit,
        onSegmentReady: (TimelineTextOverlay) -> Unit,
        onFinished: (String?) -> Unit
    ) {
        mainHandler.post {
            if (!isSpeechRecognitionAvailable()) {
                onFinished("Offline SpeechRecognizer service is unavailable on this device.")
                return@post
            }
            runCatching {
                speechRecognizer?.destroy()
                val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                speechRecognizer = recognizer
                listenStartWallMs = System.currentTimeMillis()

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {
                        listenStartWallMs = System.currentTimeMillis()
                    }
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            .orEmpty()
                        if (partial.isNotBlank()) onPartial(partial)
                    }

                    override fun onResults(results: Bundle?) {
                        val text = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()
                        val elapsed = (System.currentTimeMillis() - listenStartWallMs).coerceIn(1500L, 8000L)
                        if (text.isNotEmpty()) {
                            onSegmentReady(
                                TimelineTextOverlay(
                                    text = text,
                                    timelineStartMs = playheadAnchorMs,
                                    durationMs = elapsed,
                                    fontChoice = EditorFontChoice.INTER,
                                    colorArgb = 0xFFFFFFFF,
                                    hasBackgroundPill = true,
                                    animation = TextAnimationType.FADE,
                                    posX = 0.5f,
                                    posY = 0.84f,
                                    fontSizeSp = 19f,
                                    isCaption = true
                                )
                            )
                            onFinished(null)
                        } else {
                            onFinished("No speech recognized. You can also add or edit captions manually.")
                        }
                    }

                    override fun onError(error: Int) {
                        val reason = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected."
                            SpeechRecognizer.ERROR_AUDIO -> "Microphone audio error."
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required."
                            else -> "Offline speech recognizer stopped (code $error)."
                        }
                        onFinished(reason)
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                recognizer.startListening(intent)
            }.onFailure { t ->
                CrashLogger.logError("OfflineSpeechRecognizer", "Failed to start offline recognition", t)
                onFinished("Unable to launch offline SpeechRecognizer.")
            }
        }
    }

    fun stopOfflineSpeechCapture() {
        mainHandler.post {
            runCatching { speechRecognizer?.stopListening() }
        }
    }

    fun destroy() {
        mainHandler.post {
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null
        }
    }

    private fun parseGeminiCaptionsJson(
        responseJson: String,
        clipTimelineStartMs: Long,
        clipEffectiveDurMs: Long,
        clipSpeed: Float
    ): List<TimelineTextOverlay> {
        val root = JSONObject(responseJson)
        val candidates = root.optJSONArray("candidates") ?: return emptyList()
        if (candidates.length() == 0) return emptyList()
        val contentText = candidates.getJSONObject(0)
            .optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text")
            ?.trim()
            .orEmpty()
        if (contentText.isEmpty()) return emptyList()

        val cleaned = contentText
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val array = JSONArray(cleaned)
        val result = mutableListOf<TimelineTextOverlay>()
        val speedFactor = clipSpeed.coerceIn(0.25f, 4f)

        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val text = obj.optString("text").trim()
            if (text.isEmpty()) continue
            val rawStartMs = ((obj.optDouble("startSec", 0.0) * 1000.0) / speedFactor).toLong()
                .coerceIn(0L, (clipEffectiveDurMs - 300L).coerceAtLeast(0L))
            val rawEndMs = ((obj.optDouble("endSec", (rawStartMs + 2000L) / 1000.0) * 1000.0) / speedFactor).toLong()
                .coerceIn(rawStartMs + 400L, clipEffectiveDurMs)
            result.add(
                TimelineTextOverlay(
                    text = text,
                    timelineStartMs = clipTimelineStartMs + rawStartMs,
                    durationMs = (rawEndMs - rawStartMs).coerceAtLeast(400L),
                    fontChoice = EditorFontChoice.INTER,
                    colorArgb = 0xFFFFFFFF,
                    hasBackgroundPill = true,
                    animation = TextAnimationType.FADE,
                    posX = 0.5f,
                    posY = 0.84f,
                    fontSizeSp = 19f,
                    isCaption = true
                )
            )
        }
        return result
    }
}
