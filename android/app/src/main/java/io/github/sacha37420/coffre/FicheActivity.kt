package io.github.sacha37420.coffre

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import io.github.sacha37420.coffre.core.CoffreException
import io.github.sacha37420.coffre.core.Entries
import io.github.sacha37420.coffre.core.Generator
import org.json.JSONObject
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import java.util.concurrent.Executors

/**
 * Fiche d'une entrée : voir, copier, générer, modifier, supprimer. Afficher un mot de passe enregistré redemande
 * la phrase (ou l'empreinte) si elle n'a pas été saisie depuis 5 minutes ; il se masque de nouveau après 30 s.
 */
class FicheActivity : Activity() {
    companion object {
        const val EXTRA_ID = "id"
        const val HIDE_MS = 30_000L
    }

    private var entry: JSONObject? = null      // null : nouvelle entrée
    private var dirty = false
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val hide = Runnable { setRevealed(false) }
    private val watch = object : Runnable {
        override fun run() {
            if (coffre.vault.peekKey() == null) return close()
            handler.postDelayed(this, 15_000)
        }
    }

    private fun <T : View> v(id: Int): T = findViewById(id)

    private fun watcher(onChange: () -> Unit) = object : TextWatcher {
        override fun afterTextChanged(s: Editable?) = onChange()
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.screen_fiche)
        val vault = coffre.vault.key() ?: return close()
        val id = intent.getStringExtra(EXTRA_ID)
        entry = id?.let { i -> coffre.entries.decryptAll(vault).first.firstOrNull { it.getString("id") == i && !it.optBoolean("deleted") } }
        if (id != null && entry == null) return close()
        fill()
        listOf(R.id.f_nom, R.id.f_sites, R.id.f_identifiant, R.id.f_notes).forEach { v<EditText>(it).addTextChangedListener(watcher { dirty = true }) }
        v<EditText>(R.id.f_mdp).addTextChangedListener(watcher { dirty = true })
        dirty = false

