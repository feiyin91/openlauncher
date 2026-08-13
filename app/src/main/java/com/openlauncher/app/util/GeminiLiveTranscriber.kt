package com.openlauncher.app.util

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Speech-to-text via Gemini's Live API (WebSocket) instead of Android's
 * on-device SpeechRecognizer — chosen specifically for proper-noun accuracy
 * (road/shop/place names), the same reason Harvard Studio's lesson-note
 * transcription switched to it over browser ASR (musical terms/composer
 * names). Protocol details below were pulled from Studio's own working
 * frontend implementation (Calendar.jsx), not just API docs, because two
 * things aren't obvious from the docs alone and are confirmed live there:
 *
 * 1. responseModalities MUST be ["AUDIO"] — the model rejects "TEXT" outright
 *    for this endpoint. We don't want spoken audio back, so the system
 *    instruction tells it to stay silent, and any audio bytes it still sends
 *    are simply discarded — only inputTranscription.text is used.
 * 2. Closing the socket immediately after sending audioStreamEnd races the
 *    final inputTranscription message and can drop it — wait ~2s first.
 *
 * Falls back to the caller's onError so the caller (LauncherViewModel) can
 * fall back to Android's own SpeechRecognizer, same pattern Studio uses
 * falling back to the browser's Web Speech API when Gemini Live is
 * unavailable (offline, connect timeout, etc).
 */
