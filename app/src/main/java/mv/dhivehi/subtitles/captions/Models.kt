package mv.dhivehi.subtitles.captions

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/** One spoken word with its timing in milliseconds. */
data class Word(var text: String, var startMs: Long, var endMs: Long) {
    fun copyWord() = Word(text, startMs, endMs)
}

/** One on-screen caption: a few words shown together. */
class Caption(words: List<Word>) {
    val words: MutableList<Word> = words.map { it.copyWord() }.toMutableList()
    var startMs: Long = words.firstOrNull()?.startMs ?: 0L
    var endMs: Long = words.lastOrNull()?.endMs ?: 0L

    val text: String get() = words.joinToString(" ") { it.text }

    fun copyCaption(): Caption = Caption(words).also { it.startMs = startMs; it.endMs = endMs }

    /** Replace the text, keeping word timings when the word count is unchanged. */
    fun setText(newText: String) {
        val parts = newText.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) {
            words.clear()
            return
        }
        if (parts.size == words.size) {
            parts.forEachIndexed { i, s -> words[i].text = s }
            return
        }
        // Word count changed: share the caption's time across the new words by length.
        val total = parts.sumOf { it.length + 1 }.toDouble()
        val span = max(1L, endMs - startMs).toDouble()
        var cursor = startMs.toDouble()
        words.clear()
        for (p in parts) {
            val d = span * (p.length + 1) / total
            words.add(Word(p, cursor.toLong(), (cursor + d).toLong()))
            cursor += d
        }
        words.last().endMs = endMs
    }

    /** Move the start or end, stretching the word timings to fit. */
    fun setTiming(newStart: Long, newEnd: Long) {
        val s = newStart.coerceAtLeast(0L)
        val e = max(s + 200L, newEnd)
        val oldSpan = max(1L, endMs - startMs).toDouble()
        val newSpan = (e - s).toDouble()
        for (w in words) {
            w.startMs = s + ((w.startMs - startMs) / oldSpan * newSpan).toLong()
            w.endMs = s + ((w.endMs - startMs) / oldSpan * newSpan).toLong()
        }
        startMs = s
        endMs = e
    }

    /** Index of the word being spoken at [t] (or the last one already spoken). */
    fun activeWordAt(t: Long): Int {
        var idx = 0
        for (i in words.indices) if (words[i].startMs <= t) idx = i
        return idx
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("s", startMs); put("e", endMs)
        put("w", JSONArray().apply {
            words.forEach { put(JSONObject().put("t", it.text).put("s", it.startMs).put("e", it.endMs)) }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): Caption {
            val arr = o.getJSONArray("w")
            val ws = (0 until arr.length()).map {
                val w = arr.getJSONObject(it)
                Word(w.getString("t"), w.getLong("s"), w.getLong("e"))
            }
            return Caption(ws).also { it.startMs = o.getLong("s"); it.endMs = o.getLong("e") }
        }
    }
}

object CaptionTimeline {
    /** Binary search for the caption visible at [t]; -1 when none. */
    fun indexAt(captions: List<Caption>, t: Long): Int {
        var lo = 0
        var hi = captions.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (captions[mid].startMs <= t) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (found >= 0 && t < captions[found].endMs) return found
        return -1
    }
}
