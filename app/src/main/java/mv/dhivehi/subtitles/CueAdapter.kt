package mv.dhivehi.subtitles

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.recyclerview.widget.RecyclerView

class CueAdapter(
    private val cues: List<Cue>,
    private val onClick: (Cue) -> Unit,
    private val onLongClick: (Cue, View) -> Unit
) : RecyclerView.Adapter<CueAdapter.VH>() {

    var selected: Cue? = null
    var active: Cue? = null
    var typeface: Typeface? = null

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val index: TextView = v.findViewById(R.id.cue_index)
        val times: TextView = v.findViewById(R.id.cue_times)
        val text: TextView = v.findViewById(R.id.cue_text)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_cue, parent, false))

    override fun getItemCount() = cues.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val c = cues[position]
        h.index.text = (position + 1).toString()
        val dur = (c.end - c.start) / 100 / 10.0
        h.times.text = "${SubtitleFormats.formatTime(c.start)}  →  ${SubtitleFormats.formatTime(c.end)}   (${dur}s)"
        h.text.text = if (c.text.isBlank()) "—" else render(c.text)
        typeface?.let { h.text.typeface = it }
        val ctx = h.itemView.context
        val bg = when {
            c === selected -> ContextCompat.getColor(ctx, R.color.cue_selected)
            c === active -> ContextCompat.getColor(ctx, R.color.cue_active)
            else -> 0
        }
        h.itemView.setBackgroundColor(bg)
        h.itemView.setOnClickListener { onClick(c) }
        h.itemView.setOnLongClickListener { onLongClick(c, it); true }
    }

    fun indexOf(c: Cue?): Int = if (c == null) -1 else cues.indexOfFirst { it === c }

    companion object {
        private val ASS_TAGS = Regex("""\{[^}]*\}""")
        fun render(text: String): CharSequence {
            val clean = text.replace(ASS_TAGS, "").trim()
            if (!clean.contains('<')) return clean
            return HtmlCompat.fromHtml(clean.replace("\n", "<br>"), HtmlCompat.FROM_HTML_MODE_LEGACY)
        }
    }
}
