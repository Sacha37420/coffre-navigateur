package io.github.sacha37420.coffre

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.sacha37420.coffre.core.CoffreCrypto
import io.github.sacha37420.coffre.core.CoffreException
import io.github.sacha37420.coffre.core.Generator
import io.github.sacha37420.coffre.core.VaultState
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Écran unique qui suit l'état du coffre : importer (aucun coffre), déverrouiller, accueil.
 * Toute dérivation de clé (PBKDF2, quelques secondes) tourne hors du fil de l'interface.
 */
class MainActivity : Activity() {
    private val vault get() = coffre.vault
    private val bio get() = coffre.biometrie
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var screen: VaultState.State? = null
    private var pendingKeyFile: JSONObject? = null

    companion object {
        private const val PICK_KEY_FILE = 1
        private const val MAX_KEY_FILE = 10_000
        private const val MIN_LENGTH = 8
        private const val CLEAR_MS = 30_000L
        private const val WATCH_MS = 15_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Pas de capture d'écran ni d'aperçu dans les applications récentes.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onResume() {
        super.onResume()
        route()
        handler.postDelayed(watch, WATCH_MS)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(watch)
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }

    /** Toute interaction compte comme une utilisation du coffre ouvert (repousse le verrouillage). */
    override fun onUserInteraction() {
        super.onUserInteraction()
        if (screen == VaultState.State.DEVERROUILLE) vault.key()
    }

    private val watch = object : Runnable {
        override fun run() {
            if (vault.state() != screen) { if (vault.state() != VaultState.State.DEVERROUILLE) coffre.onLocked(); route() }
            handler.postDelayed(this, WATCH_MS)
        }
    }

    private fun route() {
        val s = vault.state()
        if (s == screen) return
        screen = s
        when (s) {
            VaultState.State.AUCUN -> showImport()
            VaultState.State.VERROUILLE -> showUnlock()
            VaultState.State.DEVERROUILLE -> showHome()
        }
        // Numéro de version en bas de chaque écran : permet de vérifier qu'une mise à jour est bien installée.
        findViewById<TextView>(R.id.version)?.text = getString(R.string.version, packageManager.getPackageInfo(packageName, 0).versionName)
    }

    private fun message(e: Throwable): String = (e as? CoffreException)?.message ?: getString(R.string.unexpected)

    private fun TextView.show(text: String?) {
        this.text = text ?: ""
        visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /** Lance un calcul hors du fil de l'interface, puis revient dessus. */
    private fun background(busy: ProgressBar, buttons: List<View>, task: () -> Unit, onError: (Throwable) -> Unit) {
        busy.visibility = View.VISIBLE
        buttons.forEach { it.isEnabled = false }
        worker.execute {
            val error = try { task(); null } catch (e: Throwable) { e }
            runOnUiThread {
                busy.visibility = View.GONE
                buttons.forEach { it.isEnabled = true }
                if (error != null) onError(error) else { screen = null; route() }
            }
        }
    }

    // ---------- Importer ----------

    private fun showImport() {
        setContentView(R.layout.screen_import)
        pendingKeyFile = null
        findViewById<Button>(R.id.choose).setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_KEY_FILE)
        }
        findViewById<Button>(R.id.import_go).setOnClickListener { doImport(false) }
    }

    @Deprecated("Activity de la plateforme : pas d'API de résultat plus récente sans AndroidX.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_KEY_FILE || resultCode != RESULT_OK || screen != VaultState.State.AUCUN && screen != null) return
        val uri = data?.data ?: return
        val msg = findViewById<TextView>(R.id.import_msg)
        try {
            val kf = CoffreCrypto.parseKeyFile(readSmall(uri))
            pendingKeyFile = kf
            findViewById<TextView>(R.id.file_state).text = getString(R.string.file_chosen, kf.getString("key_id").take(6))
            findViewById<Button>(R.id.import_go).isEnabled = true
            msg.show(null)
        } catch (e: Exception) {
            pendingKeyFile = null
            findViewById<Button>(R.id.import_go).isEnabled = false
            findViewById<TextView>(R.id.file_state).setText(R.string.no_file)
            msg.show(message(e))
        }
    }

