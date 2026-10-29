package mv.dhivehi.subtitles

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@OptIn(UnstableApi::class)
class EditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO = "video"
        const val EXTRA_SUBTITLE = "subtitle"
        const val EXTRA_NEW = "new"
        const val EXTRA_RESTORE = "restore"
        private const val MAX_UNDO = 60
    }

    private lateinit var prefs: Prefs
    private lateinit var draft: Draft
    private lateinit var playerView: PlayerView
    private lateinit var placeholder: TextView
    private lateinit var overlay: TextView
    private lateinit var status: TextView
    private lateinit var list: RecyclerView
    private lateinit var panel: View
    private lateinit var adapter: CueAdapter

    private var player: ExoPlayer? = null
    private val cues = ArrayList<Cue>()
    private var videoUri: Uri? = null
    private var subtitleUri: Uri? = null
    private var fileName = "subtitles.srt"
    private var offsetMs = 0L
    private var dirty = false
    private var resumePos = 0L
    private var resumePlay = false
    private var fullscreen = false
    private var activeCue: Cue? = null
    private val undoStack = ArrayDeque<List<Cue>>()
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            updateActiveCue(false)
            handler.postDelayed(this, 50)
        }
    }

    // ---- File pickers ----

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        Files.keepAccess(this, uri, false)
        setVideo(uri)
    }

    private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        Files.keepAccess(this, uri, true)
        loadSubtitles(uri)
    }

    private val createSrt = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        uri ?: return@registerForActivityResult
        Files.keepAccess(this, uri, true)
        writeTo(uri, Files.displayName(this, uri), vtt = false, becomeTarget = true)
    }

    private val createVtt = registerForActivityResult(ActivityResultContracts.CreateDocument("text/vtt")) { uri ->
        uri ?: return@registerForActivityResult
        writeTo(uri, Files.displayName(this, uri), vtt = true, becomeTarget = false)
    }

    // ---- Lifecycle ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        prefs = Prefs(this)
        draft = Draft(this)

        playerView = findViewById(R.id.player_view)
        placeholder = findViewById(R.id.placeholder)
        status = findViewById(R.id.status)
        list = findViewById(R.id.cue_list)
        panel = findViewById(R.id.editor_panel)

        overlay = TextView(this).apply {
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_ANY_RTL
            val p = (6 * resources.displayMetrics.density).toInt()
            setPadding(p * 2, p / 2, p * 2, p / 2)
            visibility = View.GONE
        }
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        )
        val side = (12 * resources.displayMetrics.density).toInt()
        lp.leftMargin = side
        lp.rightMargin = side
        playerView.overlayFrameLayout?.addView(overlay, lp)
        // Our own overlay draws the subtitles; hide any embedded subtitle track.
        playerView.subtitleView?.visibility = View.GONE
        playerView.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) applyOverlayPosition()
        }
        playerView.setFullscreenButtonClickListener { toggleFullscreen() }

        adapter = CueAdapter(cues, onClick = { c ->
            select(c)
            seekTo(c.start + offsetMs)
        }, onLongClick = { c, v -> showCueMenu(c, v) })
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<MaterialButton>(R.id.btn_add).setOnClickListener { addCue() }
        findViewById<MaterialButton>(R.id.btn_edit).setOnClickListener {
            val c = currentTarget() ?: return@setOnClickListener toast(R.string.tap_subtitle_first)
            editCue(c)
        }
        findViewById<MaterialButton>(R.id.btn_set_start).setOnClickListener { setStartNow() }
        findViewById<MaterialButton>(R.id.btn_set_end).setOnClickListener { setEndNow() }
        findViewById<MaterialButton>(R.id.btn_sync_minus).apply {
            setOnClickListener { changeOffset(-100) }
            setOnLongClickListener { changeOffset(-1000); true }
        }
        findViewById<MaterialButton>(R.id.btn_sync_plus).apply {
            setOnClickListener { changeOffset(100) }
            setOnLongClickListener { changeOffset(1000); true }
        }
        findViewById<MaterialButton>(R.id.btn_prev).setOnClickListener { jumpCue(-1) }
        findViewById<MaterialButton>(R.id.btn_next).setOnClickListener { jumpCue(1) }
        placeholder.setOnClickListener { pickVideo.launch(arrayOf("video/*", "audio/*")) }

        onBackPressedDispatcher.addCallback(this) {
            if (fullscreen) {
                toggleFullscreen()
            } else if (dirty) {
                askSaveBeforeExit()
            } else {
                finish()
            }
        }

        loadFromIntent(intent)
        applyLayoutMode(resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
    }

    override fun onStart() {
        super.onStart()
        initPlayer()
    }

    override fun onResume() {
        super.onResume()
        prefs.styleSubtitle(overlay)
        adapter.typeface = prefs.typeface()
        adapter.notifyDataSetChanged()
        applyOverlayPosition()
        updateActiveCue(true)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        saveDraft()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
        saveDraft()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayoutMode(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE)
    }

    // ---- Loading ----

    private fun loadFromIntent(i: Intent) {
        if (i.getBooleanExtra(EXTRA_RESTORE, false)) {
            val s = draft.load()
            if (s != null) {
                cues.clear(); cues.addAll(s.cues)
                videoUri = s.video
                subtitleUri = s.subtitle
                fileName = s.name
                offsetMs = s.offset
                resumePos = s.position
                dirty = s.dirty
            }
        } else if (i.action == Intent.ACTION_VIEW && i.data != null) {
            val uri = i.data!!
            val type = i.type ?: contentResolver.getType(uri) ?: ""
            if (type.startsWith("video/") || type.startsWith("audio/")) {
                setVideo(uri, startPlayer = false)
            } else {
                loadSubtitles(uri)
            }
        } else {
            i.getStringExtra(EXTRA_VIDEO)?.let { setVideo(Uri.parse(it), startPlayer = false) }
            i.getStringExtra(EXTRA_SUBTITLE)?.let { loadSubtitles(Uri.parse(it)) }
            if (i.getBooleanExtra(EXTRA_NEW, false) || cues.isEmpty()) dirty = false
        }
        refreshAll()
        if (videoUri != null && cues.isEmpty() && subtitleUri == null) {
            Snackbar.make(list, R.string.no_subs_yet, Snackbar.LENGTH_LONG)
                .setAction(R.string.load) { pickSubtitle.launch(arrayOf("*/*")) }
                .show()
        }
    }

    private fun setVideo(uri: Uri, startPlayer: Boolean = true) {
        videoUri = uri
        resumePos = 0
        resumePlay = false
        if (subtitleUri == null && cues.isEmpty()) {
            val name = if (uri.scheme == "http" || uri.scheme == "https") {
                uri.lastPathSegment ?: "video"
            } else Files.displayName(this, uri)
            fileName = Files.baseName(name) + ".srt"
        }
        if (startPlayer) {
            releasePlayer()
            initPlayer()
        }
        refreshAll()
    }

    private fun loadSubtitles(uri: Uri) {
        try {
            val name = Files.displayName(this, uri)
            val text = Files.readText(this, uri)
            val parsed = SubtitleFormats.parse(text, name)
            if (parsed.isEmpty()) {
                toast(R.string.no_subtitles_found)
                return
            }
            if (cues.isNotEmpty()) pushUndo()
            cues.clear()
            cues.addAll(parsed)
            val lower = name.lowercase(Locale.ROOT)
            if (lower.endsWith(".srt") || lower.endsWith(".vtt")) {
                subtitleUri = uri
                fileName = name
            } else {
                // ASS/SSA or unknown: saving creates a new .srt file.
                subtitleUri = null
                fileName = Files.baseName(name) + ".srt"
            }
            offsetMs = 0
            dirty = false
            adapter.selected = null
            refreshAll()
            toast(getString(R.string.loaded_n, parsed.size))
            if (parsed.none { Thaana.containsThaana(it.text) } &&
                parsed.any { c -> c.text.any { it.isLetter() } }
            ) {
                Snackbar.make(list, R.string.no_thaana_found, Snackbar.LENGTH_INDEFINITE)
                    .setAction(R.string.convert) { askConvertAscii() }
                    .show()
            }
        } catch (e: Exception) {
            toast(getString(R.string.error_x, e.message ?: e.javaClass.simpleName))
        }
    }

    // ---- Player ----

    private fun initPlayer() {
        val uri = videoUri
        placeholder.visibility = if (uri == null) View.VISIBLE else View.GONE
        playerView.visibility = if (uri == null) View.INVISIBLE else View.VISIBLE
        if (uri == null || player != null) return
        val p = ExoPlayer.Builder(this).build()
        playerView.player = p
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                toast(getString(R.string.cannot_play, error.errorCodeName))
            }
        })
        p.setMediaItem(MediaItem.fromUri(uri))
        p.seekTo(resumePos)
        p.playWhenReady = resumePlay
        p.prepare()
        player = p
    }

    private fun releasePlayer() {
        player?.let {
            resumePos = it.currentPosition
            resumePlay = it.playWhenReady
            it.release()
        }
        player = null
        playerView.player = null
    }

    private fun seekTo(ms: Long) {
        player?.seekTo(max(0, ms))
        handler.postDelayed({ updateActiveCue(true) }, 80)
    }

    /** Current position on the subtitle timeline (video time minus sync offset). */
    private fun now(): Long? = player?.let { it.currentPosition - offsetMs }

    // ---- Subtitle display ----

    private fun findCueAt(t: Long): Cue? {
        var lo = 0
        var hi = cues.size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].start <= t) { idx = mid; lo = mid + 1 } else hi = mid - 1
        }
        var i = idx
        while (i >= 0 && i > idx - 4) {
            val c = cues[i]
            if (t < c.end) return c
            i--
        }
        return null
    }

    private fun updateActiveCue(force: Boolean) {
        val t = now()
        val cue = if (t == null) null else findCueAt(t)
        if (!force && cue === activeCue) return
        val old = adapter.indexOf(activeCue)
        activeCue = cue
        adapter.active = cue
        if (cue == null || cue.text.isBlank()) {
            overlay.visibility = View.GONE
        } else {
            overlay.text = CueAdapter.render(cue.text)
            overlay.visibility = View.VISIBLE
        }
        val idx = adapter.indexOf(cue)
        if (force) adapter.notifyDataSetChanged() else {
            if (old >= 0) adapter.notifyItemChanged(old)
            if (idx >= 0) adapter.notifyItemChanged(idx)
        }
        if (idx >= 0 && prefs.autoScroll && player?.isPlaying == true) {
            val lm = list.layoutManager as LinearLayoutManager
            if (idx < lm.findFirstCompletelyVisibleItemPosition() || idx > lm.findLastCompletelyVisibleItemPosition()) {
                lm.scrollToPositionWithOffset(idx, list.height / 4)
            }
        }
    }

    private fun applyOverlayPosition() {
        val lp = overlay.layoutParams as? FrameLayout.LayoutParams ?: return
        val h = playerView.height
        lp.bottomMargin = (h * prefs.bottomMargin / 100f).toInt()
        overlay.layoutParams = lp
    }

    // ---- Layout / fullscreen ----

    private fun toggleFullscreen() {
        if (!fullscreen) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            applyLayoutMode(true)
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            applyLayoutMode(false)
        }
    }

    private fun applyLayoutMode(full: Boolean) {
        fullscreen = full
        val ctrl = WindowCompat.getInsetsController(window, window.decorView)
        val dm = resources.displayMetrics
        val lp = findViewById<View>(R.id.video_box).layoutParams as LinearLayout.LayoutParams
        if (full) {
            supportActionBar?.hide()
            panel.visibility = View.GONE
            ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            lp.weight = 1f
        } else {
            supportActionBar?.show()
            panel.visibility = View.VISIBLE
            ctrl.show(WindowInsetsCompat.Type.systemBars())
            lp.height = min(dm.widthPixels * 9 / 16, (dm.heightPixels * 0.45f).toInt())
            lp.weight = 0f
        }
        findViewById<View>(R.id.video_box).layoutParams = lp
    }

    // ---- Editing ----

    private fun select(c: Cue?) {
        val old = adapter.indexOf(adapter.selected)
        adapter.selected = c
        if (old >= 0) adapter.notifyItemChanged(old)
        val idx = adapter.indexOf(c)
        if (idx >= 0) adapter.notifyItemChanged(idx)
    }

    private fun currentTarget(): Cue? = adapter.selected ?: activeCue

    private fun pushUndo() {
        undoStack.addLast(cues.map { it.copyCue() })
        while (undoStack.size > MAX_UNDO) undoStack.removeFirst()
    }

    private fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return toast(R.string.nothing_to_undo)
        cues.clear()
        cues.addAll(prev)
        adapter.selected = null
        dirty = true
        refreshAll()
    }

    private fun changed() {
        cues.sortBy { it.start }
        dirty = true
        refreshAll()
    }

    private fun refreshAll() {
        adapter.notifyDataSetChanged()
        updateStatus()
        updateActiveCue(true)
        invalidateOptionsMenu()
    }

    @SuppressLint("SetTextI18n")
    private fun updateStatus() {
        val sync = if (offsetMs == 0L) "" else String.format(Locale.ROOT, "  ·  sync %+.1fs", offsetMs / 1000.0)
        val mark = if (dirty) " •" else ""
        status.text = "$fileName$mark  ·  ${cues.size} lines$sync"
    }

    private fun addCue() {
        player?.pause()
        val t = now() ?: (cues.lastOrNull()?.end?.plus(200) ?: 0L)
        val start = max(0L, t)
        var end = start + prefs.defaultCueMs
        val next = cues.firstOrNull { it.start > start }
        if (next != null && next.start - 50 > start + 500) end = min(end, next.start - 50)
        editCue(Cue(start, end, ""), isNew = true)
    }

    private fun setStartNow() {
        val c = currentTarget() ?: return toast(R.string.tap_subtitle_first)
        val t = now() ?: return toast(R.string.need_video)
        pushUndo()
        c.start = max(0, t)
        if (c.end <= c.start) c.end = c.start + prefs.defaultCueMs
        select(c)
        changed()
    }

    private fun setEndNow() {
        val c = currentTarget() ?: return toast(R.string.tap_subtitle_first)
        val t = now() ?: return toast(R.string.need_video)
        if (t <= c.start) return toast(R.string.end_after_start)
        pushUndo()
        c.end = t
        select(c)
        changed()
    }

    private fun changeOffset(delta: Long) {
        offsetMs += delta
        dirty = true
        updateStatus()
        updateActiveCue(true)
        toast(String.format(Locale.ROOT, "Sync %+.1fs", offsetMs / 1000.0))
    }

    private fun jumpCue(dir: Int) {
        if (cues.isEmpty()) return
        val cur = adapter.indexOf(currentTarget())
        val idx = if (cur < 0) {
            val t = now() ?: 0L
            if (dir > 0) cues.indexOfFirst { it.start > t }.let { if (it < 0) cues.size - 1 else it }
            else cues.indexOfLast { it.start < t }.let { if (it < 0) 0 else it }
        } else (cur + dir).coerceIn(0, cues.size - 1)
        val c = cues[idx]
        select(c)
        list.scrollToPosition(idx)
        seekTo(c.start + offsetMs)
    }

    private fun showCueMenu(c: Cue, anchor: View) {
        select(c)
        val pm = PopupMenu(this, anchor, Gravity.END)
        pm.menu.add(0, 1, 0, R.string.edit)
        pm.menu.add(0, 2, 0, R.string.insert_after)
        pm.menu.add(0, 3, 0, R.string.split)
        pm.menu.add(0, 4, 0, R.string.merge_next)
        pm.menu.add(0, 5, 0, R.string.play_from_here)
        pm.menu.add(0, 6, 0, R.string.delete)
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> editCue(c)
                2 -> {
                    val start = c.end + 100
                    val next = cues.getOrNull(cues.indexOf(c) + 1)
                    var end = start + prefs.defaultCueMs
                    if (next != null && next.start - 50 > start + 300) end = min(end, next.start - 50)
                    editCue(Cue(start, end, ""), isNew = true)
                }
                3 -> splitCue(c)
                4 -> mergeNext(c)
                5 -> { seekTo(c.start + offsetMs); player?.play() }
                6 -> deleteCue(c)
            }
            true
        }
        pm.show()
    }

    private fun splitCue(c: Cue) {
        val t = now()
        val at = if (t != null && t > c.start + 100 && t < c.end - 100) t else (c.start + c.end) / 2
        pushUndo()
        val lines = c.text.split('\n')
        val (a, b) = if (lines.size >= 2) {
            val half = (lines.size + 1) / 2
            lines.take(half).joinToString("\n") to lines.drop(half).joinToString("\n")
        } else {
            val words = c.text.split(' ')
            val half = (words.size + 1) / 2
            words.take(half).joinToString(" ") to words.drop(half).joinToString(" ")
        }
        val second = Cue(at + 1, c.end, b)
        c.end = at
        c.text = a
        cues.add(second)
        changed()
    }

    private fun mergeNext(c: Cue) {
        val i = cues.indexOf(c)
        val next = cues.getOrNull(i + 1) ?: return toast(R.string.no_next)
        pushUndo()
        c.end = max(c.end, next.end)
        c.text = (c.text.trim() + "\n" + next.text.trim()).trim()
        cues.remove(next)
        changed()
    }

    private fun deleteCue(c: Cue) {
        pushUndo()
        cues.remove(c)
        if (adapter.selected === c) adapter.selected = null
        changed()
        Snackbar.make(list, R.string.deleted, Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) { undo() }
            .show()
    }

    private fun editCue(c: Cue, isNew: Boolean = false) {
        val v = layoutInflater.inflate(R.layout.dialog_cue, null)
        val startEt = v.findViewById<EditText>(R.id.edit_start)
        val endEt = v.findViewById<EditText>(R.id.edit_end)
        val textEt = v.findViewById<EditText>(R.id.edit_text)
        val phon = v.findViewById<MaterialSwitch>(R.id.switch_phonetic)
        val info = v.findViewById<TextView>(R.id.text_info)

        startEt.setText(SubtitleFormats.formatTime(c.start))
        endEt.setText(SubtitleFormats.formatTime(c.end))
        textEt.typeface = prefs.typeface()
        textEt.setText(c.text)
        textEt.setSelection(textEt.length())
        phon.isChecked = prefs.phonetic
        ThaanaInputFilter.attach(textEt, true) { phon.isChecked }
        phon.setOnCheckedChangeListener { _, on ->
            prefs.phonetic = on
            ThaanaInputFilter.refresh(textEt, true, on)
        }
        v.findViewById<MaterialButton>(R.id.btn_start_now).setOnClickListener {
            now()?.let { startEt.setText(SubtitleFormats.formatTime(max(0, it))) } ?: toast(R.string.need_video)
        }
        v.findViewById<MaterialButton>(R.id.btn_end_now).setOnClickListener {
            now()?.let { endEt.setText(SubtitleFormats.formatTime(max(0, it))) } ?: toast(R.string.need_video)
        }
        fun updateInfo() {
            val lines = textEt.text.split('\n')
            val longest = lines.maxOfOrNull { it.length } ?: 0
            val s = SubtitleFormats.parseUserTime(startEt.text.toString())
            val e = SubtitleFormats.parseUserTime(endEt.text.toString())
            val dur = if (s != null && e != null && e > s) (e - s) / 1000.0 else 0.0
            val cps = if (dur > 0) textEt.length() / dur else 0.0
            info.text = String.format(Locale.ROOT, "%d lines · longest %d chars · %.1fs · %.0f chars/s", lines.size, longest, dur, cps)
        }
        updateInfo()
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = updateInfo()
        }
        textEt.addTextChangedListener(watcher)
        startEt.addTextChangedListener(watcher)
        endEt.addTextChangedListener(watcher)

        val b = MaterialAlertDialogBuilder(this)
            .setTitle(if (isNew) R.string.new_line else R.string.edit_line)
            .setView(v)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
        if (!isNew) b.setNeutralButton(R.string.delete) { _, _ -> deleteCue(c) }
        val dlg = b.create()
        dlg.setOnShowListener {
            textEt.requestFocus()
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val s = SubtitleFormats.parseUserTime(startEt.text.toString())
                val e = SubtitleFormats.parseUserTime(endEt.text.toString())
                if (s == null || e == null) return@setOnClickListener toast(R.string.bad_time)
                if (e <= s) return@setOnClickListener toast(R.string.end_after_start)
                pushUndo()
                c.start = s
                c.end = e
                c.text = textEt.text.toString().trim()
                if (isNew) cues.add(c)
                select(c)
                changed()
                list.scrollToPosition(max(0, adapter.indexOf(c)))
                dlg.dismiss()
            }
        }
        dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show()
    }

    // ---- Bulk tools ----

    private fun askShift() {
        val v = layoutInflater.inflate(R.layout.dialog_shift, null)
        val amount = v.findViewById<EditText>(R.id.edit_amount)
        val fromSel = v.findViewById<CheckBox>(R.id.check_from_selected)
        val note = v.findViewById<TextView>(R.id.text_offset_note)
        if (offsetMs != 0L) {
            amount.setText(String.format(Locale.ROOT, "%.3f", offsetMs / 1000.0))
            note.text = getString(R.string.offset_note)
            note.visibility = View.VISIBLE
        }
        fromSel.isEnabled = adapter.selected != null
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.shift_timings)
            .setView(v)
            .setPositiveButton(R.string.apply) { _, _ ->
                val secs = amount.text.toString().replace(',', '.').toDoubleOrNull()
                    ?: return@setPositiveButton toast(R.string.bad_time)
                val delta = (secs * 1000).toLong()
                pushUndo()
                val sel = adapter.selected
                val startIdx = if (fromSel.isChecked && sel != null) max(0, cues.indexOf(sel)) else 0
                for (i in startIdx until cues.size) {
                    cues[i].start = max(0, cues[i].start + delta)
                    cues[i].end = max(cues[i].start + 1, cues[i].end + delta)
                }
                if (offsetMs != 0L && abs(delta - offsetMs) < 2) offsetMs = 0
                changed()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askFindReplace() {
        val v = layoutInflater.inflate(R.layout.dialog_find, null)
        val find = v.findViewById<EditText>(R.id.edit_find)
        val repl = v.findViewById<EditText>(R.id.edit_replace)
        val phon = v.findViewById<MaterialSwitch>(R.id.switch_phonetic)
        val tf = prefs.typeface()
        find.typeface = tf
        repl.typeface = tf
        phon.isChecked = prefs.phonetic
        ThaanaInputFilter.attach(find, false) { phon.isChecked }
        ThaanaInputFilter.attach(repl, false) { phon.isChecked }
        phon.setOnCheckedChangeListener { _, on ->
            ThaanaInputFilter.refresh(find, false, on)
            ThaanaInputFilter.refresh(repl, false, on)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.find_replace)
            .setView(v)
            .setPositiveButton(R.string.replace_all) { _, _ ->
                val f = find.text.toString()
                if (f.isEmpty()) return@setPositiveButton
                val r = repl.text.toString()
                val count = cues.sumOf { it.text.windowed(f.length, 1).count { w -> w == f } }
                if (count == 0) return@setPositiveButton toast(R.string.not_found)
                pushUndo()
                cues.forEach { it.text = it.text.replace(f, r) }
                changed()
                toast(getString(R.string.replaced_n, count))
            }
            .setNeutralButton(R.string.find_next) { _, _ ->
                val f = find.text.toString()
                if (f.isEmpty()) return@setNeutralButton
                val from = adapter.indexOf(adapter.selected) + 1
                val order = (from until cues.size) + (0 until from)
                val hit = order.firstOrNull { cues[it].text.contains(f, ignoreCase = true) }
                    ?: return@setNeutralButton toast(R.string.not_found)
                val c = cues[hit]
                select(c)
                list.scrollToPosition(hit)
                seekTo(c.start + offsetMs)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askConvertAscii() {
        val v = layoutInflater.inflate(R.layout.dialog_convert, null)
        val reverse = v.findViewById<CheckBox>(R.id.check_reverse)
        val preview = v.findViewById<TextView>(R.id.text_preview)
        preview.typeface = prefs.typeface()
        val sample = cues.firstOrNull { c -> c.text.any { it.isLetter() } }?.text ?: ""
        fun upd() {
            preview.text = Thaana.fromLatin(sample, reverse.isChecked)
        }
        upd()
        reverse.setOnCheckedChangeListener { _, _ -> upd() }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.convert_ascii)
            .setView(v)
            .setPositiveButton(R.string.convert) { _, _ ->
                pushUndo()
                var n = 0
                cues.forEach {
                    if (!Thaana.containsThaana(it.text)) {
                        it.text = Thaana.fromLatin(it.text, reverse.isChecked); n++
                    }
                }
                changed()
                toast(getString(R.string.converted_n, n))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun fixOverlaps() {
        pushUndo()
        var fixed = 0
        val before = cues.size
        cues.removeAll { it.text.isBlank() }
        cues.sortBy { it.start }
        for (i in 0 until cues.size - 1) {
            val a = cues[i]
            val b = cues[i + 1]
            if (a.end > b.start) {
                a.end = max(a.start + 100, b.start - 1)
                fixed++
            }
        }
        changed()
        toast(getString(R.string.fixed_n, fixed, before - cues.size))
    }

    private fun newSubtitles() {
        val go = {
            pushUndo()
            cues.clear()
            subtitleUri = null
            offsetMs = 0
            fileName = videoUri?.let {
                Files.baseName(
                    if (it.scheme?.startsWith("http") == true) it.lastPathSegment ?: "video" else Files.displayName(this, it)
                ) + ".srt"
            } ?: "subtitles.srt"
            dirty = false
            adapter.selected = null
            refreshAll()
        }
        if (dirty) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.new_subtitles)
                .setMessage(R.string.unsaved_lost)
                .setPositiveButton(R.string.start_new) { _, _ -> go() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else go()
    }

    private fun askUrl() {
        val input = EditText(this).apply {
            hint = "https://example.com/video.mp4"
            setSingleLine()
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.open_url)
            .setView(box)
            .setPositiveButton(R.string.open) { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) setVideo(Uri.parse(t))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- Saving ----

    /** Cues with the current sync offset baked in. */
    private fun cuesForOutput(): List<Cue> =
        cues.map { Cue(max(0, it.start + offsetMs), max(0, it.end + offsetMs), it.text) }

    private fun bakeOffset() {
        if (offsetMs == 0L) return
        val shifted = cuesForOutput()
        cues.clear()
        cues.addAll(shifted)
        offsetMs = 0
    }

    private fun save() {
        val uri = subtitleUri
        if (uri == null) {
            createSrt.launch(fileName)
            return
        }
        writeTo(uri, fileName, vtt = fileName.lowercase(Locale.ROOT).endsWith(".vtt"), becomeTarget = true)
    }

    private fun writeTo(uri: Uri, name: String, vtt: Boolean, becomeTarget: Boolean) {
        try {
            val out = cuesForOutput()
            val text = if (vtt) SubtitleFormats.toVtt(out) else SubtitleFormats.toSrt(out)
            Files.write(this, uri, TextCodec.encode(text, prefs.writeBom && !vtt))
            if (becomeTarget) {
                bakeOffset()
                subtitleUri = uri
                fileName = name
                dirty = false
                refreshAll()
            }
            saveDraft()
            toast(getString(R.string.saved_x, name))
        } catch (e: Exception) {
            if (becomeTarget && uri == subtitleUri) {
                // No write access to the original file: ask where to save instead.
                toast(R.string.save_copy)
                createSrt.launch(fileName)
            } else {
                toast(getString(R.string.error_x, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun share() {
        try {
            val dir = File(cacheDir, "shared").apply { mkdirs() }
            val name = Files.baseName(fileName) + ".srt"
            val f = File(dir, name)
            f.writeBytes(TextCodec.encode(SubtitleFormats.toSrt(cuesForOutput()), prefs.writeBom))
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            val send = Intent(Intent.ACTION_SEND)
                .setType("application/x-subrip")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, getString(R.string.share)))
        } catch (e: Exception) {
            toast(getString(R.string.error_x, e.message ?: e.javaClass.simpleName))
        }
    }

    private fun saveDraft() {
        if (cues.isEmpty() && videoUri == null) return
        try {
            draft.save(
                Draft.State(cues, videoUri, subtitleUri, fileName, offsetMs, player?.currentPosition ?: resumePos, dirty)
            )
        } catch (_: Exception) {
        }
    }

    private fun askSaveBeforeExit() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.unsaved_title)
            .setMessage(R.string.unsaved_msg)
            .setPositiveButton(R.string.save) { _, _ -> save() }
            .setNegativeButton(R.string.discard) { _, _ ->
                dirty = false
                draft.clear()
                cues.clear()
                videoUri = null
                finish()
            }
            .setNeutralButton(R.string.keep_draft) { _, _ -> finish() }
            .show()
    }

    // ---- Menu ----

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_editor, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_undo)?.isEnabled = undoStack.isNotEmpty()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_save -> save()
            R.id.action_undo -> undo()
            R.id.action_open_video -> pickVideo.launch(arrayOf("video/*", "audio/*"))
            R.id.action_open_url -> askUrl()
            R.id.action_open_subs -> pickSubtitle.launch(arrayOf("*/*"))
            R.id.action_new -> newSubtitles()
            R.id.action_save_as -> createSrt.launch(Files.baseName(fileName) + ".srt")
            R.id.action_export_vtt -> createVtt.launch(Files.baseName(fileName) + ".vtt")
            R.id.action_share -> share()
            R.id.action_shift -> askShift()
            R.id.action_find -> askFindReplace()
            R.id.action_convert -> askConvertAscii()
            R.id.action_fix -> fixOverlaps()
            R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            R.id.action_help -> MainActivity.showHelp(this)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
