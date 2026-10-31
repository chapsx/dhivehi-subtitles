package mv.dhivehi.subtitles.captions

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import kotlin.math.pow

/**
 * Draws one Thaana caption. The same code draws the live preview and the exported video,
 * so what you see in the editor is exactly what ends up on TikTok.
 * Android's text engine shapes Thaana (letters + fili) correctly and lays it out right-to-left.
 */
class CaptionRenderer(private val context: Context) {

    private var cachedKey: String? = null
    private var cachedTypeface: Typeface = Typeface.DEFAULT_BOLD

    private val fillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val strokePaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    @Synchronized
    fun typeface(style: CaptionStyle): Typeface {
        val key = "${style.fontPath}|${style.fontId}|${style.fontWeight}"
        if (key == cachedKey) return cachedTypeface
        val tf: Typeface? = try {
            val path = style.fontPath
            if (path != null) {
                Typeface.createFromFile(path)
            } else {
                val f = BuiltInFonts.byId(style.fontId)
                if (f.variable) {
                    Typeface.Builder(context.assets, f.asset)
                        .setFontVariationSettings("'wght' ${style.fontWeight}")
                        .build()
                } else {
                    Typeface.createFromAsset(context.assets, f.asset)
                }
            }
        } catch (e: Exception) {
            null
        }
        cachedTypeface = tf ?: Typeface.DEFAULT_BOLD
        cachedKey = key
        return cachedTypeface
    }

    /** Typeface of a built-in font, for showing previews in the font picker. */
    fun previewTypeface(font: BuiltInFonts.Font): Typeface = try {
        if (font.variable) Typeface.Builder(context.assets, font.asset).setFontVariationSettings("'wght' 800").build()
        else Typeface.createFromAsset(context.assets, font.asset)
    } catch (e: Exception) {
        Typeface.DEFAULT_BOLD
    }

    private fun sizeScale(style: CaptionStyle): Float =
        if (style.fontPath != null) 1f else BuiltInFonts.byId(style.fontId).scale

    private fun layoutOf(text: CharSequence, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setTextDirection(TextDirectionHeuristics.RTL)
            .setLineSpacing(0f, 1.04f)
            .setIncludePad(true)
            .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
            .build()

    /**
     * @param timeMs current time on the caption timeline (already corrected by the sync offset).
     */
    @Synchronized
    fun draw(canvas: Canvas, width: Int, height: Int, caption: Caption, timeMs: Long, style: CaptionStyle) {
        if (caption.words.isEmpty() || width <= 0 || height <= 0) return

        val sb = StringBuilder()
        val ranges = ArrayList<IntArray>(caption.words.size)
        caption.words.forEachIndexed { i, w ->
            if (i > 0) sb.append(' ')
            val s = sb.length
            sb.append(w.text)
            ranges.add(intArrayOf(s, sb.length))
        }
        val plain = sb.toString()
        val maxW = (width * 0.76f).toInt() // stays clear of TikTok's side buttons
        val tf = typeface(style)
        var textSize = style.sizeFraction * width * sizeScale(style)

        fun configure(size: Float) {
            fillPaint.typeface = tf
            fillPaint.textSize = size
            fillPaint.color = style.textColor
            fillPaint.style = Paint.Style.FILL
            strokePaint.typeface = tf
            strokePaint.textSize = size
            strokePaint.color = style.strokeColor
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeJoin = Paint.Join.ROUND
            strokePaint.strokeCap = Paint.Cap.ROUND
            strokePaint.strokeWidth = size * style.strokeFraction
            val hasStroke = style.strokeFraction > 0f && android.graphics.Color.alpha(style.strokeColor) > 0
            val shadowPaint = if (hasStroke) strokePaint else fillPaint
            fillPaint.clearShadowLayer()
            strokePaint.clearShadowLayer()
            if (style.shadow && !style.boxed) {
                shadowPaint.setShadowLayer(size * 0.09f, 0f, size * 0.06f, 0x99000000.toInt())
            }
        }

        configure(textSize)
        var probe = layoutOf(plain, fillPaint, maxW)
        var tries = 0
        while (probe.lineCount > 2 && tries < 6) {
            textSize *= 0.88f
            configure(textSize)
            probe = layoutOf(plain, fillPaint, maxW)
            tries++
        }

        val spanned = SpannableString(plain)
        if (style.highlightWords && style.highlightColor != style.textColor) {
            val a = caption.activeWordAt(timeMs)
            if (a in ranges.indices) {
                spanned.setSpan(ForegroundColorSpan(style.highlightColor), ranges[a][0], ranges[a][1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        val fillLayout = layoutOf(spanned, fillPaint, maxW)
        val hasStroke = style.strokeFraction > 0f && android.graphics.Color.alpha(style.strokeColor) > 0
        val strokeLayout = if (hasStroke) layoutOf(plain, strokePaint, maxW) else null

        val blockH = fillLayout.height.toFloat()
        val minTop = height * 0.06f
        val maxTop = height * 0.94f - blockH
        val top = (style.positionFraction * height - blockH / 2f).coerceIn(minTop, maxOf(minTop, maxTop))
        val left = (width - maxW) / 2f

        val scale = if (style.popAnimation) {
            val p = ((timeMs - caption.startMs) / 150f).coerceIn(0f, 1f)
            0.80f + 0.20f * easeOutBack(p)
        } else 1f

        canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale, scale, maxW / 2f, blockH / 2f)

        if (style.boxed) {
            boxPaint.color = style.boxColor
            val padH = textSize * 0.30f
            val padV = textSize * 0.06f
            val r = textSize * 0.24f
            for (i in 0 until fillLayout.lineCount) {
                canvas.drawRoundRect(
                    fillLayout.getLineLeft(i) - padH,
                    fillLayout.getLineTop(i) - padV,
                    fillLayout.getLineRight(i) + padH,
                    fillLayout.getLineBottom(i) + padV,
                    r, r, boxPaint
                )
            }
        }
        strokeLayout?.draw(canvas)
        fillLayout.draw(canvas)
        canvas.restore()
    }

    private fun easeOutBack(p: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        return 1f + c3 * (p - 1f).pow(3) + c1 * (p - 1f).pow(2)
    }

    companion object {
        const val BUILT_IN_FONT = "fonts/NotoSansThaana.ttf"
    }
}