        v<Button>(R.id.f_afficher).setOnClickListener { if (revealed()) setRevealed(false) else askReveal() }
        v<Button>(R.id.f_copier_id).setOnClickListener {
            val t = v<EditText>(R.id.f_identifiant).text.toString()
            if (t.isNotEmpty()) { Presse.copy(this, t); Toast.makeText(this, R.string.copie_identifiant, Toast.LENGTH_SHORT).show() }
        }
        v<Button>(R.id.f_copier_mdp).setOnClickListener {
            val t = v<EditText>(R.id.f_mdp).text.toString()
            if (t.isNotEmpty()) { Presse.copy(this, t); Toast.makeText(this, R.string.copie_mot_de_passe, Toast.LENGTH_SHORT).show() }
        }
        v<Button>(R.id.f_generer).setOnClickListener {
            v<EditText>(R.id.f_mdp).setText(Generator.generate())
            setRevealed(true)            // un mot de passe tout juste généré n'est pas un secret déjà enregistré
        }
        v<Button>(R.id.f_enregistrer).setOnClickListener { save() }
        v<Button>(R.id.f_supprimer).setOnClickListener { remove() }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        }
    }

    private fun fill() {
        val e = entry
        v<TextView>(R.id.f_titre).text = e?.optString("name") ?: getString(R.string.fiche_nouvelle)
        v<EditText>(R.id.f_nom).setText(e?.optString("name").orEmpty())
        v<EditText>(R.id.f_sites).setText(e?.let { Entries.origins(it).joinToString("\n") }.orEmpty())
        v<EditText>(R.id.f_identifiant).setText(e?.optString("username").orEmpty())
        v<EditText>(R.id.f_mdp).setText(e?.optString("password").orEmpty())
        v<EditText>(R.id.f_notes).setText(e?.optString("notes").orEmpty())
        v<Button>(R.id.f_supprimer).visibility = if (e == null) View.GONE else View.VISIBLE
        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        v<TextView>(R.id.f_meta).text = e?.let {
            getString(R.string.fiche_meta, fmt.format(Date.from(Instant.parse(it.getString("created")))),
                fmt.format(Date.from(Instant.parse(it.getString("updated")))))
        }.orEmpty()
        setRevealed(false)
    }

    private fun revealed() = v<EditText>(R.id.f_mdp).inputType and InputType.TYPE_MASK_VARIATION == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD

    private fun setRevealed(on: Boolean) {
        handler.removeCallbacks(hide)
        val f = findViewById<EditText>(R.id.f_mdp) ?: return
        f.inputType = InputType.TYPE_CLASS_TEXT or (if (on) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD)
        f.setSelection(f.text.length)
        v<Button>(R.id.f_afficher).setText(if (on) R.string.fiche_masquer else R.string.fiche_afficher)
        if (on) handler.postDelayed(hide, HIDE_MS)
    }

    /** Redemande la phrase (ou l'empreinte) si elle date de plus de 5 min ; rien à redemander pour un mot de passe non enregistré. */
    private fun askReveal() {
        val e = entry
        // Rien à redemander si le champ ne contient plus le mot de passe enregistré (saisi ou généré ici) : comparaison du
        // texte lui-même, pas d'un indicateur (changer le type du champ relance l'observateur de saisie).
        val stored = e?.optString("password").orEmpty()
        if (e == null || stored.isEmpty() || v<EditText>(R.id.f_mdp).text.toString() != stored || coffre.vault.recentlyAuthenticated()) {
            return setRevealed(true)
        }
        val input = EditText(this).apply {
            id = R.id.f_confirmer_phrase
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.passphrase)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val b = AlertDialog.Builder(this).setTitle(R.string.fiche_confirmer_titre).setMessage(R.string.fiche_confirmer_texte).setView(input)
            .setPositiveButton(R.string.fiche_confirmer) { _, _ ->
                val phrase = input.text.toString()
                worker.execute {
                    val err = try { coffre.vault.confirm(phrase); null } catch (ex: CoffreException) { ex.message } catch (ex: Exception) { getString(R.string.unexpected) }
                    runOnUiThread { if (err == null) { message(null); setRevealed(true) } else message(err) }
                }
            }
            .setNegativeButton(R.string.cancel, null)
        val bio = coffre.biometrie
        if (bio.available() && bio.enabledFor(coffre.vault.keyId())) {
            b.setNeutralButton(R.string.fiche_empreinte) { _, _ ->
                bio.unlock(this, { _, raw -> raw.fill(0); coffre.vault.confirmedByBiometric(); setRevealed(true) }) { m -> message(m) }
            }
        }
        b.show()
    }

    private fun message(m: String?) {
        v<TextView>(R.id.f_msg).apply { text = m ?: ""; visibility = if (m.isNullOrEmpty()) View.GONE else View.VISIBLE }
    }

    private fun save() {
        message(null)
        try {
            val vault = coffre.vault.key() ?: return close()
            val name = v<EditText>(R.id.f_nom).text.toString()
            val sites = v<EditText>(R.id.f_sites).text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
            val user = v<EditText>(R.id.f_identifiant).text.toString()
            val pwd = v<EditText>(R.id.f_mdp).text.toString()
            val notes = v<EditText>(R.id.f_notes).text.toString()
            val next = entry?.let { Entries.edit(it, name, sites, user, pwd, notes) } ?: Entries.newEntry(name, sites, user, pwd, notes)
            coffre.entries.put(vault, next)
            coffre.syncNow()
            Toast.makeText(this, getString(R.string.fiche_enregistree, next.getString("name")), Toast.LENGTH_SHORT).show()
            dirty = false
            finish()
        } catch (e: CoffreException) {
            message(e.message)
        }
    }

    private fun remove() {
        val e = entry ?: return
        AlertDialog.Builder(this).setMessage(getString(R.string.fiche_supprimer_confirmer, e.optString("name")))
            .setPositiveButton(R.string.fiche_supprimer) { _, _ ->
                val vault = coffre.vault.key() ?: return@setPositiveButton close()
                coffre.entries.put(vault, Entries.tombstone(e))
                coffre.syncNow()
                dirty = false
                finish()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    /** Retour avec des modifications non enregistrées : confirmation. */
    private fun back() {
        if (!dirty) return finish()
        AlertDialog.Builder(this).setMessage(R.string.fiche_abandonner)
            .setPositiveButton(R.string.fiche_abandonner_oui) { _, _ -> dirty = false; finish() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    // Android 12 et avant : la touche et le geste « retour » passent par ici. Android 13+ : rappel de la plateforme (onCreate).
    @Deprecated("Remplacé par OnBackInvokedCallback sur Android 13+")
    @android.annotation.SuppressLint("GestureBackNavigation")
    override fun onBackPressed() = back()

    override fun onResume() {
        super.onResume()
        if (coffre.vault.peekKey() == null) return close()
        handler.postDelayed(watch, 15_000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(watch)
        setRevealed(false)   // quitter l'écran masque le mot de passe
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        coffre.vault.key()
    }

    /** Coffre verrouillé : la fiche est vidée et fermée. */
    private fun close() {
        entry = null
        listOf(R.id.f_nom, R.id.f_sites, R.id.f_identifiant, R.id.f_mdp, R.id.f_notes).forEach { findViewById<EditText>(it)?.setText("") }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }
}
