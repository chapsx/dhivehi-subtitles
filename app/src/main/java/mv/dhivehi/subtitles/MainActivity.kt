package mv.dhivehi.subtitles

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        Files.keepAccess(this, uri, false)
        openEditor(video = uri)
    }

    private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        Files.keepAccess(this, uri, true)
        openEditor(subtitle = uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<MaterialButton>(R.id.btn_open_video).setOnClickListener {
            confirmDiscardDraft { pickVideo.launch(arrayOf("video/*", "audio/*")) }
        }
        findViewById<MaterialButton>(R.id.btn_open_url).setOnClickListener { confirmDiscardDraft { askUrl() } }
        findViewById<MaterialButton>(R.id.btn_open_subs).setOnClickListener {
            confirmDiscardDraft { pickSubtitle.launch(arrayOf("*/*")) }
        }
        findViewById<MaterialButton>(R.id.btn_new).setOnClickListener {
            confirmDiscardDraft { openEditor(newProject = true) }
        }
        findViewById<MaterialButton>(R.id.btn_continue).setOnClickListener {
            startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_RESTORE, true))
        }
        findViewById<MaterialButton>(R.id.btn_converter).setOnClickListener {
            startActivity(Intent(this, ConverterActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btn_help).setOnClickListener { showHelp(this) }
    }

    override fun onResume() {
        super.onResume()
        val draft = Draft(this)
        val btn = findViewById<MaterialButton>(R.id.btn_continue)
        btn.visibility = if (draft.exists) View.VISIBLE else View.GONE
        btn.text = getString(R.string.continue_project, draft.label())
    }

    private fun confirmDiscardDraft(then: () -> Unit) {
        if (!Draft(this).isDirty) return then()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.new_subtitles)
            .setMessage(R.string.discard_draft_msg)
            .setPositiveButton(R.string.start_new) { _, _ -> then() }
            .setNeutralButton(R.string.continue_last) { _, _ ->
                startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_RESTORE, true))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askUrl() {
        val input = EditText(this).apply {
            hint = "https://example.com/video.mp4"
            setSingleLine()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.open_url)
            .setView(padded(input))
            .setPositiveButton(R.string.open) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) openEditor(video = Uri.parse(text))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun padded(v: View): View {
        val pad = (20 * resources.displayMetrics.density).toInt()
        return android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(v)
        }
    }

    private fun openEditor(video: Uri? = null, subtitle: Uri? = null, newProject: Boolean = false) {
        val i = Intent(this, EditorActivity::class.java)
        video?.let { i.putExtra(EditorActivity.EXTRA_VIDEO, it.toString()) }
        subtitle?.let { i.putExtra(EditorActivity.EXTRA_SUBTITLE, it.toString()) }
        if (newProject) i.putExtra(EditorActivity.EXTRA_NEW, true)
        startActivity(i)
    }

    companion object {
        fun showHelp(activity: AppCompatActivity) {
            val tv = TextView(activity).apply {
                text = activity.getString(R.string.help_text)
                val pad = (20 * activity.resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, 0)
                setTextIsSelectable(true)
            }
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.help)
                .setView(android.widget.ScrollView(activity).apply { addView(tv) })
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }
}