    private fun readSmall(uri: Uri): String {
        val input = contentResolver.openInputStream(uri) ?: throw CoffreException("format", getString(R.string.file_too_big))
        input.use {
            val bytes = it.readNBytesCompat(MAX_KEY_FILE + 1)
            if (bytes.size > MAX_KEY_FILE) throw CoffreException("format", getString(R.string.file_too_big))
            return String(bytes, Charsets.UTF_8)
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (out.size() < limit) {
            val n = read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun doImport(replace: Boolean) {
        val kf = pendingKeyFile ?: return
        val pass = findViewById<EditText>(R.id.import_pass)
        val msg = findViewById<TextView>(R.id.import_msg)
        msg.show(null)
        val phrase = pass.text.toString()
        background(findViewById(R.id.import_busy), listOf(findViewById(R.id.import_go), findViewById(R.id.choose)), {
            vault.install(kf, phrase, replace)
            if (replace) bio.disable()
        }) { e ->
            if (e is CoffreException && e.code == "autre_coffre_installe") {
                AlertDialog.Builder(this).setMessage(R.string.replace_confirm)
                    .setPositiveButton(R.string.replace) { _, _ -> doImport(true) }
                    .setNegativeButton(R.string.cancel, null).show()
            } else msg.show(message(e))
        }
        pass.text.clear()
    }

    // ---------- Déverrouiller ----------

    private fun showUnlock() {
        setContentView(R.layout.screen_unlock)
        val pass = findViewById<EditText>(R.id.unlock_pass)
        val go = findViewById<Button>(R.id.unlock_go)
        val bioButton = findViewById<Button>(R.id.unlock_bio)
        val msg = findViewById<TextView>(R.id.unlock_msg)
        val unlock = {
            msg.show(null)
            val phrase = pass.text.toString()
            pass.text.clear()
            background(findViewById(R.id.unlock_busy), listOf(go, bioButton), { vault.unlock(phrase) }) { msg.show(message(it)) }
        }
        go.setOnClickListener { unlock() }
        pass.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { unlock(); true } else false }
        val withBio = bio.available() && bio.enabledFor(vault.keyId())
        bioButton.visibility = if (withBio) View.VISIBLE else View.GONE
        val byBio = {
            bio.unlock(this, { keyId, raw ->
                try { vault.unlockWithRaw(keyId, raw); screen = null; route() } catch (e: Exception) { msg.show(message(e)) }
            }) { m -> msg.show(m); if (!bio.enabledFor(vault.keyId())) bioButton.visibility = View.GONE }
        }
        bioButton.setOnClickListener { byBio() }
        if (withBio) byBio()   // propose l'empreinte d'emblée ; la phrase reste disponible
    }

    // ---------- Accueil ----------

    private lateinit var output: TextView
    private lateinit var info: TextView
    private lateinit var length: SeekBar
    private lateinit var lengthValue: TextView
    private lateinit var flags: Map<String, CheckBox>

    private fun showHome() {
        setContentView(R.layout.screen_home)
        findViewById<TextView>(R.id.home_state).text = getString(R.string.home_open, vault.keyId().orEmpty().take(6))
        findViewById<Button>(R.id.home_lock).setOnClickListener { vault.lock(); coffre.onLocked(); route() }
        setupGenerator()
        setupSettings()
        setupLab()
        setupSaisie()
        findViewById<Button>(R.id.home_entrees).setOnClickListener { startActivity(Intent(this, EntreesActivity::class.java)) }
        refreshCount()
        // Au déverrouillage (et à chaque retour sur l'accueil) : un passage de synchronisation si le lab est relié.
        if (coffre.lab?.connected() == true) runSync()
    }

    private fun refreshCount() {
        val count = vault.peekKey()?.let { v -> coffre.entries.decryptAll(v).first.count { !it.optBoolean("deleted") } } ?: 0
        findViewById<Button>(R.id.home_entrees)?.text = getString(R.string.entrees_bouton, count)
    }

    // ---------- Saisie automatique ----------

    private fun setupSaisie() {
        findViewById<Button>(R.id.home_saisie).setOnClickListener { startActivity(Intent(this, SaisieActivity::class.java)) }
        val on = SaisieActivity.serviceEnabled(this)
        findViewById<TextView>(R.id.home_saisie_etat).setText(if (on) R.string.saisie_android_ok else R.string.saisie_android_todo)
        // Après le premier import : le mode d'emploi s'ouvre une fois de lui-même.
        val prefs = getSharedPreferences("reglages", MODE_PRIVATE)
        if (!on && !prefs.getBoolean("guide_saisie_vu", false)) {
            prefs.edit().putBoolean("guide_saisie_vu", true).apply()
            startActivity(Intent(this, SaisieActivity::class.java))
        }
    }

    // ---------- Connexion au lab ----------

    private fun setupLab() {
        val lab = coffre.lab
        val intro = findViewById<TextView>(R.id.lab_intro)
        val login = findViewById<Button>(R.id.lab_login)
        val sync = findViewById<Button>(R.id.lab_sync)
        val logout = findViewById<Button>(R.id.lab_logout)
        if (lab == null) {
            intro.setText(R.string.lab_none)
            listOf(login, sync, logout).forEach { it.visibility = View.GONE }
            return
        }
        intro.text = getString(R.string.lab_intro, lab.endpoints.origin.removePrefix("https://"))
        login.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(lab.beginLogin())).addCategory(Intent.CATEGORY_BROWSABLE))
                Toast.makeText(this, R.string.lab_browser, Toast.LENGTH_LONG).show()
            } catch (e: android.content.ActivityNotFoundException) {
                findViewById<TextView>(R.id.home_msg).show(getString(R.string.lab_no_browser))
            }
        }
        sync.setOnClickListener { runSync() }
        logout.setOnClickListener {
            worker.execute { lab.logout(); runOnUiThread { refreshLab() } }
        }
        refreshLab()
    }

    private fun refreshLab() {
        val lab = coffre.lab ?: return
        val online = lab.connected()
        val user = lab.username
        findViewById<TextView>(R.id.lab_state)?.text = when {
            online && user != null -> getString(R.string.lab_connected, user)
            user != null -> getString(R.string.lab_expired)
            else -> getString(R.string.lab_disconnected)
        }
        findViewById<Button>(R.id.lab_login)?.visibility = if (online) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.lab_sync)?.visibility = if (online) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.lab_logout)?.visibility = if (online) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.lab_sync_state)?.text = coffre.lastSync()?.let {
            getString(R.string.sync_last, java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(it.at)), it.text)
        } ?: ""
        val count = vault.peekKey()?.let { v -> coffre.entries.decryptAll(v).first.count { !it.optBoolean("deleted") } } ?: 0
        findViewById<Button>(R.id.home_entrees)?.text = getString(R.string.entrees_bouton, count)
    }

    private fun runSync() {
        findViewById<TextView>(R.id.lab_sync_state)?.setText(R.string.sync_running)
        coffre.syncNow { runOnUiThread { if (screen == VaultState.State.DEVERROUILLE) refreshLab() } }
    }

    private fun setupSettings() {
        val choices = VaultState.AUTO_LOCK_CHOICES
        val spinner = findViewById<Spinner>(R.id.autolock)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            choices.map { if (it < 60) getString(R.string.minutes, it) else getString(R.string.hours, it / 60) })
        spinner.setSelection(choices.indexOf(vault.autoLockMinutes))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = vault.setAutoLockMinutes(choices[pos])
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val msg = findViewById<TextView>(R.id.home_msg)
        val toggle = findViewById<Switch>(R.id.bio)
        val available = bio.available()
        findViewById<TextView>(R.id.bio_info).visibility = if (available) View.GONE else View.VISIBLE
        toggle.isEnabled = available
        toggle.isChecked = available && bio.enabledFor(vault.keyId())
        toggle.setOnCheckedChangeListener { _, on ->
            msg.show(null)
            if (!on) { bio.disable(); return@setOnCheckedChangeListener }
            val keyId = vault.keyId()
            val raw = vault.rawKeyCopy()
            if (keyId == null || raw == null) { toggle.isChecked = false; route(); return@setOnCheckedChangeListener }
            bio.enable(this, keyId, raw, { Toast.makeText(this, R.string.bio_enabled, Toast.LENGTH_SHORT).show() }) { m ->
                toggle.isChecked = false
                msg.show(m)
            }
        }

        findViewById<Button>(R.id.forget).setOnClickListener {
            AlertDialog.Builder(this).setMessage(R.string.forget_confirm)
                .setPositiveButton(R.string.forget) { _, _ -> worker.execute { coffre.forgetAll(); runOnUiThread { route() } } }
                .setNegativeButton(R.string.cancel, null).show()
        }
    }

    private fun setupGenerator() {
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
        findViewById<Button>(R.id.copy).setOnClickListener { copy(output.text.toString()) }
        render()
    }

    private fun options() = Generator.Options(
        length = length.progress + MIN_LENGTH,
        lower = flags.getValue("lower").isChecked, upper = flags.getValue("upper").isChecked,
        digits = flags.getValue("digits").isChecked, symbols = flags.getValue("symbols").isChecked,
        avoidAmbiguous = flags.getValue("ambiguous").isChecked,
    )

    private fun render() {
        lengthValue.text = String.format(Locale.getDefault(), "%d", length.progress + MIN_LENGTH)
        try {
            output.text = Generator.generate(options())
            info.text = getString(R.string.entropy, Generator.entropyBits(options()))
        } catch (e: IllegalArgumentException) {
            output.text = ""
            info.text = e.message
        }
    }

    private fun copy(value: String) {
        if (value.isEmpty()) return
        Presse.copy(this, value)
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }
}
