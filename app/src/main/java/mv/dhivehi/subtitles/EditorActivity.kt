package mv.dhivehi.subtitles

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mv.dhivehi.subtitles.audio.AudioDecoder
import mv.dhivehi.subtitles.captions.BuiltInFonts
import mv.dhivehi.subtitles.captions.Caption
import mv.dhivehi.subtitles.captions.CaptionBuilder
import mv.dhivehi.subtitles.captions.CaptionRenderer
import mv.dhivehi.subtitles.captions.CaptionStyle
import mv.dhivehi.subtitles.captions.CaptionTimeline
import mv.dhivehi.subtitles.captions.Word
import mv.dhivehi.subtitles.export.MediaSaver
import mv.dhivehi.subtitles.export.VideoExporter
import mv.dhivehi.subtitles.ui.CaptionAdapter
import mv.dhivehi.subtitles.ui.CaptionOverlayView
import mv.dhivehi.subtitles.whisper.Transcriber
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

@UnstableApi
class EditorActivity : AppCompatActivity() {

    private lateinit var project: Project
    private lateinit var style: CaptionStyle
    private lateinit var renderer: CaptionRenderer
    private lateinit var adapter: CaptionAdapter

    private lateinit var playerView: PlayerView
    private lateinit var overlay: CaptionOverlayView
    private lateinit var playIcon: ImageView
    private lateinit var playButton: ImageButton
    private lateinit var timeText: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var busyPanel: View
    private lateinit var busyText: TextView
    private lateinit var busyProgress: LinearProgressIndicator
    private lateinit var busyCancel: MaterialButton
    private lateinit var donePanel: View

    private var player: ExoPlayer? = null
    private var durationMs: Long = 0L
    private var transcribeJob: Job? = null
    private var saveJob: Job? = null
    private var exporting = false
    private var exportedUri: Uri? = null
    private val exporter by lazy { VideoExporter(this) }

    private val ticker = object : Runnable {
        override fun run() {
            updateTime()
            if (player?.isPlaying == true) overlay.postOnAnimation(this)
        }
    }

