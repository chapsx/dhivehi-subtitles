package mv.dhivehi.subtitles

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.TextView
import java.io.File

/** App settings and subtitle style. */
class Prefs(context: Context) {
    private val app = context.applicationContext
    val sp: SharedPreferences = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var fontSize: Int
        get() = sp.getInt("font_size", 22)
        set(v) = sp.edit().putInt("font_size", v).apply()
    var textColor: Int
        get() = sp.getInt("text_color", Color.WHITE)
        set(v) = sp.edit().putInt("text_color", v).apply()
    var bgOpacity: Int
        get() = sp.getInt("bg_opacity", 45)
        set(v) = sp.edit().putInt("bg_opacity", v).apply()
    var outline: Boolean
        get() = sp.getBoolean("outline", true)
        set(v) = sp.edit().putBoolean("outline", v).apply()
    var bold: Boolean
        get() = sp.getBoolean("bold", false)
        set(v) = sp.edit().putBoolean("bold", v).apply()
    var bottomMargin: Int
        get() = sp.getInt("bottom_margin", 8)
        set(v) = sp.edit().putInt("bottom_margin", v).apply()
    var phonetic: Boolean
        get() = sp.getBoolean("phonetic", true)
        set(v) = sp.edit().putBoolean("phonetic", v).apply()
    var autoScroll: Boolean
        get() = sp.getBoolean("auto_scroll", true)
        set(v) = sp.edit().putBoolean("auto_scroll", v).apply()
    var writeBom: Boolean
        get() = sp.getBoolean("write_bom", true)
        set(v) = sp.edit().putBoolean("write_bom", v).apply()
    var defaultCueMs: Int
        get() = sp.getInt("default_cue_ms", 2500)
        set(v) = sp.edit().putInt("default_cue_ms", v).apply()

    val fontFile: File get() = File(app.filesDir, "custom_font.ttf")

    fun typeface(): Typeface {
        val f = fontFile
        val base = if (f.exists()) {
            try { Typeface.createFromFile(f) } catch (e: Exception) { Typeface.DEFAULT }
        } else Typeface.DEFAULT
        return Typeface.create(base, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    /** Applies the subtitle style to the on-video text view. */
    fun styleSubtitle(tv: TextView) {
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize.toFloat())
        tv.setTextColor(textColor)
        tv.typeface = typeface()
        val alpha = (bgOpacity * 255 / 100).coerceIn(0, 255)
        tv.setBackgroundColor(Color.argb(alpha, 0, 0, 0))
        if (outline) tv.setShadowLayer(6f, 0f, 0f, Color.BLACK) else tv.setShadowLayer(0f, 0f, 0f, 0)
    }

    companion object {
        val COLORS = intArrayOf(Color.WHITE, Color.YELLOW, Color.CYAN, Color.GREEN, 0xFFFFA726.toInt())
        val COLOR_NAMES = arrayOf("White", "Yellow", "Cyan", "Green", "Orange")
    }
}
