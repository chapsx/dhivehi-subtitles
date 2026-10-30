package mv.dhivehi.subtitles.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import mv.dhivehi.subtitles.captions.Caption
import mv.dhivehi.subtitles.captions.CaptionRenderer
import mv.dhivehi.subtitles.captions.CaptionStyle
import mv.dhivehi.subtitles.captions.CaptionTimeline

/** Keeps its children in a 9:16 TikTok frame, as large as the available space allows. */
class PortraitFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec)
        val maxH = MeasureSpec.getSize(heightMeasureSpec)
        var w = maxW
        var h = w * 16 / 9
        if (maxH in 1 until h) {
            h = maxH
            w = h * 9 / 16
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
    }
}

/** Live caption preview drawn over the video, using exactly the same renderer as the export. */
class CaptionOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var renderer: CaptionRenderer? = null
    var style: CaptionStyle? = null
    var captions: List<Caption> = emptyList()
        set(value) { field = value; invalidate() }

    /** Caption-timeline time (video time minus sync offset). */
    var timeMs: Long = 0L
        set(value) {
            if (field != value) { field = value; invalidate() }
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = renderer ?: return
        val s = style ?: return
        val idx = CaptionTimeline.indexAt(captions, timeMs)
        if (idx >= 0) r.draw(canvas, width, height, captions[idx], timeMs, s)
    }
}
