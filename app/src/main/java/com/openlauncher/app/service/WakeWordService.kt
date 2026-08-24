package com.openlauncher.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.openlauncher.app.MainActivity
import com.openlauncher.app.util.VoiceAssistantBridge
import com.openlauncher.app.util.WakeWordEngine
import com.openlauncher.app.viewmodel.LauncherViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Always-on background listener for the "Hi Sebastian" wake word — runs the
 * ONNX pipeline in WakeWordEngine continuously on raw mic audio, and hands
 * off to LauncherViewModel (via VoiceAssistantBridge) the moment it hears it,
 * same as tapping the mic button manually.
 *
 * A real foreground service for the same reason TripTrackingService is one:
 * this needs to keep listening regardless of which app is in front (Waze
 * full-screen, etc.), not just while OpenLauncher's own dashboard is on
 * screen.
 *
 * Explicitly a personal build feature, not part of the upstream PR — see
 * feat/gemini-voice-assistant branch notes.
 */
class WakeWordService : Service() {

    companion object {
        private const val CHANNEL_ID = "wake_word"
        private const val NOTIFICATION_ID = 1002
        private const val SAMPLE_RATE = 16000
        // Below openWakeWord's 0.5 default (where training measured ~0.8
        // false positives/hour) because a local sweep over the real models
        // showed male voices scoring consistently lower than female ones on
        // this classifier — 0.86 for Karen/Moira/Samantha, but 0.61-0.75 for
        // Alex and only 0.23-0.28 for Daniel/Fred. 0.4 keeps a wide margin
        // over the noise floor (unrelated speech scored 0.0009) while giving
        // a male speaker room. A false trigger just opens the listener and
        // times out harmlessly, so erring low is the cheaper mistake here.
        private const val DETECTION_THRESHOLD = 0.4f
        // Guards against re-triggering off the tail end of the same
        // utterance still sitting in the sliding window right after a hit.
        private const val COOLDOWN_MS = 3000L
        // Grace period between releasing the mic and telling anyone the wake
        // word fired, so the recognizer that starts next finds it free.
        private const val MIC_HANDOVER_SETTLE_MS = 400L
    }

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var engine: WakeWordEngine? = null
    private var listenJob: Job? = null
    private var lastTriggerMs = 0L

