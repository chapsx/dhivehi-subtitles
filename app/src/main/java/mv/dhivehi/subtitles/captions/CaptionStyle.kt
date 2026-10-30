package mv.dhivehi.subtitles.captions

import android.content.Context
import android.graphics.Color

data class CaptionStyle(
    /** null = built-in Noto Sans Thaana; otherwise a path to an imported .ttf/.otf font. */
    var fontPath: String? = null,
    var fontWeight: Int = 800,
    /** Text size as a fraction of the video width. 0.095 = about 103px on a 1080px TikTok video. */
    var sizeFraction: Float = 0.095f,
    /** Vertical centre of the caption, as a fraction of video height (kept clear of TikTok's buttons). */
    var positionFraction: Float = 0.64f,
    var textColor: Int = Color.WHITE,
    var highlightColor: Int = 0xFFFFD60A.toInt(),
    var strokeColor: Int = Color.BLACK,
    var strokeFraction: Float = 0.13f,
    var shadow: Boolean = true,
    var boxed: Boolean = false,
    var boxColor: Int = 0xD9000000.toInt(),
    var highlightWords: Boolean = true,
    var popAnimation: Boolean = true,
    var wordsPerCaption: Int = 3,
    /** true = fill the 9:16 frame (crop the sides), false = fit the whole video with black bars. */
    var fillFrame: Boolean = true,
) {
    fun applyPreset(p: Preset) {
        when (p) {
            Preset.POP -> {
                textColor = Color.WHITE; highlightColor = 0xFFFFD60A.toInt(); strokeColor = Color.BLACK
                strokeFraction = 0.13f; shadow = true; boxed = false; highlightWords = true; popAnimation = true
            }
            Preset.CLEAN -> {
                textColor = Color.WHITE; highlightColor = Color.WHITE; strokeColor = 0xCC000000.toInt()
                strokeFraction = 0.06f; shadow = true; boxed = false; highlightWords = false; popAnimation = false
            }
            Preset.BOX -> {
                textColor = Color.WHITE; highlightColor = 0xFF3EE6A8.toInt(); strokeColor = Color.TRANSPARENT
                strokeFraction = 0f; shadow = false; boxed = true; highlightWords = true; popAnimation = true
            }
            Preset.NEON -> {
                textColor = 0xFFFFFFFF.toInt(); highlightColor = 0xFFFF3B8D.toInt(); strokeColor = 0xFF1B0A3A.toInt()
                strokeFraction = 0.15f; shadow = true; boxed = false; highlightWords = true; popAnimation = true
            }
        }
    }

    enum class Preset { POP, CLEAN, BOX, NEON }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putString("fontPath", fontPath)
            putInt("fontWeight", fontWeight)
            putFloat("size", sizeFraction)
            putFloat("pos", positionFraction)
            putInt("text", textColor)
            putInt("hl", highlightColor)
            putInt("stroke", strokeColor)
            putFloat("strokeF", strokeFraction)
            putBoolean("shadow", shadow)
            putBoolean("boxed", boxed)
            putInt("box", boxColor)
            putBoolean("hlWords", highlightWords)
            putBoolean("pop", popAnimation)
            putInt("wpc", wordsPerCaption)
            putBoolean("fill", fillFrame)
        }.apply()
    }

    companion object {
        private const val PREFS = "caption_style"

        fun load(context: Context): CaptionStyle {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = CaptionStyle()
            if (!p.contains("size")) return d
            return CaptionStyle(
                fontPath = p.getString("fontPath", null),
                fontWeight = p.getInt("fontWeight", d.fontWeight),
                sizeFraction = p.getFloat("size", d.sizeFraction),
                positionFraction = p.getFloat("pos", d.positionFraction),
                textColor = p.getInt("text", d.textColor),
                highlightColor = p.getInt("hl", d.highlightColor),
                strokeColor = p.getInt("stroke", d.strokeColor),
                strokeFraction = p.getFloat("strokeF", d.strokeFraction),
                shadow = p.getBoolean("shadow", d.shadow),
                boxed = p.getBoolean("boxed", d.boxed),
                boxColor = p.getInt("box", d.boxColor),
                highlightWords = p.getBoolean("hlWords", d.highlightWords),
                popAnimation = p.getBoolean("pop", d.popAnimation),
                wordsPerCaption = p.getInt("wpc", d.wordsPerCaption),
                fillFrame = p.getBoolean("fill", d.fillFrame),
            )
        }
    }
}
