package mv.dhivehi.subtitles.whisper

import android.os.Build
import java.io.File

fun interface ProgressListener {
    fun onProgress(percent: Int)
}

/** Thin wrapper over the native whisper.cpp library (see app/src/main/cpp/jni.c). */
object WhisperLib {

    init {
        loadBestLibrary()
    }

    private fun loadBestLibrary() {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: ""
        val cpuInfo = try { File("/proc/cpuinfo").readText() } catch (e: Exception) { "" }
        val hasFp16 = cpuInfo.contains("fphp") && cpuInfo.contains("asimdhp")
        if (abi == "arm64-v8a" && hasFp16) {
            try {
                System.loadLibrary("whisper_v8fp16_va")
                return
            } catch (e: UnsatisfiedLinkError) {
                // fall back to the generic build below
            }
        }
        System.loadLibrary("whisper")
    }

    external fun initContext(modelPath: String): Long
    external fun freeContext(ptr: Long)
    external fun requestAbort()
    external fun transcribe(
        ptr: Long,
        samples: FloatArray,
        nThreads: Int,
        language: String,
        listener: ProgressListener?
    ): Int

    external fun segmentCount(ptr: Long): Int
    external fun segmentT0(ptr: Long, seg: Int): Long
    external fun segmentT1(ptr: Long, seg: Int): Long
    external fun tokenCount(ptr: Long, seg: Int): Int
    external fun isSpecialToken(ptr: Long, seg: Int, tok: Int): Boolean
    external fun tokenBytes(ptr: Long, seg: Int, tok: Int): ByteArray
    external fun tokenTiming(ptr: Long, seg: Int, tok: Int): LongArray
    external fun systemInfo(): String
}
