package mv.dhivehi.subtitles

/** One subtitle entry. Times are in milliseconds. */
data class Cue(var start: Long, var end: Long, var text: String) {
    fun copyCue() = Cue(start, end, text)
}