    override fun onCreate() {
        super.onCreate()
        // Same class of bug found and fixed in TripTrackingService today —
        // a Service.onCreate() exception is dispatched by the OS, outside
        // any try/catch the caller wrapped around startForegroundService().
        runCatching { createNotificationChannel() }
        // Loading the ONNX sessions can take a moment on this 2GB device —
        // do it once here rather than per-chunk.
        engine = runCatching { WakeWordEngine(applicationContext) }
            .onFailure { VoiceAssistantBridge.wakeWordDebug.value = "engine failed: ${it.message}" }
            .getOrNull()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }
            .onFailure { return START_NOT_STICKY }
        startListenLoop()
        return START_STICKY
    }

    private fun startListenLoop() {
        listenJob?.cancel()
        listenJob = scope.launch {
            val engine = engine ?: return@launch
            while (isActive) {
                // Hold off entirely while a voice command is running — the
                // mic must be genuinely free, not just unread (see
                // captureSession).
                while (isActive && VoiceAssistantBridge.voiceState.value != LauncherViewModel.VoiceAssistantState.IDLE) {
                    VoiceAssistantBridge.wakeWordDebug.value = "paused — voice command active"
                    delay(300)
                }
                if (!isActive) break
                // Let the recognizer's own capture stream finish tearing down
                // before claiming the input device again; grabbing it in the
                // same breath tends to hand back a silenced stream.
                delay(400)
                val detected = captureSession(engine)
                if (detected) {
                    // AudioRecord.release() returning does not mean the input
                    // device is free yet — the audio HAL tears the session
                    // down asynchronously. Handing over immediately left
                    // SpeechRecognizer erroring out on a still-busy mic the
                    // instant it started, which surfaced as "didn't catch
                    // that" before the driver had a chance to say anything.
                    delay(MIC_HANDOVER_SETTLE_MS)
                    // Emitted only after captureSession has returned, i.e.
                    // after its finally block released the mic — the ViewModel
                    // starts SpeechRecognizer the instant it sees this, so
                    // emitting while still holding the device is precisely the
                    // race that made every wake-word-triggered command fail
                    // with "didn't catch that".
                    VoiceAssistantBridge.wakeWordDetected.tryEmit(Unit)
                    VoiceAssistantBridge.wakeWordDebug.value = "detected — handing over mic"
                    // Wait for the handover to actually register before the
                    // loop comes back around, otherwise the pause check above
                    // still sees IDLE and we immediately reclaim the mic out
                    // from under the recognizer we just triggered. Bounded so
                    // a dropped/ignored event can't wedge listening forever.
                    var waited = 0
                    while (isActive && waited < 2000 &&
                        VoiceAssistantBridge.voiceState.value == LauncherViewModel.VoiceAssistantState.IDLE
                    ) {
                        delay(100)
                        waited += 100
                    }
                }
            }
        }
    }

    /**
     * Owns the mic for one continuous listening stretch: opens AudioRecord,
     * runs inference until either the wake word fires or a voice command
     * starts elsewhere, then always releases the device on the way out.
     *
     * The recorder is deliberately created and destroyed per stretch rather
     * than once for the service's lifetime. Android hands input to one client
     * at a time, and after SpeechRecognizer takes over, a long-lived
     * AudioRecord keeps returning full-length reads of silence rather than
     * failing — which looks exactly like a working pipeline scoring zero, and
     * is why detection went permanently dead after the first voice command.
     *
     * @return true if the wake word was detected (caller emits once the mic is free).
     */
    private suspend fun captureSession(engine: WakeWordEngine): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            VoiceAssistantBridge.wakeWordDebug.value = "getMinBufferSize failed"
            delay(1000)
            return false
        }
        val bufferSize = maxOf(minBuf, WakeWordEngine.CHUNK_SAMPLES * 4)

        // VOICE_RECOGNITION deliberately hands over raw audio with device
        // processing disabled, which is what we want in a quiet cabin and is
        // what the model was trained against. But it also means no echo
        // cancellation, so once the stereo is playing the wake word is buried
        // under it — the score never moves at all. VOICE_COMMUNICATION is the
        // source the platform actually attaches echo cancellation to, since
        // it exists for exactly this problem: hearing the near end while the
        // far end is playing. Only used while something is actually playing,
        // to avoid its noise suppression and gain control colouring the audio
        // the rest of the time.
        val musicPlaying = runCatching {
            (getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager)?.isMusicActive == true
        }.getOrDefault(false)
        val source = if (musicPlaying) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }

        val recorder = runCatching {
            AudioRecord(
                source, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
            )
        }.onFailure { VoiceAssistantBridge.wakeWordDebug.value = "AudioRecord ctor failed: ${it.message}" }
            .getOrNull() ?: run { delay(1000); return false }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            VoiceAssistantBridge.wakeWordDebug.value = "AudioRecord not initialized (state=${recorder.state})"
            recorder.release()
            delay(1000)
            return false
        }

        // Subtract what the unit is playing through its own speakers, so the
        // wake word isn't buried under the car stereo. Both effects are
        // optional device capabilities — absent on plenty of hardware, and
        // this one is a cheap Unisoc head unit, so treat them as a bonus
        // rather than something to depend on.
        val aec = runCatching {
            if (android.media.audiofx.AcousticEchoCanceler.isAvailable())
                android.media.audiofx.AcousticEchoCanceler.create(recorder.audioSessionId)
                    ?.apply { enabled = true }
            else null
        }.getOrNull()
        val ns = runCatching {
            if (android.media.audiofx.NoiseSuppressor.isAvailable())
                android.media.audiofx.NoiseSuppressor.create(recorder.audioSessionId)
                    ?.apply { enabled = true }
            else null
        }.getOrNull()

        // Whether these actually attached is the difference between "echo
        // cancellation didn't help" and "echo cancellation was never running",
        // and the hardware decides — this is a budget head unit, so neither is
        // guaranteed to exist.
        val fx = buildString {
            append(if (musicPlaying) "comm" else "recog")
            append(if (aec != null) "+aec" else "-aec")
            append(if (ns != null) "+ns" else "-ns")
        }

        // Audio either side of a mic handover isn't contiguous, so carrying
        // the previous stretch's buffered frames across would analyse a
        // window that never actually occurred.
        engine.reset()

        val chunk = ShortArray(WakeWordEngine.CHUNK_SAMPLES)
        var chunkCount = 0L
        // Peak over a rolling ~5s window rather than since startup, so the
        // readout reflects the attempt just made instead of being pinned high
        // forever by one loud noise earlier in the drive.
        var peakScore = 0f
        var peakWindowStart = 0L
        recorder.startRecording()
        try {
            while (currentCoroutineContext().isActive) {
                if (VoiceAssistantBridge.voiceState.value != LauncherViewModel.VoiceAssistantState.IDLE) return false

                // The audio source is fixed when the stream opens, so music
                // starting or stopping mid-session has to reopen it with the
                // other source. Checked about once a second rather than per
                // chunk — it crosses into the audio service.
                if (chunkCount % 12L == 0L) {
                    val nowPlaying = runCatching {
                        (getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager)?.isMusicActive == true
                    }.getOrDefault(musicPlaying)
                    if (nowPlaying != musicPlaying) return false
                }

                val read = recorder.read(chunk, 0, chunk.size)
                if (read != chunk.size) {
                    VoiceAssistantBridge.wakeWordDebug.value = "short read: $read/${chunk.size}"
                    continue
                }
                chunkCount++
                // Raw input level, reported alongside the score purely to
                // tell two very different failures apart: a mic that has
                // been silenced or handed to another app reads ~0 here while
                // the score sits flat, whereas a mic that is working but
                // swamped by music reads high with the score still low.
                var sumSq = 0.0
                for (s in chunk) sumSq += s.toDouble() * s.toDouble()
                val rms = kotlin.math.sqrt(sumSq / chunk.size).toInt()

                val score = runCatching { engine.processChunk(chunk) }
                    .onFailure { VoiceAssistantBridge.wakeWordDebug.value = "inference error: ${it.message}" }
                    .getOrNull()
                if (score == null) {
                    VoiceAssistantBridge.wakeWordDebug.value = "warming up… mic:$rms"
                    continue
                }
                if (chunkCount - peakWindowStart >= 62) { // ~5s at 80ms/chunk
                    peakWindowStart = chunkCount
                    peakScore = 0f
                }
                peakScore = maxOf(peakScore, score)
                VoiceAssistantBridge.wakeWordDebug.value =
                    "score:%.3f peak5s:%.3f mic:%d %s".format(score, peakScore, rms, fx)
                val now = System.currentTimeMillis()
                if (score >= DETECTION_THRESHOLD && now - lastTriggerMs > COOLDOWN_MS) {
                    lastTriggerMs = now
                    return true
                }
            }
        } finally {
            runCatching { aec?.release() }
            runCatching { ns?.release() }
            runCatching { recorder.stop() }
            recorder.release()
        }
        return false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(
            CHANNEL_ID, "Wake Word Listening",
            NotificationManager.IMPORTANCE_MIN // silent, minimal visibility — required to exist, not meant to be noticed
        ).apply { description = "Listens for \"Hi Sebastian\" to start a voice command" }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Listening for \"Hi Sebastian\"")
            .setContentText("Wake word detection active")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        listenJob?.cancel()
        engine?.close()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
