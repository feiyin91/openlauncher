package com.openlauncher.app.util

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.nio.FloatBuffer

/**
 * Runs the three-stage openWakeWord inference pipeline (melspectrogram ->
 * embedding -> custom classifier) entirely on-device via ONNX Runtime.
 *
 * Gemini Live was tried first for "Hi Sebastian" wake-word detection but
 * proved unreliable over this car's actual network path (see
 * GeminiLiveTranscriber's history) — this runs fully offline instead, using
 * the same feature-extraction models openWakeWord's own Python training
 * pipeline uses, plus a small classifier head trained specifically on the
 * "Hi Sebastian" phrase (see docs/wakeword-training notes / the Colab
 * training session — car_name chosen by Harvard and his wife).
 *
 * Constants (chunk size, window sizes, stride, transform) mirror
 * openwakeword/utils.py's AudioFeatures class exactly — these aren't
 * arbitrary tuning values, they're the shapes the shared melspectrogram/
 * embedding models and the classifier were trained against, so drifting
 * from them would silently produce garbage predictions rather than an
 * error.
 */
class WakeWordEngine(context: Context) {

    companion object {
        const val CHUNK_SAMPLES = 1280 // 80ms @ 16kHz — feed processChunk() exactly this many samples at a time
        // The melspectrogram model needs overlap context from the previous
        // chunk, not just the new samples: openWakeWord feeds it
        // raw_buffer[-n_samples - 160*3:], i.e. 1280 new + 480 carried over.
        // This is load-bearing, not a tuning value — 1280 samples alone
        // yields only 5 mel frames where 1760 yields 8, so dropping the
        // context silently compresses the whole time axis to 62% and the
        // 76-frame window ends up spanning ~1.2s of audio instead of the
        // 0.76s the embedding model and classifier were trained on. Verified
        // locally against the real models: with context "Hey Sebastian"
        // scores 0.845, without it 0.0016 (indistinguishable from silence).
        private const val MEL_CONTEXT_SAMPLES = 160 * 3
        private const val MEL_BINS = 32
        private const val MEL_WINDOW = 76 // frames of mel spectrogram the embedding model consumes per call
        private const val EMB_DIM = 96
        private const val EMB_WINDOW = 16 // embeddings the classifier consumes per call
        private const val MAX_MEL_FRAMES = MEL_WINDOW * 2 // only the last MEL_WINDOW are ever used; cap to bound memory
        private const val MAX_EMB_FRAMES = EMB_WINDOW * 2

        /**
         * v2 was retrained on several spellings of the phrase after v1 turned
         * out to only reward a careful three-syllable "Sebastian" — Harvard's
         * natural delivery peaked around 0.16 against a 0.4 threshold. Measured
         * against the real models locally, v2 fires on six pronunciations v1
         * missed entirely, loses none, and leaves unrelated speech at 0.001.
         */
        private const val CLASSIFIER = "hey_sebastian_v2.onnx"

        private val ASSET_FILES = listOf(
            "melspectrogram.onnx", "embedding_model.onnx", CLASSIFIER, "$CLASSIFIER.data"
        )

        /**
         * ONNX Runtime needs real filesystem paths (not raw asset bytes) —
         * the classifier model references its weights via a sibling
         * "hey_sebastian.onnx.data" file by relative filename, which only
         * resolves correctly if both files sit together on disk. Copies
         * once; skips files already present at the right size.
         */
        fun ensureModelsCopied(context: Context): File {
            val dir = File(context.filesDir, "wakeword").apply { mkdirs() }
            // Drop models left behind by a previous version of the app, so
            // superseded classifiers don't sit in internal storage forever on
            // a device with limited space.
            dir.listFiles()?.forEach { f -> if (f.name !in ASSET_FILES) runCatching { f.delete() } }
            for (name in ASSET_FILES) {
                val out = File(dir, name)
                val assetSize = context.assets.open("wakeword/$name").use { it.available().toLong() }
                if (out.exists() && out.length() == assetSize) continue
                context.assets.open("wakeword/$name").use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
            return dir
        }
    }

    private val env = OrtEnvironment.getEnvironment()
    private val modelsDir = ensureModelsCopied(context)
    private val melSession = env.createSession(File(modelsDir, "melspectrogram.onnx").absolutePath)
    private val embSession = env.createSession(File(modelsDir, "embedding_model.onnx").absolutePath)
    private val clfSession = env.createSession(File(modelsDir, CLASSIFIER).absolutePath)

    private val melFrames = ArrayDeque<FloatArray>()
    private val embeddings = ArrayDeque<FloatArray>()

    /** Tail of the previous chunk, prepended to the next one as STFT context (see MEL_CONTEXT_SAMPLES). */
    private var melContext = FloatArray(MEL_CONTEXT_SAMPLES)

    /**
     * Feed exactly [CHUNK_SAMPLES] raw 16kHz mono PCM16 samples. Returns the
     * latest wake-word probability once enough audio has accumulated to run
     * the full pipeline (roughly the first ~7s after starting), else null.
     */
    fun processChunk(chunk: ShortArray): Float? {
        require(chunk.size == CHUNK_SAMPLES) { "WakeWordEngine.processChunk expects exactly $CHUNK_SAMPLES samples, got ${chunk.size}" }

        for (frame in runMelspectrogram(chunk)) {
            melFrames.addLast(frame)
            while (melFrames.size > MAX_MEL_FRAMES) melFrames.removeFirst()
        }
        if (melFrames.size < MEL_WINDOW) return null

        val window = melFrames.toList().takeLast(MEL_WINDOW)
        embeddings.addLast(runEmbedding(window))
        while (embeddings.size > MAX_EMB_FRAMES) embeddings.removeFirst()
        if (embeddings.size < EMB_WINDOW) return null

        return runClassifier(embeddings.toList().takeLast(EMB_WINDOW))
    }

    private fun runMelspectrogram(chunk: ShortArray): List<FloatArray> {
        // Previous chunk's tail + this chunk, so the STFT has the overlap it
        // expects (see MEL_CONTEXT_SAMPLES).
        val floatSamples = FloatArray(MEL_CONTEXT_SAMPLES + chunk.size)
        melContext.copyInto(floatSamples, 0)
        for (i in chunk.indices) floatSamples[MEL_CONTEXT_SAMPLES + i] = chunk[i].toFloat()
        melContext = floatSamples.copyOfRange(floatSamples.size - MEL_CONTEXT_SAMPLES, floatSamples.size)

        val inputName = melSession.inputNames.first()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(floatSamples), longArrayOf(1, floatSamples.size.toLong())).use { tensor ->
            melSession.run(mapOf(inputName to tensor)).use { results ->
                val flat = flattenTensor(results[melSession.outputNames.first()].get().value)
                // openWakeWord's own melspec_transform: raw model output isn't
                // used directly, this normalization is part of the trained
                // pipeline's expected input range for the embedding model.
                val transformed = FloatArray(flat.size) { flat[it] / 10f + 2f }
                val frameCount = transformed.size / MEL_BINS
                return (0 until frameCount).map { i -> transformed.copyOfRange(i * MEL_BINS, (i + 1) * MEL_BINS) }
            }
        }
    }

