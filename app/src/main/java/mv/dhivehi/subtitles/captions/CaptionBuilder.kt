package mv.dhivehi.subtitles.captions

import kotlin.math.max
import kotlin.math.min

/** Groups words into short, punchy TikTok-style captions. */
object CaptionBuilder {

    private val sentenceEnd = Regex("[.!?،؟,:;]$")

    fun build(words: List<Word>, maxWords: Int, maxChars: Int = 22): MutableList<Caption> {
        val out = mutableListOf<Caption>()
        val current = mutableListOf<Word>()
        var chars = 0

        fun flush() {
            if (current.isNotEmpty()) out.add(Caption(current))
            current.clear()
            chars = 0
        }

        for (w in words) {
            if (current.isNotEmpty()) {
                val last = current.last()
                val gap = w.startMs - last.endMs
                val tooLong = current.size >= maxWords || chars + 1 + w.text.length > maxChars
                if (tooLong || gap > 700 || sentenceEnd.containsMatchIn(last.text)) flush()
            }
            current.add(w)
            chars += w.text.length + if (current.size > 1) 1 else 0
        }
        flush()
        smooth(out)
        return out
    }

    /** Close tiny gaps (avoids flicker) and give every caption enough time to be read. */
    fun smooth(caps: MutableList<Caption>) {
        for (i in caps.indices) {
            val c = caps[i]
            val nextStart = if (i + 1 < caps.size) caps[i + 1].startMs else Long.MAX_VALUE
            if (nextStart - c.endMs in 0L..400L) c.endMs = nextStart
            val minEnd = c.startMs + 450
            if (c.endMs < minEnd) c.endMs = min(minEnd, nextStart)
            if (c.endMs > nextStart) c.endMs = nextStart
            c.endMs = max(c.endMs, c.startMs + 100)
        }
    }

    /** Re-group after the user changes words-per-caption, keeping their text edits. */
    fun regroup(caps: List<Caption>, maxWords: Int): MutableList<Caption> =
        build(caps.flatMap { c -> c.words.map { it.copyWord() } }, maxWords)
}
