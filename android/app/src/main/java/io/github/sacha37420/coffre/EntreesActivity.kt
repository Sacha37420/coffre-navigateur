package io.github.sacha37420.coffre

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.SimpleAdapter
import android.widget.TextView
import io.github.sacha37420.coffre.core.Entries
import org.json.JSONObject

/** « Mes mots de passe » : liste triée et recherche. Les entrées en clair ne vivent qu'en mémoire de l'écran. */
class EntreesActivity : Activity() {
    private var entries: List<JSONObject> = emptyList()
    private var shown: List<JSONObject> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private val watch = object : Runnable {
        override fun run() {
            if (coffre.vault.peekKey() == null) return close()
            handler.postDelayed(this, 15_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.screen_entrees)
        findViewById<EditText>(R.id.e_recherche).addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = render()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        findViewById<Button>(R.id.e_ajouter).setOnClickListener { startActivity(Intent(this, FicheActivity::class.java)) }
        findViewById<ListView>(R.id.e_liste).setOnItemClickListener { _, _, pos, _ ->
            startActivity(Intent(this, FicheActivity::class.java).putExtra(FicheActivity.EXTRA_ID, shown[pos].getString("id")))
        }
    }

    override fun onResume() {
        super.onResume()
        val v = coffre.vault.key() ?: return close()
        entries = coffre.entries.decryptAll(v).first
        render()
        handler.postDelayed(watch, 15_000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(watch)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        coffre.vault.key()
    }

    private fun close() {
        entries = emptyList()
        shown = emptyList()
        finish()
    }

    private fun render() {
        val q = findViewById<EditText>(R.id.e_recherche).text.toString()
        shown = Entries.search(entries, q)
        val total = entries.count { !it.optBoolean("deleted") }
        findViewById<TextView>(R.id.e_compte).text = if (q.isBlank()) getString(R.string.entrees_compte, total)
        else getString(R.string.entrees_compte_filtre, shown.size, total)
        findViewById<TextView>(R.id.e_vide).visibility = if (total == 0) View.VISIBLE else View.GONE
        val rows = shown.map { e ->
            mapOf("titre" to e.optString("name"),
                "sous" to (listOf(e.optString("username")) + Entries.origins(e).map { Saisie.host(it) }).filter { it.isNotEmpty() }.joinToString(" · "))
        }
        findViewById<ListView>(R.id.e_liste).adapter = SimpleAdapter(this, rows, android.R.layout.simple_list_item_2,
            arrayOf("titre", "sous"), intArrayOf(android.R.id.text1, android.R.id.text2))
    }
}
