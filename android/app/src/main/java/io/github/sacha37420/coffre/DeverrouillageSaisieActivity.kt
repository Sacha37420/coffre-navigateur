package io.github.sacha37420.coffre

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import io.github.sacha37420.coffre.core.CoffreException
import java.util.concurrent.Executors

/**
 * Déverrouillage demandé par la saisie automatique (coffre verrouillé) : pour remplir (rend les propositions à
 * Android) ou pour enregistrer (enregistre puis se ferme). Mêmes moyens que l'écran principal : phrase ou empreinte.
 */
class DeverrouillageSaisieActivity : Activity() {
    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_REMPLIR = "remplir"
        const val MODE_ENREGISTRER = "enregistrer"
        const val EXTRA_ORIGIN = "origine"
        const val EXTRA_USERNAME_ID = "champ_identifiant"
        const val EXTRA_PASSWORD_IDS = "champs_mot_de_passe"
        const val EXTRA_CREATION = "creation"
        const val EXTRA_USERNAME = "identifiant"
        const val EXTRA_PASSWORD = "mot_de_passe"
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val mode get() = intent.getStringExtra(EXTRA_MODE)
    private val origin get() = intent.getStringExtra(EXTRA_ORIGIN).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (origin.isEmpty() || coffre.vault.keyId() == null) { finish(); return }
        setContentView(R.layout.screen_unlock)
        findViewById<TextView>(R.id.unlock_title).text = getString(
            if (mode == MODE_ENREGISTRER) R.string.autofill_unlock_save else R.string.autofill_unlock_fill, Saisie.host(origin))
        findViewById<TextView>(R.id.version)?.visibility = View.GONE
        if (coffre.vault.key() != null) return done()

        val pass = findViewById<EditText>(R.id.unlock_pass)
        val go = findViewById<Button>(R.id.unlock_go)
        val bioButton = findViewById<Button>(R.id.unlock_bio)
        val msg = findViewById<TextView>(R.id.unlock_msg)
        val busy = findViewById<ProgressBar>(R.id.unlock_busy)
        fun show(m: String?) { msg.text = m ?: ""; msg.visibility = if (m.isNullOrEmpty()) View.GONE else View.VISIBLE }
        val unlock = {
            show(null)
            val phrase = pass.text.toString()
            pass.text.clear()
            busy.visibility = View.VISIBLE
            go.isEnabled = false
            worker.execute {
                val err = try { coffre.vault.unlock(phrase); null } catch (e: CoffreException) { e.message } catch (e: Exception) { getString(R.string.unexpected) }
                runOnUiThread {
                    busy.visibility = View.GONE
                    go.isEnabled = true
                    if (err != null) show(err) else done()
                }
            }
        }
        go.setOnClickListener { unlock() }
        pass.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { unlock(); true } else false }
        val bio = coffre.biometrie
        if (bio.available() && bio.enabledFor(coffre.vault.keyId())) {
            bioButton.visibility = View.VISIBLE
            val byBio = {
                bio.unlock(this, { keyId, raw ->
                    try { coffre.vault.unlockWithRaw(keyId, raw); done() } catch (e: Exception) { show((e as? CoffreException)?.message) }
                }) { show(it) }
            }
            bioButton.setOnClickListener { byBio() }
            byBio()
        }
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T> Intent.extra(name: String): T? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(name, T::class.java) else getParcelableExtra(name) as T?

    @Suppress("DEPRECATION")
    private fun passwordIds(): List<AutofillId> =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayExtra(EXTRA_PASSWORD_IDS, AutofillId::class.java)
        else intent.getParcelableArrayExtra(EXTRA_PASSWORD_IDS))?.map { it as AutofillId } ?: emptyList()

    /** Coffre ouvert : rendre les propositions à Android, ou enregistrer. */
    private fun done() {
        val vault = coffre.vault.key() ?: return
        if (mode == MODE_ENREGISTRER) {
            val saved = try {
                Saisie.enregistrer(this, vault, origin, intent.getStringExtra(EXTRA_USERNAME).orEmpty(), intent.getStringExtra(EXTRA_PASSWORD).orEmpty())
            } catch (e: Exception) { false }
            if (saved) Toast.makeText(this, getString(R.string.autofill_saved, Saisie.host(origin)), Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
        } else {
            val c = Saisie.Cible(origin, intent.extra<AutofillId>(EXTRA_USERNAME_ID), passwordIds(), intent.getBooleanExtra(EXTRA_CREATION, false))
            setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, Saisie.reponse(this, c, vault)))
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }
}
