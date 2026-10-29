package mv.dhivehi.subtitles

import android.content.Context
import android.net.Uri
import java.io.File

/** Autosaved working copy, so nothing is lost if the app is closed. */
class Draft(context: Context) {
    private val app = context.applicationContext
    private val sp = app.getSharedPreferences("draft", Context.MODE_PRIVATE)
    private val file get() = File(app.filesDir, "draft.srt")

    val exists: Boolean get() = sp.getBoolean("exists", false) && file.exists()

    data class State(
        val cues: List<Cue>,
        val video: Uri?,
        val subtitle: Uri?,
        val name: String,
        val offset: Long,
        val position: Long,
        val dirty: Boolean
    )

    fun save(s: State) {
        file.writeText(SubtitleFormats.toSrt(s.cues), Charsets.UTF_8)
        sp.edit()
            .putBoolean("exists", true)
            .putString("video", s.video?.toString())
            .putString("subtitle", s.subtitle?.toString())
            .putString("name", s.name)
            .putLong("offset", s.offset)
            .putLong("position", s.position)
            .putBoolean("dirty", s.dirty)
            .apply()
    }

    fun load(): State? {
        if (!exists) return null
        return try {
            State(
                SubtitleFormats.parse(file.readText(Charsets.UTF_8)),
                sp.getString("video", null)?.let(Uri::parse),
                sp.getString("subtitle", null)?.let(Uri::parse),
                sp.getString("name", null) ?: "subtitles.srt",
                sp.getLong("offset", 0),
                sp.getLong("position", 0),
                sp.getBoolean("dirty", false)
            )
        } catch (e: Exception) {
            null
        }
    }

    val isDirty: Boolean get() = exists && sp.getBoolean("dirty", false)

    fun label(): String = sp.getString("name", null) ?: "subtitles.srt"

    fun clear() {
        file.delete()
        sp.edit().clear().apply()
    }
}