    private fun runEmbedding(window: List<FloatArray>): FloatArray {
        val flat = FloatArray(MEL_WINDOW * MEL_BINS)
        var idx = 0
        for (frame in window) for (v in frame) flat[idx++] = v
        val inputName = embSession.inputNames.first()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), longArrayOf(1, MEL_WINDOW.toLong(), MEL_BINS.toLong(), 1)).use { tensor ->
            embSession.run(mapOf(inputName to tensor)).use { results ->
                return flattenTensor(results[embSession.outputNames.first()].get().value)
            }
        }
    }

    private fun runClassifier(embWindow: List<FloatArray>): Float {
        val flat = FloatArray(EMB_WINDOW * EMB_DIM)
        var idx = 0
        for (e in embWindow) for (v in e) flat[idx++] = v
        val inputName = clfSession.inputNames.first()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), longArrayOf(1, EMB_WINDOW.toLong(), EMB_DIM.toLong())).use { tensor ->
            clfSession.run(mapOf(inputName to tensor)).use { results ->
                val out = flattenTensor(results[clfSession.outputNames.first()].get().value)
                return out.firstOrNull() ?: 0f
            }
        }
    }

    /** Recursively unwraps ONNX Runtime's nested Java-array tensor output into one flat, row-major FloatArray, regardless of the tensor's rank. */
    private fun flattenTensor(value: Any?): FloatArray = when (value) {
        is FloatArray -> value
        is Array<*> -> {
            val parts = value.map { flattenTensor(it) }
            val total = parts.sumOf { it.size }
            val out = FloatArray(total)
            var offset = 0
            for (p in parts) { p.copyInto(out, offset); offset += p.size }
            out
        }
        else -> FloatArray(0)
    }

    /**
     * Clears all buffered audio context. Call when the mic is reclaimed after
     * a voice command — the frames either side of that gap aren't contiguous,
     * and a 76-frame window spanning the seam would be analysing audio that
     * never actually occurred back to back.
     */
    fun reset() {
        melFrames.clear()
        embeddings.clear()
        melContext = FloatArray(MEL_CONTEXT_SAMPLES)
    }

    fun close() {
        runCatching { melSession.close() }
        runCatching { embSession.close() }
        runCatching { clfSession.close() }
    }
}
