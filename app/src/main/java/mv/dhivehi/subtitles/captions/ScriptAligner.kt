package mv.dhivehi.subtitles.captions

import kotlin.math.max
import kotlin.math.min

/**
 * "Paste script" mode: the person supplies the exact Dhivehi text, and only the timing comes
 * from the speech model. Every written word is matched to the words the model heard (by spelling
 * similarity, allowing for misheard, missing, extra, split and joined words) and takes their
 * timing. Written words with no match are spread by length between their matched neighbours.
 *
 * The result has the script's exact words and punctuation, so spelling and fili are always right.
 */
object ScriptAligner {

    /** Similarity at or above which a written word counts as the word that was heard. */
    private const val MATCH_SIM = 0.45
    private const val GAP_COST = 0.75
    private const val MIN_WORD_MS = 90L
    /** Rough speaking pace used when there is nothing to anchor to (per letter). */
    private const val MS_PER_CHAR = 75L

    fun tokenize(script: String): List<String> =
        script.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    /**
     * @param heard words from the speech model, in time order, with timings.
     * @param totalMs length of the video (used when little or nothing was heard).
     */
    fun align(script: String, heard: List<Word>, totalMs: Long): List<Word> {
        val written = tokenize(script)
        if (written.isEmpty()) return emptyList()
        val h = heard.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
        val wKeys = written.map { key(it) }
        val hKeys = h.map { key(it.text) }

        val n = written.size
        val m = h.size
        // DP over (written i, heard j). Moves: 1:1, skip written, skip heard, 1 written : 2 heard, 2 written : 1 heard.
        val inf = Double.MAX_VALUE / 4
        val cost = Array(n + 1) { DoubleArray(m + 1) { inf } }
        val back = Array(n + 1) { IntArray(m + 1) { -1 } }
        cost[0][0] = 0.0
        for (i in 0..n) for (j in 0..m) {
            val c = cost[i][j]
            if (c >= inf) continue
            fun relax(ni: Int, nj: Int, add: Double, move: Int) {
                if (ni > n || nj > m) return
                val v = c + add
                if (v < cost[ni][nj]) { cost[ni][nj] = v; back[ni][nj] = move }
            }
            if (i < n && j < m) relax(i + 1, j + 1, 1.0 - sim(wKeys[i], hKeys[j]), MOVE_MATCH)
            if (i < n) relax(i + 1, j, GAP_COST, MOVE_SKIP_WRITTEN)
            if (j < m) relax(i, j + 1, GAP_COST, MOVE_SKIP_HEARD)
            if (i < n && j + 1 < m) relax(i + 1, j + 2, 1.15 - sim(wKeys[i], hKeys[j] + hKeys[j + 1]), MOVE_ONE_TWO)
            if (i + 1 < n && j < m) relax(i + 2, j + 1, 1.15 - sim(wKeys[i] + wKeys[i + 1], hKeys[j]), MOVE_TWO_ONE)
        }

        // Walk back and record timings for written words that matched well enough.
        val start = LongArray(n) { -1L }
        val end = LongArray(n) { -1L }
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            when (back[i][j]) {
                MOVE_MATCH -> {
                    if (sim(wKeys[i - 1], hKeys[j - 1]) >= MATCH_SIM) {
                        start[i - 1] = h[j - 1].startMs; end[i - 1] = h[j - 1].endMs
                    }
                    i--; j--
                }
                MOVE_SKIP_WRITTEN -> i--
                MOVE_SKIP_HEARD -> j--
                MOVE_ONE_TWO -> {
                    if (sim(wKeys[i - 1], hKeys[j - 2] + hKeys[j - 1]) >= MATCH_SIM) {
                        start[i - 1] = h[j - 2].startMs; end[i - 1] = h[j - 1].endMs
                    }
                    i--; j -= 2
                }
                MOVE_TWO_ONE -> {
                    if (sim(wKeys[i - 2] + wKeys[i - 1], hKeys[j - 1]) >= MATCH_SIM) {
                        val w = h[j - 1]
                        val a = written[i - 2].length.toDouble()
                        val b = written[i - 1].length.toDouble()
                        val mid = w.startMs + ((w.endMs - w.startMs) * a / (a + b)).toLong()
                        start[i - 2] = w.startMs; end[i - 2] = mid
                        start[i - 1] = mid; end[i - 1] = w.endMs
                    }
                    i -= 2; j--
                }
                else -> break
            }
        }

