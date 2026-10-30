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

    fun refine(words: List<Word>) {
        if (speech.isEmpty() || speech.none { it }) return
        for (idx in words.indices) {
            val w = words[idx]
            val prevEnd = if (idx > 0) words[idx - 1].endMs else 0L
            val nextStart = if (idx + 1 < words.size) words[idx + 1].startMs else Long.MAX_VALUE
            var s = frameOf(w.startMs)

            if (!isSpeech(s)) {
                // Starts in silence: move forward to where the voice begins (up to 400 ms).
                val limit = min(frameOf(min(w.endMs, nextStart)), s + 40)
                var f = s
                while (f < limit && !isSpeech(f)) f++
                if (f < limit) s = f
            } else {
                // Starts mid-sound: if the voice began just before (≤ 200 ms) and the previous word was
                // already over, start with the voice so the caption is never late.
                var f = s
                while (f > 0 && isSpeech(f - 1) && s - f < 20) f--
                if (!isSpeech(f - 1) && f.toLong() * FRAME_MS >= prevEnd) s = f
            }
            w.startMs = max(prevEnd, s.toLong() * FRAME_MS - LEAD_MS).coerceAtLeast(0L)

            // Ends in silence: pull the end back to just after the voice stops.
            val e = frameOf(w.endMs)
            if (!isSpeech(e - 1)) {
                var f = e - 1
                val first = frameOf(w.startMs)
                while (f > first && !isSpeech(f)) f--
                if (f > first) w.endMs = min(w.endMs, (f + 1).toLong() * FRAME_MS + TAIL_MS)
            }
            if (w.endMs < w.startMs + 120) w.endMs = w.startMs + 120
        }
        // Keep order and no overlaps after adjustments.
        for (k in 1 until words.size) {
            if (words[k].startMs < words[k - 1].endMs) words[k - 1].endMs = max(words[k - 1].startMs + 60, words[k].startMs)
        }
    }

    companion object {
        private const val HOP = 160          // 10 ms at 16 kHz
        private const val WIN = 320          // 20 ms window
        private const val FRAME_MS = 10L
        private const val LEAD_MS = 40L      // show a word a hair before its sound
        private const val TAIL_MS = 150L     // keep it a moment after the voice stops
    }
}
