package io.github.sacha37420.coffre

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import io.github.sacha37420.coffre.core.Generator

/** Lot 7 : écran du générateur. Le coffre arrive au lot 8. */
class MainActivity : Activity() {
    private lateinit var output: TextView
    private lateinit var info: TextView
    private lateinit var length: SeekBar
    private lateinit var lengthValue: TextView
    private lateinit var flags: Map<String, CheckBox>
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Pas de capture d'écran ni d'aperçu dans les applications récentes.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_main)
        output = findViewById(R.id.password)
        info = findViewById(R.id.info)
        length = findViewById(R.id.length)
        lengthValue = findViewById(R.id.length_value)
        flags = mapOf(
            "lower" to findViewById(R.id.lower), "upper" to findViewById(R.id.upper),
            "digits" to findViewById(R.id.digits), "symbols" to findViewById(R.id.symbols),
            "ambiguous" to findViewById(R.id.ambiguous),
        )
        length.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) = render()
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        flags.values.forEach { it.setOnCheckedChangeListener { _, _ -> render() } }
        findViewById<Button>(R.id.regen).setOnClickListener { render() }
        findViewById<Button>(R.id.copy).setOnClickListener { copy() }
        render()
    }

    private fun options() = Generator.Options(
        length = length.progress + MIN_LENGTH,
        lower = flags.getValue("lower").isChecked, upper = flags.getValue("upper").isChecked,
        digits = flags.getValue("digits").isChecked, symbols = flags.getValue("symbols").isChecked,
        avoidAmbiguous = flags.getValue("ambiguous").isChecked,
    )

    private fun render() {
        lengthValue.text = String.format(java.util.Locale.getDefault(), "%d", length.progress + MIN_LENGTH)
        try {
            output.text = Generator.generate(options())
            info.text = getString(R.string.entropy, Generator.entropyBits(options()))
        } catch (e: IllegalArgumentException) {
            output.text = ""
            info.text = e.message
        }
    }

    private fun copy() {
        val value = output.text.toString()
        if (value.isEmpty()) return
        val cm = getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText(getString(R.string.app_name), value)
        // Android 13+ : contenu marqué sensible, masqué dans l'aperçu du presse-papiers.
        clip.description.extras = PersistableBundle().apply {
            putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
        }
        cm.setPrimaryClip(clip)
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        // Vidé au bout de 30 s s'il contient toujours ce mot de passe.
        handler.postDelayed({
            val current = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
            if (current == value) {
                if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }, CLEAR_MS)
    }

    companion object {
        private const val MIN_LENGTH = 8
        private const val CLEAR_MS = 30_000L
    }
}