        // A match that would put words out of order is dropped (it will be filled in instead).
        var lastEnd = -1L
        for (k in 0 until n) {
            if (start[k] < 0) continue
            if (start[k] < lastEnd) { start[k] = -1L; end[k] = -1L } else lastEnd = end[k]
        }

        fill(written, start, end, h, totalMs)
        val out = written.indices.map { Word(written[it], start[it], end[it]) }
        for (k in 1 until out.size) {
            if (out[k].startMs < out[k - 1].endMs) out[k - 1].endMs = max(out[k - 1].startMs + 40, out[k].startMs)
        }
        return out
    }

    /** Gives every unmatched written word a time, by length, between the matched words around it. */
    private fun fill(written: List<String>, start: LongArray, end: LongArray, heard: List<Word>, totalMs: Long) {
        val n = written.size
        val anchors = (0 until n).filter { start[it] >= 0 }
        val speechStart = heard.firstOrNull()?.startMs ?: 0L
        val speechEnd = heard.lastOrNull()?.endMs ?: totalMs
        if (anchors.isEmpty()) {
            spread(written, 0, n, start, end, speechStart, max(speechEnd, speechStart + n * 300L))
            return
        }
        // Before the first match.
        val first = anchors.first()
        if (first > 0) {
            val need = (0 until first).sumOf { written[it].length * MS_PER_CHAR + 60 }
            val to = start[first]
            val from = max(0L, to - need)
            spread(written, 0, first, start, end, from, max(to, from + first * MIN_WORD_MS))
        }
        // Between matches.
        for (k in 0 until anchors.size - 1) {
            val a = anchors[k]
            val b = anchors[k + 1]
            if (b - a <= 1) continue
            val count = b - a - 1
            var from = end[a]
            val to = start[b]
            if (to - from < count * MIN_WORD_MS) {
                // Not enough room: share the matched word on the left with the missing ones.
                from = start[a] + (end[a] - start[a]) / 3
                end[a] = from
            }
            spread(written, a + 1, b, start, end, from, max(to, from + count * MIN_WORD_MS))
        }
        // After the last match.
        val last = anchors.last()
        if (last < n - 1) {
            val need = (last + 1 until n).sumOf { written[it].length * MS_PER_CHAR + 60 }
            val from = end[last]
            val limit = if (totalMs > from) totalMs else from + need
            val to = min(limit, max(speechEnd, from + need))
            spread(written, last + 1, n, start, end, from, max(to, from + (n - last - 1) * MIN_WORD_MS))
        }
    }

    private fun spread(written: List<String>, from: Int, to: Int, start: LongArray, end: LongArray, t0: Long, t1: Long) {
        if (to <= from) return
        val weights = (from until to).map { written[it].length + 1.0 }
        val total = weights.sum()
        val span = max(1L, t1 - t0).toDouble()
        var cursor = t0.toDouble()
        for ((k, idx) in (from until to).withIndex()) {
            val d = span * weights[k] / total
            start[idx] = cursor.toLong()
            end[idx] = (cursor + d).toLong()
            cursor += d
        }
        end[to - 1] = t1
    }

    /** Letters only (Thaana consonants + fili, digits); punctuation and spaces are ignored. */
    internal fun key(s: String): String = s.filter { it.isLetterOrDigit() || Character.getType(it) == Character.NON_SPACING_MARK.toInt() }

    /** 1 = same spelling, 0 = nothing in common (normalised edit distance). */
    internal fun sim(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (x in 1..a.length) {
            cur[0] = x
            for (y in 1..b.length) {
                val sub = if (a[x - 1] == b[y - 1]) 0 else 1
                cur[y] = min(min(cur[y - 1] + 1, prev[y] + 1), prev[y - 1] + sub)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return 1.0 - prev[b.length].toDouble() / max(a.length, b.length)
    }

    private const val MOVE_MATCH = 0
    private const val MOVE_SKIP_WRITTEN = 1
    private const val MOVE_SKIP_HEARD = 2
    private const val MOVE_ONE_TWO = 3
    private const val MOVE_TWO_ONE = 4
}
