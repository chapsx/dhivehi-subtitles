package mv.dhivehi.subtitles

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

/** Thaana notepad: phonetic typing and conversion of Latin / ASCII-font Dhivehi text. */
class ConverterActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_converter)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val prefs = Prefs(this)
        val input = findViewById<EditText>(R.id.edit_input)
        val output = findViewById<EditText>(R.id.edit_output)
        val reverse = findViewById<CheckBox>(R.id.check_reverse)
        val phon = findViewById<MaterialSwitch>(R.id.switch_phonetic)
        val tf = prefs.typeface()
        output.typeface = tf
        input.typeface = tf

        phon.isChecked = false
        ThaanaInputFilter.attach(input, true) { phon.isChecked }
        phon.setOnCheckedChangeListener { _, on -> ThaanaInputFilter.refresh(input, true, on) }

        findViewById<MaterialButton>(R.id.btn_convert).setOnClickListener {
            output.setText(Thaana.fromLatin(input.text.toString(), reverse.isChecked))
        }
        findViewById<MaterialButton>(R.id.btn_reverse).setOnClickListener {
            output.setText(output.text.toString().split('\n').joinToString("\n") { Thaana.reverseVisual(it) })
        }
        findViewById<MaterialButton>(R.id.btn_copy).setOnClickListener {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("Thaana", output.text.toString()))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialButton>(R.id.btn_clear).setOnClickListener {
            input.setText("")
            output.setText("")
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
