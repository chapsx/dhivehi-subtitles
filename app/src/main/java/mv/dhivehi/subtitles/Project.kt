package mv.dhivehi.subtitles

import android.content.Context
import android.net.Uri
import mv.dhivehi.subtitles.captions.Caption
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Captions for one video, saved on the phone so work is never lost if the app closes. */
class Project(val videoUri: Uri) {
    var captions: MutableList<Caption> = mutableListOf()
    /** Positive = captions appear later, negative = earlier. */
    var offsetMs: Long = 0L

    fun save(context: Context) {
        val o = JSONObject()
        o.put("uri", videoUri.toString())
        o.put("offset", offsetMs)
        o.put("captions", JSONArray().apply { captions.forEach { put(it.toJson()) } })
        fileFor(context, videoUri).writeText(o.toString())
    }

    companion object {
        private fun fileFor(context: Context, uri: Uri): File {
            val dir = File(context.filesDir, "projects").apply { mkdirs() }
            val hash = MessageDigest.getInstance("SHA-1").digest(uri.toString().toByteArray())
                .joinToString("") { "%02x".format(it) }
            return File(dir, "$hash.json")
        }

        fun load(context: Context, uri: Uri): Project? = try {
            val f = fileFor(context, uri)
            if (!f.exists()) null else {
                val o = JSONObject(f.readText())
                Project(uri).apply {
                    offsetMs = o.optLong("offset", 0L)
                    val arr = o.getJSONArray("captions")
                    for (i in 0 until arr.length()) captions.add(Caption.fromJson(arr.getJSONObject(i)))
                }
            }
        } catch (e: Exception) {
            null
        }

        fun delete(context: Context, uri: Uri) {
            fileFor(context, uri).delete()
        }
    }
}
