package mv.dhivehi.subtitles.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.IOException
import java.nio.ByteOrder

/**
 * Pulls the sound out of a video and converts it to 16 kHz mono, which is what the speech model needs.
 */
object AudioDecoder {

    const val TARGET_RATE = 16000

    class NoAudioException : IOException("This video has no sound track.")

    fun decode(context: Context, uri: Uri, isCancelled: () -> Boolean, onProgress: (Float) -> Unit): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        var track = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                track = i; format = f; break
            }
        }
        if (track < 0 || format == null) {
            extractor.release()
            throw NoAudioException()
        }
        extractor.selectTrack(track)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var resampler = Resampler(format.getInteger(MediaFormat.KEY_SAMPLE_RATE), TARGET_RATE)
        var pcmFloat = false
        val out = FloatList(if (durationUs > 0) (durationUs / 1_000_000L * TARGET_RATE + TARGET_RATE).toInt() else 1 shl 20)

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                if (isCancelled()) throw InterruptedException()
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    val rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (rate != resampler.inRate) resampler = Resampler(rate, TARGET_RATE)
                    pcmFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (outIdx >= 0) {
                    val buf = codec.getOutputBuffer(outIdx)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.order(ByteOrder.nativeOrder())
                        val ch = channels.coerceAtLeast(1)
                        if (pcmFloat) {
                            val fb = buf.asFloatBuffer()
                            val frames = fb.remaining() / ch
                            for (f in 0 until frames) {
                                var sum = 0f
                                for (c in 0 until ch) sum += fb.get()
                                resampler.push(sum / ch, out)
                            }
                        } else {
                            val sb = buf.asShortBuffer()
                            val frames = sb.remaining() / ch
                            for (f in 0 until frames) {
                                var sum = 0
                                for (c in 0 until ch) sum += sb.get().toInt()
                                resampler.push(sum / (32768f * ch), out)
                            }
                        }
                        if (durationUs > 0) onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            try { codec.stop() } catch (e: Exception) {}
            codec.release()
            extractor.release()
        }
        resampler.flush(out)
        normalise(out)
        return out.toArray()
    }

    /** Gentle gain so quiet phone recordings are heard clearly by the model. */
    private fun normalise(list: FloatList) {
        var peak = 0f
        for (i in 0 until list.size) {
            val a = kotlin.math.abs(list.data[i])
            if (a > peak) peak = a
        }
        if (peak in 0.0001f..0.5f) {
            val g = (0.9f / peak).coerceAtMost(6f)
            for (i in 0 until list.size) list.data[i] *= g
        }
    }

    /** Streaming box-filter resampler (averages samples into each output slot, so no harsh aliasing). */
    private class Resampler(val inRate: Int, private val outRate: Int) {
        private var inIndex = 0L
        private var bin = 0L
        private var sum = 0f
        private var count = 0

        fun push(s: Float, out: FloatList) {
            val b = (inIndex * outRate * 2 + inRate) / (inRate * 2L) // round(inIndex * outRate / inRate)
            inIndex++
            if (b != bin) {
                if (count > 0) {
                    val v = sum / count
                    // upsampling: fill skipped slots by interpolation
                    val gap = (b - bin - 1).toInt()
                    out.add(v)
                    for (k in 1..gap) out.add(v + (s - v) * k / (gap + 1))
                }
                bin = b
                sum = 0f
                count = 0
            }
            sum += s
            count++
        }

        fun flush(out: FloatList) {
            if (count > 0) out.add(sum / count)
            count = 0
        }
    }

    class FloatList(initial: Int) {
        var data = FloatArray(initial.coerceAtLeast(1024))
        var size = 0
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }
        fun toArray(): FloatArray = data.copyOf(size)
    }
}
