package mv.dhivehi.subtitles.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import mv.dhivehi.subtitles.captions.Caption
import mv.dhivehi.subtitles.captions.CaptionRenderer
import mv.dhivehi.subtitles.captions.CaptionStyle
import mv.dhivehi.subtitles.captions.CaptionTimeline
import java.io.File

/**
 * Burns the Thaana captions into the video and produces a TikTok-ready file:
 * 1080 x 1920 (9:16), H.264 video at ~10 Mbps, AAC audio, MP4.
 */
@UnstableApi
class VideoExporter(context: Context) {

    interface Listener {
        fun onProgress(percent: Int)
        fun onDone(file: File)
        fun onFailed(message: String)
    }

    private val appContext = context.applicationContext
    private var transformer: Transformer? = null
    private val handler = Handler(Looper.getMainLooper())

    fun start(
        input: Uri,
        captions: List<Caption>,
        style: CaptionStyle,
        offsetMs: Long,
        output: File,
        listener: Listener
    ) {
        output.delete()
        val snapshot = captions.map { it.copyCaption() }
        val styleCopy = style.copy()

        val presentation = Presentation.createForWidthAndHeight(
            WIDTH, HEIGHT,
            if (styleCopy.fillFrame) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT
        )
        val overlay = CaptionOverlay(CaptionRenderer(appContext), snapshot, styleCopy, offsetMs)
        val videoEffects: List<Effect> = listOf(presentation, OverlayEffect(ImmutableList.of<TextureOverlay>(overlay)))
        val audioEffects: List<AudioProcessor> = emptyList()

        val edited = EditedMediaItem.Builder(MediaItem.fromUri(input))
            .setEffects(Effects(audioEffects, videoEffects))
            .build()
        val composition = Composition.Builder(EditedMediaItemSequence(ImmutableList.of(edited)))
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()

        val encoderFactory = DefaultEncoderFactory.Builder(appContext)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(10_000_000).build())
            .setEnableFallback(true)
            .build()

        val t = Transformer.Builder(appContext)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    stopPolling()
                    transformer = null
                    listener.onDone(output)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    stopPolling()
                    transformer = null
                    listener.onFailed(exportException.message ?: "Export failed")
                }
            })
            .build()
        transformer = t
        t.start(composition, output.absolutePath)
        startPolling(listener)
    }

    fun cancel() {
        stopPolling()
        transformer?.cancel()
        transformer = null
    }

    private val holder = ProgressHolder()
    private var poll: Runnable? = null

    private fun startPolling(listener: Listener) {
        val r = object : Runnable {
            override fun run() {
                val t = transformer ?: return
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) listener.onProgress(holder.progress)
                handler.postDelayed(this, 300)
            }
        }
        poll = r
        handler.post(r)
    }

    private fun stopPolling() {
        poll?.let { handler.removeCallbacks(it) }
        poll = null
    }

    /** Supplies a full-frame transparent bitmap with the caption drawn on it, for each video frame. */
    private class CaptionOverlay(
        private val renderer: CaptionRenderer,
        private val captions: List<Caption>,
        private val style: CaptionStyle,
        private val offsetMs: Long
    ) : BitmapOverlay() {

        // Two alternating buffers: a new object each time the picture changes tells Media3 to re-upload it.
        private val buffers = arrayOf(
            Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888),
            Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        )
        private var current = 0
        private var lastKey = Long.MIN_VALUE

        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            val t = presentationTimeUs / 1000L - offsetMs
            val idx = CaptionTimeline.indexAt(captions, t)
            val key: Long = if (idx < 0) -1L else {
                val c = captions[idx]
                val word = if (style.highlightWords) c.activeWordAt(t) else 0
                val anim = if (style.popAnimation) ((t - c.startMs) / 30L).coerceIn(0L, 6L) else 0L
                idx * 1000L + word * 10L + anim
            }
            if (key == lastKey) return buffers[current]
            current = 1 - current
            val bmp = buffers[current]
            bmp.eraseColor(Color.TRANSPARENT)
            if (idx >= 0) renderer.draw(Canvas(bmp), WIDTH, HEIGHT, captions[idx], t, style)
            lastKey = key
            return bmp
        }
    }

    companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1920
    }
}
