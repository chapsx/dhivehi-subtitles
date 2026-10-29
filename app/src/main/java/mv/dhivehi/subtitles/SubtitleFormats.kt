package mv.dhivehi.subtitles

import java.util.Locale

/** Reads SRT, WebVTT and ASS/SSA subtitles and writes SRT and WebVTT. */
object SubtitleFormats {

    private val TIMING = Regex(
        """((?:\d+:)?\d{1,2}:\d{1,2}(?:[,.:]\d{1,3})?)\s*-->\s*((?:\d+:)?\d{1,2}:\d{1,2}(?:[,.:]\d{1,3})?)"""
    )

    fun parse(content: String, fileName: String? = null): List<Cue> {
        val text = normalize(content)
        val name = fileName?.lowercase(Locale.ROOT) ?: ""
        val looksAss = name.endsWith(".ass") || name.endsWith(".ssa") ||
            (text.contains("[Events]", ignoreCase = true) && text.contains("Dialogue:"))
        val cues = if (looksAss) parseAss(text) else parseSrtOrVtt(text)
        return cues.filter { it.end >= it.start }.sortedBy { it.start }
    }

    fun normalize(s: String): String =
        s.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')

    private fun parseSrtOrVtt(text: String): List<Cue> {
        val out = ArrayList<Cue>()
        val lines = text.split('\n')
        var i = 0
        while (i < lines.size) {
            val m = TIMING.find(lines[i])
            if (m == null) {
                i++
                continue
            }
            val start = parseTime(m.groupValues[1])
            val end = parseTime(m.groupValues[2])
            i++
            val body = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                // A missing blank line before the next cue: stop at its timing line.
                if (TIMING.containsMatchIn(lines[i])) break
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i].trimEnd())
                i++
            }
            // Drop a trailing SRT index number that belongs to the next cue.
            var bodyText = body.toString()
            if (i < lines.size && TIMING.containsMatchIn(lines[i])) {
                val parts = bodyText.split('\n')
                if (parts.size > 1 && parts.last().trim().all { it.isDigit() }) {
                    bodyText = parts.dropLast(1).joinToString("\n")
                }
            }
            if (start != null && end != null) {
                out.add(Cue(start, end, stripVttTags(bodyText)))
            }
        }
        return out
    }

    private fun stripVttTags(s: String): String =
        s.replace(Regex("""</?(c|v|lang|ruby|rt)(\.[^\s>]*)?(\s[^>]*)?>"""), "")
            .replace(Regex("""<\d{2}:\d{2}[:.\d]*>"""), "")

    private fun parseAss(text: String): List<Cue> {
        val out = ArrayList<Cue>()
        var inEvents = false
        var fields = listOf("Layer", "Start", "End", "Style", "Name", "MarginL", "MarginR", "MarginV", "Effect", "Text")
        for (raw in text.split('\n')) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                inEvents = line.equals("[Events]", ignoreCase = true)
                continue
            }
            if (!inEvents) continue
            if (line.startsWith("Format:", ignoreCase = true)) {
                fields = line.substringAfter(':').split(',').map { it.trim() }
                continue
            }
            if (!line.startsWith("Dialogue:", ignoreCase = true)) continue
            val values = line.substringAfter(':').split(',', limit = fields.size)
            if (values.size < fields.size) continue
            val startIdx = fields.indexOfFirst { it.equals("Start", true) }
            val endIdx = fields.indexOfFirst { it.equals("End", true) }
            val textIdx = fields.indexOfFirst { it.equals("Text", true) }
            if (startIdx < 0 || endIdx < 0 || textIdx < 0) continue
            val start = parseTime(values[startIdx].trim()) ?: continue
            val end = parseTime(values[endIdx].trim()) ?: continue
            val body = values[textIdx]
                .replace(Regex("""\{[^}]*\}"""), "")
                .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
                .trim()
            out.add(Cue(start, end, body))
        }
        return out
    }

    /** Parses "hh:mm:ss,mmm", "mm:ss.mmm", "h:mm:ss.cc" (ASS) and similar. */
    fun parseTime(s: String): Long? {
        val t = s.trim()
        val m = Regex("""^(?:(\d+):)?(\d{1,2}):(\d{1,2})(?:[,.:](\d{1,3}))?$""").find(t) ?: return null
        val h = m.groupValues[1].ifEmpty { "0" }.toLong()
        val min = m.groupValues[2].toLong()
        val sec = m.groupValues[3].toLong()
        val fracStr = m.groupValues[4]
        val frac = when (fracStr.length) {
            0 -> 0L
            1 -> fracStr.toLong() * 100
            2 -> fracStr.toLong() * 10
            else -> fracStr.toLong()
        }
        return ((h * 60 + min) * 60 + sec) * 1000 + frac
    }

    /** Lenient parser for user input: accepts time codes or plain seconds ("12.5"). */
    fun parseUserTime(s: String): Long? {
        val t = s.trim()
        if (t.isEmpty()) return null
        t.toDoubleOrNull()?.let { return (it * 1000).toLong() }
        return parseTime(t)
    }

    fun formatTime(ms: Long, msSeparator: Char = ','): String {
        val v = if (ms < 0) 0 else ms
        val h = v / 3_600_000
        val m = (v / 60_000) % 60
        val s = (v / 1000) % 60
        val f = v % 1000
        return String.format(Locale.ROOT, "%02d:%02d:%02d%c%03d", h, m, s, msSeparator, f)
    }

    fun formatShort(ms: Long): String {
        val v = if (ms < 0) 0 else ms
        val m = v / 60_000
        val s = (v / 1000) % 60
        val f = (v % 1000) / 100
        return String.format(Locale.ROOT, "%d:%02d.%d", m, s, f)
    }

    fun toSrt(cues: List<Cue>): String {
        val sb = StringBuilder()
        cues.sortedBy { it.start }.forEachIndexed { i, c ->
            sb.append(i + 1).append("\r\n")
            sb.append(formatTime(c.start)).append(" --> ").append(formatTime(c.end)).append("\r\n")
            sb.append(c.text.trim().replace("\n", "\r\n")).append("\r\n\r\n")
        }
        return sb.toString()
    }

    fun toVtt(cues: List<Cue>): String {
        val sb = StringBuilder("WEBVTT\n\n")
        cues.sortedBy { it.start }.forEachIndexed { i, c ->
            sb.append(i + 1).append('\n')
            sb.append(formatTime(c.start, '.')).append(" --> ").append(formatTime(c.end, '.')).append('\n')
            sb.append(c.text.trim()).append("\n\n")
        }
        return sb.toString()
    }
}
