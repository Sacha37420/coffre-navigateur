package io.github.sacha37420.coffre

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.autofill.AutofillManager
import android.widget.Button
import android.widget.TextView

/**
 * « Saisie automatique dans Chrome » : explique pas à pas comment faire du Coffre du lab le gestionnaire de mots
 * de passe du téléphone, montre l'état de chaque réglage et ouvre directement les bons écrans.
 */
class SaisieActivity : Activity() {
    companion object {
        /** Le service de saisie choisi dans Android est-il celui de cette application ? */
        fun serviceEnabled(context: android.content.Context): Boolean = try {
            val am = context.getSystemService(AutofillManager::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 28) am?.autofillServiceComponentName?.packageName == context.packageName
            else am?.hasEnabledAutofillServices() == true
        } catch (e: Exception) { false }

        const val CHROME = "com.android.chrome"
        private const val CHROME_PROVIDER = "content://com.android.chrome.AutofillThirdPartyModeContentProvider/autofill_third_party_mode"
        private const val CHROME_COLUMN = "autofill_third_party_state"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.screen_saisie)
        findViewById<Button>(R.id.saisie_android).setOnClickListener { openAndroidSetting() }
        findViewById<Button>(R.id.saisie_chrome).setOnClickListener { openChromeSetting() }
        findViewById<Button>(R.id.saisie_fermer).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** Ce téléphone utilise-t-il le Coffre du lab comme service de saisie automatique ? */
    fun androidServiceEnabled(): Boolean = serviceEnabled(this)

    /** Chrome est-il réglé sur « Saisie automatique avec un autre service » ? null si on ne peut pas le savoir. */
    fun chromeThirdPartyMode(): Boolean? = try {
        contentResolver.query(Uri.parse(CHROME_PROVIDER), arrayOf(CHROME_COLUMN), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getInt(0) == 1 else null
        }
    } catch (e: Exception) { null }

    private fun chromeInstalled(): Boolean = try { packageManager.getPackageInfo(CHROME, 0); true } catch (e: Exception) { false }

    private fun refresh() {
        val android = androidServiceEnabled()
        findViewById<TextView>(R.id.saisie_etat_android).setText(if (android) R.string.saisie_android_ok else R.string.saisie_android_todo)
        val chrome = if (chromeInstalled()) chromeThirdPartyMode() else null
        findViewById<TextView>(R.id.saisie_etat_chrome).setText(when {
            !chromeInstalled() -> R.string.saisie_chrome_absent
            chrome == true -> R.string.saisie_chrome_ok
            chrome == false -> R.string.saisie_chrome_todo
            else -> R.string.saisie_chrome_unknown
        })
        findViewById<Button>(R.id.saisie_android).visibility = if (android) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.saisie_chrome).visibility = if (chromeInstalled() && chrome != true) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.saisie_pret).visibility = if (android && chrome != false) View.VISIBLE else View.GONE
    }

    private fun openAndroidSetting() {
        // Demande directe : Android affiche « Utiliser Coffre du lab ? ». Repli : les réglages de saisie.
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, Uri.parse("package:$packageName")))
        } catch (e: ActivityNotFoundException) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (e2: ActivityNotFoundException) { }
        }
    }

    private fun openChromeSetting() {
        // Lien documenté par Google vers le réglage de saisie automatique de Chrome ; repli : ouvrir Chrome.
        val direct = Intent(Intent.ACTION_APPLICATION_PREFERENCES)
            .addCategory(Intent.CATEGORY_DEFAULT).addCategory(Intent.CATEGORY_APP_BROWSER).addCategory(Intent.CATEGORY_PREFERENCE)
            .setPackage(CHROME)
        try {
            startActivity(direct)
        } catch (e: ActivityNotFoundException) {
            packageManager.getLaunchIntentForPackage(CHROME)?.let { startActivity(it) }
        }
    }
}
