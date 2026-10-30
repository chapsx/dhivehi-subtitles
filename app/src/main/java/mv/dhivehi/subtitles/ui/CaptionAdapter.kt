package mv.dhivehi.subtitles.ui

import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import mv.dhivehi.subtitles.R
import mv.dhivehi.subtitles.captions.Caption
import java.util.Locale

class CaptionAdapter(
    private val typeface: Typeface,
    private val onTextEdited: (Int, String) -> Unit,
    private val onSeek: (Int) -> Unit,
    private val onMenu: (Int, View) -> Unit,
) : RecyclerView.Adapter<CaptionAdapter.Holder>() {

    var captions: List<Caption> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }

    var activeIndex: Int = -1
        set(value) {
            if (field == value) return
            val old = field
            field = value
            if (old in captions.indices) notifyItemChanged(old, PAYLOAD_ACTIVE)
            if (value in captions.indices) notifyItemChanged(value, PAYLOAD_ACTIVE)
        }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.card)
        val time: TextView = view.findViewById(R.id.time)
        val text: EditText = view.findViewById(R.id.text)
        val more: ImageButton = view.findViewById(R.id.more)
        var binding = false

        init {
            text.typeface = typeface
            text.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (binding) return
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) onTextEdited(pos, s?.toString() ?: "")
                }
            })
            time.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onSeek(pos)
            }
            text.setOnFocusChangeListener { _, hasFocus ->
                val pos = bindingAdapterPosition
                if (hasFocus && pos != RecyclerView.NO_POSITION) onSeek(pos)
            }
            more.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onMenu(pos, it)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_caption, parent, false))

    override fun getItemCount() = captions.size

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_ACTIVE)) bindActive(holder, position) else onBindViewHolder(holder, position)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val c = captions[position]
        holder.binding = true
        holder.time.text = "${fmt(c.startMs)}\n${fmt(c.endMs)}"
        if (holder.text.text.toString() != c.text) holder.text.setText(c.text)
        holder.binding = false
        bindActive(holder, position)
    }

    private fun bindActive(holder: Holder, position: Int) {
        val ctx = holder.card.context
        val active = position == activeIndex
        holder.card.strokeColor = ctx.getColor(if (active) R.color.accent else R.color.card_stroke)
        holder.card.strokeWidth = if (active) dp(holder.card, 2) else dp(holder.card, 1)
    }

    /** Refresh only the times (used after nudging), without disturbing typing. */
    fun refreshTimes(position: Int) {
        if (position in captions.indices) notifyItemChanged(position)
    }

    private fun dp(v: View, d: Int) = (d * v.resources.displayMetrics.density).toInt()

    companion object {
        private const val PAYLOAD_ACTIVE = "active"

        fun fmt(ms: Long): String {
            val t = ms.coerceAtLeast(0L)
            val m = t / 60000
            val s = (t % 60000) / 1000.0
            return String.format(Locale.US, "%d:%04.1f", m, s)
        }
    }
}
