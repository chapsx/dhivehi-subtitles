package mv.dhivehi.subtitles

import android.text.InputFilter
import android.text.InputType
import android.text.Spanned
import android.widget.EditText

/** Turns Latin keystrokes into Thaana letters while typing (phonetic keyboard). */
class ThaanaInputFilter(private val enabled: () -> Boolean) : InputFilter {
    override fun filter(source: CharSequence, start: Int, end: Int, dest: Spanned, dstart: Int, dend: Int): CharSequence? {
        if (!enabled()) return null
        var changed = false
        val sb = StringBuilder(end - start)
        for (i in start until end) {
            val c = source[i]
            val m = Thaana.mapChar(c)
            if (m != c) changed = true
            sb.append(m)
        }
        return if (changed) sb.toString() else null
    }

    companion object {
        /** Attaches the filter and switches off keyboard word suggestions while it is on. */
        fun attach(edit: EditText, multiLine: Boolean, enabled: () -> Boolean) {
            edit.filters = edit.filters + ThaanaInputFilter(enabled)
            refresh(edit, multiLine, enabled())
        }

        fun refresh(edit: EditText, multiLine: Boolean, phonetic: Boolean) {
            var type = InputType.TYPE_CLASS_TEXT
            if (multiLine) type = type or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            type = if (phonetic) {
                type or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            } else {
                type or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            val sel = edit.selectionEnd
            val face = edit.typeface
            edit.inputType = type
            // The visible-password type forces a monospace font; restore the original one.
            edit.typeface = face
            // Visible-password type turns single-line on; undo that for subtitle text.
            if (multiLine) edit.isSingleLine = false
            if (sel >= 0 && sel <= edit.length()) edit.setSelection(sel)
        }
    }
}