    private val importFont = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFontFrom(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val uri = intent.data
        if (uri == null) { finish(); return }

        style = CaptionStyle.load(this)
        renderer = CaptionRenderer(applicationContext)

        playerView = findViewById(R.id.playerView)
        overlay = findViewById(R.id.captionOverlay)
        playIcon = findViewById(R.id.playIcon)
        playButton = findViewById(R.id.playButton)
        timeText = findViewById(R.id.timeText)
        recycler = findViewById(R.id.captionList)
        busyPanel = findViewById(R.id.busyPanel)
        busyText = findViewById(R.id.busyText)
        busyProgress = findViewById(R.id.busyProgress)
        busyCancel = findViewById(R.id.busyCancel)
        donePanel = findViewById(R.id.donePanel)

        overlay.renderer = renderer
        overlay.style = style

        adapter = CaptionAdapter(
            renderer.typeface(CaptionStyle(fontId = "noto")),
            onTextEdited = { pos, text -> onTextEdited(pos, text) },
            onSeek = { pos -> seekToCaption(pos) },
            onMenu = { pos, anchor -> showCaptionMenu(pos, anchor) },
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        setupPlayer(uri)
        durationMs = readDuration(uri)

        findViewById<View>(R.id.backButton).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        findViewById<View>(R.id.redoButton).setOnClickListener { confirmRedo() }
        playButton.setOnClickListener { togglePlay() }
        findViewById<View>(R.id.previewFrame).setOnClickListener { togglePlay() }
        findViewById<View>(R.id.syncButton).setOnClickListener { showSyncSheet() }
        findViewById<View>(R.id.styleButton).setOnClickListener { showStyleSheet() }
        findViewById<View>(R.id.exportButton).setOnClickListener { startExport() }
        findViewById<View>(R.id.doneClose).setOnClickListener { donePanel.visibility = View.GONE }
        findViewById<View>(R.id.doneTikTok).setOnClickListener { exportedUri?.let { MediaSaver.shareToTikTok(this, it) } }
        findViewById<View>(R.id.doneShare).setOnClickListener { exportedUri?.let { MediaSaver.share(this, it) } }
        findViewById<View>(R.id.doneOpen).setOnClickListener { exportedUri?.let { MediaSaver.open(this, it) } }
        busyCancel.setOnClickListener { cancelBusyWork() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    donePanel.visibility == View.VISIBLE -> donePanel.visibility = View.GONE
                    busyPanel.visibility == View.VISIBLE -> cancelBusyWork()
                    else -> finish()
                }
            }
        })

        applyFraming()
        val saved = Project.load(this, uri)
        if (saved != null && saved.captions.isNotEmpty()) {
            project = saved
            showCaptions()
        } else {
            project = Project(uri)
            if (durationMs > 20 * 60_000L) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.too_long_title)
                    .setMessage(R.string.too_long_message)
                    .setPositiveButton(R.string.ok) { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            } else {
                startTranscription()
            }
        }
    }

    // ---------------------------------------------------------------- player

    private fun setupPlayer(uri: Uri) {
        val p = ExoPlayer.Builder(this).build()
        playerView.player = p
        playerView.useController = false
        p.setMediaItem(MediaItem.fromUri(uri))
        p.prepare()
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playButton.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                playIcon.visibility = if (isPlaying) View.GONE else View.VISIBLE
                overlay.removeCallbacks(ticker)
                if (isPlaying) overlay.postOnAnimation(ticker) else updateTime()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    player?.seekTo(0)
                    player?.pause()
                }
                if (playbackState == Player.STATE_READY && durationMs <= 0) durationMs = player?.duration ?: 0L
                updateTime()
            }
        })
        player = p
    }

    private fun readDuration(uri: Uri): Long = try {
        MediaMetadataRetriever().run {
            setDataSource(this@EditorActivity, uri)
            val d = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            release()
            d
        }
    } catch (e: Exception) { 0L }

    private fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    private fun applyFraming() {
        playerView.resizeMode =
            if (style.fillFrame) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    private fun updateTime() {
        val p = player ?: return
        val pos = p.currentPosition
        val t = pos - currentOffset()
        overlay.timeMs = t
        timeText.text = "${CaptionAdapter.fmt(pos)} / ${CaptionAdapter.fmt(max(durationMs, p.duration.coerceAtLeast(0L)))}"
        val idx = CaptionTimeline.indexAt(captions(), t)
        if (idx >= 0 && idx != adapter.activeIndex) {
            adapter.activeIndex = idx
            if (p.isPlaying && recycler.focusedChild == null) {
                (recycler.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(idx, recycler.height / 3)
            }
        }
    }

    private fun currentOffset(): Long = if (this::project.isInitialized) project.offsetMs else 0L

    private fun captions(): List<Caption> =
        if (this::project.isInitialized) project.captions else emptyList()

    private fun seekToCaption(pos: Int) {
        val c = captions().getOrNull(pos) ?: return
        player?.seekTo(max(0L, c.startMs + project.offsetMs + 10))
        adapter.activeIndex = pos
        overlay.postDelayed({ updateTime() }, 60)
    }

    // ---------------------------------------------------------------- transcription

    private fun startTranscription() {
        showBusy(getString(R.string.reading_audio), 0)
        transcribeJob = lifecycleScope.launch {
            try {
                val words: List<Word> = withContext(Dispatchers.Default) {
                    val scope = this
                    val audio = AudioDecoder.decode(applicationContext, project.videoUri, { !scope.isActive }) { p ->
                        runOnUiThread { setBusy(getString(R.string.reading_audio), (p * 10).toInt()) }
                    }
                    Transcriber.ensureLoaded(applicationContext) { s -> runOnUiThread { setBusy(s, 10) } }
                    // Short clips report progress only at the end, so show elapsed time meanwhile.
                    var lastPct = 0
                    val started = System.currentTimeMillis()
                    val ticker = launch(Dispatchers.Main) {
                        while (isActive) {
                            if (lastPct == 0) {
                                val sec = (System.currentTimeMillis() - started) / 1000
                                setBusy(getString(R.string.listening_elapsed, sec), 12)
                            }
                            delay(1000)
                        }
                    }
                    try {
                        Transcriber.transcribe(audio, AudioDecoder.lastAudioStartUs / 1000L) { pct ->
                            if (pct > 0) {
                                lastPct = pct
                                runOnUiThread { setBusy(getString(R.string.listening, pct), 12 + pct * 88 / 100) }
                            }
                        }
                    } finally {
                        ticker.cancel()
                    }
                }
                hideBusy()
                if (words.isEmpty()) {
                    MaterialAlertDialogBuilder(this@EditorActivity)
                        .setTitle(R.string.no_speech_title)
                        .setMessage(R.string.no_speech_message)
                        .setPositiveButton(R.string.ok) { _, _ -> finish() }
                        .setCancelable(false)
                        .show()
                    return@launch
                }
                project.captions = CaptionBuilder.build(words, style.wordsPerCaption)
                project.save(applicationContext)
                showCaptions()
                player?.play()
            } catch (e: CancellationException) {
                throw e
            } catch (e: InterruptedException) {
                finish()
            } catch (e: Throwable) {
                hideBusy()
                val msg = when (e) {
                    is AudioDecoder.NoAudioException -> getString(R.string.no_audio)
                    is OutOfMemoryError -> getString(R.string.out_of_memory)
                    else -> e.message ?: e.toString()
                }
                MaterialAlertDialogBuilder(this@EditorActivity)
                    .setTitle(R.string.error_title)
                    .setMessage(msg)
                    .setPositiveButton(R.string.ok) { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    private fun showCaptions() {
        overlay.captions = project.captions
        adapter.captions = project.captions
        updateTime()
    }

    private fun confirmRedo() {
        if (busyPanel.visibility == View.VISIBLE) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.redo_title)
            .setMessage(R.string.redo_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.redo_confirm) { _, _ ->
                player?.pause()
                Project.delete(this, project.videoUri)
                project.captions = mutableListOf()
                showCaptions()
                startTranscription()
            }
            .show()
    }

    // ---------------------------------------------------------------- editing

    private fun onTextEdited(pos: Int, text: String) {
        val c = captions().getOrNull(pos) ?: return
        c.setText(text)
        overlay.invalidate()
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = lifecycleScope.launch {
            delay(600)
            withContext(Dispatchers.IO) { project.save(applicationContext) }
        }
    }

    private fun captionsChanged(focus: Int = -1) {
        CaptionBuilder.smooth(project.captions)
        adapter.captions = project.captions
        overlay.captions = project.captions
        if (focus >= 0) recycler.scrollToPosition(focus)
        scheduleSave()
    }

    private fun showCaptionMenu(pos: Int, anchor: View) {
        val list = project.captions
        val c = list.getOrNull(pos) ?: return
        val menu = PopupMenu(this, anchor)
        val items = listOf(
            R.string.start_earlier, R.string.start_later, R.string.end_earlier, R.string.end_later,
            R.string.start_at_playhead, R.string.end_at_playhead,
            R.string.merge_next, R.string.split, R.string.insert_after, R.string.delete
        )
        items.forEachIndexed { i, res -> menu.menu.add(0, res, i, res) }
        menu.setOnMenuItemClickListener { item ->
            val playhead = (player?.currentPosition ?: 0L) - project.offsetMs
            when (item.itemId) {
                R.string.start_earlier -> retime(pos, c.startMs - 100, c.endMs)
                R.string.start_later -> retime(pos, c.startMs + 100, c.endMs)
                R.string.end_earlier -> retime(pos, c.startMs, c.endMs - 100)
                R.string.end_later -> retime(pos, c.startMs, c.endMs + 100)
                R.string.start_at_playhead -> retime(pos, playhead, max(c.endMs, playhead + 300))
                R.string.end_at_playhead -> retime(pos, min(c.startMs, playhead - 300), playhead)
                R.string.merge_next -> {
                    val next = list.getOrNull(pos + 1)
                    if (next != null) {
                        val merged = Caption(c.words + next.words)
                        merged.startMs = c.startMs
                        merged.endMs = next.endMs
                        list[pos] = merged
                        list.removeAt(pos + 1)
                        captionsChanged(pos)
                    }
                }
                R.string.split -> {
                    if (c.words.size >= 2) {
                        val half = c.words.size / 2
                        val a = Caption(c.words.subList(0, half))
                        val b = Caption(c.words.subList(half, c.words.size))
                        a.startMs = c.startMs
                        a.endMs = b.startMs
                        b.endMs = c.endMs
                        list[pos] = a
                        list.add(pos + 1, b)
                        captionsChanged(pos)
                    } else Toast.makeText(this, R.string.split_needs_two, Toast.LENGTH_SHORT).show()
                }
                R.string.insert_after -> {
                    val start = c.endMs
                    val nextStart = list.getOrNull(pos + 1)?.startMs ?: (start + 1500)
                    val end = if (nextStart - start >= 400) min(start + 1500, nextStart) else start + 1500
                    val nc = Caption(listOf(Word(getString(R.string.new_caption_text), start, end)))
                    list.add(pos + 1, nc)
                    list.getOrNull(pos + 2)?.let { n -> if (n.startMs < end) n.setTiming(end, max(n.endMs, end + 300)) }
                    captionsChanged(pos + 1)
                }
                R.string.delete -> {
                    list.removeAt(pos)
                    captionsChanged(min(pos, list.size - 1))
                }
            }
            true
        }
        menu.show()
    }

    /** Changes one caption's times and trims its neighbours so nothing overlaps. */
    private fun retime(pos: Int, start: Long, end: Long) {
        val list = project.captions
        val c = list[pos]
        val limit = if (durationMs > 0) durationMs else Long.MAX_VALUE
        val s = start.coerceIn(0L, limit - 200)
        val e = end.coerceIn(s + 200, limit)
        c.setTiming(s, e)
        list.getOrNull(pos - 1)?.let { p -> if (p.endMs > s) p.setTiming(p.startMs, max(p.startMs + 200, s)) }
        list.getOrNull(pos + 1)?.let { n -> if (n.startMs < e) n.setTiming(min(n.endMs - 200, e), n.endMs) }
        adapter.refreshTimes(pos - 1)
        adapter.refreshTimes(pos)
        adapter.refreshTimes(pos + 1)
        overlay.invalidate()
        player?.seekTo(max(0L, c.startMs + project.offsetMs))
        updateTime()
        scheduleSave()
    }

    // ---------------------------------------------------------------- sync & style

    private fun showSyncSheet() {
        if (!this::project.isInitialized) return
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_sync, null)
        val slider = view.findViewById<Slider>(R.id.offsetSlider)
        val label = view.findViewById<TextView>(R.id.offsetLabel)
        fun show(v: Float) {
            label.text = when {
                v > 0.001f -> getString(R.string.offset_later, String.format(Locale.US, "%.2f", v))
                v < -0.001f -> getString(R.string.offset_earlier, String.format(Locale.US, "%.2f", -v))
                else -> getString(R.string.offset_none)
            }
        }
        val current = (project.offsetMs / 1000f).coerceIn(-2f, 2f)
        slider.value = (Math.round(current * 20f) / 20f)
        show(slider.value)
        slider.addOnChangeListener { _, value, _ ->
            project.offsetMs = (value * 1000f).toLong()
            show(value)
            updateTime()
            scheduleSave()
        }
        view.findViewById<View>(R.id.offsetReset).setOnClickListener { slider.value = 0f }
        view.findViewById<View>(R.id.offsetPlay).setOnClickListener {
            val idx = max(0, adapter.activeIndex)
            seekToCaption(idx)
            player?.play()
        }
        dialog.setContentView(view)
        dialog.show()
    }

    private fun showStyleSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_style, null)

        val presets = view.findViewById<ChipGroup>(R.id.presetGroup)
        val size = view.findViewById<Slider>(R.id.sizeSlider)
        val position = view.findViewById<Slider>(R.id.positionSlider)
        val words = view.findViewById<Slider>(R.id.wordsSlider)
        val weight = view.findViewById<Slider>(R.id.weightSlider)
        val highlight = view.findViewById<MaterialSwitch>(R.id.highlightSwitch)
        val pop = view.findViewById<MaterialSwitch>(R.id.popSwitch)
        val framing = view.findViewById<MaterialButtonToggleGroup>(R.id.framingGroup)
        val fontName = view.findViewById<TextView>(R.id.fontName)
        val gallery = view.findViewById<LinearLayout>(R.id.fontGallery)
        val galleryCards = ArrayList<Pair<String, MaterialCardView>>()
        val dp = resources.displayMetrics.density
        for (f in BuiltInFonts.ALL) {
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams((104 * dp).toInt(), (84 * dp).toInt()).apply { marginEnd = (8 * dp).toInt() }
                radius = 14 * dp
                cardElevation = 0f
                setCardBackgroundColor(getColor(R.color.surface_high))
                isClickable = true
                isFocusable = true
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding((6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt())
            }
            box.addView(TextView(this).apply {
                text = getString(R.string.font_sample)
                typeface = renderer.previewTypeface(f)
                textSize = minOf(24f * f.scale, 34f)
                setTextColor(getColor(R.color.text))
                gravity = android.view.Gravity.CENTER
                maxLines = 1
            })
            box.addView(TextView(this).apply {
                text = f.name
                textSize = 11f
                setTextColor(getColor(R.color.text_dim))
                gravity = android.view.Gravity.CENTER
                maxLines = 1
            })
            card.addView(box)
            card.setOnClickListener {
                style.fontId = f.id
                style.fontPath = null
                refreshFontCards(galleryCards, fontName, weight)
                style.save(this)
                overlay.invalidate()
            }
            gallery.addView(card)
            galleryCards.add(f.id to card)
        }

        fun refreshControls() {
            size.value = style.sizeFraction.coerceIn(size.valueFrom, size.valueTo)
            position.value = style.positionFraction.coerceIn(position.valueFrom, position.valueTo)
            words.value = style.wordsPerCaption.toFloat().coerceIn(words.valueFrom, words.valueTo)
            weight.value = (style.fontWeight / 100 * 100).toFloat().coerceIn(weight.valueFrom, weight.valueTo)
            refreshFontCards(galleryCards, fontName, weight)
            highlight.isChecked = style.highlightWords
            pop.isChecked = style.popAnimation
            framing.check(if (style.fillFrame) R.id.framingFill else R.id.framingFit)
        }

        fun changed() {
            style.save(this)
            overlay.invalidate()
        }

        refreshControls()

        presets.setOnCheckedStateChangeListener { _, ids ->
            val p = when (ids.firstOrNull()) {
                R.id.presetPop -> CaptionStyle.Preset.POP
                R.id.presetClean -> CaptionStyle.Preset.CLEAN
                R.id.presetBox -> CaptionStyle.Preset.BOX
                R.id.presetNeon -> CaptionStyle.Preset.NEON
                else -> null
            } ?: return@setOnCheckedStateChangeListener
            style.applyPreset(p)
            refreshControls()
            changed()
        }
        size.addOnChangeListener { _, v, fromUser -> if (fromUser) { style.sizeFraction = v; changed() } }
        position.addOnChangeListener { _, v, fromUser -> if (fromUser) { style.positionFraction = v; changed() } }
        weight.addOnChangeListener { _, v, fromUser -> if (fromUser) { style.fontWeight = v.toInt(); changed() } }
        words.addOnChangeListener { _, v, fromUser ->
            if (fromUser && v.toInt() != style.wordsPerCaption) {
                style.wordsPerCaption = v.toInt()
                if (this::project.isInitialized && project.captions.isNotEmpty()) {
                    project.captions = CaptionBuilder.regroup(project.captions, style.wordsPerCaption)
                    captionsChanged()
                }
                changed()
            }
        }
        highlight.setOnCheckedChangeListener { _, c -> style.highlightWords = c; changed() }
        pop.setOnCheckedChangeListener { _, c -> style.popAnimation = c; changed() }
        framing.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                style.fillFrame = id == R.id.framingFill
                applyFraming()
                changed()
            }
        }
        view.findViewById<View>(R.id.fontImport).setOnClickListener {
            dialog.dismiss()
            importFont.launch(arrayOf("font/ttf", "font/otf", "font/*", "application/x-font-ttf", "application/octet-stream", "*/*"))
        }

        dialog.setContentView(view)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.window?.setDimAmount(0.15f)
        dialog.show()
    }

    private fun refreshFontCards(cards: List<Pair<String, MaterialCardView>>, label: TextView, weight: Slider) {
        val dp = resources.displayMetrics.density
        for ((id, card) in cards) {
            val on = style.fontPath == null && style.fontId == id
            card.strokeWidth = if (on) (2 * dp).toInt() else 0
            card.strokeColor = getColor(R.color.accent)
        }
        val path = style.fontPath
        label.text = if (path != null) getString(R.string.font_imported, File(path).nameWithoutExtension) else getString(R.string.font_credit)
        weight.isEnabled = path == null && BuiltInFonts.byId(style.fontId).variable
    }

    private fun importFontFrom(uri: Uri) {
        lifecycleScope.launch {
            val path = withContext(Dispatchers.IO) {
                try {
                    val name = (uri.lastPathSegment ?: "font").substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val base = if (name.endsWith(".ttf", true) || name.endsWith(".otf", true)) name else "$name.ttf"
                    val dir = File(filesDir, "fonts").apply { mkdirs() }
                    val dest = File(dir, base)
                    contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
                    // Check that Android can actually read this font.
                    android.graphics.Typeface.Builder(dest).build() ?: throw IllegalStateException()
                    dest.absolutePath
                } catch (e: Exception) {
                    null
                }
            }
            if (path == null) {
                Toast.makeText(this@EditorActivity, R.string.font_failed, Toast.LENGTH_LONG).show()
            } else {
                style.fontPath = path
                style.save(this@EditorActivity)
                overlay.invalidate()
                Toast.makeText(this@EditorActivity, R.string.font_added, Toast.LENGTH_SHORT).show()
            }
            showStyleSheet()
        }
    }

    // ---------------------------------------------------------------- export

    private fun startExport() {
        if (!this::project.isInitialized || busyPanel.visibility == View.VISIBLE) return
        val caps = project.captions.filter { it.words.isNotEmpty() }
        if (caps.isEmpty()) {
            Toast.makeText(this, R.string.nothing_to_export, Toast.LENGTH_SHORT).show()
            return
        }
        player?.pause()
        project.save(applicationContext)
        exporting = true
        showBusy(getString(R.string.exporting, 0), 0)
        val out = File(cacheDir, "export.mp4")
        exporter.start(project.videoUri, caps, style, project.offsetMs, out, object : VideoExporter.Listener {
            override fun onProgress(percent: Int) {
                setBusy(getString(R.string.exporting, percent), percent)
            }

            override fun onDone(file: File) {
                lifecycleScope.launch {
                    setBusy(getString(R.string.saving), 100)
                    val uri = withContext(Dispatchers.IO) {
                        try { MediaSaver.saveToGallery(applicationContext, file) } catch (e: Exception) { null } finally { file.delete() }
                    }
                    exporting = false
                    hideBusy()
                    if (uri == null) {
                        showError(getString(R.string.save_failed))
                    } else {
                        exportedUri = uri
                        findViewById<MaterialButton>(R.id.doneTikTok).setText(
                            if (MediaSaver.isTikTokInstalled(this@EditorActivity)) R.string.post_tiktok else R.string.share_tiktok_missing
                        )
                        donePanel.visibility = View.VISIBLE
                    }
                }
            }

            override fun onFailed(message: String) {
                exporting = false
                hideBusy()
                showError(getString(R.string.export_failed, message))
            }
        })
    }

    private fun showError(msg: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.error_title)
            .setMessage(msg)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    // ---------------------------------------------------------------- busy panel

    private fun showBusy(text: String, progress: Int) {
        busyPanel.visibility = View.VISIBLE
        setBusy(text, progress)
    }

    private fun setBusy(text: String, progress: Int) {
        busyText.text = text
        busyProgress.setProgressCompat(progress.coerceIn(0, 100), true)
    }

    private fun hideBusy() {
        busyPanel.visibility = View.GONE
    }

    private fun cancelBusyWork() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.cancel_confirm)
            .setNegativeButton(R.string.keep_going, null)
            .setPositiveButton(R.string.stop) { _, _ ->
                if (exporting) {
                    exporter.cancel()
                    exporting = false
                    hideBusy()
                } else {
                    Transcriber.cancel()
                    transcribeJob?.cancel()
                    finish()
                }
            }
            .show()
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
        if (this::project.isInitialized && project.captions.isNotEmpty()) project.save(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (exporting) exporter.cancel()
        if (transcribeJob?.isActive == true) Transcriber.cancel()
        player?.release()
        player = null
    }
}
