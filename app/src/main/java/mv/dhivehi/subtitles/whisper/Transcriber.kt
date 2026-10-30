package mv.dhivehi.subtitles.whisper

import android.content.Context
import android.util.Log
import mv.dhivehi.subtitles.captions.Word
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/**
 * Runs the Dhivehi speech model fully on the phone and returns words with timings.
 * The model was fine-tuned on Dhivehi speech using Whisper's Sinhala language slot ("si"),
 * so that is the language code it expects.
 */
object Transcriber {

    private const val MODEL_ASSET = "models/ggml-dhivehi.bin"
    private const val LANGUAGE = "si"

    @Volatile private var ctx: Long = 0L
    private val lock = Any()

    class ModelMissingException : IOException(
        "The Dhivehi speech model is missing from this build. Rebuild the app with the GitHub build workflow."
    )

    fun isModelBundled(context: Context): Boolean =
        try { context.assets.openFd(MODEL_ASSET).use { it.length > 1_000_000 } } catch (e: Exception) {
            try { context.assets.open(MODEL_ASSET).use { it.available() > 0 } } catch (e2: Exception) { false }
        }

    /** Copies the model out of the app package once (fast after the first time) and loads it. */
    fun ensureLoaded(context: Context, onStatus: (String) -> Unit) {
        synchronized(lock) {
            if (ctx != 0L) return
            val dest = File(context.filesDir, "ggml-dhivehi.bin")
            val expected = try { context.assets.openFd(MODEL_ASSET).use { it.length } } catch (e: Exception) { -1L }
            if (expected <= 0L && !dest.exists()) throw ModelMissingException()
            if (!dest.exists() || (expected > 0 && dest.length() != expected)) {
                onStatus("Preparing the Dhivehi speech model (first time only)…")
                val tmp = File(context.filesDir, "ggml-dhivehi.tmp")
                context.assets.open(MODEL_ASSET).use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
                if (!tmp.renameTo(dest)) throw IOException("Could not save the speech model.")
            }
            onStatus("Loading the Dhivehi speech model…")
            val p = WhisperLib.initContext(dest.absolutePath)
            if (p == 0L) {
                dest.delete()
                throw IOException("The speech model could not be loaded. Please reinstall the app.")
            }
            ctx = p
        }
    }

    fun cancel() = WhisperLib.requestAbort()

    private fun threadCount(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return min(4, max(2, cores / 2))
    }

    /**
     * @param audioStartMs where the first audio sample sits on the video's timeline (usually 0).
     * @return words with start/end in milliseconds; empty if nothing was heard.
     */
    fun transcribe(samples: FloatArray, audioStartMs: Long = 0L, onProgress: (Int) -> Unit): List<Word> {
        synchronized(lock) {
            val c = ctx
            check(c != 0L) { "Model not loaded" }
            val r = WhisperLib.transcribe(c, samples, threadCount(), LANGUAGE, ProgressListener { onProgress(it.coerceIn(0, 100)) })
            if (r == -2) throw InterruptedException()
            if (r != 0) throw IOException("Speech recognition failed (code $r).")
            val audioMs = samples.size * 1000L / 16000L
            val words = cleanUp(collectWords(c), audioMs)
            SpeechAligner(samples).refine(words)
            if (audioStartMs in 1..2000) words.forEach { it.startMs += audioStartMs; it.endMs += audioStartMs }
            Log.i(TAG, "CAPTION_WORDS " + words.joinToString(" | ") { "${it.startMs}-${it.endMs}" })
            return words
        }
    }

    private const val TAG = "DhivehiSubs"

    private class Builder(val startCs: Long) {
        val bytes = ByteArrayOutputStream()
        var endCs: Long = startCs
    }

    private fun collectWords(c: Long): List<Word> {
        val words = ArrayList<Word>()
        val nSeg = WhisperLib.segmentCount(c)
        for (s in 0 until nSeg) {
            val segT0 = WhisperLib.segmentT0(c, s)
            val segT1 = WhisperLib.segmentT1(c, s)
            val segWords = ArrayList<Word>()
            var cur: Builder? = null
            fun flush() {
                val b = cur ?: return
                val text = String(b.bytes.toByteArray(), Charsets.UTF_8).trim()
                if (text.isNotEmpty() && text.any { it.isLetterOrDigit() }) {
                    val st = b.startCs.coerceIn(segT0, max(segT0, segT1))
                    val en = b.endCs.coerceIn(st, max(segT1, st))
                    segWords.add(Word(text, st * 10, en * 10))
                } else if (text.isNotEmpty() && segWords.isNotEmpty()) {
                    // stray punctuation joins the previous word
                    segWords.last().text += text
                }
                cur = null
            }
            var usedDtw = true
            val nTok = WhisperLib.tokenCount(c, s)
            for (t in 0 until nTok) {
                if (WhisperLib.isSpecialToken(c, s, t)) continue
                val bytes = WhisperLib.tokenBytes(c, s, t)
                if (bytes.isEmpty()) continue
                val timing = WhisperLib.tokenTiming(c, s, t)
                val dtw = if (timing.size > 3) timing[3] else -1L
                if (dtw < 0) usedDtw = false
                val startsWord = bytes[0] == ' '.code.toByte()
                if (cur == null || startsWord) {
                    flush()
                    // DTW gives the moment each token is spoken; much closer than the plain token timestamps.
                    cur = Builder(if (dtw >= 0) dtw else timing[0])
                }
                cur!!.bytes.write(bytes)
                cur!!.endCs = max(cur!!.endCs, if (dtw >= 0) dtw else timing[1])
            }
            flush()
            if (usedDtw) {
                // With DTW only start moments are reliable: a word lasts until the next one starts.
                for (i in segWords.indices) {
                    val next = if (i + 1 < segWords.size) segWords[i + 1].startMs else segT1 * 10
                    segWords[i].endMs = max(segWords[i].startMs + 120, next)
                }
            }
            words.addAll(segWords)
        }
        return words
    }

    /** Fixes overlaps, drops repeated hallucinations, and guarantees sensible durations. */
    private fun cleanUp(raw: List<Word>, audioMs: Long): List<Word> {
        val out = ArrayList<Word>()
        var repeat = 0
        for (w in raw) {
            val prev = out.lastOrNull()
            if (prev != null && prev.text == w.text) {
                repeat++
                if (repeat >= 3) continue
            } else repeat = 0
            out.add(w)
        }
        for (i in out.indices) {
            val w = out[i]
            if (i > 0 && w.startMs < out[i - 1].endMs) {
                // overlap: split the difference
                val mid = (w.startMs + out[i - 1].endMs) / 2
                out[i - 1].endMs = mid
                w.startMs = max(mid, out[i - 1].startMs + 60)
            }
            if (w.endMs - w.startMs < 120) w.endMs = w.startMs + 120
            if (w.endMs > audioMs) w.endMs = audioMs
            if (w.startMs > w.endMs) w.startMs = max(0L, w.endMs - 120)
        }
        return out
    }
}
