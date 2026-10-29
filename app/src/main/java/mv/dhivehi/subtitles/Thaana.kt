package mv.dhivehi.subtitles

/**
 * Thaana helpers. The phonetic map follows the standard Dhivehi phonetic keyboard,
 * which is also the layout used by the old ASCII Thaana fonts (A_Faruma etc.),
 * so the same table converts legacy "Latin-typed" Dhivehi text to Unicode.
 */
object Thaana {
    private val MAP: Map<Char, Char> = mapOf(
        'h' to 'ހ', 'S' to 'ށ', 'n' to 'ނ', 'r' to 'ރ',
        'b' to 'ބ', 'L' to 'ޅ', 'k' to 'ކ', 'w' to 'އ',
        'v' to 'ވ', 'm' to 'މ', 'f' to 'ފ', 'd' to 'ދ',
        't' to 'ތ', 'l' to 'ލ', 'g' to 'ގ', 'N' to 'ޏ',
        's' to 'ސ', 'D' to 'ޑ', 'z' to 'ޒ', 'T' to 'ޓ',
        'y' to 'ޔ', 'p' to 'ޕ', 'j' to 'ޖ', 'c' to 'ޗ',
        'X' to 'ޘ', 'H' to 'ޙ', 'K' to 'ޚ', 'J' to 'ޛ',
        'R' to 'ޜ', 'C' to 'ޝ', 'B' to 'ޞ', 'M' to 'ޟ',
        'Y' to 'ޠ', 'Z' to 'ޡ', 'W' to 'ޢ', 'G' to 'ޣ',
        'Q' to 'ޤ', 'V' to 'ޥ',
        'a' to 'ަ', 'A' to 'ާ', 'i' to 'ި', 'I' to 'ީ',
        'u' to 'ު', 'U' to 'ޫ', 'e' to 'ެ', 'E' to 'ޭ',
        'o' to 'ޮ', 'O' to 'ޯ', 'q' to 'ް',
        ',' to '،', ';' to '؛', '?' to '؟', 'F' to 'ﷲ'
    )

    fun mapChar(c: Char): Char = MAP[c] ?: c

    fun isThaana(c: Char) = c in 'ހ'..'޿'

    fun containsThaana(s: CharSequence) = s.any { isThaana(it) }

    /** Converts Latin phonetic / ASCII-font text to Unicode Thaana, leaving tags like <i> or {\an8} alone. */
    fun fromLatin(s: String, reverseLines: Boolean = false): String {
        val lines = s.split('\n').map { line ->
            val sb = StringBuilder()
            var depthAngle = 0
            var depthCurly = 0
            for (c in line) {
                when (c) {
                    '<' -> { depthAngle++; sb.append(c) }
                    '>' -> { if (depthAngle > 0) depthAngle--; sb.append(c) }
                    '{' -> { depthCurly++; sb.append(c) }
                    '}' -> { if (depthCurly > 0) depthCurly--; sb.append(c) }
                    else -> sb.append(if (depthAngle > 0 || depthCurly > 0) c else mapChar(c))
                }
            }
            if (reverseLines) reverseVisual(sb.toString()) else sb.toString()
        }
        return lines.joinToString("\n")
    }

    /** Reverses a line that was stored in visual (left-to-right) order, keeping tags intact. */
    fun reverseVisual(line: String): String {
        // A Thaana letter and the vowel sign after it stay together.
        val tokens = Regex("""<[^>]*>|\{[^}]*\}|[\u0780-\u07A5\u07B1][\u07A6-\u07B0]*|.""")
            .findAll(line).map { it.value }.toList()
        val swapped = tokens.reversed().map {
            when (it) {
                "(" -> ")"; ")" -> "("; "[" -> "]"; "]" -> "["
                else -> it
            }
        }
        return swapped.joinToString("")
    }
}
