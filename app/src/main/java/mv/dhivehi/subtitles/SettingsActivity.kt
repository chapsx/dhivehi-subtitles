package mv.dhivehi.subtitles

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var preview: TextView
    private lateinit var fontLabel: TextView

    private val pickFont = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@registerForActivityResult
            val tmp = java.io.File(cacheDir, "font_check.ttf")
            tmp.writeBytes(bytes)
            android.graphics.Typeface.createFromFile(tmp) // throws if not a font
            tmp.delete()
            prefs.fontFile.writeBytes(bytes)
            prefs.sp.edit().putString("font_name", Files.displayName(this, uri)).apply()
            Toast.makeText(this, R.string.font_set, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.font_bad, Toast.LENGTH_LONG).show()
        }
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        prefs = Prefs(this)
        preview = findViewById(R.id.preview)
        fontLabel = findViewById(R.id.font_label)

        seek(R.id.seek_size, R.id.label_size, 12, 48, prefs.fontSize, { "${it}sp" }) { prefs.fontSize = it }
        seek(R.id.seek_bg, R.id.label_bg, 0, 100, prefs.bgOpacity, { "$it%" }) { prefs.bgOpacity = it }
        seek(R.id.seek_margin, R.id.label_margin, 0, 40, prefs.bottomMargin, { "$it%" }) { prefs.bottomMargin = it }
        seek(R.id.seek_duration, R.id.label_duration, 5, 80, prefs.defaultCueMs / 100, { "${it / 10.0}s" }) {
            prefs.defaultCueMs = it * 100
        }

        val colors = findViewById<Spinner>(R.id.spinner_color)
        colors.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Prefs.COLOR_NAMES)
        colors.setSelection(Prefs.COLORS.indexOf(prefs.textColor).coerceAtLeast(0))
        colors.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                prefs.textColor = Prefs.COLORS[pos]
                refresh()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        switch(R.id.switch_outline, prefs.outline) { prefs.outline = it }
        switch(R.id.switch_bold, prefs.bold) { prefs.bold = it }
        switch(R.id.switch_phonetic, prefs.phonetic) { prefs.phonetic = it }
        switch(R.id.switch_autoscroll, prefs.autoScroll) { prefs.autoScroll = it }
        switch(R.id.switch_bom, prefs.writeBom) { prefs.writeBom = it }

        findViewById<MaterialButton>(R.id.btn_font).setOnClickListener {
            pickFont.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream", "*/*"))
        }
        findViewById<MaterialButton>(R.id.btn_font_reset).setOnClickListener {
            prefs.fontFile.delete()
            prefs.sp.edit().remove("font_name").apply()
            refresh()
        }
        refresh()
    }

    private fun seek(id: Int, labelId: Int, min: Int, max: Int, value: Int, fmt: (Int) -> String, set: (Int) -> Unit) {
        val sb = findViewById<SeekBar>(id)
        val label = findViewById<TextView>(labelId)
        sb.max = max - min
        sb.progress = (value - min).coerceIn(0, max - min)
        label.text = fmt(value)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                label.text = fmt(p + min)
                if (fromUser) {
                    set(p + min)
                    refresh()
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun switch(id: Int, value: Boolean, set: (Boolean) -> Unit) {
        val sw = findViewById<MaterialSwitch>(id)
        sw.isChecked = value
        sw.setOnCheckedChangeListener { _, on ->
            set(on)
            refresh()
        }
    }

    private fun refresh() {
        prefs.styleSubtitle(preview)
        fontLabel.text = if (prefs.fontFile.exists()) {
            prefs.sp.getString("font_name", null) ?: getString(R.string.custom_font)
        } else getString(R.string.system_font)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
