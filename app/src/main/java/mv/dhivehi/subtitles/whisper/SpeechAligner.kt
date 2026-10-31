package mv.dhivehi.subtitles.whisper

import mv.dhivehi.subtitles.captions.Word
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Fine-tunes word timings against the actual sound (16 kHz mono):
 * a word never starts in silence before the speaker talks, and never hangs on screen through a pause.
 */
class SpeechAligner(samples: FloatArray) {

    private val speech: BooleanArray

    init {
        val n = samples.size / HOP
        val db = FloatArray(n)
        for (f in 0 until n) {
            val from = f * HOP
            val to = min(samples.size, from + WIN)
            var sum = 0.0
            for (i in from until to) sum += samples[i] * samples[i]
            val rms = sqrt(sum / max(1, to - from))
            db[f] = (20 * log10(rms + 1e-7)).toFloat()
        }
        val sorted = db.sortedArray()
        val noise = if (n > 0) sorted[(n * 0.15).toInt().coerceIn(0, n - 1)] else -80f
        val loud = if (n > 0) sorted[(n * 0.95).toInt().coerceIn(0, n - 1)] else -20f
        val threshold = max(noise + 6f, noise + 0.30f * (loud - noise))
        val raw = BooleanArray(n) { db[it] > threshold }
        // Bridge short gaps inside words (< 150 ms) and drop clicks (< 50 ms).
        speech = raw.copyOf()
        var i = 0
        while (i < n) {
            if (!speech[i]) {
                var j = i
                while (j < n && !speech[j]) j++
                if (i > 0 && j < n && j - i < 15) for (k in i until j) speech[k] = true
                i = j
            } else i++
        }
        i = 0
        while (i < n) {
            if (speech[i]) {
                var j = i
                while (j < n && speech[j]) j++
                if (j - i < 5) for (k in i until j) speech[k] = false
                i = j
            } else i++
        }
    }

    private fun frameOf(ms: Long) = (ms / FRAME_MS).toInt()
    private fun isSpeech(f: Int) = f in speech.indices && speech[f]

    /** Voice regions as [startFrame, endFrame) pairs (10 ms frames). */
    val regions: List<IntArray> by lazy {
        val out = ArrayList<IntArray>()
        var i = 0
        while (i < speech.size) {
            if (speech[i]) {
                var j = i
                while (j < speech.size && speech[j]) j++
                out.add(intArrayOf(i, j))
                i = j
            } else i++
        }
        out
    }

    /** A piece of the audio to transcribe on its own, in 10 ms frames. */
    class Chunk(val startFrame: Int, val endFrame: Int)

    /**
     * Cuts the audio into sentence-sized pieces at natural pauses. The Dhivehi model was trained on
     * short single sentences without timing, so it transcribes and times short pieces far better than
     * a whole video (measured on real Dhivehi speech: ~0.14 s average caption error vs. many seconds).
     */
    fun chunks(): List<Chunk> {
        val totalFrames = speech.size
        if (regions.isEmpty()) {
            // No clear voice detected (music, noise): fall back to fixed 20 s windows.
            return (0 until totalFrames step 2000).map { Chunk(it, min(totalFrames, it + 2000)) }
        }
        val groups = ArrayList<MutableList<IntArray>>()
        var cur = ArrayList<IntArray>()
        for (r in regions) {
            if (cur.isNotEmpty() && (r[1] - cur[0][0] > CHUNK_MAX_FRAMES || r[0] - cur.last()[1] >= CHUNK_SPLIT_PAUSE)) {
                groups.add(cur); cur = ArrayList()
            }
            cur.add(r)
        }
        if (cur.isNotEmpty()) groups.add(cur)
        return groups.map { g -> Chunk(max(0, g.first()[0] - CHUNK_PAD), min(totalFrames, g.last()[1] + CHUNK_PAD)) }
    }

    private fun regionAt(f: Int): IntArray? = regions.firstOrNull { f >= it[0] && f < it[1] }

    fun refine(words: List<Word>) {
        if (regions.isEmpty()) return

        // 1) A word never stays on screen through a pause: end it when the voice stops.
        for (w in words) {
            val s = frameOf(w.startMs + 100)
            val e = frameOf(w.endMs)
            for (k in 0 until regions.size - 1) {
                val gapStart = regions[k][1]
                val gapEnd = regions[k + 1][0]
                if (gapEnd - gapStart >= MIN_PAUSE_FRAMES && gapStart > s && gapStart < e) {
                    w.endMs = gapStart.toLong() * FRAME_MS + TAIL_MS
                    break
                }
            }
        }

        // 2) Starts follow the voice.
        for (idx in words.indices) {
            val w = words[idx]
            val prevEnd = if (idx > 0) words[idx - 1].endMs else 0L
            val nextStart = if (idx + 1 < words.size) words[idx + 1].startMs else Long.MAX_VALUE
            val s = frameOf(w.startMs)
            var region = regionAt(s)
            if (region == null) {
                // Starts in silence: the voice should begin shortly after (up to 400 ms).
                region = regions.firstOrNull { it[0] > s && it[0] - s <= 40 && it[0].toLong() * FRAME_MS < nextStart }
                if (region != null) w.startMs = region[0].toLong() * FRAME_MS
            } else if (region[0].toLong() * FRAME_MS >= prevEnd) {
                // First word heard after a pause: start exactly when the voice comes back.
                w.startMs = region[0].toLong() * FRAME_MS
            }
            w.startMs = max(w.startMs, prevEnd).coerceAtLeast(0L)

            // Ends in silence: pull back to just after the voice stops.
            val e = frameOf(w.endMs)
            if (!isSpeech(e - 1)) {
                var f = e - 1
                val first = frameOf(w.startMs)
                while (f > first && !isSpeech(f)) f--
                if (f > first) w.endMs = min(w.endMs, (f + 1).toLong() * FRAME_MS + TAIL_MS)
            }
            if (w.endMs < w.startMs + 120) w.endMs = w.startMs + 120
        }
        for (k in 1 until words.size) {
            if (words[k].startMs < words[k - 1].endMs) words[k - 1].endMs = max(words[k - 1].startMs + 60, words[k].startMs)
        }
        // Show every word slightly before its sound: viewers read a moment ahead, so this feels in sync.
        for (w in words) {
            w.startMs = (w.startMs - LEAD_MS).coerceAtLeast(0L)
            w.endMs = (w.endMs - LEAD_MS).coerceAtLeast(w.startMs + 120)
        }
    }

    companion object {
        const val SAMPLES_PER_FRAME = 160
        private const val CHUNK_MAX_FRAMES = 1200   // pieces up to 12 s
        private const val CHUNK_SPLIT_PAUSE = 25    // cut at every pause of 0.25 s or more (most accurate)
        private const val CHUNK_PAD = 15            // keep 150 ms of room around the voice
        private const val HOP = 160          // 10 ms at 16 kHz
        private const val WIN = 320          // 20 ms window
        private const val FRAME_MS = 10L
        private const val LEAD_MS = 100L     // show a word slightly before its sound (feels in sync)
        private const val MIN_PAUSE_FRAMES = 50 // 500 ms of silence counts as a pause
        private const val TAIL_MS = 150L     // keep it a moment after the voice stops
    }
}