class GeminiLiveTranscriber(
    private val apiKey: String,
    private val scope: CoroutineScope
) {
    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CONNECT_TIMEOUT_MS = 5_000L
        private const val CLOSE_DRAIN_DELAY_MS = 2_000L
        // Simple energy-based VAD, not ML-based — cheap and good enough to
        // detect "driver stopped talking." Threshold is a starting point for
        // in-cabin road noise; may need retuning once tested live in the car.
        private const val SILENCE_RMS_THRESHOLD = 700.0
        private const val CHUNK_MS = 100
        private const val SILENCE_MS_TO_AUTO_STOP = 1_500
        private const val SILENT_CHUNKS_TO_AUTO_STOP = SILENCE_MS_TO_AUTO_STOP / CHUNK_MS
        // Safety cap in case VAD never trips (e.g. continuous background noise
        // above threshold) — don't listen forever and drain the mic/battery.
        private const val MAX_LISTEN_MS = 20_000L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // streaming — no fixed read timeout
        .build()
    private val gson = Gson()

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var isConnected = false
    private var stopped = false
    private val pendingChunks = mutableListOf<String>() // base64, buffered until connected
    private val transcriptBuilder = StringBuilder()
    private var hasDetectedSpeech = false
    private var silentChunkCount = 0
    // If stop() (auto or manual) fires before the WebSocket handshake has
    // finished — very possible for a normal-length sentence, since connect
    // takes ~1-2s — the old code just closed immediately and silently
    // dropped every buffered chunk, since flushing only ever happened inside
    // onMessage's "just connected" branch. Now stop() defers the actual
    // audioStreamEnd+close until that branch runs, instead of abandoning it.
    private var stopRequested = false

    /**
     * Starts capturing mic audio immediately (before the WebSocket is even
     * open) and buffers it — connection setup takes ~1-2s, and anything said
     * during that window would otherwise be lost, exactly the issue Studio's
     * comment describes ("the first few words are always missing").
     */
    fun start(
        onTranscriptUpdate: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        stopped = false
        stopRequested = false
        transcriptBuilder.clear()
        hasDetectedSpeech = false
        silentChunkCount = 0

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            onError("Microphone not available.")
            return
        }
        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
        }.getOrNull()
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            onError("Microphone permission not granted or unavailable.")
            return
        }
        audioRecord = record
        record.startRecording()

        captureJob = scope.launch(Dispatchers.IO) {
            val buffer = ShortArray(1600) // 100ms chunks at 16kHz
            while (!stopped) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                // RMS on the raw samples, before byte-packing — cheap way to
                // tell "driver is talking" from "driver stopped."
                var sumSquares = 0.0
                for (i in 0 until read) sumSquares += buffer[i].toDouble() * buffer[i]
                val rms = kotlin.math.sqrt(sumSquares / read)
                if (rms >= SILENCE_RMS_THRESHOLD) {
                    hasDetectedSpeech = true
                    silentChunkCount = 0
                } else if (hasDetectedSpeech) {
                    silentChunkCount++
                    if (silentChunkCount >= SILENT_CHUNKS_TO_AUTO_STOP) {
                        stop()
                        break
                    }
                }

                val bytes = ByteArray(read * 2)
                for (i in 0 until read) {
                    val s = buffer[i].toInt()
                    bytes[i * 2] = (s and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                }
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                if (isConnected) {
                    sendAudioChunk(b64)
                } else {
                    synchronized(pendingChunks) {
                        if (pendingChunks.size < 300) pendingChunks.add(b64) // ~30s cap
                    }
                }
            }
        }

        // Safety cap — if VAD never trips (e.g. sustained road/cabin noise
        // above threshold), don't listen forever.
        scope.launch {
            delay(MAX_LISTEN_MS)
            if (!stopped) stop()
        }

        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(url).build()
        var connectTimedOut = false
        val timeoutJob = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (!isConnected) {
                connectTimedOut = true
                stop()
                onError("Gemini Live connect timed out.")
            }
        }

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val setup = JsonObject().apply {
                    add("setup", JsonObject().apply {
                        addProperty("model", "models/gemini-3.1-flash-live-preview")
                        add("responseModalities", gson.toJsonTree(listOf("AUDIO")))
                        add("inputAudioTranscription", JsonObject())
                        add("systemInstruction", JsonObject().apply {
                            add("parts", gson.toJsonTree(listOf(mapOf(
                                "text" to "You are a silent dictation service for a car dashboard voice assistant. Never respond conversationally and never speak out loud — only listen and transcribe exactly what is said, including place names, road names, and shop names as accurately as possible."
                            ))))
                        })
                    })
                }
                webSocket.send(gson.toJson(setup))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (connectTimedOut) return
                if (!isConnected) {
                    isConnected = true
                    timeoutJob.cancel()
                    synchronized(pendingChunks) {
                        pendingChunks.forEach { sendAudioChunk(it) }
                        pendingChunks.clear()
                    }
                    if (stopRequested) {
                        finalizeStop(webSocket)
                        return
                    }
                }
                val json = runCatching { gson.fromJson(text, JsonObject::class.java) }.getOrNull() ?: return
                val serverContent = json.getAsJsonObject("serverContent") ?: return
                val chunk = serverContent.getAsJsonObject("inputTranscription")?.get("text")?.asString
                if (!chunk.isNullOrEmpty()) {
                    transcriptBuilder.append(chunk)
                    onTranscriptUpdate(transcriptBuilder.toString())
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (connectTimedOut) return
                timeoutJob.cancel()
                stopInternal()
                onError(t.message ?: "Connection to Gemini Live failed.")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (connectTimedOut) return
                val finalText = transcriptBuilder.toString().trim()
                if (finalText.isNotEmpty()) onFinal(finalText)
                else onError("Didn't catch anything.")
            }
        })
    }

    private fun sendAudioChunk(base64Pcm: String) {
        val msg = JsonObject().apply {
            add("realtimeInput", JsonObject().apply {
                add("audio", JsonObject().apply {
                    addProperty("data", base64Pcm)
                    addProperty("mimeType", "audio/pcm;rate=$SAMPLE_RATE")
                })
            })
        }
        webSocket?.send(gson.toJson(msg))
    }

    /** Signals end of speech, then waits before closing — closing immediately
     * races the final inputTranscription message (confirmed in Studio's own
     * live testing) and drops it. If the WebSocket handshake hasn't finished
     * yet, defers to onMessage's "just connected" branch instead of
     * abandoning the buffered audio (see stopRequested). */
    fun stop() {
        stopInternal()
        stopRequested = true
        val ws = webSocket
        if (ws != null && isConnected) {
            finalizeStop(ws)
        }
        // else: not connected yet — finalizeStop() runs once onMessage sees
        // the connection succeed. If it never does, onFailure/the connect
        // timeout already handle cleanup and the fallback recognizer.
    }

    private fun finalizeStop(ws: WebSocket) {
        val endMsg = JsonObject().apply {
            add("realtimeInput", JsonObject().apply { addProperty("audioStreamEnd", true) })
        }
        ws.send(gson.toJson(endMsg))
        scope.launch {
            delay(CLOSE_DRAIN_DELAY_MS)
            runCatching { ws.close(1000, null) }
        }
    }

    private fun stopInternal() {
        stopped = true
        captureJob?.cancel()
        audioRecord?.let { runCatching { it.stop(); it.release() } }
        audioRecord = null
    }
}
